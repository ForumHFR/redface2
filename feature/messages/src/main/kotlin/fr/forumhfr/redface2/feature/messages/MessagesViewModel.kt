package fr.forumhfr.redface2.feature.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.error.classifyHfrError
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageReadPositionSeeder
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.model.AuthState
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the Messages tab: loads one page of the private-message inbox and exposes a
 * [MessagesUiState] state machine (Loading → Content / Error). Unlike the topic listing it has
 * no cache layer yet (#298 MVP) — each [load] hits the network once. The inbox requires an
 * authenticated session; an anonymous/expired session surfaces as [MessagesUiState.Mode.Error]
 * because HFR redirects `cat=prive` to login.
 */
@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val repository: MessagesRepository,
    private val authRepository: AuthRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val mpStorageReadPositionSeeder: MpStorageReadPositionSeeder,
) : ViewModel() {

    private val _state = MutableStateFlow(MessagesUiState())
    val state: StateFlow<MessagesUiState> = _state.asStateFlow()

    // Tracks the in-flight load so a new one (page change, retry, refresh) cancels the
    // previous: otherwise a slow refresh could land after a page navigation and overwrite the
    // newer page with its stale result.
    private var loadJob: Job? = null
    private var authenticatedPseudo: String? = null

    // One-shot guard for the MPStorage DT seed (#6, ADR-014). Reset whenever the active pseudo
    // changes so a re-login / account switch re-seeds for the new account, never carries the
    // previous account's "already done" flag across the switch.
    private var dtSeedAttempted = false

    init {
        viewModelScope.launch {
            authRepository.observeAuthState()
                .distinctUntilChanged()
                .collect { authState ->
                    when (authState) {
                        AuthState.Anonymous -> {
                            dtSeedAttempted = false
                            clearPrivateState()
                        }
                        is AuthState.Authenticated -> {
                            if (authenticatedPseudo != authState.pseudo) dtSeedAttempted = false
                            authenticatedPseudo = authState.pseudo
                            load(page = 1)
                            maybeSeedDtReadPositions()
                        }
                    }
                }
        }
    }

    /**
     * #6 / ADR-014 — once per authenticated session, when the « section DT » setting is on, seed the
     * local MP reading positions from the MPStorage document (DTCloud's `mpFlags`). Best-effort and
     * fire-and-forget: it must never block or fail the inbox load (a missing storage MP is the
     * nominal case). Gated behind the opt-in setting so non-DT users never pay the inbox scan.
     */
    private fun maybeSeedDtReadPositions() {
        if (dtSeedAttempted) return
        dtSeedAttempted = true
        viewModelScope.launch {
            if (!userPreferencesRepository.observeShowDtSection().first()) return@launch
            runCatching { mpStorageReadPositionSeeder.seed() }
        }
    }

    /** Navigates to another inbox page (pager). No-op for out-of-range pages. */
    fun selectPage(page: Int) {
        if (page < 1) return
        load(page)
    }

    /** Re-runs the current page load after an error. */
    fun retry() {
        load(_state.value.page)
    }

    /** Pull-to-refresh: reloads the current page, keeping the existing content visible. */
    fun refresh() {
        load(_state.value.page, refreshing = true)
    }

    /**
     * #301 follow-up — a new conversation was just sent : it appears at the TOP of page 1,
     * so always land there (a plain refresh from page 2+ would never show it — Codex review
     * of #404). Already on page 1 → soft refresh, keeping the visible content.
     */
    fun showFreshInbox() {
        load(page = 1, refreshing = _state.value.page == 1)
    }

    private fun clearPrivateState() {
        authenticatedPseudo = null
        loadJob?.cancel()
        _state.value = MessagesUiState(mode = MessagesUiState.Mode.RequiresLogin)
    }

    private fun load(page: Int, refreshing: Boolean = false) {
        if (authenticatedPseudo == null) {
            clearPrivateState()
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { current ->
                if (refreshing) {
                    current.copy(isRefreshing = true)
                } else {
                    current.copy(mode = MessagesUiState.Mode.Loading, page = page)
                }
            }
            try {
                val result = repository.getPrivateMessageList(page = page)
                _state.update {
                    it.copy(
                        mode = MessagesUiState.Mode.Content(result.items),
                        page = result.page,
                        totalPages = result.totalPages,
                        isRefreshing = false,
                        // #531 — a genuine network success (no cache layer here): bump the generation
                        // so the screen reconciles the optimistic read marks exactly once per fetch.
                        networkLoadGeneration = it.networkLoadGeneration + 1,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                // The throwable MESSAGE is intentionally NOT propagated to the UI state — it can
                // embed the private forum2.php?cat=prive&post=<id> URL (#316). It must not reach
                // the screen, nor the exportable DiagnosticsLog. The Error state only carries the
                // #324 kind, a closed enum derived from the exception TYPE (classifyHfrError) so
                // the screen can tell an HFR 5xx outage from a network cut.
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                _state.update { current ->
                    // A failed pull-to-refresh must not wipe the conversations already shown:
                    // keep the existing Content and just drop the spinner. A failed initial /
                    // page load (no content yet) surfaces the Error screen with a retry.
                    if (refreshing && current.mode is MessagesUiState.Mode.Content) {
                        current.copy(isRefreshing = false)
                    } else {
                        current.copy(
                            mode = MessagesUiState.Mode.Error(classifyHfrError(error)),
                            isRefreshing = false,
                        )
                    }
                }
            }
        }
    }
}
