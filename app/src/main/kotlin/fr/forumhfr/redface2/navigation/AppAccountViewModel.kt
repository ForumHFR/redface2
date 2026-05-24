package fr.forumhfr.redface2.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.model.AuthState
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Issue #198 — single source of truth for the global account menu shared by every main screen
 * (`Drapeaux`, `Forum`, `Recherche`, `Messages`, `Topic`, `ForumCategory`).
 *
 * Hoisted out of `FlagsViewModel` / `MessagesViewModel` so the menu does not depend on whichever
 * tab is currently bound. The logout ordering — clear the per-user flag cache **before** wiping
 * auth — is preserved from `FlagsViewModel.logout` so a Flags tab recomposed mid-logout never
 * leaks the previous pseudo's CYAN list for a frame.
 *
 * Lives in `:app/navigation/` rather than `:core:ui` because it owns the Hilt injection of
 * domain repositories. `RedfaceAccountMenu` in `:core:ui` stays pure UI and is fed by this
 * ViewModel's state + callbacks.
 */
@HiltViewModel
class AppAccountViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val flagRepository: FlagRepository,
) : ViewModel() {

    val authState: StateFlow<AuthState?> = authRepository.observeAuthState()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    fun logout() {
        viewModelScope.launch {
            // Same ordering as FlagsViewModel.logout / MessagesViewModel.logout: drop the
            // private cache before flipping auth state, so any recomposing tab cannot
            // re-render the previous user's content for a frame.
            flagRepository.clearSessionCache()
            authRepository.logout()
        }
    }
}
