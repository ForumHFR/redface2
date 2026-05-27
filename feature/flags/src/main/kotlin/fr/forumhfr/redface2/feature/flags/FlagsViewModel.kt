package fr.forumhfr.redface2.feature.flags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.FlagType
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
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
) : ViewModel() {

    private var observedPseudo: String? = null

    private val _selectedTab = MutableStateFlow(FlagType.CYAN)
    val selectedTab: StateFlow<FlagType> = _selectedTab.asStateFlow()

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
     * Flag list for the currently selected tab. Anonymous → emits null (the home tab
     * shows the login intro instead of an empty list); Authenticated → emits the result
     * from FlagRepository for the current tab. Switching tabs (or auth state) cancels
     * any in-flight observation via [flatMapLatest].
     */
    val flagsState: StateFlow<FlagsResult?> = authState
        .onEach(::clearFlagsCacheIfSessionChanged)
        .flatMapLatest { state ->
            when (state) {
                null -> flowOf<FlagsResult?>(null)
                AuthState.Anonymous -> flowOf<FlagsResult?>(null)
                is AuthState.Authenticated -> selectedTab.flatMapLatest { type ->
                    combine(
                        flagRepository.observe(type),
                        _showReadParticipatedTopics,
                    ) { result, showRead ->
                        filterReadParticipatedIfNeeded(result, type, showRead)
                    }
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    fun selectTab(type: FlagType) {
        _selectedTab.value = type
    }

    fun setShowReadParticipatedTopics(value: Boolean) {
        _showReadParticipatedTopics.value = value
    }

    fun refresh() {
        viewModelScope.launch { flagRepository.refresh(_selectedTab.value) }
    }

    // Round-2 review (PR #207): `logout()` was removed from this ViewModel — the global account
    // menu (#198) now drives the logout from `AppAccountViewModel.logout()`, which owns the
    // canonical `clearSessionCache → authRepository.logout` ordering. Keeping a second copy
    // here was dead code that drifted at the first refactor; the matching invariant test was
    // moved to `AppAccountViewModelTest`.

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
