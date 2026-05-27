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
 * Issue #198 — single source of truth for the global account menu surfaced from the top-bar
 * slot `topBarActions` of `Drapeaux`, `Forum`, `Recherche` and `Messages` (cf.
 * `RedfaceNavigation.kt`). `ForumCategoryScreen` and `TopicScreen` deliberately stay
 * untouched in this PR — those sub-pages have their own back-stack and the slot integration
 * is tracked as follow-up.
 *
 * Hoisted out of the legacy `FlagsViewModel.logout` / `MessagesViewModel.logout` (both removed
 * by PRs #207 and #198 respectively) so the menu does not depend on whichever tab is
 * currently bound. The logout ordering — clear the per-user flag cache **before** wiping
 * auth — is preserved so a Flags tab recomposed mid-logout never leaks the previous pseudo's
 * CYAN list for a frame. The contract is pinned by `AppAccountViewModelTest`.
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
            // Drop the per-user cache BEFORE flipping auth state so any recomposing tab
            // cannot re-render the previous user's content for a frame. The ordering is
            // verrouillé par `AppAccountViewModelTest`.
            flagRepository.clearSessionCache()
            authRepository.logout()
        }
    }
}
