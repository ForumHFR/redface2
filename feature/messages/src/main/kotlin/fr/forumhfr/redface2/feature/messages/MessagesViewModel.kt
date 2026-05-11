package fr.forumhfr.redface2.feature.messages

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
 * Backing ViewModel for the Messages tab while it doubles as the account + alpha-tools
 * surface (#154). Same logout semantics as `FlagsViewModel`: clearing the per-user flag
 * cache must precede the auth wipe to avoid a stale cyan list leaking across pseudos.
 *
 * Once real MP screens land in Phase 3, the account/alpha block migrates out of here
 * and this ViewModel becomes the MP-list backing — keep the API surface lean now so
 * the next phase doesn't have to undo the temporary scaffolding.
 */
@HiltViewModel
class MessagesViewModel @Inject constructor(
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
            // Same ordering as FlagsViewModel.logout: drop the private cache before
            // flipping auth state, so the Flags tab can't re-render the previous user's
            // CYAN/RED rows for a frame while auth resets.
            flagRepository.clearSessionCache()
            authRepository.logout()
        }
    }
}
