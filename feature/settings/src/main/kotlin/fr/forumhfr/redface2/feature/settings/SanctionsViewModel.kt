package fr.forumhfr.redface2.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.error.classifyHfrError
import fr.forumhfr.redface2.core.domain.profile.SanctionsRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.profile.SanctionsHistory
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@HiltViewModel
class SanctionsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val sanctionsRepository: SanctionsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<SanctionsUiState>(SanctionsUiState.Loading)
    val state: StateFlow<SanctionsUiState> = _state.asStateFlow()

    private var activeAuthState: AuthState = AuthState.Anonymous
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            authRepository.observeAuthState().distinctUntilChanged().collect { authState ->
                loadJob?.cancel()
                activeAuthState = authState
                when (authState) {
                    AuthState.Anonymous -> _state.value = SanctionsUiState.SignInRequired
                    is AuthState.Authenticated -> loadSanctions()
                }
            }
        }
    }

    fun submit(intent: SanctionsIntent) {
        when (intent) {
            SanctionsIntent.Retry -> if (
                activeAuthState is AuthState.Authenticated && _state.value is SanctionsUiState.Error
            ) {
                loadSanctions()
            }
        }
    }

    private fun loadSanctions() {
        loadJob?.cancel()
        _state.value = SanctionsUiState.Loading
        loadJob = viewModelScope.launch {
            val result = sanctionsRepository.loadSanctions()
            // A cancelled account's response must never repopulate the screen after logout/switch.
            currentCoroutineContext().ensureActive()
            _state.value = result.fold(::historyState, ::errorState)
        }
    }

    private fun historyState(history: SanctionsHistory): SanctionsUiState = when (history) {
        SanctionsHistory.SignInRequired -> SanctionsUiState.SignInRequired
        is SanctionsHistory.Loaded -> if (history.sanctions.isEmpty()) {
            SanctionsUiState.Empty(history.pseudo)
        } else {
            SanctionsUiState.Loaded(history.pseudo, history.sanctions)
        }
    }

    private fun errorState(error: Throwable): SanctionsUiState = when (error) {
        is CancellationException -> throw error
        is SessionExpiredException -> SanctionsUiState.SignInRequired
        else -> SanctionsUiState.Error(classifyHfrError(error))
    }
}
