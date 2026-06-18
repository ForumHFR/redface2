package fr.forumhfr.redface2.feature.flags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.domain.preferences.FlagsViewSettings
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.messages.PrivateMessageSummary
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageResult
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
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
    private val messagesRepository: MessagesRepository,
    private val mpStorageRepository: MpStorageRepository,
    private val clock: Clock,
) : ViewModel() {

    private var observedPseudo: String? = null

    /**
     * #378 — instant of the last refresh triggered through [maybeAutoRefresh], for the
     * throttle. ViewModel-scoped on purpose: the ViewModel survives the screen leaving the
     * composition (tab switch, topic push), so rapid back-and-forth shares one window, while
     * a cold app start (fresh ViewModel) always allows the first auto-refresh.
     */
    private var lastAutoRefreshAt: Instant? = null

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

    private val _selectedTab = MutableStateFlow<FlagTab>(FlagTab.Cyan)
    val selectedTab: StateFlow<FlagTab> = _selectedTab.asStateFlow()

    /**
     * Whether the opt-in « DT » placeholder tab is shown (Settings toggle, default off).
     * The content arrives with the MPStorage sync (#6) — until then the tab only renders
     * a placeholder body, like [FlagTab.Super].
     */
    val showDtTab: StateFlow<Boolean> = userPreferencesRepository.observeShowDtSection()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    /**
     * #6 — UI state for the « DT » tab: the user's MultiMP conversations (inbox `cat=prive`,
     * [PrivateMessageSummary.isMultiRecipient]) enriched best-effort with the MPStorage reading
     * positions (DTCloud's `mpFlags`). A dedicated state (NOT a [FlagsListUiState] / [Flag] reuse,
     * arbitrage Codex): the DT channel is a private-message list, not a flag list, so it never
     * shares the flag model's contract (`flagType == null` stays a pure « no backend » marker for
     * Super/DT placeholders elsewhere in this ViewModel).
     *
     * The fetch is triggered on tab OPENING ([onDtTabOpened]) — never from the per-category
     * auto-refresh — because [MpStorageRepository.fetchStorage] is expensive (it scans the inbox to
     * discover the storage MP). Idle until first opened.
     */
    private val _dtListState = MutableStateFlow<DtListUiState>(DtListUiState.Loading)
    val dtListState: StateFlow<DtListUiState> = _dtListState.asStateFlow()

    /** One-shot guard so re-entering the composition (recomposition, ON_RESUME) does not re-scan. */
    private var dtFetchStarted = false

    val authState: StateFlow<AuthState?> = authRepository.observeAuthState()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    /**
     * UI state for the currently selected tab (#179). Replaces the former flat `FlagsResult?`
     * exposure: the screen renders either one section per forum category in canonical order
     * (empty sections included for HFR web parity) or — when the persisted « grouper par
     * catégorie » preference is off — the legacy flat list, grouping the already-loaded flat list
     * client-side either way (no extra authenticated fetch, prefetch-non-auth invariant). The
     * « masquer les catégories sans non-lu » preference further trims the grouped sections.
     *
     * - Anonymous → `null` (the home tab shows the login intro instead of a list).
     * - [FlagTab.Super] → `null` (placeholder body, no backend, **no** repository observation —
     *   neither flags NOR categories are observed, cf. §5 « no double-fetch »).
     * - Authenticated + a real [FlagType] → the per-tab flag flow (« non-lus uniquement » filter
     *   #154/#317, then [keepContentDuringRefresh] #225) is combined with
     *   [ForumRepository.observeCategories] to build [FlagsListUiState]. Categories are observed
     *   **only** in this branch so we never trigger a spurious public categories fetch for
     *   Anonymous/Super.
     *
     * Ordering of the mapping is load-bearing: unread filter → `keepContentDuringRefresh` →
     * combine with categories → map to sections. Reversing the first two would regress #225
     * (the list blanks to a cold spinner during a pull-to-refresh).
     *
     * A [ForumResult.Failure]/[ForumResult.Loading] — or an EMPTY [ForumResult.Success] — on the
     * categories side NEVER turns a `FlagsResult.Success` into a [FlagsListUiState.Failure]: the
     * hard-coded [FALLBACK_CATEGORY_ORDER] is used so the sections still render and no flag is lost
     * (an empty Success is treated as « no catalogue yet », guarding the double-empty blank body).
     *
     * Empty sections are kept for **all** tabs (web parity, MVP); the per-tab empty wording is
     * chosen in Compose. `refreshCategories()` is **never** called from here (the 24h memory
     * cache of [ForumRepository] is enough; pull-to-refresh refreshes flags only).
     */
    val flagsState: StateFlow<FlagsListUiState?> = authState
        .onEach(::clearFlagsCacheIfSessionChanged)
        .flatMapLatest { state ->
            when (state) {
                null -> flowOf<FlagsListUiState?>(null)
                AuthState.Anonymous -> flowOf<FlagsListUiState?>(null)
                is AuthState.Authenticated -> selectedTab.flatMapLatest { tab ->
                    when (val type = tab.flagType) {
                        null -> flowOf<FlagsListUiState?>(null) // Super placeholder: no fetch.
                        else -> authenticatedFlagsListState(type)
                    }
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    /**
     * Display-settings bottom sheet state (#309). Tracks the RESOLVED view settings for the
     * currently selected tab so the sheet's two switches reflect what the list is actually using
     * (global, or this tab's override). The [FlagTab.Super] placeholder has no real [FlagType], so
     * it falls back to the global pair — the trigger is hidden there anyway (no list to configure).
     */
    val flagsViewSettings: StateFlow<FlagsViewSettings> = selectedTab
        .flatMapLatest { tab ->
            when (val type = tab.flagType) {
                null -> combine(
                    userPreferencesRepository.observeFlagsGroupByCategory(),
                    userPreferencesRepository.observeFlagsHideReadCategories(),
                ) { group, hide -> FlagsViewSettings(group, hide) }
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
     * Optimistic shim for CYAN's « non-lus uniquement » value (#317), mirroring
     * [pendingPerTabOverride]. The « +lus » re-tap is the ONLY read-then-flip site, and it is always
     * CYAN — so the shim is deliberately CYAN-scoped (RED/FAVORITE writes never touch it, so a
     * concurrent RED write can't clobber a CYAN flip). [setFlagsUnreadOnly] seeds it synchronously
     * for CYAN; cleared once that write persists.
     */
    private val pendingCyanUnreadOnly = MutableStateFlow<Boolean?>(null)

    /**
     * CYAN's resolved « non-lus uniquement » value, optimistic shim ([pendingCyanUnreadOnly]) winning
     * until the persisted value catches up. Tracks CYAN **regardless of the selected tab** (it is not
     * keyed on [selectedTab]) and is eagerly seeded with CYAN's type-aware default (`true`), so the
     * « +lus » re-tap and the Cyan tab suffix never read a value lagging a tab switch or the cold
     * start (cf. [selectTab] / [cyanShowsReadShortcut]). The bottom-sheet switch keeps reading the
     * selected-tab [flagsViewSettings] like every other toggle.
     */
    val cyanUnreadOnly: StateFlow<Boolean> = combine(
        userPreferencesRepository.observeFlagsViewSettings(FlagType.CYAN).map { it.unreadOnly },
        pendingCyanUnreadOnly,
    ) { persisted, pending -> pending ?: persisted }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = true,
        )

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

    /**
     * Builds the UI state for an authenticated tab with a real [type]. Kept as a dedicated method
     * so the `flatMapLatest` chain stays readable. Both `observe(type)` and `observeCategories()`
     * are subscribed here — and only here.
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
                // The « +lus » override: keep fully-read categories under « masquer les catégories
                // sans non-lu » ONLY on CYAN when its unread filter is off — i.e. the user explicitly
                // opted to see read participated topics, so this filter must not hide them right back.
                // RED/FAVORITE default to showing read topics, but there hide-read keeps its literal
                // meaning (drop categories without an unread flag), preserving the #179/#309 behaviour.
                keepFullyReadSections = type == FlagType.CYAN && !unreadOnly,
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

        // #309 — resolved per-tab LAYOUT pair (global, or this tab's override). Projected to
        // layout-only + distinctUntilChanged so an `unreadOnly` flip re-fires ONLY the inner
        // [unreadOnlyFlow] (→ filteredFlags), never this outer source — keeping the « toggling
        // re-emits exactly once » property exact (cf. KDoc above).
        val layoutFlow = userPreferencesRepository.observeFlagsViewSettings(type)
            .map { it.groupByCategory to it.hideReadCategories }
            .distinctUntilChanged()

        return combine(
            filteredFlags,
            categories,
            layoutFlow,
        ) { filtered, catsResult, (groupByCategory, hideReadCategories) ->
            toFlagsListUiState(
                flagsResult = filtered.result,
                categoriesResult = catsResult,
                groupByCategory = groupByCategory,
                hideReadCategories = hideReadCategories,
                keepFullyReadSections = filtered.keepFullyReadSections,
            )
        }
    }

    /**
     * Carries the « +lus » decision ([keepFullyReadSections]) alongside the filtered [result] so it
     * travels as one unit through [keepContentDuringRefresh] and the outer `combine` (cf.
     * [authenticatedFlagsListState]). It is the CYAN-specific override (`type == CYAN && !unreadOnly`,
     * #317) — RED/FAVORITE always pass `false` so hide-read keeps its literal meaning there.
     */
    private data class FilteredFlags(
        val result: FlagsResult,
        val keepFullyReadSections: Boolean,
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
     * Toggled around the user-driven [refresh] round-trip so the Material 3
     * `PullToRefreshBox` indicator can stay anchored over the existing list instead of
     * blanking it back to a cold spinner. Same pattern as `ForumViewModel.isRefreshing`.
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
        if (tab == FlagTab.Cyan && _selectedTab.value == FlagTab.Cyan) {
            // Re-tapping the already-selected Cyan tab toggles its « non-lus uniquement » filter
            // (the « +lus » shortcut). It flips the CYAN per-type value, persisted via
            // [setFlagsUnreadOnly] — the same write the bottom-sheet toggle uses (single mutation
            // point). Flip from [cyanUnreadOnly], which tracks CYAN specifically (not the selected
            // tab) with an optimistic shim and an eager `true` default — so a tab switch, the cold
            // start, or a rapid double re-tap can never feed it a lagging value and lose a toggle.
            setFlagsUnreadOnly(!cyanUnreadOnly.value)
            return
        }
        _selectedTab.value = tab
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
            val result = flagRepository.removeFlag(flag)
            _removeFlagState.value = RemoveFlagState.Idle
            _removeFlagEvent.update {
                if (result.isSuccess) {
                    RemoveFlagEvent.Success(flag.title)
                } else {
                    RemoveFlagEvent.Failure(flag.title)
                }
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
        // CYAN is the only read-then-flip path (the « +lus » re-tap), so only CYAN needs the
        // optimistic shim: seed it synchronously (instant, lag-free re-tap target), then persist and
        // drop the shim only if no newer flip superseded it (compareAndSet) — same pattern as
        // [setFlagsPerTabOverride]. RED/FAVORITE writes (bottom sheet, explicit on/off) never touch
        // the shim, so they can't clobber a concurrent CYAN flip. Resolved settings take over once
        // DataStore commits.
        if (type == FlagType.CYAN) pendingCyanUnreadOnly.value = enabled
        viewModelScope.launch {
            userPreferencesRepository.setFlagsUnreadOnlyForType(type, enabled)
            if (type == FlagType.CYAN) pendingCyanUnreadOnly.compareAndSet(expect = enabled, update = null)
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

    fun refresh() {
        // Super is a placeholder with no backing FlagType — pull-to-refresh is a no-op there.
        val type = _selectedTab.value.flagType ?: return
        // A manual refresh captures the post-reading state too, so the generations visible at this
        // call would only duplicate the fan-out on the next landing — consume them (#378 follow-up).
        flagOpenedGenerationConsumed = flagOpenedGeneration
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                flagRepository.refresh(type)
            } finally {
                _isRefreshing.value = false
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
     * - no refresh is already in flight,
     * - the last auto-refresh is older than [AUTO_REFRESH_THROTTLE] — the per-tab REST fan-out
     *   is ~one GET per public category, so rapid back-and-forth between a topic and the list
     *   must not multiply it. A manual pull-to-refresh is never throttled and does not arm the
     *   throttle (it goes straight through [refresh]).
     */
    fun maybeAutoRefresh() {
        // Snapshot the tab at CALL time (the landing being refreshed) — re-reading it after the
        // suspension points below could refresh a different tab than the one that landed, or
        // no-op on a placeholder while still arming the throttle (Codex review on PR #421).
        val type = _selectedTab.value.flagType ?: return
        if (_isRefreshing.value) return
        // Snapshot at CALL time (same rationale as the tab snapshot above): a read armed AFTER
        // this landing's call belongs to the NEXT landing. The launch suspends on the pref/auth
        // gates below — reading the generation there would let this refresh consume a reading it
        // cannot have captured yet, losing the bypass for the actual return (Codex review).
        val openedGenerationAtCall = flagOpenedGeneration
        viewModelScope.launch {
            if (!userPreferencesRepository.observeFlagsAutoRefresh().first()) return@launch
            if (authRepository.observeAuthState().first() !is AuthState.Authenticated) return@launch
            val now = clock.instant()
            val last = lastAutoRefreshAt
            // Throttle SKIPPED when a topic was opened since the last consuming refresh (see
            // [onFlagOpened]): that landing is a return from a read, the state most worth
            // refreshing.
            val returningFromTopic = openedGenerationAtCall > flagOpenedGenerationConsumed
            if (!returningFromTopic && last != null && Duration.between(last, now) < AUTO_REFRESH_THROTTLE) {
                return@launch
            }
            // Re-check after the suspensions: a manual pull-to-refresh may have started while
            // the pref/auth reads were in flight — don't double the REST fan-out.
            if (_isRefreshing.value) return@launch
            // Consume ONLY the generations visible at call time — a read armed during the
            // suspensions above stays pending for the next landing.
            flagOpenedGenerationConsumed = maxOf(flagOpenedGenerationConsumed, openedGenerationAtCall)
            lastAutoRefreshAt = now
            _isRefreshing.value = true
            try {
                flagRepository.refresh(type)
                // #546 — a genuine landing / tab-switch / resume refresh: recall the list to the top
                // so the freshly prepended flags are visible. A return-from-topic refresh keeps the
                // reader's current position (it is not a fresh landing). One-shot signal, consumed by
                // the screen — see [recallListToTop].
                if (!returningFromTopic) _recallListToTop.value = true
            } finally {
                _isRefreshing.value = false
            }
        }
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
        loadDt()
    }

    /** Explicit user-driven reload of the DT list (pull-to-refresh / retry), bypassing the guard. */
    fun refreshDt() {
        loadDt()
    }

    /**
     * Loads the DT list: the inbox MultiMP rows, each joined best-effort with its MPStorage reading
     * position. The join tolerates every degraded MPStorage outcome ([MpStorageResult.NotFound] /
     * [MpStorageResult.Unreadable] / an exception): the list still renders, just without the
     * « reprise p.N » badge — the storage scan must never fail the conversation list.
     *
     * Only the inbox load itself is fatal: it is the list's primary source, so a network/session
     * error there surfaces [DtListUiState.Error] (the screen offers a retry / reconnect CTA).
     */
    private fun loadDt() {
        dtFetchStarted = true
        viewModelScope.launch {
            _dtListState.value = DtListUiState.Loading
            val conversations = try {
                messagesRepository.getPrivateMessageList(page = DT_INBOX_PAGE)
                    .items
                    .filter { it.isMultiRecipient }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                // Reset the guard so a retry can re-run; the inbox is the list's only hard source.
                dtFetchStarted = false
                _dtListState.value = DtListUiState.Error(error)
                return@launch
            }

            if (conversations.isEmpty()) {
                _dtListState.value = DtListUiState.Empty
                return@launch
            }

            // Best-effort enrichment: a missing / unreadable / failed storage read leaves an empty
            // map, so every row simply renders without a resume badge (the list is never blocked).
            val resumeByThread = fetchResumePositions()
            _dtListState.value = DtListUiState.Content(
                conversations.map { summary ->
                    DtListItem(
                        conversation = summary,
                        resumePage = resumeByThread[summary.threadId],
                    )
                },
            )
        }
    }

    /**
     * Best-effort MPStorage read for the « reprise p.N » badges. Returns a `threadId → page` map of
     * the DTCloud `mpFlags` entries, EMPTY for every degraded outcome ([MpStorageResult.NotFound] /
     * [MpStorageResult.Unreadable]) or transport failure — the caller renders the list regardless.
     * `mpFlags` is a reading-RESUME position, NOT a read/unread state (#361/ADR-013): the unread dot
     * is the inbox [PrivateMessageSummary.hasUnread], never this page number.
     */
    @Suppress("SwallowedException") // best-effort: a storage read failure deliberately degrades to
    // « no resume badge » so the conversation list still renders (cf. KDoc); the cause is irrelevant.
    private suspend fun fetchResumePositions(): Map<Int, Int> = try {
        when (val storage = mpStorageRepository.fetchStorage()) {
            is MpStorageResult.Found -> storage.document.mpFlags.associate { it.threadId to it.page }
            MpStorageResult.NotFound, MpStorageResult.Unreadable -> emptyMap()
        }
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        emptyMap()
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
     * flag. [keepFullyReadSections] (the CYAN « +lus » override, #317) is forwarded from
     * [FilteredFlags]: when CYAN explicitly shows read topics, its fully-read categories survive the
     * hide filter so those topics stay reachable.
     */
    private fun toFlagsListUiState(
        flagsResult: FlagsResult,
        categoriesResult: ForumResult<List<Category>>,
        groupByCategory: Boolean,
        hideReadCategories: Boolean,
        keepFullyReadSections: Boolean,
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
    ): FlagsContent {
        if (!groupByCategory) return FlagsContent.Flat(flags)
        val grouped = groupFlagsByCategory(flags, resolveCategoryOrder(categoriesResult))
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
     * previous user's MultiMP conversations; the next [onDtTabOpened] re-scans for the new session. */
    private fun resetDtState() {
        dtFetchStarted = false
        _dtListState.value = DtListUiState.Loading
    }
}

/**
 * UI-level tab model for the Drapeaux screen. The three real tabs map to a [FlagType] the
 * repository can fetch ; [Super] is a placeholder for the future « super favoris » feature
 * and intentionally carries no [FlagType] (no `flag_owntopic` is known, no backend exists).
 *
 * The ViewModel keeps fetching/filtering on [FlagType] for the three real tabs ; this type
 * only drives which tab is selected so the screen can render a placeholder body for [Super]
 * without polluting the domain enum.
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

    /** Placeholder — future « super favoris ». No fetch, no backend. */
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

/**
 * Single route-facing UI state for the category-grouped Drapeaux list (#179). Replaces a
 * direct `FlagsResult` exposure so a `Loading`/`Failure` can never diverge from a stale set
 * of sections (cf. impl prompt §4.1 correction #2).
 *
 * A `null` value (not a member of this interface) keeps its prior meaning « not applicable »:
 * the Anonymous login intro or the [FlagTab.Super] placeholder.
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
    data class Flat(val flags: List<Flag>) : FlagsContent
}

/**
 * #378 — minimum delay between two auto-refreshes ([FlagsViewModel.maybeAutoRefresh]). 15 s:
 * shorter than any realistic topic-read round-trip (the « back from a topic » trigger stays
 * effective) but long enough that bouncing between tabs or popping in and out of a topic does
 * not re-run the per-category REST fan-out every time.
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

    /** Loaded MultiMP conversations, each best-effort joined with its MPStorage resume position. */
    data class Content(val items: List<DtListItem>) : DtListUiState

    /** The inbox load failed (network / session). The MPStorage read NEVER reaches this state —
     * it is best-effort and degrades to « no badge ». [cause] drives the reconnect/retry CTA. */
    data class Error(val cause: Throwable) : DtListUiState
}

/**
 * One DT row: a MultiMP [conversation] plus its optional MPStorage reading-resume page
 * ([resumePage], DTCloud `mpFlags`). [resumePage] is the « reprise p.N » badge — a reading
 * POSITION, never a read/unread state (#361/ADR-013): the unread dot is
 * [PrivateMessageSummary.hasUnread]. `null` when the conversation has no MPStorage entry (or the
 * storage was absent / unreadable / failed to load).
 */
data class DtListItem(
    val conversation: PrivateMessageSummary,
    val resumePage: Int?,
)
