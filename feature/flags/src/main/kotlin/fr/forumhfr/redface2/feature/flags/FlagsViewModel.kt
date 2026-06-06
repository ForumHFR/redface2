package fr.forumhfr.redface2.feature.flags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
) : ViewModel() {

    private var observedPseudo: String? = null

    private val _selectedTab = MutableStateFlow<FlagTab>(FlagTab.Cyan)
    val selectedTab: StateFlow<FlagTab> = _selectedTab.asStateFlow()

    /**
     * User-controlled visibility of CYAN flags whose [fr.forumhfr.redface2.core.model.Flag.hasUnread]
     * is `false` — i.e. topics the user already finished reading but still participated in.
     * Default `false`: a fresh launch shows only actionable « Mes sujets » entries. Toggling this
     * reactively re-emits the filtered list without a refetch (cf. [combine] in [flagsState]).
     *
     * Filter applies only when [selectedTab] == [FlagType.CYAN]. RED (« Lus uniquement » — topics
     * the user reads without participating) and FAVORITE (bookmarks) keep their full content
     * regardless — they don't have the « stale read flag » pollution problem CYAN does, where the
     * user explicitly wants the actionable subset by default.
     *
     * In-memory only for now (#154 polish scope) — persisting the preference is deferred
     * until a real settings surface exists.
     */
    private val _showReadParticipatedTopics = MutableStateFlow(false)
    val showReadParticipatedTopics: StateFlow<Boolean> = _showReadParticipatedTopics.asStateFlow()

    val authState: StateFlow<AuthState?> = authRepository.observeAuthState()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    /**
     * Category-grouped UI state for the currently selected tab (#179). Replaces the former flat
     * `FlagsResult?` exposure: the screen renders one section per forum category in canonical
     * order (empty sections included for HFR web parity), grouping the already-loaded flat list
     * client-side — no extra authenticated fetch (prefetch-non-auth invariant).
     *
     * - Anonymous → `null` (the home tab shows the login intro instead of a list).
     * - [FlagTab.Super] → `null` (placeholder body, no backend, **no** repository observation —
     *   neither flags NOR categories are observed, cf. §5 « no double-fetch »).
     * - Authenticated + a real [FlagType] → the per-tab flag flow (filtered cyan #154, then
     *   [keepContentDuringRefresh] #225) is combined with [ForumRepository.observeCategories]
     *   to build [FlagsListUiState]. Categories are observed **only** in this branch so we never
     *   trigger a spurious public categories fetch for Anonymous/Super.
     *
     * Ordering of the mapping is load-bearing: cyan filter → `keepContentDuringRefresh` →
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
     * Builds the grouped UI state for an authenticated tab with a real [type]. Kept as a
     * dedicated method so the `flatMapLatest` chain stays readable. Both `observe(type)` and
     * `observeCategories()` are subscribed here — and only here.
     */
    private fun authenticatedFlagsListState(type: FlagType): Flow<FlagsListUiState?> {
        val filteredFlags = combine(
            flagRepository.observe(type),
            _showReadParticipatedTopics,
        ) { result, showRead ->
            filterReadParticipatedIfNeeded(result, type, showRead)
        }
            // #225 — keep the existing list anchored during a user refresh instead of blanking
            // it to a cold centered spinner under the PullToRefreshBox indicator (double loader).
            // MUST stay before the section mapping (cf. KDoc on flagsState).
            .keepContentDuringRefresh(
                isLoading = { it is FlagsResult.Loading },
                isContent = { it is FlagsResult.Success },
            )

        // Prepend a synthetic Loading on the categories side so `combine` does not BLOCK the
        // flags render until the categories flow has emitted (cf. test 10bis): on a cold start
        // where Success(flags) lands before observeCategories emits, the screen must render
        // immediately using FALLBACK_CATEGORY_ORDER, then re-derive when the real catalogue
        // arrives. Without this onStart, `combine` waits for both sources and the list stalls.
        val categories = forumRepository.observeCategories()
            .onStart { emit(ForumResult.Loading) }

        return combine(filteredFlags, categories) { flagsResult, catsResult ->
            toFlagsListUiState(flagsResult, catsResult)
        }
    }

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
     * Tab selection. Re-tapping [FlagTab.Cyan] while it is **already** selected toggles
     * [showReadParticipatedTopics] (show / hide the already-read participated topics) —
     * the inline replacement for the former « Cyans lus » FilterChip. Selecting Cyan from
     * another tab only switches to it (no toggle). Other tabs just switch.
     */
    fun selectTab(tab: FlagTab) {
        if (tab == FlagTab.Cyan && _selectedTab.value == FlagTab.Cyan) {
            // Re-tapping the already-selected Cyan tab toggles the read-cyan filter. Route the
            // mutation through [setShowReadParticipatedTopics] so that setter stays the single
            // mutation point for `_showReadParticipatedTopics` (no second code path to drift).
            setShowReadParticipatedTopics(!_showReadParticipatedTopics.value)
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

    fun setShowReadParticipatedTopics(value: Boolean) {
        _showReadParticipatedTopics.value = value
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
     * Maps a (filtered) [FlagsResult] + a categories [ForumResult] into the single grouped UI
     * state (#179). A `Loading`/`Failure` on the categories side is **non-fatal**: it only
     * affects which canonical order is used, never whether the flags render. The hard-coded
     * [FALLBACK_CATEGORY_ORDER] kicks in until the real catalogue arrives, so flags appear
     * immediately on a cold start and no flag is ever lost (anti-regression #251).
     */
    private fun toFlagsListUiState(
        flagsResult: FlagsResult,
        categoriesResult: ForumResult<List<Category>>,
    ): FlagsListUiState = when (flagsResult) {
        FlagsResult.Loading -> FlagsListUiState.Loading
        is FlagsResult.Failure -> FlagsListUiState.Failure(flagsResult.cause)
        is FlagsResult.Success -> {
            // A `Success` carrying an EMPTY catalogue is treated like « no catalogue yet »
            // (falls back to FALLBACK_CATEGORY_ORDER), not like an empty order. Otherwise the
            // double-empty case (zero flags AND zero categories) would render zero sections —
            // a fully blank body with not even an « aucun drapeau » band. In prod the REST
            // endpoint always returns the 19 categories, so this only guards the degenerate
            // Success-but-empty edge; flags in unknown cats are still never lost either way.
            val order = when (categoriesResult) {
                is ForumResult.Success -> categoriesResult.value
                    .takeIf { it.isNotEmpty() }
                    ?.map { FlagCategoryOrderEntry(it.id, it.name) }
                    ?: FALLBACK_CATEGORY_ORDER
                else -> FALLBACK_CATEGORY_ORDER
            }
            FlagsListUiState.Success(groupFlagsByCategory(flagsResult.flags, order))
        }
    }

    private fun filterReadParticipatedIfNeeded(
        result: FlagsResult,
        type: FlagType,
        showReadParticipated: Boolean,
    ): FlagsResult {
        // CYAN is the only bucket where « topics already finished reading » legitimately
        // pollutes the actionable view (the user *participated*, then moved on). RED
        // (« Lus uniquement » — topics watched without participation) and FAVORITE
        // (bookmarks) are not filtered: their value comes from listing both read and
        // unread entries.
        if (type != FlagType.CYAN || showReadParticipated) return result
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
     * Grouped sections in canonical category order, empty sections included (web parity).
     * [sections] is non-empty in practice — the fallback order guarantees at least the known
     * categories even before the catalogue loads.
     */
    data class Success(val sections: List<FlagCategorySection>) : FlagsListUiState

    /** A flags fetch failed; [cause] drives the reconnect/retry CTA (e.g. SessionExpiredException). */
    data class Failure(val cause: Throwable) : FlagsListUiState
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
