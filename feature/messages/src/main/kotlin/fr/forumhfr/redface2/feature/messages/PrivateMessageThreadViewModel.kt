package fr.forumhfr.redface2.feature.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.error.classifyHfrError
import fr.forumhfr.redface2.core.domain.media.ImageSaveException
import fr.forumhfr.redface2.core.domain.media.PostImageSaver
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import fr.forumhfr.redface2.core.domain.messages.PrivateMessageReadPositionStore
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageFlagEntry
import fr.forumhfr.redface2.core.model.write.PrivateMessageReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyForm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
// Assisted route + one injected collaborator per independent thread concern; the image saver reuses
// this existing lifecycle/effect owner instead of introducing the duplicate ViewModel rejected for #1051.
@Suppress("LongParameterList")
class PrivateMessageThreadViewModel @AssistedInject constructor(
    @Assisted private val request: PrivateMessageThreadRequest,
    private val repository: MessagesRepository,
    private val authRepository: AuthRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val readPositionStore: PrivateMessageReadPositionStore,
    private val mpStorageRepository: MpStorageRepository,
    private val writeRepository: PrivateMessageWriteRepository,
    private val postImageSaver: PostImageSaver,
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

    // #612 — participant roster. Single in-flight fetch (dedup on rapid taps / recomposition) and a
    // memory cache for the life of the screen: re-opening the sheet reuses the parsed list without a
    // second network round-trip. `null` means « not yet loaded ».
    private var rosterJob: Job? = null
    private var cachedRosterForm: ReplyForm? = null

    init {
        viewModelScope.launch {
            authRepository.observeAuthState()
                .distinctUntilChanged()
                .collect { authState ->
                    when (authState) {
                        AuthState.Anonymous -> clearPrivateState()
                        is AuthState.Authenticated -> {
                            // Architecture contract (architecture.md): private state is purged on
                            // anonymous, logout AND session change. A DIRECT A → B switch must
                            // therefore reset through the same path as the logout — A's kept-on-
                            // screen conversation, roster job and cached roster form must never
                            // survive into B's session (with B's fetch free to fail without
                            // resurfacing them). Only the landing mode differs: B's load is known
                            // to follow, so the screen shows Loading, not the login placeholder.
                            if (authenticatedPseudo != null && authenticatedPseudo != authState.pseudo) {
                                clearPrivateState(nextMode = PrivateMessageThreadUiState.Mode.Loading)
                            }
                            authenticatedPseudo = authState.pseudo
                            // #1050 — expose the session pseudo for the Ego markers. The list
                            // derives both markers from this session-bound value (never from the
                            // cached Post.isOwnPost bit), so an A → B switch re-resolves them.
                            _state.update { it.copy(connectedPseudo = authState.pseudo) }
                            loadInitial()
                        }
                    }
                }
        }
        // #1050 — the two global reading preferences are render-only flows. Keeping them separate
        // from page loading guarantees a hot toggle cannot trigger a private network request.
        userPreferencesRepository.observeTopicFullWidthPosts()
            .onEach { fullWidth -> _state.update { it.copy(fullWidthPosts = fullWidth) } }
            .launchIn(viewModelScope)
        userPreferencesRepository.observeTopicSignatures()
            .onEach { show -> _state.update { it.copy(showSignatures = show) } }
            .launchIn(viewModelScope)
        // #1050 — the two #874 Ego preferences are deliberately independent flows (a message can
        // keep its EgoPost background while an auto-quote inside keeps its own EgoQuote marker).
        userPreferencesRepository.observeTopicEgoQuoteEnabled()
            .onEach { enabled -> _state.update { it.copy(egoQuoteEnabled = enabled) } }
            .launchIn(viewModelScope)
        userPreferencesRepository.observeTopicEgoPostEnabled()
            .onEach { enabled -> _state.update { it.copy(egoPostEnabled = enabled) } }
            .launchIn(viewModelScope)
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

    /**
     * #831/#1051 — saves a post image outside the sheet composition. The sheet closes immediately
     * after routing the URL here; [viewModelScope] keeps the write alive and the typed effect is
     * rendered by [PrivateMessageThreadScreen] when it completes.
     */
    fun saveImage(url: String) {
        viewModelScope.launch {
            val effect = try {
                postImageSaver.save(url)
                PrivateMessageThreadEffect.ImageSaved
            } catch (e: ImageSaveException) {
                when (e) {
                    is ImageSaveException.Fetch -> PrivateMessageThreadEffect.ImageSaveFailedFetch
                    is ImageSaveException.Storage -> PrivateMessageThreadEffect.ImageSaveFailedStorage
                    is ImageSaveException.TooLarge -> PrivateMessageThreadEffect.ImageSaveFailedTooLarge
                }
            }
            _effects.send(effect)
        }
    }

    /**
     * #612 — open the « Participants » sheet. LAZY (Codex framing): the reply form is fetched here,
     * on demand, never on screen entry — it is an owner-only, rarely-used GET that also carries a
     * session `hash_check`. A cached form (from a previous open this screen-life) is reused, so
     * re-opening is instant. The fetch is deduplicated: a tap while a load is already in flight is a
     * no-op rather than a second round-trip.
     */
    fun openRoster() {
        // Reuse the cached form if we already loaded it this screen-life (no second GET).
        cachedRosterForm?.let { form ->
            _state.update { it.copy(roster = form.toRoster(authenticatedPseudo)) }
            return
        }
        if (rosterJob?.isActive == true) {
            // A fetch is already running (e.g. a double tap) — just make sure the sheet is open.
            _state.update { it.copy(roster = PrivateMessageThreadUiState.Roster.Loading) }
            return
        }
        loadRoster()
    }

    /** #612 — retry a failed roster load, from the sheet's retry affordance. */
    fun retryRoster() {
        if (rosterJob?.isActive == true) return
        loadRoster()
    }

    /** #612 — close the sheet. The cached form survives so the next open is instant. */
    fun dismissRoster() {
        // Cancel any in-flight load so a late response can't flip the roster back to Loaded/Error and
        // reopen the just-dismissed sheet (Codex).
        rosterJob?.cancel()
        _state.update { it.copy(roster = PrivateMessageThreadUiState.Roster.Hidden) }
    }

    private fun loadRoster() {
        if (authenticatedPseudo == null) {
            clearPrivateState()
            return
        }
        _state.update { it.copy(roster = PrivateMessageThreadUiState.Roster.Loading) }
        rosterJob = viewModelScope.launch {
            try {
                val form = writeRepository.fetchReplyForm(
                    PrivateMessageReplyContext(
                        threadId = request.threadId,
                        page = _state.value.page.coerceAtLeast(1),
                    ),
                    // The roster NEEDS newdest (message.php only); never degrade to the quick-reply
                    // form on a follow-GET failure — surface it as Error+retry instead (Codex).
                    allowEmbeddedFallback = false,
                )
                cachedRosterForm = form
                _state.update { it.copy(roster = form.toRoster(authenticatedPseudo)) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                // #316 — the raw message can embed the private conversation URL: surface only the
                // type-derived kind (classifyHfrError), never the throwable text.
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                _state.update {
                    it.copy(roster = PrivateMessageThreadUiState.Roster.Error(classifyHfrError(error)))
                }
            }
        }
    }

    /**
     * #612 / #618 — maps a loaded reply form to a roster state. HFR ships the member list (MINUS the
     * viewer — it never lists you in your own row) two ways on message.php:
     *  - OWNER : the editable `newdest` input → `canManageRecipients` true, roster from
     *    [ReplyForm.recipientsRoster] (falls back to [manageableRecipients] for a form built before
     *    #618 / a test fake that only sets `newdest`).
     *  - NON-OWNER : the read-only « Destinataires » span → `canManageRecipients` false, roster from
     *    [ReplyForm.recipientsRoster].
     *
     * Either way the roster prepends [owner] so the sheet shows the FULL group including the viewer
     * (Codex). Only a form with NO roster at all (a one-to-one MP) maps to
     * [PrivateMessageThreadUiState.Roster.Unavailable]. The `canManageRecipients` flag is forwarded so
     * the sheet offers the owner-only « Gérer les destinataires » entry to the owner alone.
     */
    private fun ReplyForm.toRoster(owner: String?): PrivateMessageThreadUiState.Roster {
        val rosterCsv = recipientsRoster
            ?: manageableRecipients
            ?: return PrivateMessageThreadUiState.Roster.Unavailable
        val members = RecipientCsv.parse(rosterCsv)
        val full = if (owner != null && members.none { it == owner.trim() }) {
            listOf(owner.trim()) + members
        } else {
            members
        }
        return PrivateMessageThreadUiState.Roster.Loaded(
            members = full,
            canManageRecipients = canManageRecipients,
        )
    }

    /**
     * Purges everything owned by the previous session: the in-flight jobs, the cached roster form
     * and the whole UI state — only the four render-only preferences survive the reset (they are
     * not session data). This is the single purge path for the three session exits of the
     * architecture contract (anonymous, logout, session change): the anonymous/logout callers keep
     * the default [nextMode] (the login placeholder), while a direct A → B account switch passes
     * [PrivateMessageThreadUiState.Mode.Loading] because B's load is already known to follow — the
     * reader must see neither A's conversation nor a spurious login screen in between.
     */
    private fun clearPrivateState(
        nextMode: PrivateMessageThreadUiState.Mode = PrivateMessageThreadUiState.Mode.RequiresLogin,
    ) {
        val fullWidthPosts = _state.value.fullWidthPosts
        val showSignatures = _state.value.showSignatures
        val egoQuoteEnabled = _state.value.egoQuoteEnabled
        val egoPostEnabled = _state.value.egoPostEnabled
        authenticatedPseudo = null
        loadJob?.cancel()
        saveJob?.cancel()
        rosterJob?.cancel()
        cachedRosterForm = null
        // The render-only preferences survive the reset (they are not session data); the session
        // pseudo does NOT — falling back to initial()'s null connectedPseudo purges it on every
        // session exit (logout AND account switch), so no former identity leaks past this line
        // and both Ego markers go dark until the next session re-exposes its own pseudo.
        _state.value = PrivateMessageThreadUiState.initial(request)
            .copy(
                mode = nextMode,
                fullWidthPosts = fullWidthPosts,
                showSignatures = showSignatures,
                egoQuoteEnabled = egoQuoteEnabled,
                egoPostEnabled = egoPostEnabled,
            )
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
                // IDENTITY GUARD (C2) — the owner we snapshotted for THIS read. The repo re-resolves the
                // active pseudo and refuses (no POST) if it switched since this VM's own session re-check
                // above, closing the account-switch race across the repository's own suspension points.
                expectedPseudo = owner,
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
