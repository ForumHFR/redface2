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
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageFlagEntry
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
    private val mpStorageRepository: MpStorageRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PrivateMessageThreadUiState.initial(request))
    val state: StateFlow<PrivateMessageThreadUiState> = _state.asStateFlow()

    private val _effects: Channel<PrivateMessageThreadEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<PrivateMessageThreadEffect> = _effects.receiveAsFlow()

    // A new page load (or retry) cancels the previous in-flight one so a stale result cannot
    // overwrite the page the user is actually on.
    private var loadJob: Job? = null
    // #430 — single in-flight position write, latest-wins (cf. savePosition).
    private var saveJob: Job? = null
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
                            loadInitial()
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
        saveJob?.cancel()
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
    private suspend fun openingPage(owner: String): Int {
        val saved = try {
            readPositionStore.readPage(owner, request.threadId)
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            null
        }
        return maxOf(saved ?: 1, request.page.coerceAtLeast(1))
    }

    /**
     * Initial load of a (re)authenticated session (#430). The previous session's in-flight load
     * is cancelled BEFORE the first suspension point (the position-store read inside
     * [openingPage]): on an `Authenticated(A) → Authenticated(B)` switch, A's fetch could
     * otherwise land inside that window, pose A's conversation state and save A's position
     * under B's userId (review Codex PR #462).
     */
    private fun loadInitial() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val owner = authenticatedPseudo ?: return@launch
            fetchPage(openingPage(owner))
        }
    }

    private fun load(page: Int) {
        if (authenticatedPseudo == null) {
            clearPrivateState()
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            fetchPage(page)
        }
    }

    private suspend fun fetchPage(page: Int) {
        // Snapshot of the session that issued this fetch — savePosition is sealed to it (#462).
        val owner = authenticatedPseudo ?: return
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
            // The anchor of the LAST post on the landed page — the furthest the reader can have reached
            // on it. Null when the page has no posts (defensive); the auto MPStorage update then keeps
            // any existing anchor rather than nulling it.
            savePosition(thread.page, owner, lastPostNumreponse = thread.messages.lastOrNull()?.numreponse)
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

    /**
     * #430 — records the landed page so the next opening (or a process-death restoration)
     * resumes here. Best-effort: a lost write only costs the resume position. Two guards from
     * the Codex review of PR #462:
     *
     * - **Serialized, latest-wins**: a new landing cancels the previous in-flight write
     *   ([saveJob]), so a save delayed by IO can never overwrite a more recent position
     *   (the cancelled write either never commits or had already committed BEFORE the newer
     *   one — Room serializes writers).
     * - **Sealed to the loading session**: [owner] is the pseudo snapshotted when the fetch
     *   started; if the active session changed before this write fires, it is dropped (the
     *   store also re-resolves the active session and no-ops when none is left).
     */
    private fun savePosition(page: Int, owner: String, lastPostNumreponse: Int?) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (owner != authenticatedPseudo) return@launch
            try {
                readPositionStore.savePage(owner, request.threadId, page)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                // Swallowed: the resume position is a nicety, never worth surfacing an error.
            }
            syncMpStoragePosition(page, owner, lastPostNumreponse)
        }
    }

    /**
     * #597 — best-effort AUTO sync of the landed reading position to the shared MPStorage document,
     * inside the SAME [saveJob] launch as the local save (so it shares its serialize-latest-wins and
     * session guards) and AFTER it (the local write is the source of truth ; the remote sync is a
     * bonus that must never delay or break the local save).
     *
     * UPDATE-ONLY ([MpStorageRepository.writeBackFlagIfPresent]) : it only refreshes a `threadId` the
     * document ALREADY tracks (DTCloud's DT conversations) and NEVER adds a new entry from a passive
     * page land (anti-pollution of the cross-userscript storage). The whole call is silent : the opt-in
     * is OFF by default (no network then), and any failure — transport, session, an unwritable document —
     * is swallowed. A null [numreponse] preserves the entry's existing anchor (the repository keeps it).
     *
     * Re-checks the session AFTER the local save's suspension point: the launch's initial
     * `owner == authenticatedPseudo` guard only held when the job started, but `savePage` suspends, so
     * the active account may have changed in between. We must never write the shared MPStorage document
     * under a session other than the one that actually read this page (invariant: authenticated user only).
     */
    private suspend fun syncMpStoragePosition(page: Int, owner: String, numreponse: Int?) {
        if (owner != authenticatedPseudo) return
        try {
            mpStorageRepository.writeBackFlagIfPresent(
                MpStorageFlagEntry(
                    threadId = request.threadId,
                    page = page,
                    numreponse = numreponse,
                    // uri↔page coherence: only emit a fresh desktop uri when the anchor is known (so the
                    // uri's #t<anchor> matches the page). With no anchor, pass null → the update-only path
                    // PRESERVES both the existing href AND uri rather than minting an anchorless / stale uri.
                    uri = numreponse?.let { buildDesktopUri(request.threadId, page, it) },
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            // Swallowed: the MPStorage sync is a bonus on top of the (already done) local save. A failure
            // must never break the reading session — the local resume position is the source of truth.
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(request: PrivateMessageThreadRequest): PrivateMessageThreadViewModel
    }
}

/**
 * Rebuilds the canonical DTCloud desktop URI for an MPStorage `mpFlags.list[]` entry (#597) :
 * `/forum2.php?config=hfr.inc&cat=prive&post=<threadId>&page=<page>#t<numreponse>`. This is the exact
 * shape DTCloud stores (cf. the MpStorage parser fixtures) — relative path, the `t<numreponse>` anchor
 * as a fragment. The caller only builds it when the anchor is known, so the `#t…` fragment always
 * matches the page (uri↔page coherence); with no anchor the entry keeps its existing uri instead.
 */
internal fun buildDesktopUri(threadId: Int, page: Int, numreponse: Int): String =
    "/forum2.php?config=hfr.inc&cat=prive&post=$threadId&page=$page#t$numreponse"
