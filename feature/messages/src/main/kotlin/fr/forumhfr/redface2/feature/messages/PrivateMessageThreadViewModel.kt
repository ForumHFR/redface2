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
import fr.forumhfr.redface2.core.domain.messages.PrivateMessageReadPositionStore
import fr.forumhfr.redface2.core.model.AuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
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
    private val readPositionStore: PrivateMessageReadPositionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(PrivateMessageThreadUiState.initial(request))
    val state: StateFlow<PrivateMessageThreadUiState> = _state.asStateFlow()

    private val _effects: Channel<PrivateMessageThreadEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<PrivateMessageThreadEffect> = _effects.receiveAsFlow()

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
                            load(openingPage())
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

    /**
     * #351 — manual pull-to-refresh of the displayed page. NO-OP unless a page is on screen and no
     * keep-content load is already in flight (guards a double pull); the actual keep-content
     * behaviour lives in [load], which any reload from a loaded conversation shares.
     */
    fun refresh() {
        val current = _state.value
        if (current.mode !is PrivateMessageThreadUiState.Mode.Content || current.isRefreshing) return
        load(current.page)
    }

    private fun clearPrivateState() {
        authenticatedPseudo = null
        loadJob?.cancel()
        _state.value = PrivateMessageThreadUiState.initial(request)
            .copy(mode = PrivateMessageThreadUiState.Mode.RequiresLogin)
    }

    /**
     * Page the conversation opens on (#430). The route freezes the page known at opening time
     * (the inbox passes its last-page link — web parity), while the local per-account store
     * remembers the page actually displayed last (it survives process death, the original #430
     * bug). `max` of the two: a conversation that grew since the last visit opens on its NEW
     * last page (fresh messages win over an older resume point), and a reader who advanced past
     * the frozen opening page resumes where they actually were. A store failure falls back to
     * the route — opening the conversation must never break on a local read.
     */
    private suspend fun openingPage(): Int {
        val saved = try {
            readPositionStore.readPage(request.threadId)
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            null
        }
        return maxOf(saved ?: 1, request.page.coerceAtLeast(1))
    }

    private fun load(page: Int) {
        if (authenticatedPseudo == null) {
            clearPrivateState()
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // #351 — keep the displayed conversation on screen while (re)loading. There is no MP
            // cache (ADR-013: nothing persisted), so a page change or a pull-to-refresh from a
            // loaded page is a full network round-trip; wiping to Mode.Loading would flash a
            // full-screen spinner on every page turn. `page` is NOT advanced optimistically: the
            // pager keeps describing the page on screen until the new one actually lands.
            val keepContent = _state.value.mode is PrivateMessageThreadUiState.Mode.Content
            _state.update {
                if (keepContent) {
                    it.copy(isRefreshing = true)
                } else {
                    it.copy(mode = PrivateMessageThreadUiState.Mode.Loading, page = page)
                }
            }
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
                        isRefreshing = false,
                    )
                }
                savePosition(thread.page)
            } catch (cancellation: CancellationException) {
                // Deliberately does NOT clear isRefreshing: a cancellation only comes from a
                // superseding load() (which owns the flag from its own start) or from
                // clearPrivateState() (which resets the whole state) — clearing it here could
                // race the superseding load and hide its in-flight indicator.
                throw cancellation
            } catch (
                // The throwable MESSAGE is intentionally NOT propagated to the UI state — it can
                // embed the private forum2.php?cat=prive&post=<id> URL (#316), so it must reach
                // neither the screen nor the exportable DiagnosticsLog. The Error state only
                // carries the #324 kind, a closed enum derived from the exception TYPE
                // (classifyHfrError) so the screen can tell an HFR 5xx outage from a network cut.
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                if (keepContent) {
                    // #351 — the page on screen stays put (it is still valid); the screen surfaces
                    // a one-shot Toast instead of swapping a readable conversation for an Error
                    // placeholder. Initial loads (nothing on screen) keep the Error + Retry path.
                    _state.update { it.copy(isRefreshing = false) }
                    _effects.send(PrivateMessageThreadEffect.RefreshFailed)
                } else {
                    _state.update {
                        it.copy(mode = PrivateMessageThreadUiState.Mode.Error(classifyHfrError(error)))
                    }
                }
            }
        }
    }

    /**
     * #430 — records the landed page so the next opening (or a process-death restoration)
     * resumes here. Launched in its own coroutine so a quick page change cancelling [loadJob]
     * cannot abort a write for a page that WAS displayed; best-effort (a lost write only costs
     * the resume position, the store itself no-ops when the session ended meanwhile).
     */
    private fun savePosition(page: Int) {
        viewModelScope.launch {
            runCatching { readPositionStore.savePage(request.threadId, page) }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(request: PrivateMessageThreadRequest): PrivateMessageThreadViewModel
    }
}
