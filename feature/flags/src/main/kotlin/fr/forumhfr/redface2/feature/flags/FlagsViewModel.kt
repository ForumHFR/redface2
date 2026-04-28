package fr.forumhfr.redface2.feature.flags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.FlagType
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Home tab ViewModel. Owns the auth-aware flag list rendering plus the auxiliary state
 * the home screen surfaces (pseudo / MP count) so the UI layer can stay declarative.
 *
 * State flows are nullable where "not known yet" is meaningful: a `null` `authState`
 * means the cookie jar is still warming up from DataStore, and Compose renders nothing
 * to avoid a cold-start flicker (cf. PR #91 review). Same convention for `unreadMpCount`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FlagsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val flagRepository: FlagRepository,
    messagesRepository: MessagesRepository,
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(FlagType.RED)
    val selectedTab: StateFlow<FlagType> = _selectedTab.asStateFlow()

    val authState: StateFlow<AuthState?> = authRepository.observeAuthState()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    val unreadMpCount: StateFlow<Int?> = messagesRepository.observeUnreadMpCount()
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
        .flatMapLatest { state ->
            when (state) {
                null -> flowOf(null)
                AuthState.Anonymous -> flowOf(null)
                is AuthState.Authenticated -> selectedTab.flatMapLatest { type ->
                    flagRepository.observe(type).map { it as FlagsResult? }
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

    fun refresh() {
        viewModelScope.launch { flagRepository.refresh(_selectedTab.value) }
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }
}
