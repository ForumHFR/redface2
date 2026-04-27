package fr.forumhfr.redface2

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.model.AuthState
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Lightweight home-screen ViewModel that exposes the global AuthState. Phase 1B.1 surfaces
 * the connected pseudo (or "Se connecter" CTA) on FlagsScreen; Phases 1B.3+ will move this
 * state up to a top-level scaffold once login becomes a precondition for other tabs.
 */
@HiltViewModel
class FlagsHomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.observeAuthState()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AuthState.Anonymous,
        )

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }
}
