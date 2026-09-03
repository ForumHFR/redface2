package fr.forumhfr.redface2.feature.flags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.coroutines.ApplicationScope
import fr.forumhfr.redface2.core.domain.coroutines.awaitDetached
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.domain.preferences.AvatarAppearance
import fr.forumhfr.redface2.core.domain.preferences.CategoryBandStyle
import fr.forumhfr.redface2.core.domain.preferences.FlagGlyphStyle
import fr.forumhfr.redface2.core.domain.preferences.FlagsViewSettings
import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.domain.preferences.PlusLusIndicatorStyle
import fr.forumhfr.redface2.core.domain.preferences.SuperFavoriteRepository
import fr.forumhfr.redface2.core.domain.preferences.SuperFavoriteTopic
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.preferences.matches
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.messages.PrivateMessageSummary
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageFlagEntry
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageResult
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Home tab ViewModel. Owns the auth-aware flag list rendering and the per-tab filtering
 * state so the UI layer can stay declarative.
 *
 * State flows are nullable where "not known yet" is meaningful: a `null` `authState`
 * means the cookie jar is still warming up from DataStore, and Compose renders nothing
 * to avoid a cold-start flicker (cf. PR #91 review).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
// Hilt-injected dependency cluster : flags + forum + messages + MPStorage + prefs + auth + clock,
// each provided independently by the graph — there is no cohesive bundle to extract (a wrapper
// would only relay them). Same pragmatic exception as the hoisted-composable suppressions elsewhere.
@Suppress("LongParameterList")
class FlagsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val flagRepository: FlagRepository,
    private val forumRepository: ForumRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val superFavoriteRepository: SuperFavoriteRepository,
    private val superFavoriteListMapper: SuperFavoriteListMapper,
    private val messagesRepository: MessagesRepository,
    private val mpStorageRepository: MpStorageRepository,
    private val clock: Clock,
    // #1144 — process-lifetime scope for the ONE HFR mutation this ViewModel owns (« Retirer le
    // drapeau »). Since #1083 the delflag GET is genuinely cancellable, so leaving the Drapeaux
    // screen right after confirming would abort it; [awaitDetached] re-parents it here. Every read
    // and every preference write stays as it was.
    @param:ApplicationScope private val externalScope: CoroutineScope,
) : ViewModel() {

    private var observedPseudo: String? = null

    /**
     * #378 — instant of the last refresh triggered through [maybeAutoRefresh], PER [FlagType], for
     * the throttle. ViewModel-scoped on purpose: the ViewModel survives the screen leaving the
     * composition (tab switch, topic push), so rapid back-and-forth on the SAME tab shares one
     * window, while a cold app start (fresh ViewModel) always allows the first auto-refresh.
     *
     * One window per type since #743 (styx42): a single shared instant meant that landing on another
     * tab right after this one's refresh was swallowed by the window, so the freshly shown tab kept
     * its cache — the throttle is now consulted for the landing tab's own type. Keyed on [FlagType]
     * rather than [FlagTab] because the throttled unit is `flagRepository.refresh(type)` — the
     * per-category REST fan-out of ONE type — and every tab that fetches maps 1:1 to a type
     * (Super/DT carry none and return before the throttle). A future multi-type tab (#668) would
     * re-decide this key together with the repository-level pull it needs (cf.
     * `DefaultFlagRepository.refresh`).
     */
    private val lastAutoRefreshAt: MutableMap<FlagType, Instant> = mutableMapOf()

    /**
     * #378 follow-up — generation counter incremented by [onFlagOpened] (a topic was opened from
     * this list). A refresh CONSUMES the generations it can see at its **call time** by advancing
     * [flagOpenedGenerationConsumed]; [maybeAutoRefresh] bypasses the throttle while un-consumed
     * generations remain. A counter (not a boolean) so a read armed WHILE a landing refresh is
     * suspended on its pref/auth gates is never swallowed by that refresh — it could not have
     * captured that reading yet (Codex review on this PR). Same ViewModel scoping rationale as
     * [lastAutoRefreshAt].
     */
    private var flagOpenedGeneration = 0L
    private var flagOpenedGenerationConsumed = 0L

    /**
     * #743 — the [FlagType] whose refresh (manual or auto) is currently behind [isRefreshing], and
     * the landing that met that in-flight refresh while it was running for ANOTHER type. Refreshes
     * stay strictly sequential — the repository treats every `refresh(type)` as a generation barrier
     * for its shared sticky sweep, so two concurrent per-type refreshes would degrade the older one
     * to a bucket-only result (cf. `DefaultFlagRepository.refresh`) — so a tab switched to while a
     * refresh runs cannot start its own fan-out; its landing is REPLAYED once the in-flight refresh
     * settles, if that tab is still the selected one (a tab left meanwhile lands again on its own).
     * Latest landing wins; a landing on the type already in flight clears it (nothing to replay —
     * the running refresh covers it, and its throttle window absorbs the resume/tab-effect double
     * fire). Without this, « relancer l'app puis changer tout de suite d'onglet » still showed the
     * new tab's cache whenever the first tab's fan-out was in flight at the switch.
     */
    private var refreshingType: FlagType? = null
    private var deferredLandingType: FlagType? = null

    private val _selectedTab = MutableStateFlow<FlagTab>(FlagTab.Cyan)
    val selectedTab: StateFlow<FlagTab> = _selectedTab.asStateFlow()

    /**
     * Whether the opt-in « DT » tab is shown (Settings toggle, default off). The tab lists the user's
     * MultiMP conversations enriched with MPStorage reading positions (see [dtListState]) — it is a
     * real backed list now (#6), NOT a placeholder like [FlagTab.Super].
     */
    val showDtTab: StateFlow<Boolean> = userPreferencesRepository.observeShowDtSection()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    /**
     * #662 — « états vides humoristiques » opt-in. When `true`, an empty tab swaps the sober
     * style-A icon for a HFR perso smiley (style C); the contextual text is unchanged. Eager so the
     * empty state renders the right visual without a subscription warm-up flash.
     */
    val funnyEmptyState: StateFlow<Boolean> = userPreferencesRepository.observeFunnyEmptyState()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    /**
     * #603 PR5 / #737 — local « super favori » topics (ADR-017 decision 5), a client-side pin
     * distinct from the server `isFavorite`. Eager so the long-press sheet and the Super tab reflect
     * the current state without a subscription warm-up.
     */
    val superFavoriteTopics: StateFlow<Set<SuperFavoriteTopic>> =
        superFavoriteRepository.observeSuperFavoriteTopics()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptySet(),
            )

    /** Toggles the local super-favorite mark of [flag] (long-press sheet). */
    fun toggleSuperFavorite(flag: Flag) {
        val enabled = superFavoriteTopics.value.none { it.matches(flag) }
        viewModelScope.launch { superFavoriteRepository.setSuperFavorite(flag, enabled) }
    }

    /**
     * #6 — UI state for the « DT » tab: the user's MultiMP conversations (inbox `cat=prive`,
     * [PrivateMessageSummary.isMultiRecipient]) enriched best-effort with the MPStorage reading
     * positions (DTCloud's `mpFlags`). A dedicated state (NOT a [FlagsListUiState] / [Flag] reuse,
     * arbitrage Codex): the DT channel is a private-message list, not a flag list, so it never
     * shares the flag model's contract (`flagType == null` stays a pure « no flag-pipeline fetch »
     * marker — for the [FlagTab.Super] placeholder, and for DT which is served by [dtListState] instead).
     *
     * The fetch is triggered on tab OPENING ([onDtTabOpened]) — never from the per-category
     * auto-refresh — because [MpStorageRepository.fetchStorage] is expensive (it scans the inbox to
     * discover the storage MP). Idle until first opened.
     */
    private val _dtListState = MutableStateFlow<DtListUiState>(DtListUiState.Loading)
    val dtListState: StateFlow<DtListUiState> = _dtListState.asStateFlow()

    /**
     * #546 directive XaTriX — « non-lus uniquement » filter for the DT tab, mirroring CYAN's
     * [cyanUnreadOnly]: default `true` so the DT list opens on the actionable unread subset, and a
     * re-tap of the already-selected DT tab toggles it (the « +lus » shortcut, cf. [selectTab]).
     *
     * Per-SESSION only (in-memory ViewModel state), NOT persisted: persisting it would mean adding a
     * method to [UserPreferencesRepository], which breaks every fake (the eager constructor read of
     * the new flow throws a MockKException in the theme tests). Persistence is a deliberate FOLLOW-UP.
     */
    private val _dtUnreadOnly = MutableStateFlow(true)
    val dtUnreadOnly: StateFlow<Boolean> = _dtUnreadOnly.asStateFlow()

    /**
     * #546 directive XaTriX — toggled around the user-driven [refreshDt] round-trip so the Material 3
     * `PullToRefreshBox` indicator stays anchored over the existing DT list instead of blanking it to
     * a cold spinner. Same pattern as [isRefreshing] for the flag tabs.
     */
    private val _dtIsRefreshing = MutableStateFlow(false)
    val dtIsRefreshing: StateFlow<Boolean> = _dtIsRefreshing.asStateFlow()

    /**
     * #546 directive XaTriX — whether the DT tab currently shows read conversations (drives the
     * discreet « +lus » tab-label suffix): DT selected AND its unread filter off. Mirrors
     * [cyanShowsReadShortcut].
     */
    val dtShowsRead: StateFlow<Boolean> = combine(
        selectedTab,
        _dtUnreadOnly,
    ) { tab, unreadOnly -> tab == FlagTab.Dt && !unreadOnly }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    /**
     * #546 directive XaTriX — the DT list state the SCREEN consumes: the raw [_dtListState] union
     * passed through the « non-lus uniquement » filter ([_dtUnreadOnly]). [dtListState] stays exposed
     * for the union/dedup tests; this is the displayed projection. When the filter is on, only the
     * inbox-backed conversations with an unread message survive — orphan storage-only rows (unknown
     * read state) and read inbox rows are excluded (cf. [filterDt]).
     */
    val dtDisplayState: StateFlow<DtListUiState> = combine(
        _dtListState,
        _dtUnreadOnly,
    ) { state, unreadOnly -> filterDt(state, unreadOnly) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = DtListUiState.Loading,
        )

    /** One-shot guard so re-entering the composition (recomposition, ON_RESUME) does not re-scan. */
    private var dtFetchStarted = false

    /**
     * #6 Codex review — the in-flight DT fetch and a monotonic generation counter, together guarding
     * against STALE WRITES. A logout / account switch ([resetDtState]) cancels [dtFetchJob] AND bumps
     * [dtGeneration]; each [loadDt] captures its generation at launch and only publishes [_dtListState]
     * while that capture still equals [dtGeneration]. So a fetch that was mid-flight when the account
     * switched can never republish the previous account's MultiMP into the new session. Two rapid
     * [refreshDt] calls are also latest-wins: the second cancels the first's job and out-generations it.
     */
    private var dtFetchJob: kotlinx.coroutines.Job? = null
    private var dtGeneration = 0

    val authState: StateFlow<AuthState?> = authRepository.observeAuthState()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    /**
     * UI state for the tab whose list content is ready to render (#179/#742). The tab and list travel
     * as one emission so Compose can never observe « newly selected tab + previous tab content » and
     * bind that stale content to the new tab's [LazyListState].
     *
     * - Anonymous → selected tab + `null` (the home tab shows the login intro instead of a list).
     * - [FlagTab.Dt] → DT tab + `null` (DT has its own [dtDisplayState]).
     * - [FlagTab.Super] → local Super list backed by [SuperFavoriteRepository] (#737), no flag-bucket
     *   observation and no pull-to-refresh.
     * - Real [FlagType] tabs → the per-tab flag flow (« non-lus uniquement » filter #154/#317, then
     *   [keepContentDuringRefresh] #225) combined with the category catalogue and lazy subcategory
     *   labels (#741).
     *
     * Ordering of the real-tab mapping is load-bearing: unread filter → `keepContentDuringRefresh` →
     * lazy subcategory names → combine with categories/layout → sections. Reversing the first two
     * would regress #225 (the list blanks to a cold spinner during a pull-to-refresh).
     */
    val flagsTabState: StateFlow<FlagsTabListUiState> = authState
        .onEach(::clearFlagsCacheIfSessionChanged)
        .flatMapLatest { state ->
            when (state) {
                null, AuthState.Anonymous -> selectedTab.map { tab ->
                    FlagsTabListUiState(tab = tab, flagsState = null)
                }
                is AuthState.Authenticated -> selectedTab.flatMapLatest { tab ->
                    authenticatedFlagsListState(tab).map { flagsState ->
                        FlagsTabListUiState(tab = tab, flagsState = flagsState)
                    }
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = FlagsTabListUiState(tab = _selectedTab.value, flagsState = null),
        )

    /** Backward-compatible projection for tests/callers that only care about the list payload. */
    val flagsState: StateFlow<FlagsListUiState?> = flagsTabState
        .map { it.flagsState }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    /**
     * Display-settings bottom sheet state (#309). Tracks the RESOLVED view settings for the
     * currently selected tab so the sheet's two switches reflect what the list is actually using
     * (global, or this tab's override). The DT tab and local [FlagTab.Super] list have no real
     * [FlagType], so they resolve the GLOBAL appearance fields (via the CYAN path) plus the global
     * group/hide pair.
     */
    val flagsViewSettings: StateFlow<FlagsViewSettings> = selectedTab
        .flatMapLatest { tab ->
            when (val type = tab.flagType) {
                // #603 audit fix — DT/Super (no real FlagType) must KEEP the 7 GLOBAL appearance
                // fields (markerStyle, flagGlyphStyle, plusLusIndicatorStyle, showLoadingBar,
                // markerBorder, singleLineTitle, categoryBandStyle). Resolving via CYAN carries them
                // verbatim (they are type-agnostic on every resolution path); the per-type triplet
                // is then overwritten with the GLOBAL group/hide and an inert unreadOnly. Without
                // this, a user who turned the loading bar OFF (#728) or picked glyph=Dot / +lus=Eye
                // saw the bar reappear and the glyph/indicator reset to defaults on DT/Super.
                null -> combine(
                    userPreferencesRepository.observeFlagsViewSettings(FlagType.CYAN),
                    userPreferencesRepository.observeFlagsGroupByCategory(),
                    userPreferencesRepository.observeFlagsHideReadCategories(),
                ) { resolved, group, hide ->
                    resolved.copy(
                        groupByCategory = group,
                        hideReadCategories = hide,
                        unreadOnly = false,
                    )
                }.distinctUntilChanged()
                else -> userPreferencesRepository.observeFlagsViewSettings(type)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            // Seed the type-aware #317 default for the initial tab (CYAN → unread-only) so the
            // bottom sheet's « non-lus uniquement » switch shows the right state on a cold start
            // (before observeFlagsViewSettings emits), instead of the data-class default `false`.
            // The « +lus » suffix and the re-tap read [cyanUnreadOnly], not this StateFlow.
            initialValue = FlagsViewSettings(unreadOnly = _selectedTab.value == FlagTab.Cyan),
        )

    // #718 — GLOBAL account-avatar appearance (border + background). Exposed here only so the Drapeaux
    // « Réglages d'affichage » sheet (the EDITING point) reflects + writes it; the badge itself reads
    // the same preference globally via AppAccountViewModel, independent of this screen.
    val avatarAppearance: StateFlow<AvatarAppearance> = userPreferencesRepository.observeAvatarAppearance()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AvatarAppearance(),
        )

    /**
     * #385 — the selected tab and ITS resolved « non-lus uniquement » value as one atomic
     * emission, for the screen's filter-flip scroll reset. The screen must never compare a new
     * tab against the previous tab's filter value: collecting [selectedTab] and the resolved
     * settings as two separate states lets Compose observe « new tab + stale filter » then
     * « new tab + real filter » — a phantom same-tab flip that would reset the scroll on every
     * tab switch (Codex review on PR #421). `flatMapLatest` pins each filter emission to the
     * tab that produced it, so consecutive emissions with the same tab are real flips only.
     * Placeholder tabs (Super/DT) emit `false` — they have no list, the value is inert.
     */
    val tabUnreadFilter: StateFlow<Pair<FlagTab, Boolean>> = selectedTab
        .flatMapLatest { tab ->
            when (val type = tab.flagType) {
                null -> flowOf(tab to false)
                else -> userPreferencesRepository.observeFlagsViewSettings(type)
                    .map { tab to it.unreadOnly }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            // Type-aware seed mirroring flagsViewSettings: CYAN defaults to unread-only.
            initialValue = _selectedTab.value to (_selectedTab.value == FlagTab.Cyan),
        )

    /**
     * Optimistic shim for the per-type « non-lus uniquement » values (#317 → #751), mirroring
     * [pendingPerTabOverride]. The « +lus » re-tap is a read-then-flip site and — since #751 wired
     * the shortcut on Red and Favori too — it is **type-scoped**, not CYAN-only: each type's pending
     * flip lives under its own key, so a concurrent write on one type can never clobber another's.
     * [setFlagsUnreadOnly] seeds the key synchronously; cleared once that write persists.
     */
    private val pendingUnreadOnly = MutableStateFlow<Map<FlagType, Boolean>>(emptyMap())

    /**
     * [type]'s resolved « non-lus uniquement » value, optimistic shim ([pendingUnreadOnly]) winning
     * until the persisted value catches up. Tracks the type **regardless of the selected tab** (it is
     * not keyed on [selectedTab]) and is eagerly seeded with the type-aware default (CYAN `true`,
     * others `false` — mirrors the DataStore default), so the « +lus » re-tap and the tab suffix
     * never read a value lagging a tab switch or the cold start (cf. [selectTab] /
     * [cyanShowsReadShortcut]). The bottom-sheet switch keeps reading the selected-tab
     * [flagsViewSettings] like every other toggle.
     */
    private fun unreadOnlyState(type: FlagType): StateFlow<Boolean> = combine(
        userPreferencesRepository.observeFlagsViewSettings(type).map { it.unreadOnly },
        pendingUnreadOnly,
    ) { persisted, pending -> pending[type] ?: persisted }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = type == FlagType.CYAN,
        )

    val cyanUnreadOnly: StateFlow<Boolean> = unreadOnlyState(FlagType.CYAN)

    // #751 — Red and Favori get the same resolved values: the « +lus » re-tap shortcut and the
    // top-bar read-state indicator now cover every real flag type, not just Cyan.
    val redUnreadOnly: StateFlow<Boolean> = unreadOnlyState(FlagType.RED)
    val favoriteUnreadOnly: StateFlow<Boolean> = unreadOnlyState(FlagType.FAVORITE)

    /**
     * Whether the Cyan tab currently shows read participated topics (drives the discreet « +lus »
     * tab-label suffix, #317): Cyan selected AND its unread filter off. Reads [cyanUnreadOnly] (not
     * the selected-tab [flagsViewSettings]) so it never flashes the suffix on a cold start or a
     * tab switch before DataStore re-resolves.
     */
    val cyanShowsReadShortcut: StateFlow<Boolean> = combine(
        selectedTab,
        cyanUnreadOnly,
    ) { tab, unreadOnly -> tab == FlagTab.Cyan && !unreadOnly }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    /**
     * #751 — same « shows read » state for the Red and Favori tabs (they default to showing read
     * topics, so the indicator is honest as soon as the combine emits). Mirrors
     * [cyanShowsReadShortcut]; initialValue `false` is the resting « not selected » state — both
     * sources are eager StateFlows, so the real `tab && !unreadOnly` value lands on the first
     * collection and the topbar only reads the SELECTED tab's value through
     * [flagsReadFilterShowsRead] anyway.
     */
    val redShowsReadShortcut: StateFlow<Boolean> = combine(
        selectedTab,
        redUnreadOnly,
    ) { tab, unreadOnly -> tab == FlagTab.Red && !unreadOnly }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    val favoriteShowsReadShortcut: StateFlow<Boolean> = combine(
        selectedTab,
        favoriteUnreadOnly,
    ) { tab, unreadOnly -> tab == FlagTab.Favorite && !unreadOnly }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    /**
     * Optimistic shim for the per-tab master switch (#309 Codex review). `setFlagsPerTabOverride`
     * seeds this synchronously so [flagsPerTabOverride] (hence both the rendered switch and the
     * write routing) reflects a flip immediately, instead of waiting for the DataStore round-trip —
     * otherwise a quick « master ON » then content-toggle could route to the wrong scope because the
     * persisted value (and the switch) had not caught up. Cleared once the write persists.
     */
    private val pendingPerTabOverride = MutableStateFlow<Boolean?>(null)

    /**
     * Per-tab override master switch (#309), surfaced so the bottom sheet can show + flip it and so
     * the write routing in [setFlagsGroupByCategory] / [setFlagsHideReadCategories] reads its
     * `.value` to decide global vs per-type. The optimistic [pendingPerTabOverride] wins until the
     * persisted value catches up, so the rendered switch and the routing scope agree with the user's
     * latest tap even before DataStore commits.
     */
    val flagsPerTabOverride: StateFlow<Boolean> = combine(
        userPreferencesRepository.observeFlagsPerTabOverride(),
        pendingPerTabOverride,
    ) { persisted, pending -> pending ?: persisted }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    private fun authenticatedFlagsListState(tab: FlagTab): Flow<FlagsListUiState?> = when (tab) {
        FlagTab.Super -> superFavoriteListMapper.superFavoriteListState(superFavoriteTopics)
        FlagTab.Dt -> flowOf(null)
        else -> authenticatedFlagsListState(requireNotNull(tab.flagType))
    }

    /**
     * Builds the UI state for an authenticated tab with a real [type]. Kept as a dedicated method so
     * the `flatMapLatest` chain stays readable. Both `observe(type)` and `observeCategories()` are
     * subscribed here — and only here.
     *
     * The « non-lus uniquement » decision ([FlagsViewSettings.unreadOnly], #317) travels INSIDE
     * [FilteredFlags] rather than only as part of the outer `combine` source. This is load-bearing:
     * folding the topic filter into the same source the flags arrive on means toggling it re-emits
     * exactly once (no transient intermediate state where the new toggle value meets the stale flag
     * list), and lets the downstream section filter know whether read topics are being shown
     * ([keepFullyReadSections]). It is sourced via a `distinctUntilChanged` projection of the
     * resolved view settings so an unrelated layout change doesn't re-run the topic filter.
     */
    private fun authenticatedFlagsListState(type: FlagType): Flow<FlagsListUiState?> {
        val unreadOnlyFlow = userPreferencesRepository.observeFlagsViewSettings(type)
            .map { it.unreadOnly }
            .distinctUntilChanged()
        val filteredFlags = combine(
            flagRepository.observe(type),
            unreadOnlyFlow,
        ) { result, unreadOnly ->
            FilteredFlags(
                result = filterUnreadOnly(result, unreadOnly),
                // The « +lus » override: under « masquer les catégories sans non-lu », keep the
                // fully-read categories whenever the tab shows read topics (unreadOnly off). Showing
                // read topics is an explicit user choice on EVERY real type since the #751 re-tap
                // shortcut, so this filter must not hide them right back. It used to be CYAN-only
                // (#317), which leaked the literal filter onto the RED/FAVORITE read views and
                // collapsed them to an empty body once every flag was read (#825).
                // Semantics decision (#825): the option means « hide categories with no flag VISIBLE
                // under the current filter » — no unread flag in an unread-only view, no flag at all
                // in a « +lus » view. Inert when unreadOnly is on: [filterUnreadOnly] already removed
                // every read flag, so a surviving non-empty section always has an unread flag.
                keepFullyReadSections = !unreadOnly,
            )
        }
            // #225 — keep the existing list anchored during a user refresh instead of blanking
            // it to a cold centered spinner under the PullToRefreshBox indicator (double loader).
            // MUST stay before the section mapping (cf. KDoc on flagsState).
            .keepContentDuringRefresh(
                isLoading = { it.result is FlagsResult.Loading },
                isContent = { it.result is FlagsResult.Success },
            )

        // Prepend a synthetic Loading on the categories side so `combine` does not BLOCK the
        // flags render until the categories flow has emitted (cf. test 10bis): on a cold start
        // where Success(flags) lands before observeCategories emits, the screen must render
        // immediately using FALLBACK_CATEGORY_ORDER, then re-derive when the real catalogue
        // arrives. Without this onStart, `combine` waits for both sources and the list stalls.
        val categories = forumRepository.observeCategories()
            .onStart { emit(ForumResult.Loading) }

        // #309 — resolved per-tab LAYOUT fields (global, or this tab's override). Projected to
        // layout-only + distinctUntilChanged so an `unreadOnly` flip re-fires ONLY the inner
        // [unreadOnlyFlow] (→ filteredFlags), never this outer source — keeping the « toggling
        // re-emits exactly once » property exact (cf. KDoc above).
        val layoutFlow = userPreferencesRepository.observeFlagsViewSettings(type)
            .map { settings ->
                FlagsListLayout(
                    groupByCategory = settings.groupByCategory,
                    hideReadCategories = settings.hideReadCategories,
                    markerStyle = settings.markerStyle,
                )
            }
            .distinctUntilChanged()

        val filteredWithSubcategories = filteredFlags.flatMapLatest { filtered ->
            forumRepository.subcategoryNamesForFlags((filtered.result as? FlagsResult.Success)?.flags.orEmpty())
                .map { names -> filtered to names }
        }

        return combine(
            filteredWithSubcategories,
            categories,
            layoutFlow,
        ) { (filtered, subcategoryNames), catsResult, layout ->
            toFlagsListUiState(
                flagsResult = filtered.result,
                categoriesResult = catsResult,
                groupByCategory = layout.groupByCategory,
                hideReadCategories = layout.hideReadCategories,
                keepFullyReadSections = filtered.keepFullyReadSections,
                markerStyle = layout.markerStyle,
                subcategoryNames = subcategoryNames,
            )
        }
    }

    /**
     * Carries the « +lus » decision ([keepFullyReadSections]) alongside the filtered [result] so it
     * travels as one unit through [keepContentDuringRefresh] and the outer `combine` (cf.
     * [authenticatedFlagsListState]). It is `!unreadOnly` for every real type (#317, generalised by
     * #825): whenever a tab shows read topics, hide-read only drops truly empty categories.
     */
    private data class FilteredFlags(
        val result: FlagsResult,
        val keepFullyReadSections: Boolean,
    )

    private data class FlagsListLayout(
        val groupByCategory: Boolean,
        val hideReadCategories: Boolean,
        val markerStyle: MarkerStyle,
    )

    /**
     * Drives the « Retirer le drapeau » interaction (#99). MVI-style explicit state so the
     * UI stays declarative and the network call is gated behind a confirmation :
     *
     * - [RemoveFlagState.Idle] — nothing pending.
     * - [RemoveFlagState.Confirming] — the user tapped « Retirer » ; the screen shows the M3
     *   confirmation dialog ([RemoveFlagState.Confirming.flag] feeds its title + type).
     * - [RemoveFlagState.Removing] — the user confirmed ; the network call is in flight and the
     *   action is disabled (anti double-tap).
     *
     * One-shot results are exposed separately via [removeFlagEvents] so a config change does
     * not replay a stale snackbar.
     */
    private val _removeFlagState = MutableStateFlow<RemoveFlagState>(RemoveFlagState.Idle)
    val removeFlagState: StateFlow<RemoveFlagState> = _removeFlagState.asStateFlow()

    /**
     * One-shot success/failure of a removal, consumed by the screen to show a snackbar.
     * `null` once consumed (cf. [consumeRemoveFlagEvent]) so it does not re-fire across
     * recompositions / config changes.
     */
    private val _removeFlagEvent = MutableStateFlow<RemoveFlagEvent?>(null)
    val removeFlagEvent: StateFlow<RemoveFlagEvent?> = _removeFlagEvent.asStateFlow()

    /**
     * Toggled around ANY refresh round-trip of a flag tab — manual [refresh] AND auto
     * [maybeAutoRefresh]. Drives the top [FlagsLoadingBar], which is the SINGLE loading cue
     * (#603/#648): the central spinner was retired and the circular `PullToRefreshBox` indicator is
     * hidden (`indicator = {}`), so the thin top bar is the only cue for manual, auto and initial
     * loads. Same pattern as `ForumViewModel.isRefreshing`.
     */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * #546 — one-shot signal raised when a LANDING auto-refresh commits (app open, tab switch,
     * resume) so the screen can recall the list to the top. An auto-refresh prepends freshly-surfaced
     * flags; a held [androidx.compose.foundation.lazy.LazyListState] would otherwise leave those new
     * top rows scrolled off-screen, the « faut scroller vers le haut » beta report (tinc, Lt Ripley).
     * A return-from-topic refresh is deliberately EXCLUDED so the reader keeps their place in the list
     * they were browsing.
     *
     * Modelled as a **consumable** boolean (set on the landing refresh, reset by
     * [consumeRecallListToTop] once the screen has scrolled), exactly like [removeFlagEvent] — NOT a
     * replayable counter. A counter's latest value re-arms a brand-new collector, so a rotation or a
     * route recreation would replay the last scroll-to-top with no fresh refresh behind it (Codex
     * review #546); a consumed `false` is inert on re-subscription, and an un-consumed `true` survives
     * the recreation as a genuine still-pending scroll.
     */
    private val _recallListToTop = MutableStateFlow(false)
    val recallListToTop: StateFlow<Boolean> = _recallListToTop.asStateFlow()

    /**
     * Tab selection. Re-tapping [FlagTab.Cyan] while it is **already** selected toggles its
     * « non-lus uniquement » filter (show / hide the already-read participated topics) — the « +lus »
     * shortcut, inline replacement for the former « Cyans lus » FilterChip. Selecting Cyan from
     * another tab only switches to it (no toggle). Other tabs just switch.
     */
    fun selectTab(tab: FlagTab) {
        // A re-tap on the already-selected tab is handled in [handleReTap] (Cyan/DT « +lus » toggle,
        // or a no-op for the other tabs) — it returns true when it consumed the tap, so a real
        // transition is everything below.
        if (tab == _selectedTab.value) {
            handleReTap(tab)
            return
        }
        _selectedTab.value = tab
        // #106 — a real tab transition (tap OR swipe commit, both route through selectTab) recalls the
        // shared list to the top, reusing the one-shot #546 signal (consumed by the screen via
        // recallListToTop → requestScrollToItem(0)). NOT raised on the Cyan « +lus » filter re-tap (the
        // FilterFlipScrollResetEffect handles that) nor on a no-op re-tap nor on return-from-topic
        // (selectedTab unchanged there) — so no spurious reset on rotation either.
        _recallListToTop.value = true
    }

    /**
     * Handles a re-tap on the already-selected [tab]. Every real tab flips its « non-lus uniquement »
     * filter (the « +lus » shortcut) — Cyan/Red/Favori through the persisted [setFlagsUnreadOnly]
     * write (reading their optimistic resolved values — #751, thibw : the shortcut used to no-op
     * outside Cyan), DT through the in-memory [_dtUnreadOnly] (per-session, not persisted). Only the
     * Super keeps the #106 no-op (no unread filter to flip).
     * Never raises [_recallListToTop]: a filter flip is not a tab transition (the screen's
     * FilterFlipScrollResetEffect handles the scroll — it watches the ATOMIC per-type
     * [tabUnreadFilter], so Red/Favori flips reset the scroll exactly like Cyan's).
     */
    private fun handleReTap(tab: FlagTab) {
        when (tab) {
            FlagTab.Cyan -> setFlagsUnreadOnly(!cyanUnreadOnly.value)
            FlagTab.Red -> setFlagsUnreadOnly(!redUnreadOnly.value)
            FlagTab.Favorite -> setFlagsUnreadOnly(!favoriteUnreadOnly.value)
            FlagTab.Dt -> _dtUnreadOnly.value = !_dtUnreadOnly.value
            FlagTab.Super -> Unit // #106 — local list, but no unread filter to flip.
        }
    }

    /** User tapped « Retirer le drapeau » on [flag] : raise the confirmation dialog. */
    fun requestRemoveFlag(flag: Flag) {
        // Ignore a second request while a removal is already in flight (anti double-tap):
        // the in-flight flag wins until it resolves.
        if (_removeFlagState.value is RemoveFlagState.Removing) return
        _removeFlagState.value = RemoveFlagState.Confirming(flag)
    }

    /** User dismissed the confirmation dialog without confirming. */
    fun cancelRemoveFlag() {
        if (_removeFlagState.value is RemoveFlagState.Confirming) {
            _removeFlagState.value = RemoveFlagState.Idle
        }
    }

    /**
     * User confirmed the removal in the dialog. Moves to [RemoveFlagState.Removing] (disables
     * the action), calls the repository, and emits a one-shot [RemoveFlagEvent]. The repository
     * owns the cache reconciliation, so the list updates on its own on success — no optimistic
     * mutation here (addflag is not proven for every type, so we never speculatively re-add).
     */
    fun confirmRemoveFlag() {
        val confirming = _removeFlagState.value as? RemoveFlagState.Confirming ?: return
        val flag = confirming.flag
        _removeFlagState.value = RemoveFlagState.Removing(flag)
        viewModelScope.launch {
            try {
                // #1144 — detached: the removal the user just confirmed must not die with the
                // screen. The event below stays on this coroutine — with no screen left there is no
                // snackbar to raise; the outcome trail is DiagnosticsLog, written by the repository.
                val result = externalScope.awaitDetached { flagRepository.removeFlag(flag) }
                _removeFlagEvent.update {
                    if (result.isSuccess) {
                        RemoveFlagEvent.Success(flag.title)
                    } else {
                        RemoveFlagEvent.Failure(flag.title)
                    }
                }
            } finally {
                // #603 audit fix (fork #5) — always release the Removing lock, even if removeFlag
                // throws an error not wrapped in its Result (or the coroutine is cancelled).
                // Otherwise the state stays Removing forever and the anti-double-tap guard in
                // [requestRemoveFlag] permanently blocks any further removal until the VM is recreated.
                _removeFlagState.value = RemoveFlagState.Idle
            }
        }
    }

    /** Consume the one-shot removal event after the snackbar has been shown. */
    fun consumeRemoveFlagEvent() {
        _removeFlagEvent.value = null
    }

    /** Consume the one-shot « recall to top » signal once the screen has scrolled (#546). */
    fun consumeRecallListToTop() {
        _recallListToTop.value = false
    }

    /**
     * Bottom-sheet write (and CYAN re-tap shortcut) for « non-lus uniquement » (#317). Always writes
     * the CURRENT tab's per-type value — unlike the layout toggles, this filter is never global, so
     * there is no override routing. Super has no real [FlagType], so it is a no-op there (the sheet
     * trigger is hidden on Super anyway).
     */
    fun setFlagsUnreadOnly(enabled: Boolean) {
        val type = _selectedTab.value.flagType ?: return
        // Every real type is a read-then-flip path since #751 (the « +lus » re-tap on Cyan/Red/Favori),
        // so the optimistic shim is seeded per type: synchronously (instant, lag-free re-tap target),
        // then persist and drop the type's key only if no newer flip superseded it — same pattern as
        // [setFlagsPerTabOverride]. Keys are type-scoped, so a concurrent write on one type can't
        // clobber another's pending flip. Resolved settings take over once DataStore commits.
        pendingUnreadOnly.update { it + (type to enabled) }
        viewModelScope.launch {
            userPreferencesRepository.setFlagsUnreadOnlyForType(type, enabled)
            pendingUnreadOnly.update { pending ->
                if (pending[type] == enabled) pending - type else pending
            }
        }
    }

    /**
     * Bottom-sheet write for « grouper par catégorie » (#309). Routes to the per-type key when the
     * per-tab master switch is on AND the active tab has a real [FlagType] (not Super); otherwise
     * writes the global value. The scope is decided from [flagsPerTabOverride]`.value` — the very
     * StateFlow the sheet's master switch renders from — so the routing always matches what the
     * user currently sees (reading a fresh `observeFlagsPerTabOverride().first()` instead would open
     * an independent cold flow that can race the still-in-flight master write).
     */
    fun setFlagsGroupByCategory(enabled: Boolean) {
        val type = _selectedTab.value.flagType
        viewModelScope.launch {
            if (type != null && flagsPerTabOverride.value) {
                userPreferencesRepository.setFlagsGroupByCategoryForType(type, enabled)
            } else {
                userPreferencesRepository.setFlagsGroupByCategory(enabled)
            }
        }
    }

    /** Bottom-sheet write for « masquer les catégories sans non-lu » (#309). Same routing as
     * [setFlagsGroupByCategory]: per-type when [flagsPerTabOverride] is on and the tab is real, else
     * global. */
    fun setFlagsHideReadCategories(enabled: Boolean) {
        val type = _selectedTab.value.flagType
        viewModelScope.launch {
            if (type != null && flagsPerTabOverride.value) {
                userPreferencesRepository.setFlagsHideReadCategoriesForType(type, enabled)
            } else {
                userPreferencesRepository.setFlagsHideReadCategories(enabled)
            }
        }
    }

    /**
     * Flips the per-tab override master switch (#309) from the bottom sheet. Sets the optimistic
     * [pendingPerTabOverride] synchronously (instant switch + routing scope), then persists. The
     * shim is dropped only if no newer flip superseded this one (compareAndSet), so the persisted
     * value — and any external change, e.g. the Settings mirror — takes over afterwards.
     */
    fun setFlagsPerTabOverride(enabled: Boolean) {
        pendingPerTabOverride.value = enabled
        viewModelScope.launch {
            userPreferencesRepository.setFlagsPerTabOverride(enabled)
            pendingPerTabOverride.compareAndSet(expect = enabled, update = null)
        }
    }

    /** Bottom-sheet write for the GLOBAL marker shape (#603 PR6) — one shape for every tab, so no
     *  per-tab routing (ignores [flagsPerTabOverride]). */
    fun setFlagsMarkerStyle(style: MarkerStyle) {
        viewModelScope.launch { userPreferencesRepository.setFlagsMarkerStyle(style) }
    }

    /** Bottom-sheet write for the GLOBAL « single-line topic titles » toggle (#603). */
    fun setFlagsSingleLineTitle(enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setFlagsSingleLineTitle(enabled) }
    }

    /** Bottom-sheet write for the GLOBAL grouped-view category band style (#603). */
    fun setFlagsCategoryBandStyle(style: CategoryBandStyle) {
        viewModelScope.launch { userPreferencesRepository.setFlagsCategoryBandStyle(style) }
    }

    /** Bottom-sheet write for the GLOBAL « marker outline » toggle (#690) — one value for every tab. */
    fun setFlagsMarkerBorder(enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setFlagsMarkerBorder(enabled) }
    }

    /** Bottom-sheet write for the GLOBAL « afficher la barre de chargement » toggle (#728). */
    fun setFlagsShowLoadingBar(enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setFlagsShowLoadingBar(enabled) }
    }

    /** Bottom-sheet write for the GLOBAL account-avatar border toggle (#718). */
    fun setAvatarBorder(enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setAvatarBorder(enabled) }
    }

    /** Bottom-sheet write for the GLOBAL « +lus » indicator style (#661) — one value for every tab. */
    fun setFlagsPlusLusIndicatorStyle(style: PlusLusIndicatorStyle) {
        viewModelScope.launch { userPreferencesRepository.setFlagsPlusLusIndicatorStyle(style) }
    }

    /** Bottom-sheet write for the GLOBAL left-container glyph style (#603/#665) — one value for every tab. */
    fun setFlagsGlyphStyle(style: FlagGlyphStyle) {
        viewModelScope.launch { userPreferencesRepository.setFlagsGlyphStyle(style) }
    }

    fun refresh() {
        // Super is a placeholder with no backing FlagType — pull-to-refresh is a no-op there.
        val type = _selectedTab.value.flagType ?: return
        // A manual refresh captures the post-reading state too, so the generations visible at this
        // call would only duplicate the fan-out on the next landing — consume them (#378 follow-up).
        flagOpenedGenerationConsumed = flagOpenedGeneration
        // #743 — a pull on the tab whose landing is waiting behind an in-flight refresh IS that
        // landing's fetch: drop the replay so it does not double the fan-out right after the pull.
        if (deferredLandingType == type) deferredLandingType = null
        viewModelScope.launch {
            beginRefresh(type)
            try {
                flagRepository.refresh(type)
            } finally {
                endRefresh()
            }
        }
    }

    /**
     * #378 follow-up (retours dev v118) — the screen calls this when the user opens a topic from
     * the list. The next [maybeAutoRefresh] then BYPASSES the throttle window: coming back from a
     * just-read topic is precisely when the flag state changed (the read topic should drop out of
     * an unread-only view), and the 15 s window was eating exactly that case for fast readers
     * ("je lis vite fait le dernier message → retour → pas de refresh"). The throttle keeps
     * protecting the no-read round-trips (bottom-tab switches, immediate back-and-forth), which
     * is what it was for — the REST fan-out is one GET per public category.
     */
    fun onFlagOpened() {
        flagOpenedGeneration++
    }

    /**
     * #378 — auto-refresh on landing. Invoked by the screen every time it (re)enters the
     * composition: app open, back from a topic, return from another bottom tab. Delegates to
     * [refresh] (same `isRefreshing` indicator — the requested visual cue) when ALL of:
     *
     * - the « auto-refresh » preference is on (default; the Settings toggle is the opt-out),
     * - the user is authenticated (an anonymous landing has no flags to refresh),
     * - the selected tab has a real [FlagType] (Super/DT placeholders have no backend),
     * - no refresh is already in flight — a landing on ANOTHER tab that meets one is not dropped
     *   but replayed once it settles (#743, cf. [deferredLandingType]),
     * - the last auto-refresh OF THAT TAB'S TYPE is older than [AUTO_REFRESH_THROTTLE] (one window
     *   per type, #743) — the per-tab REST fan-out is ~one GET per public category, so rapid
     *   back-and-forth between a topic and the list must not multiply it. A manual pull-to-refresh
     *   is never throttled and does not arm the throttle (it goes straight through [refresh]).
     */
    fun maybeAutoRefresh() {
        // Snapshot the tab at CALL time (the landing being refreshed) — re-reading it after the
        // suspension points below could refresh a different tab than the one that landed, or
        // no-op on a placeholder while still arming the throttle (Codex review on PR #421).
        val type = _selectedTab.value.flagType ?: return
        if (deferLandingIfRefreshing(type)) return
        // Snapshot at CALL time (same rationale as the tab snapshot above): a read armed AFTER
        // this landing's call belongs to the NEXT landing. The launch suspends on the pref/auth
        // gates below — reading the generation there would let this refresh consume a reading it
        // cannot have captured yet, losing the bypass for the actual return (Codex review).
        val openedGenerationAtCall = flagOpenedGeneration
        viewModelScope.launch {
            if (!userPreferencesRepository.observeFlagsAutoRefresh().first()) return@launch
            if (authRepository.observeAuthState().first() !is AuthState.Authenticated) return@launch
            val now = clock.instant()
            // #743 — this tab's OWN window: another tab's recent refresh never throttles this landing.
            val last = lastAutoRefreshAt[type]
            // Throttle SKIPPED when a topic was opened since the last consuming refresh (see
            // [onFlagOpened]): that landing is a return from a read, the state most worth
            // refreshing.
            val returningFromTopic = openedGenerationAtCall > flagOpenedGenerationConsumed
            if (!returningFromTopic && last != null && Duration.between(last, now) < AUTO_REFRESH_THROTTLE) {
                return@launch
            }
            // Re-check after the suspensions: a manual pull-to-refresh may have started while
            // the pref/auth reads were in flight — don't double the REST fan-out (same type), or
            // defer this landing behind it (other type, #743).
            if (deferLandingIfRefreshing(type)) return@launch
            // Consume ONLY the generations visible at call time — a read armed during the
            // suspensions above stays pending for the next landing.
            flagOpenedGenerationConsumed = maxOf(flagOpenedGenerationConsumed, openedGenerationAtCall)
            lastAutoRefreshAt[type] = now
            beginRefresh(type)
            try {
                flagRepository.refresh(type)
                // #546 — a genuine landing / tab-switch / resume refresh: recall the list to the top
                // so the freshly prepended flags are visible. A return-from-topic refresh keeps the
                // reader's current position (it is not a fresh landing). One-shot signal, consumed by
                // the screen — see [recallListToTop].
                if (!returningFromTopic) _recallListToTop.value = true
            } finally {
                endRefresh()
            }
        }
    }

    /**
     * #743 — in-flight guard shared by the two [maybeAutoRefresh] checkpoints. `true` when a refresh
     * is running (the landing must not start a concurrent fan-out); the landing is then remembered
     * for replay in [endRefresh] if it targets ANOTHER type than the one in flight, and cleared
     * otherwise (latest landing wins, cf. [deferredLandingType]).
     */
    private fun deferLandingIfRefreshing(type: FlagType): Boolean {
        if (!_isRefreshing.value) return false
        deferredLandingType = type.takeIf { it != refreshingType }
        return true
    }

    /** Raises the single loading cue ([isRefreshing]) for [type]'s round-trip, manual or auto. */
    private fun beginRefresh(type: FlagType) {
        refreshingType = type
        _isRefreshing.value = true
    }

    /**
     * Clears the loading cue, then replays the landing deferred behind this refresh (#743) — through
     * [maybeAutoRefresh], so it still meets the pref/auth gates and its own throttle window — provided
     * that tab is still the selected one; a tab left meanwhile lands again on its own when re-selected.
     */
    private fun endRefresh() {
        refreshingType = null
        _isRefreshing.value = false
        val deferred = deferredLandingType ?: return
        deferredLandingType = null
        if (deferred == _selectedTab.value.flagType) maybeAutoRefresh()
    }

    /**
     * #6 — the screen calls this when the « DT » tab is OPENED (a stable [LaunchedEffect], not the
     * raw composition). Loads the MultiMP conversations once per session: re-entering the
     * composition reuses the already-loaded state ([dtFetchStarted] guard). The fan-out (inbox page
     * + MPStorage scan) is deliberately kept off the per-category auto-refresh path. Use
     * [refreshDt] for an explicit user re-pull.
     */
    fun onDtTabOpened() {
        if (dtFetchStarted) return
        loadDt(isRefresh = false)
    }

    /**
     * Explicit user-driven reload of the DT list (pull-to-refresh / retry), bypassing the guard.
     * [isRefresh] keeps the current content visible under the `PullToRefreshBox` indicator instead of
     * blanking it to a cold spinner (#546 directive XaTriX) — the retry CTA path also lands here, so
     * the (already-Error) state is simply re-loaded; only a refresh-over-content needs the no-blank.
     */
    fun refreshDt() {
        loadDt(isRefresh = true)
    }

    /**
     * Loads the DT list as the UNION of two sources, deduplicated by `threadId` (#6, directive
     * XaTriX 2026-06-19):
     *  - the inbox MultiMP rows of PAGE 1 ([DT_INBOX_PAGE]) — the hard, fully-described source
     *    ([DtListItem.InboxBacked]: subject, unread dot, lastPage), each best-effort joined with its
     *    MPStorage resume page ;
     *  - the MPStorage `mpFlags` entries whose `threadId` is ABSENT from that page
     *    ([DtListItem.StorageOnly]: thread id + resume page only — no subject, no read/unread state).
     *
     * Rows are concatenated inbox-first (recent activity, complete + reliable data) then the orphan
     * entries in `mpFlags.list` order (no per-entry date to sort on). The MPStorage read is
     * best-effort: every degraded outcome ([MpStorageResult.NotFound] / [MpStorageResult.Unreadable]
     * / an exception) yields no entries, so the list still renders from the inbox alone — the storage
     * scan never fails the conversation list.
     *
     * Only the inbox load itself is fatal: it is the list's primary source, so a network/session
     * error there surfaces [DtListUiState.Error] (the screen offers a retry / reconnect CTA).
     *
     * [DtListUiState.Empty] iff the inbox PAGE 1 has no MultiMP AND MPStorage has no orphan entry.
     * Scope (#6): only inbox PAGE 1 is scanned + whatever MPStorage already knows ; a full multi-page
     * inbox sweep stays DEFERRED, so older pages are not covered — the scan caveat lives in the « Section
     * DT » settings toggle description (`settings_flags_show_dt_section_description`, #662), and the
     * empty-state copy (`flags_dt_empty_subtitle`) assumes that semantics.
     */
    private fun loadDt(isRefresh: Boolean) {
        dtFetchStarted = true
        // Latest-wins: cancel any in-flight DT fetch and out-generation it so a previous load's
        // late completion is ignored (two rapid refreshDt, or a refresh racing a still-running open).
        dtFetchJob?.cancel()
        val generation = ++dtGeneration
        // #546 — a user refresh keeps the current content visible (the PullToRefreshBox indicator
        // anchors over it); a cold open shows the centered spinner. So only the cold path writes
        // Loading, and the refresh path raises [_dtIsRefreshing] instead.
        if (isRefresh) {
            _dtIsRefreshing.value = true
        } else {
            _dtListState.value = DtListUiState.Loading
        }
        dtFetchJob = viewModelScope.launch {
            try {
                loadDtBody(generation)
            } finally {
                // Clear the refresh indicator only for the LIVE generation: a load cancelled by a
                // newer refreshDt or an account switch (resetDtState bumped the generation and already
                // owns the indicator reset) must not clear the indicator the new load just raised.
                if (generation == dtGeneration) _dtIsRefreshing.value = false
            }
        }
    }

    /**
     * Body of [loadDt], extracted so the [isRefresh] indicator bookkeeping (the `try`/`finally`) stays
     * out of the fetch logic and the cyclomatic budget. Publishes only for the still-live [generation]
     * (stale-write guard, cf. [loadDt] KDoc): a fetch invalidated by an account switch or a newer
     * refresh never overwrites the current session's state.
     */
    @Suppress("ReturnCount") // The stale-write generation guards are the natural shape of this fetch.
    private suspend fun loadDtBody(generation: Int) {
        val inboxMulti = try {
            messagesRepository.getPrivateMessageList(page = DT_INBOX_PAGE)
                .items
                .filter { it.isMultiRecipient }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            // Reset the guard so a retry can re-run; the inbox is the list's only hard source.
            // Only the live generation may publish — a fetch invalidated by an account switch
            // (resetDtState bumped the generation) must not overwrite the new session's Loading.
            if (generation == dtGeneration) {
                dtFetchStarted = false
                _dtListState.value = DtListUiState.Error(error)
            }
            return
        }

        // Best-effort: a missing / unreadable / failed storage read leaves an empty list, so the
        // union degrades to the inbox rows alone (no resume badge, no orphan rows). The list is
        // never blocked. NOTE: no early-return on an empty inbox — orphan MPStorage entries may
        // still populate the list (a page-1 box without any MultiMP is NOT necessarily Empty).
        val entries = fetchStorageEntries()
        // Re-check the generation AFTER the (suspending) storage scan too: the account may have
        // switched while the scan was in flight, so the stale result must not republish.
        if (generation != dtGeneration) return

        val items = buildDtItems(inboxMulti, entries)
        if (items.isEmpty()) {
            if (generation == dtGeneration) _dtListState.value = DtListUiState.Empty
            return
        }
        if (generation == dtGeneration) _dtListState.value = DtListUiState.Content(items)
    }

    /**
     * Best-effort MPStorage read for the DT union. Returns the FULL list of DTCloud `mpFlags`
     * entries, EMPTY for every degraded outcome ([MpStorageResult.NotFound] /
     * [MpStorageResult.Unreadable]) or transport failure — the caller renders the list regardless.
     * Each entry's `page` is a reading-RESUME position, NOT a read/unread state (#361/ADR-013): the
     * unread dot is the inbox [PrivateMessageSummary.hasUnread], never this page number.
     */
    @Suppress("SwallowedException") // best-effort: a storage read failure deliberately degrades to
    // « inbox rows only, no orphan, no resume badge » so the list still renders; the cause is irrelevant.
    private suspend fun fetchStorageEntries(): List<MpStorageFlagEntry> = try {
        when (val storage = mpStorageRepository.fetchStorage()) {
            is MpStorageResult.Found -> storage.document.mpFlags
            MpStorageResult.NotFound, MpStorageResult.Unreadable -> emptyList()
        }
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        emptyList()
    }

    // Round-2 review (PR #207): `logout()` was removed from this ViewModel — the global account
    // menu (#198) now drives the logout from `AppAccountViewModel.logout()`, which owns the
    // canonical `clearSessionCache → authRepository.logout` ordering. Keeping a second copy
    // here was dead code that drifted at the first refactor; the matching invariant test was
    // moved to `AppAccountViewModelTest`.

    /**
     * Maps a (filtered) [FlagsResult] + a categories [ForumResult] into the single UI state
     * (#179). A `Loading`/`Failure` on the categories side is **non-fatal**: it only affects which
     * canonical order is used, never whether the flags render. The hard-coded
     * [FALLBACK_CATEGORY_ORDER] kicks in until the real catalogue arrives, so flags appear
     * immediately on a cold start and no flag is ever lost (anti-regression #251).
     *
     * [groupByCategory] / [hideReadCategories] are the two persisted Drapeaux layout preferences
     * (#179 follow-up): flat vs grouped layout, and whether to hide categories without an unread
     * flag. [keepFullyReadSections] (the « +lus » override, #317/#825) is forwarded from
     * [FilteredFlags]: when a tab explicitly shows read topics, its fully-read categories survive
     * the hide filter so those topics stay reachable.
     */
    private fun toFlagsListUiState(
        flagsResult: FlagsResult,
        categoriesResult: ForumResult<List<Category>>,
        groupByCategory: Boolean,
        hideReadCategories: Boolean,
        keepFullyReadSections: Boolean,
        markerStyle: MarkerStyle,
        subcategoryNames: Map<SubcategoryKey, String>,
    ): FlagsListUiState = when (flagsResult) {
        FlagsResult.Loading -> FlagsListUiState.Loading
        is FlagsResult.Failure -> FlagsListUiState.Failure(flagsResult.cause)
        is FlagsResult.Success -> FlagsListUiState.Success(
            flagsContent(
                flags = flagsResult.flags,
                categoriesResult = categoriesResult,
                groupByCategory = groupByCategory,
                hideReadCategories = hideReadCategories,
                keepFullyReadSections = keepFullyReadSections,
                markerStyle = markerStyle,
                subcategoryNames = subcategoryNames,
            ),
        )
    }

    /**
     * Shapes the [FlagsContent] for a `Success`: a flat list (legacy view) when [groupByCategory]
     * is off, otherwise the category-grouped sections — with the « masquer les catégories sans
     * non-lu » filter applied when [hideReadCategories] is on. The flat list keeps the repository
     * order (last reply descending); grouping reorders by canonical category.
     */
    private fun flagsContent(
        flags: List<Flag>,
        categoriesResult: ForumResult<List<Category>>,
        groupByCategory: Boolean,
        hideReadCategories: Boolean,
        keepFullyReadSections: Boolean,
        markerStyle: MarkerStyle,
        subcategoryNames: Map<SubcategoryKey, String>,
    ): FlagsContent {
        val rows = toFlagRows(flags, markerStyle, subcategoryNames)
        if (!groupByCategory) return FlagsContent.Flat(rows)
        val grouped = groupFlagRowsByCategory(rows, resolveCategoryOrder(categoriesResult))
        val sections = if (hideReadCategories) {
            filterCategoriesWithUnread(grouped, keepFullyRead = keepFullyReadSections)
        } else {
            grouped
        }
        return FlagsContent.Grouped(sections)
    }

    /**
     * Resolves the canonical category order from the catalogue result. A `Success` carrying an
     * EMPTY catalogue is treated like « no catalogue yet » (falls back to
     * [FALLBACK_CATEGORY_ORDER]), not like an empty order. Otherwise the double-empty case (zero
     * flags AND zero categories) would render zero sections — a fully blank body with not even an
     * « aucun drapeau » band. In prod the REST endpoint always returns the 19 categories, so this
     * only guards the degenerate Success-but-empty edge; flags in unknown cats are still never
     * lost either way.
     */
    private fun resolveCategoryOrder(
        categoriesResult: ForumResult<List<Category>>,
    ): List<FlagCategoryOrderEntry> = when (categoriesResult) {
        is ForumResult.Success -> categoriesResult.value
            .takeIf { it.isNotEmpty() }
            ?.map { FlagCategoryOrderEntry(it.id, it.name) }
            ?: FALLBACK_CATEGORY_ORDER
        else -> FALLBACK_CATEGORY_ORDER
    }

    /**
     * Applies the « non-lus uniquement » filter (#317) when [unreadOnly] is on: keeps only topics
     * with [fr.forumhfr.redface2.core.model.Flag.hasUnread]. Generalises the former cyan-only
     * actionable-subset filter to every type — RED and FAVORITE now honour the same toggle (off by
     * default for them, on by default for CYAN, cf. the type-aware default in the repository).
     */
    private fun filterUnreadOnly(result: FlagsResult, unreadOnly: Boolean): FlagsResult {
        if (!unreadOnly) return result
        return when (result) {
            is FlagsResult.Success -> result.copy(flags = result.flags.filter { it.hasUnread })
            else -> result
        }
    }

    private fun clearFlagsCacheIfSessionChanged(state: AuthState?) {
        when (state) {
            null -> Unit
            AuthState.Anonymous -> {
                observedPseudo = null
                resetDtState()
                flagRepository.clearSessionCache()
            }
            is AuthState.Authenticated -> {
                // Clear on the first authenticated emission too: the repository is a
                // singleton and may outlive this ViewModel, so a recreated Flags screen
                // must not trust whatever per-user cache was left in memory.
                if (observedPseudo != state.pseudo) {
                    resetDtState()
                    flagRepository.clearSessionCache()
                }
                observedPseudo = state.pseudo
            }
        }
    }

    /** Drop the DT list (and its one-shot guard) so a logout / account switch never shows the
     * previous user's MultiMP conversations; the next [onDtTabOpened] re-scans for the new session.
     * Cancels any in-flight fetch and bumps [dtGeneration] so a load that was mid-flight when the
     * account switched can no longer publish the previous account's conversations (stale write). */
    private fun resetDtState() {
        dtFetchJob?.cancel()
        dtFetchJob = null
        dtGeneration++
        dtFetchStarted = false
        _dtListState.value = DtListUiState.Loading
        // Clear any in-flight refresh indicator (the cancelled fetch's finally is out-generationed
        // and won't). KEEP [_dtUnreadOnly] — it is a display preference, not session data, so an
        // account switch must not silently reset the user's « +lus » choice (#546 directive XaTriX).
        _dtIsRefreshing.value = false
    }
}

/**
 * UI-level tab model for the Drapeaux screen. The three real tabs map to a [FlagType] the
 * repository can fetch ; [Super] is a local « super favoris » feature and intentionally carries no
 * [FlagType] (no `flag_owntopic` is known, no HFR backend exists).
 *
 * The ViewModel keeps fetching/filtering on [FlagType] for the three real tabs ; this type
 * only drives which tab is selected so the screen can render the local Super body without polluting
 * the domain enum.
 */
sealed interface FlagTab {
    /** Backing flag type, or `null` for the placeholder [Super] tab. */
    val flagType: FlagType?

    data object Cyan : FlagTab {
        override val flagType: FlagType = FlagType.CYAN
    }

    data object Red : FlagTab {
        override val flagType: FlagType = FlagType.RED
    }

    data object Favorite : FlagTab {
        override val flagType: FlagType = FlagType.FAVORITE
    }

    /** Local « super favoris ». No flag-bucket fetch, no HFR backend. */
    data object Super : FlagTab {
        override val flagType: FlagType? = null
    }

    /**
     * Placeholder — future « discussions suivies » (DT) whose flags will sync through the
     * MPStorage v0.1 document (#6, mpFlags DTCloud). No fetch, no backend yet ; the tab
     * itself is opt-in behind the « section DT » Settings toggle.
     */
    data object Dt : FlagTab {
        override val flagType: FlagType? = null
    }
}

data class FlagsTabListUiState(
    val tab: FlagTab,
    val flagsState: FlagsListUiState?,
)

/**
 * Single route-facing UI state for the category-grouped Drapeaux list (#179). Replaces a
 * direct `FlagsResult` exposure so a `Loading`/`Failure` can never diverge from a stale set
 * of sections (cf. impl prompt §4.1 correction #2).
 *
 * A `null` value (not a member of this interface) keeps its prior meaning « not applicable »:
 * the Anonymous login intro or the DT body, which owns [DtListUiState].
 */
sealed interface FlagsListUiState {
    /** Cold fetch in flight, no prior content to keep. */
    data object Loading : FlagsListUiState

    /**
     * Loaded flags, rendered either grouped by category or as a flat list depending on the
     * persisted view preference (cf. [FlagsContent]).
     */
    data class Success(val content: FlagsContent) : FlagsListUiState

    /** A flags fetch failed; [cause] drives the reconnect/retry CTA (e.g. SessionExpiredException). */
    data class Failure(val cause: Throwable) : FlagsListUiState
}

/**
 * The two render shapes of a loaded Drapeaux list (#179 follow-up). The user picks the layout in
 * Settings ([UserPreferencesRepository.observeFlagsGroupByCategory]); the ViewModel emits the
 * matching shape so the screen stays a pure `when`.
 */
sealed interface FlagsContent {
    /**
     * Grouped sections in canonical category order. With the « masquer les catégories sans
     * non-lu » preference off, empty sections are included (HFR web parity) and [sections] is
     * non-empty in practice (the fallback order guarantees the known categories). With it on,
     * only categories carrying an unread flag survive (cf. [filterCategoriesWithUnread]).
     */
    data class Grouped(val sections: List<FlagCategorySection>) : FlagsContent

    /**
     * Flat list in repository order (last reply descending) — the legacy pre-#179 view, kept as
     * an explicit fallback so the user can always read every flag at once without category bands.
     */
    data class Flat(val rows: List<FlagRowUiModel>) : FlagsContent
}

/**
 * #378 — minimum delay between two auto-refreshes OF THE SAME [FlagType]
 * ([FlagsViewModel.maybeAutoRefresh]; one window per type since #743). 15 s: shorter than any
 * realistic topic-read round-trip (the « back from a topic » trigger stays effective) but long
 * enough that re-landing on a tab — resume, back-and-forth with another tab, popping in and out of
 * a topic — does not re-run its per-category REST fan-out every time. Bouncing across the three
 * tabs costs at most one fan-out per tab per window, and never two at once (refreshes stay
 * sequential, cf. [FlagsViewModel.maybeAutoRefresh]).
 */
private val AUTO_REFRESH_THROTTLE: Duration = Duration.ofSeconds(15)

/**
 * State of the « Retirer le drapeau » interaction (#99). [Confirming] and [Removing] carry
 * the target [Flag] so the dialog can render its title + type and the screen can disable the
 * matching row's action while the call is in flight.
 */
sealed interface RemoveFlagState {
    data object Idle : RemoveFlagState
    data class Confirming(val flag: Flag) : RemoveFlagState
    data class Removing(val flag: Flag) : RemoveFlagState
}

/**
 * One-shot outcome of a removal, surfaced as a snackbar. Carries the topic [title] for the
 * message ; no raw error detail (the repository already redacts the HFR body).
 */
sealed interface RemoveFlagEvent {
    data class Success(val title: String) : RemoveFlagEvent
    data class Failure(val title: String) : RemoveFlagEvent
}

/**
 * #6 — inbox page scanned for the DT (MultiMP) list. Page 1 holds the most recent conversations;
 * the inbox is paged at 50 newest-first, so this covers the active DT in practice. A full
 * multi-page sweep is deliberately deferred (it would multiply the cost of an already-expensive
 * MPStorage scan) — see the rapport / follow-up note.
 */
private const val DT_INBOX_PAGE = 1

/**
 * #6 — UI state for the « DT » tab (the user's MultiMP conversations). A dedicated channel, NOT a
 * reuse of [FlagsListUiState] / [Flag] : a DT row is a private-message conversation enriched with
 * an MPStorage reading position, never a forum flag (arbitrage Codex).
 */
sealed interface DtListUiState {
    /** First scan in flight (or reset after a logout / account switch). */
    data object Loading : DtListUiState

    /** The inbox loaded but holds no MultiMP conversation. */
    data object Empty : DtListUiState

    /**
     * #546 directive XaTriX — the union is NON-empty but the « non-lus uniquement » filter
     * ([FlagsViewModel.dtUnreadOnly]) hid every row (no unread inbox-backed conversation). Distinct
     * from [Empty] (no conversation at all): the screen shows « aucune conversation non lue » and the
     * user can re-tap the DT tab to reveal the read ones (« +lus »). Produced only by [filterDt].
     */
    data object NoUnread : DtListUiState

    /**
     * The DT union: inbox PAGE 1 MultiMP rows ∪ orphan MPStorage entries, deduplicated by
     * `threadId` and ordered inbox-first then `mpFlags.list` order (#6). The multi-page inbox limit
     * remains — surfaced in the « Section DT » settings toggle description (#662).
     */
    data class Content(val items: List<DtListItem>) : DtListUiState

    /** The inbox load failed (network / session). The MPStorage read NEVER reaches this state —
     * it is best-effort and degrades to « no badge ». [cause] drives the reconnect/retry CTA. */
    data class Error(val cause: Throwable) : DtListUiState
}

/**
 * One DT row (#6). Two natures, both carrying a [threadId] (identity / dedup key) and an optional
 * MPStorage [resumePage] — the « reprise p.N » badge, a reading POSITION never a read/unread state
 * (#361/ADR-013).
 */
sealed interface DtListItem {
    val threadId: Int
    val resumePage: Int?

    /**
     * A conversation present on inbox PAGE 1: the rich, fully-described row (subject, `hasUnread`
     * dot, `lastPage`). [resumePage] is `null` when the conversation has no MPStorage entry (or the
     * storage was absent / unreadable / failed to load).
     */
    data class InboxBacked(
        val conversation: PrivateMessageSummary,
        override val resumePage: Int?,
    ) : DtListItem {
        override val threadId: Int get() = conversation.threadId
    }

    /**
     * An MPStorage `mpFlags` entry absent from inbox PAGE 1: only [threadId] + reading position are
     * known — NO subject, NO read/unread state. [resumePage] is the entry's `page` (non-null in
     * practice; the parser falls back to 1). [numreponse] is kept for a future anchor but does not
     * influence navigation today.
     */
    data class StorageOnly(
        override val threadId: Int,
        override val resumePage: Int?,
        val numreponse: Int?,
    ) : DtListItem
}

/**
 * Pure builder of the DT union (#6, directive XaTriX 2026-06-19). [inboxMulti] are the inbox PAGE 1
 * MultiMP summaries (already filtered) ; [storageEntries] are the DTCloud `mpFlags` entries
 * (best-effort, possibly empty). Returns inbox-backed rows first (in inbox order, each joined with
 * its MPStorage resume page), then the orphan storage-only rows whose `threadId` is absent from the
 * inbox, in `mpFlags.list` order. Dedup is by `threadId`: an entry already inbox-backed is never
 * doubled as storage-only, and an internal `mpFlags` `threadId` duplicate keeps the first occurrence
 * (so the [Content] list never holds two items with the same LazyColumn key).
 */
internal fun buildDtItems(
    inboxMulti: List<PrivateMessageSummary>,
    storageEntries: List<MpStorageFlagEntry>,
): List<DtListItem> {
    // Dedup the MPStorage entries by threadId FIRST (keep first occurrence) so both the inbox-row
    // resume join and the orphan rows agree on the same page when mpFlags carries a duplicate
    // threadId (Codex review: `associate` kept the LAST, diverging from the orphans' `distinctBy`).
    val dedupedEntries = storageEntries.distinctBy { it.threadId }
    val resumeByThread = dedupedEntries.associate { it.threadId to it.page }
    val inboxThreadIds = inboxMulti.mapTo(HashSet()) { it.threadId }
    return buildList {
        inboxMulti.forEach { summary ->
            add(DtListItem.InboxBacked(summary, resumeByThread[summary.threadId]))
        }
        dedupedEntries.asSequence()
            .filter { it.threadId !in inboxThreadIds }
            .forEach { entry ->
                add(DtListItem.StorageOnly(entry.threadId, entry.page, entry.numreponse))
            }
    }
}

/**
 * Pure « non-lus uniquement » filter for the DT list (#546 directive XaTriX), applied to the raw
 * union [state] before it reaches the screen. Mirrors the flag-list [FlagsViewModel.filterUnreadOnly]
 * but for the DT channel's two item natures:
 *
 *  - [unreadOnly] off → passthrough (the full union, « +lus »).
 *  - [unreadOnly] on, [DtListUiState.Content] → keep ONLY the [DtListItem.InboxBacked] rows whose
 *    conversation has an unread message. [DtListItem.StorageOnly] orphans (read state UNKNOWN off
 *    inbox) and read inbox rows are excluded — the unread view is the actionable subset only.
 *  - Filtered to empty while the raw union was NON-empty → [DtListUiState.NoUnread] (distinct from
 *    [DtListUiState.Empty]: there ARE conversations, just none unread; the screen invites « +lus »).
 *  - [DtListUiState.Loading] / [DtListUiState.Empty] / [DtListUiState.Error] → passthrough.
 */
internal fun filterDt(state: DtListUiState, unreadOnly: Boolean): DtListUiState {
    // Passthrough unless we are filtering a loaded Content union (Loading/Empty/Error and the
    // « +lus » view keep the state as-is). Single return point (detekt ReturnCount).
    if (!unreadOnly || state !is DtListUiState.Content) return state
    val unread = state.items.filter { it is DtListItem.InboxBacked && it.conversation.hasUnread }
    // The union held conversations but none is unread → a dedicated NoUnread state, not Empty.
    return if (unread.isEmpty()) DtListUiState.NoUnread else DtListUiState.Content(unread)
}
