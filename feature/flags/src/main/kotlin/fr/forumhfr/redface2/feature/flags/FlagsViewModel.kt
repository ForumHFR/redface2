package fr.forumhfr.redface2.feature.flags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.domain.preferences.FlagsViewSettings
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
class FlagsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val flagRepository: FlagRepository,
    private val forumRepository: ForumRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private var observedPseudo: String? = null

    private val _selectedTab = MutableStateFlow<FlagTab>(FlagTab.Cyan)
    val selectedTab: StateFlow<FlagTab> = _selectedTab.asStateFlow()

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
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                flagRepository.refresh(type)
            } finally {
                _isRefreshing.value = false
            }
        }
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
                flagRepository.clearSessionCache()
            }
            is AuthState.Authenticated -> {
                // Clear on the first authenticated emission too: the repository is a
                // singleton and may outlive this ViewModel, so a recreated Flags screen
                // must not trust whatever per-user cache was left in memory.
                if (observedPseudo != state.pseudo) {
                    flagRepository.clearSessionCache()
                }
                observedPseudo = state.pseudo
            }
        }
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
