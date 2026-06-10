package fr.forumhfr.redface2.feature.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.error.classifyHfrError
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import fr.forumhfr.redface2.core.model.AuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for one private-message conversation. Receives its route arguments via Hilt
 * assisted injection ([PrivateMessageThreadRequest]), mirroring [TopicViewModel]. Loads the
 * requested page once (no cache in the #298 MVP). Route arguments deliberately exclude
 * subject/correspondent so stale Navigation state cannot expose private metadata after logout.
 */
@HiltViewModel(assistedFactory = PrivateMessageThreadViewModel.Factory::class)
class PrivateMessageThreadViewModel @AssistedInject constructor(
    @Assisted private val request: PrivateMessageThreadRequest,
    private val repository: MessagesRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PrivateMessageThreadUiState.initial(request))
    val state: StateFlow<PrivateMessageThreadUiState> = _state.asStateFlow()

    // A new page load (or retry) cancels the previous in-flight one so a stale result cannot
    // overwrite the page the user is actually on.
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
                            load(request.page.coerceAtLeast(1))
                        }
                    }
                }
        }
    }

    fun selectPage(page: Int) {
        if (page < 1) return
        load(page)
    }

    fun retry() {
        load(_state.value.page)
    }

    private fun clearPrivateState() {
        authenticatedPseudo = null
        loadJob?.cancel()
        _state.value = PrivateMessageThreadUiState.initial(request)
            .copy(mode = PrivateMessageThreadUiState.Mode.RequiresLogin)
    }

    private fun load(page: Int) {
        if (authenticatedPseudo == null) {
            clearPrivateState()
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(mode = PrivateMessageThreadUiState.Mode.Loading, page = page) }
            try {
                val thread = repository.getPrivateMessageThread(
                    threadId = request.threadId,
                    page = page,
                    fallbackCorrespondent = null,
                )
                _state.update {
                    it.copy(
                        mode = PrivateMessageThreadUiState.Mode.Content(thread),
                        page = thread.page,
                        totalPages = thread.totalPages,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                // The throwable MESSAGE is intentionally NOT propagated to the UI state — it can
                // embed the private forum2.php?cat=prive&post=<id> URL (#316), so it must reach
                // neither the screen nor the exportable DiagnosticsLog. The Error state only
                // carries the #324 kind, a closed enum derived from the exception TYPE
                // (classifyHfrError) so the screen can tell an HFR 5xx outage from a network cut.
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                _state.update {
                    it.copy(mode = PrivateMessageThreadUiState.Mode.Error(classifyHfrError(error)))
                }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(request: PrivateMessageThreadRequest): PrivateMessageThreadViewModel
    }
}
