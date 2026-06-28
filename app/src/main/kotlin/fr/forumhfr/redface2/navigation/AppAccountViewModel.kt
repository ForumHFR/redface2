package fr.forumhfr.redface2.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.preferences.AvatarAppearance
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.profile.ProfileRepository
import fr.forumhfr.redface2.core.model.AuthState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    private val profileRepository: ProfileRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    val authState: StateFlow<AuthState?> = authRepository.observeAuthState()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    /**
     * #479 — avatar URL of the connected user, resolved per `userId` from the public profile so
     * the top-bar account badge can show the real HFR avatar instead of the pseudo initial.
     *
     * Lives here (not in [AuthState]) so the auth model stays a pure session verdict: the avatar
     * is a display enrichment fetched lazily, and a fetch failure / missing avatar simply leaves
     * the map empty so [RedfaceAccountMenu] falls back to the initial. Keyed by `userId` and
     * memoised across recompositions / tab switches (the ViewModel is a shared singleton per the
     * nav host) so a tab change never refetches; a logout/login to another id falls through the
     * cache miss and refetches.
     */
    private val avatarUrlByUserId = MutableStateFlow<Map<Int, String?>>(emptyMap())

    /**
     * Resolved avatar URL for the *currently* connected user, or `null` (anonymous, loading, or
     * no avatar) — in which case the badge renders the pseudo initial. `combine` so it re-emits on
     * BOTH inputs: a logout (auth → Anonymous) must drop the previous user's avatar even though the
     * cache is untouched, and the lazily-filled cache must surface the avatar once the fetch lands.
     */
    val avatarUrl: StateFlow<String?> = combine(authState, avatarUrlByUserId) { auth, cache ->
        (auth as? AuthState.Authenticated)?.userId?.let(cache::get)
    }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    /**
     * #718 — GLOBAL appearance of the top-bar account avatar badge (border + background). Observed
     * directly from the user preferences (NOT routed through the Drapeaux sheet, which is only an
     * editing point) so the badge looks the same on every main screen's top bar. Bundled so
     * [RedfaceAccountMenu] reads ONE value and never flickers between two independently-observed flows.
     */
    val avatarAppearance: StateFlow<AvatarAppearance> = userPreferencesRepository.observeAvatarAppearance()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AvatarAppearance(),
        )

    init {
        observeAvatarForConnectedUser()
    }

    private fun observeAvatarForConnectedUser() {
        viewModelScope.launch {
            authState
                .map { (it as? AuthState.Authenticated)?.userId }
                .distinctUntilChanged()
                // collectLatest: a logout / account switch mid-fetch CANCELS the in-flight
                // getProfile so a stale avatar can never be written back for the previous user
                // after the auth state moved on (Codex review).
                .collectLatest { userId ->
                    if (userId != null && userId !in avatarUrlByUserId.value) {
                        fetchAvatar(userId)
                    }
                }
        }
    }

    private suspend fun fetchAvatar(userId: Int) {
        // Anonymous fetch (ProfileRepository uses the unauthenticated client) so resolving our own
        // avatar never marks drapeaux as read, matching the prefetch-non-authentifié rule. A
        // failure stores `null` so we don't hammer the endpoint and the badge stays on the initial.
        val avatar = profileRepository.getProfile(userId).getOrNull()?.avatarUrl
        avatarUrlByUserId.update { it + (userId to avatar) }
    }

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
