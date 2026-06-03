package fr.forumhfr.redface2.feature.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import fr.forumhfr.redface2.core.model.AuthState
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
) : ViewModel() {

    private val _state = MutableStateFlow(MessagesUiState())
    val state: StateFlow<MessagesUiState> = _state.asStateFlow()

    // Tracks the in-flight load so a new one (page change, retry, refresh) cancels the
    // previous: otherwise a slow refresh could land after a page navigation and overwrite the
    // newer page with its stale result.
    private var loadJob: Job? = null
    private var authenticatedPseudo: String? = null

    init {
        viewModelScope.launch {
            authRepository.observeAuthState()
                .distinctUntilChanged()
                .collect { authState ->
                    when (authState) {
                        AuthState.Anonymous -> clearPrivateState()
                        is AuthState.Authenticated -> {
                            authenticatedPseudo = authState.pseudo
                            load(page = 1)
                        }
                    }
                }
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
     * HFR marks a private-message thread as read when it is opened. Reflect that immediately
     * in the retained inbox list so returning from the thread does not show a stale unread dot.
     */
    fun openThread(threadId: Int) {
        _state.update { current ->
            val content = current.mode as? MessagesUiState.Mode.Content ?: return@update current
            current.copy(
                mode = MessagesUiState.Mode.Content(
                    content.conversations.map { conversation ->
                        if (conversation.threadId == threadId) {
                            conversation.copy(hasUnread = false)
                        } else {
                            conversation
                        }
                    },
                ),
            )
        }
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
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                _state.update { current ->
                    // A failed pull-to-refresh must not wipe the conversations already shown:
                    // keep the existing Content and just drop the spinner. A failed initial /
                    // page load (no content yet) surfaces the Error screen with a retry.
                    if (refreshing && current.mode is MessagesUiState.Mode.Content) {
                        current.copy(isRefreshing = false)
                    } else {
                        current.copy(
                            mode = MessagesUiState.Mode.Error(error.message),
                            isRefreshing = false,
                        )
                    }
                }
            }
        }
    }
}
