package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.coroutines.DefaultDispatcher
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.SmileyPickerCloseCause
import fr.forumhfr.redface2.core.domain.diagnostics.recordImagePickerEvent
import fr.forumhfr.redface2.core.domain.diagnostics.recordImagesPicked
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftSession
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorPreparationException
import fr.forumhfr.redface2.core.domain.editor.EditorSendOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionErrors
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.PendingInlineQuoteRequest
import fr.forumhfr.redface2.core.domain.editor.SubmissionRequest
import fr.forumhfr.redface2.core.domain.editor.insertEditorQuotes
import fr.forumhfr.redface2.core.domain.editor.mergeEditorBody
import fr.forumhfr.redface2.core.domain.editor.narrowDraftOffers
import fr.forumhfr.redface2.core.domain.editor.validateBbcodeDraft
import fr.forumhfr.redface2.core.domain.editor.visibleOver
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.smiley.HfrFavoriteSmileyRepository
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollections
import fr.forumhfr.redface2.core.domain.smiley.SmileyRepository
import fr.forumhfr.redface2.core.domain.upload.ImageUploadReader
import fr.forumhfr.redface2.core.domain.upload.UploadException
import fr.forumhfr.redface2.core.domain.upload.UploadFailureDiagnostics
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import fr.forumhfr.redface2.core.domain.write.EditPostRepository
import fr.forumhfr.redface2.core.domain.write.QuotedNumreponses
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.domain.write.TopicReplyQuoteMaterializer
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.model.write.EditPostContext
import fr.forumhfr.redface2.core.model.write.PostSubmitOperation
import fr.forumhfr.redface2.core.model.write.PrivateMessageEditContext
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.BbcodeFormatResult
import fr.forumhfr.redface2.core.ui.editor.EditorCause
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorHostMemory
import fr.forumhfr.redface2.core.ui.editor.EditorPreviewState
import fr.forumhfr.redface2.core.ui.editor.EditorSessionCheckpoint
import fr.forumhfr.redface2.core.ui.editor.EditorSessionField
import fr.forumhfr.redface2.core.ui.editor.EditorSessionRestoration
import fr.forumhfr.redface2.core.ui.editor.EditorSessionRestore
import fr.forumhfr.redface2.core.ui.editor.EditorSessionSurface
import fr.forumhfr.redface2.core.ui.editor.MAX_IMAGES_PER_UPLOAD
import fr.forumhfr.redface2.core.ui.editor.SmileyFavoriteMutations
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerFavoriteSources
import fr.forumhfr.redface2.core.ui.editor.SmileyWikiKeywordsSource
import fr.forumhfr.redface2.core.ui.editor.UploadProgress
import fr.forumhfr.redface2.core.ui.editor.afterFormLoaded
import fr.forumhfr.redface2.core.ui.editor.applyBbcodeAction
import fr.forumhfr.redface2.core.ui.editor.editBbcode
import fr.forumhfr.redface2.core.ui.editor.imageInsertBbcodeOrNull
import fr.forumhfr.redface2.core.ui.editor.insertBbcodeToken
import fr.forumhfr.redface2.core.ui.editor.pickedImagesForUpload
import fr.forumhfr.redface2.core.ui.editor.toEditorError
import fr.forumhfr.redface2.core.ui.editor.toEditorUploadError
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ViewModel backing the post-level editor. Owns the BBCode draft, the parsed
 * preview AST, the preview-visibility toggle, and the Phase 2C (#145) reply
 * submission lifecycle :
 *
 * 1. On init, when the request is a reply with a known `(page, subcat, topicId)`,
 *    fetches the HFR reply form (`message.php`) to grab the per-session
 *    `hash_check` and the hidden contract fields.
 * 2. On [PostEditorIntent.SubmitClicked], POSTs `bddpost.php` via the repository
 *    and emits a one-shot [PostEditorEffect.SubmitSucceeded] on success.
 *
 * ED-161/169/170: [submission] reserves synchronously, persists the immutable snapshot and
 * keeps success terminal. Errors classified by the repository remain in [EditorError].
 */
@HiltViewModel(assistedFactory = PostEditorViewModel.Factory::class)
@Suppress("LongParameterList", "LargeClass")
// LongParameterList — Hilt constructor injection, one dependency per collaborator (#459 PR2 adds
// the upload reader + repository + auth source for the in-editor image upload).
// LargeClass — one class per ViewModel co-locates every code path (#145 reply / #146 quote /
// #147 edit / #11 smiley / #189 image-url / #405 draft / #459 image-upload) with its dispatcher ;
// splitting per phase would shred the shared state transformers (same rationale as TopicFormVM).
class PostEditorViewModel @AssistedInject constructor(
    @Assisted private val request: PostEditorRequest,
    private val previewParser: BbcodePreviewParser,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val replyRepository: ReplyRepository,
    private val editPostRepository: EditPostRepository,
    private val smileyRepository: SmileyRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val draftStore: EditorDraftStore,
    private val diagnostics: DiagnosticsLog,
    private val uploadRepository: UploadRepository,
    private val imageUploadReader: ImageUploadReader,
    private val authRepository: AuthRepository,
    private val quoteMaterializer: TopicReplyQuoteMaterializer,
    hfrFavoriteSmileyRepository: HfrFavoriteSmileyRepository,
    smileyLocalCollections: SmileyLocalCollections,
    clock: DiagnosticsClock,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    /** The live body, selection and IME state stay in memory, outside the Bundle. */
    val bodyFieldState = TextFieldState()
    val bodyScrollState = ScrollState(0)
    /** ED-14: hydration/restoration resets reveal without taking ownership of ED-81 scroll restoration. */
    var bodyCaretRevealResetKey by mutableIntStateOf(0)
        private set
    private val restoration = EditorSessionRestoration(savedStateHandle, EditorSessionSurface.Post)
    private var previousBody = bodyFieldState.text.toString()
    private var bodyEditedBeforeHydration = false

    private val _state: MutableStateFlow<PostEditorState> = MutableStateFlow(
        PostEditorState(
            isInitializing = true,
            mode = request.mode,
            target = request.target,
            hasMessageTone = request.target == PostEditorTarget.Public,
            cat = request.cat,
            topicId = request.topicId,
            numreponse = request.numreponse,
            page = request.page,
            subcat = request.subcat,
            // #1553: project inline coordinates only after loading the durable row. The topic
            // already chose the rendering; initialQuotes contains transient cards only.
            quotes = emptyList(),
            quoteFetchFailures = savedStateHandle.get<Int>(QUOTE_FAILURES_KEY) ?: 0,
        ),
    )
    val state: StateFlow<PostEditorState> = _state.asStateFlow()
    internal val chromeEvents = PostEditorChromeEvents()
    // ED-63/C8: VM lifetime only; visibility remains in the existing preview pipeline.
    internal val previewLayout by lazy { EditorPreviewState(userPreferencesRepository, viewModelScope) }
    internal val chromeMemory = EditorHostMemory<PostEditorChromeSurface>()

    /** Capture operation transitions synchronously; observing StateFlow would lose short operations. */
    private fun updateChromeState(transform: (PostEditorState) -> PostEditorState) {
        val previous = _state.value
        _state.update { transform(it).copy(blocksFormReload = submission.blocksMutation) }
        if (chromeEvents.state.session != submission.session) chromeMemory.reset()
        chromeEvents.open(submission.session)
        chromeEvents.observe(previous, _state.value)
    }

    private val _effects: Channel<PostEditorEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<PostEditorEffect> = _effects.receiveAsFlow()

    /**
     * ED-88 (#1616) — one signal per SEALED C/Q transaction (quoted body written, request erased,
     * together) : `:app` empties the multi-quote basket on it. Its own conflated channel, not an
     * effect : a delivery is not a navigation outcome and must never precede one in that queue.
     */
    private val _quotesDelivered: Channel<Unit> = Channel(capacity = Channel.CONFLATED)
    val quotesDelivered: Flow<Unit> = _quotesDelivered.receiveAsFlow()

    // Keep the last served capability across token invalidation; null means no form has loaded yet.
    private var servedSubjectEditable: Boolean? = null

    /**
     * Cached form pulled lazily on [PostEditorMode.Reply]. Keeping it on the
     * ViewModel rather than the [PostEditorState] avoids leaking `hash_check`
     * through Compose snapshot tooling / state restoration.
     */
    private var loadedForm: ReplyForm? = null
        set(value) {
            field = value
            if (value != null) servedSubjectEditable = value.isSubjectEditable
            _state.update { it.copy(hasLoadedForm = value != null,
                hasAuthenticatedForm = value?.isAnonymous == false, topicTitle = value?.sujet.orEmpty()) }
            if (value == null) smileyPicker.refreshFavorites(null)
        }

    /**
     * #441 — smiley picker (Favorites + Standard + Wiki), delegated to the shared
     * [SmileyPickerController] exactly like the MP composers (#387/#440) : the controller owns
     * open/dismiss/query, the 300 ms debounce and its race guards, and the #824
     * restore-search-on-reopen contract. This ViewModel keeps only the insertion
     * ([onSmileySelected] — a draft mutation, so it stays an MVI intent). Mixed wiring is
     * deliberate : the sheet talks to the controller directly for open/dismiss/query and goes
     * through [PostEditorIntent.SmileySelected] for insertion — do not re-route one into the
     * other for « consistency ».
     *
     * Lifecycle (réserve Codex #441) : this ViewModel's editorial context is frozen at
     * construction (`@Assisted request` ; one `PostEditorRoute` nav entry = one ViewModel,
     * cf. `RedfaceNavigation`), so the controller can never be re-armed for another post —
     * no targeted reset is needed beyond the ViewModel's own death.
     *
     * Failure logging stays a policy of THIS feature (the MP composers keep the controller's
     * no-op default) : class name only — never the query nor the userId (anti-leak test).
     */
    // Keep before init: loadedForm's setter can run synchronously there and crash on an uninitialized picker.
    val smileyPicker = SmileyPickerController(
        diagnostics = diagnostics,
        scope = viewModelScope,
        searchWiki = smileyRepository::searchWiki,
        wikiKeywords = SmileyWikiKeywordsSource(
            cached = smileyRepository::cachedWikiKeywords,
            load = smileyRepository::getWikiKeywords,
        ),
        favoriteSources = SmileyPickerFavoriteSources(
            favoriteSmileys = { loadedForm?.favoriteSmileys.orEmpty() },
            hfrFavoriteMutations = SmileyFavoriteMutations(
                addHfrFavorite = hfrFavoriteSmileyRepository::add,
                removeHfrFavorite = hfrFavoriteSmileyRepository::remove,
            ),
            localCollections = smileyLocalCollections,
        ),
        userId = { _state.value.userId },
        onSearchFailed = { error ->
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG_VM,
                "wiki smiley search failed: ${error::class.simpleName}",
            )
        },
    )

    /**
     * #405 — stable, content-free draft key for this editor session, or null when the routing args
     * cannot identify a target (no autosave/restore then). Reply ignores the quoted numreponse on
     * purpose (cf. [EditorDraftKey]). Edit needs the numreponse to identify the post.
     */
    private val draftKey: String? = when (request.mode) {
        PostEditorMode.Reply -> request.topicId?.let { EditorDraftKey.reply(checkNotNull(request.cat), it) }
        PostEditorMode.Edit -> request.numreponse?.let {
            when (request.target) {
                PostEditorTarget.Public -> EditorDraftKey.editPost(checkNotNull(request.cat), it)
                is PostEditorTarget.PrivateMessage -> EditorDraftKey.mpEdit(it)
            }
        }
    }

    /** #405 — debounced autosave coroutine ; relaunched (cancelling the previous) on each edit. */
    private val submission: EditorSubmissionBinding<PostEditorEffect> = EditorSubmissionBinding(
        environment = EditorSubmissionEnvironment(viewModelScope, clock, diagnostics),
        drafts = EditorDraftSession(draftStore, draftKey),
        errors = EditorSubmissionErrors(
            uncertain = EditorErrors.uncertain, draftWriteFailed = EditorErrors.draftWriteFailed,
            sessionExpired = EditorErrors.sessionExpired, unknown = EditorErrors.uncertain,
            network = EditorErrors.network, preparationFailed = EditorErrors.invalidForm,
            quoteFetchFailed = EditorErrors.quoteFetchFailed,
            draftSaveFailed = EditorErrors.draftSaveFailed,
        ),
        callbacks = EditorSubmissionCallbacks<PostEditorEffect>(
            state = { value ->
                updateChromeState {
                    it.copy(
                        submission = value,
                        isSubmitting = value is EditorSubmissionState.PersistingAttempt ||
                            value is EditorSubmissionState.Sending,
                        showSubmitConfirmation = value is EditorSubmissionState.Armed,
                    )
                }
            },
            error = { value ->
                updateChromeState {
                    val mapped = if (value?.cause == EditorCause.QuoteFetchFailed) {
                        EditorErrors.quoteFetchFailed(inline = it.pendingQuotes.isNotEmpty())
                    } else { value }
                    it.copy(submitError = mapped)
                }
            },
            success = { value -> _effects.trySend(value) },
            durableWriteSucceeded = { chromeEvents.durableWriteSucceeded(); refreshDraftOffers() },
            clearError = { error ->
                updateChromeState { if (it.submitError == error) it.copy(submitError = null) else it }
            },
        ),
    )

    /**
     * #459 PR2 — mirror of the persisted [EditorImageInsert] preference (full / linked / reduced).
     * Collected on init like the confirmation preference and read SYNCHRONOUSLY at insert time so the
     * image-URL / upload paths can mutate the draft in the same frame as the user action (no
     * suspend between the action and the draft mutation — Codex PR2 review). Default mirrors the
     * repository's REDUCED default until the first emission lands.
     */
    private var imageInsertMode: EditorImageInsert = EditorImageInsert.REDUCED

    /**
     * #459 PR2 — active lowercased HFR pseudo (the upload `userId`), or null when anonymous.
     * Captured from the auth stream exactly like `MyImagesViewModel` / `FlagsViewModel` so an
     * [PostEditorIntent.ImagePicked] can scope the upload to the right owner without re-reading the
     * flow. Kept off [PostEditorState] : the editor never renders the pseudo, only gates on its
     * presence.
     */
    private var activeUserId: String? = null
    private var authObserved = false

    /** #459 PR2 — in-flight image upload job ; one at a time, cancelled on [onCleared]. */
    private var uploadJob: Job? = null
    private var draftRestored = false
    private var sharedDraftResumed = false
    private var draftRestoreDeciding = false
    private var draftDecisionCompleted: CompletableDeferred<Unit>? = null
    private var restoredOfferAwaitingWrite: String? = null
    private var mergedDraftOffer: String? = null
    private var selectedDraftOffer: EditorDraftStore.DraftOffer? = null
    private var inlineQuoteDeliveryRequested = false
    private var duplicateQuoteDelivery = false
    private var deliveredInlineQuoteRequest: PendingInlineQuoteRequest? = null
    // Declared before `init`: the first delivery can run synchronously during construction.
    private val quoteCommit = Mutex()
    private var deliveredQuoteBody: String? = null
    private var quoteCardsDelivered = false
    private var screenStopped = false
    private var stoppedBody: String? = null
    private var pendingCheckpoints = 0
    private var lastCheckpointVersion = 0L

    /** Synchronous guard, including the frame before readOnly recomposes. */
    val bodyInputTransformation = InputTransformation {
        if (_state.value.isAwaitingInitialBody || !acceptMutation(blockDuringUpload = true)) {
            revertAllChanges()
        } else if (asCharSequence().toString() != bodyFieldState.text.toString()) {
            bodyEditedBeforeHydration = true
        }
    }

    private fun acceptMutation(blockDuringUpload: Boolean = false): Boolean {
        val uploadBlocked = blockDuringUpload && _state.value.isUploading
        if (_state.value.isInitializing || submission.blocksMutation || uploadBlocked) return false
        return submission.tryDisarm()
    }

    init {
        submission.drafts.checkpointObserver = { draft, version, result ->
            if (!submission.drafts.invalidated) {
                if (result == null) {
                    captureCheckpoint(draft, version)
                } else {
                    restoration.completed(draft, version, result)
                }
            }
        }
        observeBody()
        observePreview()
        submission.observePreference(userPreferencesRepository.observeConfirmBeforePosting())
        viewModelScope.launch {
            userPreferencesRepository.observeEditorImageInsert().collect { mode ->
                imageInsertMode = mode
            }
        }
        viewModelScope.launch {
            userPreferencesRepository.observeEditorShortcut().collect { shortcut ->
                _state.update { it.copy(editorShortcut = shortcut) }
            }
        }
        viewModelScope.launch {
            userPreferencesRepository.observeImagePickerMode().collect { mode ->
                _state.update { it.copy(imagePickerMode = mode) }
            }
        }
        viewModelScope.launch {
            authRepository.observeAuthState().collect { authState ->
                val newUserId = when (authState) {
                    AuthState.Anonymous -> null
                    is AuthState.Authenticated -> authState.pseudo.lowercase()
                }
                // Account switched/logged out mid-session: cancel any in-flight upload and pending
                // autosave so this session's image URL / body is never attributed to the new
                // account (Codex beta review). The draft store also drops the now-stale save.
                if (authObserved && newUserId != activeUserId) {
                    uploadJob?.cancel()
                    submission.accountChanged()
                    loadedForm = null
                    restoration.invalidate()
                    inlineQuoteDeliveryRequested = false
                    restoredOfferAwaitingWrite = null
                    mergedDraftOffer = null
                    deliveredInlineQuoteRequest = null
                    updateChromeState { it.copy(isUploading = false, uploadProgress = null,
                        restorableDraft = null, draftOffers = emptyList()) }
                }
                authObserved = true
                activeUserId = newUserId
            }
        }
        viewModelScope.launch {
            restoreDraftIfAny()
            draftRestored = true
            if (submission.isInitialized) {
                when (request.mode) {
                    PostEditorMode.Reply -> resolveQuoteRenderingThenLoad()
                    PostEditorMode.Edit -> {
                        _state.update { it.copy(isInitializing = false) }
                        loadEditFormIfPossible()
                    }
                }
            }
            _state.update { it.copy(isInitializing = false) }
        }
    }

    private fun observeBody() {
        viewModelScope.launch {
            snapshotFlow { bodyFieldState.text.toString() }.collect { body ->
                _state.update { it.copy(bodyText = body, validation = validateBbcodeDraft(body)) }
                if (body != previousBody) {
                    previousBody = body
                    scheduleAutosave()
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observePreview() {
        viewModelScope.launch {
            combine(
                snapshotFlow { bodyFieldState.text.toString() },
                state.map { it.isPreviewVisible }.distinctUntilChanged(),
            ) { body, visible -> body.takeIf { visible } }
                .distinctUntilChanged()
                .conflate()
                .mapLatest { body ->
                    body?.let { it to withContext(defaultDispatcher) { previewParser.parsePreview(it) } }
                }
                .collect { parsed ->
                    if (parsed == null) {
                        _state.update { it.copy(preview = PostContent(blocks = emptyList())) }
                    } else if (_state.value.isPreviewVisible && bodyFieldState.text.toString() == parsed.first) {
                        _state.update { it.copy(preview = parsed.second) }
                    }
                }
        }
    }

    /** #1553: Room is the only authority for inline requests; handoffs contain transient cards only. */
    private fun resolveQuoteRenderingThenLoad() {
        val pending = submission.drafts.pendingInlineQuoteRequest
        if (!quoteCardsDelivered) {
            // Room owns the rendering of this durable handoff; never also deliver it as a card.
            // Consume cards once, including across Room reconciliation after a failed write.
            val inlinePosts = pending?.quotes?.map { it.numreponse }.orEmpty().toSet()
            val delivered = request.initialQuotes.takeUnless { restoration.isRecreation }.orEmpty()
                .filterNot { it.numreponse in inlinePosts }
            val cards = distinctQuoteCards(checkNotNull(request.cat), delivered)
            _state.update { it.copy(quotes = cards) }
            quoteCardsDelivered = true
            if (cards.size < delivered.size) chromeEvents.quoteAlreadyDelivered()
        }
        if (pending != null) {
            setPendingQuotes(pending.quotes.map {
                QuoteSelection(QuoteLocator(it.page, it.numreponse, it.ref), "", "", it.truncate)
            })
            _state.update { it.copy(isInitializing = false) }
            servePendingInlineQuoteIfEligible(requestDelivery = true)
        } else {
            savedStateHandle.remove<Int>(QUOTE_FAILURES_KEY)
            _state.update { it.copy(quoteFetchFailures = 0, isInitializing = false) }
            loadReplyFormIfPossible()
        }
    }

    private fun setPendingQuotes(quotes: List<QuoteSelection>) {
        _state.update { it.copy(pendingQuotes = quotes) }
    }

    private fun reloadForm() {
        if (!draftRestored || _state.value.blocksFormReload || _state.value.isLoadingForm) return
        if (_state.value.quoteStorageUnavailable) {
            updateChromeState { it.copy(isLoadingForm = true) }
            viewModelScope.launch {
                submission.flush(null)
                restoreDraftIfAny(reconcile = true)
                if (submission.isInitialized) {
                    when (request.mode) {
                        PostEditorMode.Reply -> resolveQuoteRenderingThenLoad()
                        PostEditorMode.Edit -> loadEditFormIfPossible()
                    }
                }
            }
            return
        }
        submission.disarm()
        val pending = _state.value.pendingQuotes
        when {
            pending.isNotEmpty() -> servePendingInlineQuoteIfEligible(
                requestDelivery = true,
            )
            _state.value.quotes.isNotEmpty() && _state.value.submitError?.cause == EditorCause.QuoteFetchFailed ->
                loadReplyFormWithInlineQuotes(_state.value.quotes, insertInline = false)
            request.mode == PostEditorMode.Edit -> loadEditFormIfPossible()
            else -> loadReplyFormIfPossible()
        }
    }

    private fun ignorePendingQuote() {
        if (!_state.value.canIgnorePendingQuote || submission.blocksMutation) return
        val pending = submission.drafts.pendingInlineQuoteRequest
        submission.disarm()
        updateChromeState { it.copy(isLoadingForm = true) }
        viewModelScope.launch {
            // ED-88: Ignore seals an inserted delivery only while all its quotes remain in the field.
            val present = QuotedNumreponses.fromBbcode(bodyFieldState.text.toString())
            val sealsDelivery = pending != null && pending == deliveredInlineQuoteRequest &&
                !_state.value.unreadableQuoteRequest && present.containsAll(pending.quotes.map { it.numreponse })
            val result = if (_state.value.unreadableQuoteRequest) {
                submission.drafts.discardUnreadableInlineQuoteRequest()
            } else {
                if (sealsDelivery) {
                    submission.drafts.consumeInlineQuoteRequest(checkNotNull(pending), deliveredDraft(),
                        deliveredBody = deliveredQuoteBody)
                } else submission.drafts.consumeInlineQuoteRequest(checkNotNull(pending))
            }
            if (result is EditorDraftStore.StoreWriteResult.Written && !submission.drafts.invalidated) {
                submission.resolveDraftWrite(result)
                if (_state.value.unreadableQuoteRequest) {
                    restoreDraftIfAny(reconcile = true)
                    resolveQuoteRenderingThenLoad()
                } else {
                    finishInlineQuoteDelivery()
                    // Sealed: C was written with Q. Not sealed: edits made while the delivery was
                    // pending were never autosaved (draftSnapshot is null until now), so persist C.
                    if (sealsDelivery) _quotesDelivered.trySend(Unit) else scheduleAutosave()
                    if (loadedForm == null) {
                        loadReplyFormIfPossible(preserveDraft = true)
                    }
                }
            } else {
                quoteStorageFailed(result)
            }
        }
    }

    private fun resumeDormantQuote() {
        if (!_state.value.hasDormantQuote || _state.value.isLoadingForm || submission.blocksMutation) return
        submission.acknowledge {
            servePendingInlineQuoteIfEligible(requestDelivery = true)
        }
    }

    private fun quoteStorageFailed(result: EditorDraftStore.StoreWriteResult?) {
        submission.resolveDraftWrite(result)
        updateChromeState {
            it.copy(
                isLoadingForm = false,
                quoteStorageUnavailable = it.quoteStorageUnavailable ||
                    result is EditorDraftStore.StoreWriteResult.Conflict,
                submitError = if (it.unreadableQuoteRequest) EditorErrors.unreadableQuoteRequest else it.submitError,
            )
        }
    }

    private fun loadReplyFormWithInlineQuotes(quotes: List<QuoteSelection>, insertInline: Boolean = true) {
        if (inlineQuoteLoadBlocked(insertInline)) return
        submission.disarm()
        if (insertInline) setPendingQuotes(quotes)
        // #1553: retry a failed CAS without replaying an insertion the user may have edited.
        if (insertInline && submission.drafts.pendingInlineQuoteRequest == deliveredInlineQuoteRequest) {
            updateChromeState { it.copy(isLoadingForm = true) }
            viewModelScope.launch {
                commitInlineQuoteDelivery()
                if (loadedForm == null) loadReplyFormIfPossible(preserveDraft = true)
            }
        } else {
            fetchQuoteForm(quotes, insertInline)
        }
    }

    /** Inline delivery only runs once this opening chose the inline rendering (cards ON never does). */
    private fun inlineQuoteLoadBlocked(insertInline: Boolean): Boolean {
        if (request.mode != PostEditorMode.Reply || _state.value.isLoadingForm || submission.blocksMutation) return true
        val storageBlocked = _state.value.unreadableQuoteRequest || _state.value.quoteStorageUnavailable
        return insertInline &&
            (submission.drafts.pendingInlineQuoteRequest == null || _state.value.hasDormantQuote || storageBlocked)
    }

    private fun finishInlineQuoteDelivery() {
        inlineQuoteDeliveryRequested = false
        deliveredInlineQuoteRequest = null
        savedStateHandle.remove<Int>(QUOTE_FAILURES_KEY)
        updateChromeState {
            it.copy(
                pendingQuotes = emptyList(), quoteFetchFailures = 0, isLoadingForm = false, quoteCommitPending = false,
                submitError = it.submitError.afterFormLoaded(quotesResolved = true),
            )
        }
    }

    private fun fetchQuoteForm(
        quotes: List<QuoteSelection>,
        insertInline: Boolean,
    ) {
        val context = buildReplyContext() ?: run {
            updateChromeState { it.copy(submitError = EditorErrors.missingSubcat) }
            return
        }
        val quoteContext = context.copy(quotedNumreponse = quotes.first().numreponse, quoteRef = null)
        val opening = submission.session
        val favoriteStamp = smileyPicker.favoriteFormStamp()
        updateChromeState { it.copy(isLoadingForm = true, isFetchingQuote = true) }
        viewModelScope.launch {
            val outcome = runCatching {
                quoteMaterializer.fetchFormWithQuotes(
                    context = quoteContext,
                    extraQuoteNumreponses = quotes.drop(1).map { it.numreponse },
                    truncate = quotes.any { it.truncate },
                ).also { currentCoroutineContext().ensureActive() }
            }
            if (submission.session != opening) return@launch
            outcome.fold(
                onSuccess = { form ->
                    if (form.initialContent.isBlank()) {
                        handleFetchFailure(IllegalStateException("empty quote prefill"), quoteFetch = true)
                        return@fold
                    }
                    draftDecisionCompleted?.await()
                    if (submission.session != opening) return@fold
                    loadedForm = form
                    smileyPicker.refreshFavorites(favoriteStamp)
                    if (_state.value.isAwaitingInitialBody || !acceptMutation(blockDuringUpload = insertInline)) {
                        inlineQuoteDeliveryRequested =
                            insertInline && _state.value.isUploading && !submission.blocksMutation
                        updateChromeState { it.copy(isLoadingForm = false) }
                        return@fold
                    }
                    if (!insertInline) {
                        applyFormHydration(form.copy(initialContent = ""))
                        updateChromeState {
                            it.copy(submitError = it.submitError.afterFormLoaded(quotesResolved = true))
                        }
                        return@fold
                    }
                    deliverInlineQuoteForm(form)
                },
                onFailure = { error -> handleFetchFailure(error, quoteFetch = true) },
            )
        }
    }


    private suspend fun deliverInlineQuoteForm(form: ReplyForm) {
        // ED-52: compare content in the live field, including keystrokes made during GET.
        val pending = checkNotNull(submission.drafts.pendingInlineQuoteRequest)
        val existing = bodyFieldState.text.toString()
        val delivery = filterInlineQuoteDelivery(
            pending.cat,
            checkNotNull(request.cat), form.initialContent.trimEnd(), existing,
        )
        duplicateQuoteDelivery = delivery.duplicates > 0
        val prefills = delivery.bbcode
        // ED-132: reconcile on the live field immediately before this single edit.
        // #881/#790: a quote alone ends with one newline; a prefix uses two.
        chromeMemory.focus.clearToolSelection()
        if (prefills.isNotBlank()) bodyFieldState.editBbcode { text, _, _ ->
            val inserted = insertEditorQuotes(text, prefills, submission.drafts.provenanceFor(text))
            val combined = if (text.isBlank()) inserted.body + "\n" else inserted.body
            submission.drafts.stageBodyMerge(inserted.copy(body = combined))
            BbcodeFormatResult(combined, combined.length, combined.length)
        }
        deliveredInlineQuoteRequest = pending
        deliveredQuoteBody = form.initialContent.trimEnd()
        if (existing.isBlank() && prefills.isNotBlank()) resetUndoBaseline()
        applyFormHydration(form.copy(initialContent = ""))
        val combined = bodyFieldState.text.toString()
        updateChromeState {
            it.copy(
                isLoadingForm = true, quoteCommitPending = true,
                draftHydratedContent = combined.takeIf { existing.isBlank() },
            )
        }
        commitInlineQuoteDelivery()
    }

    /** Deliver the current body and remove exactly this request in one awaited CAS. */
    private suspend fun commitInlineQuoteDelivery(): Unit = quoteCommit.withLock {
        // A close settling this delivery may queue behind the first CAS: it then finds Q consumed.
        val pending = submission.drafts.pendingInlineQuoteRequest ?: return@withLock
        submission.flush(null)
        val result = submission.drafts.consumeInlineQuoteRequest(
            pending, deliveredDraft(), deliveredBody = deliveredQuoteBody,
        )
        if (result is EditorDraftStore.StoreWriteResult.Written && !submission.drafts.invalidated) {
            submission.resolveDraftWrite(result)
            finishInlineQuoteDelivery()
            deliveredQuoteBody = null
            _quotesDelivered.trySend(Unit) // ED-88: the citations are durable, the basket may go.
            if (duplicateQuoteDelivery) chromeEvents.quoteAlreadyDelivered()
            duplicateQuoteDelivery = false
            // Preserve keystrokes made while the CAS was suspended.
            scheduleAutosave()
        } else {
            quoteStorageFailed(result)
        }
    }

    /**
     * #405/#1415 — offer a cached draft for [draftKey] until the user handles it. An untouched
     * server prefill remains eligible: the banner is a choice and never overwrites it automatically.
     * A genuine user edit (content distinct from [PostEditorState.draftHydratedContent]) suppresses
     * the initial offer. The content fingerprint is recorded only after Ignore, so a restored row
     * can be offered again if process death loses the live editor state before autosave.
     *
     * #790 exception — when the route carries `resumeSharedDraft` (escalation of a quick-reply
     * sheet, which JUST wrote the row), a decided body is APPENDED instead of banner'd :
     * the escalation continues the same composition act. With cards OFF (#805 default) the
     * open-time quote form DOES carry a `[quotemsg]` prefill again : this append and the form
     * hydration prepend are commutative, guarded against double-hydration — the original #790
     * contract, restored. The sheet requires a decision on its offer before escalation.
     */
    @Suppress("CyclomaticComplexMethod", "ComplexCondition") // Ordered restore, shared handoff and quote projection.
    private suspend fun restoreDraftIfAny(reconcile: Boolean = false) {
        // v20 coordinates are deliberately never decoded or imported (ED-132).
        val legacy = savedStateHandle.remove<IntArray>("postEditor.pendingQuotes")
        updateChromeState {
            it.copy(legacyQuoteRequestDropped = it.legacyQuoteRequestDropped || legacy?.isNotEmpty() == true)
        }
        if (reconcile) {
            _state.update { it.copy(restorableDraft = null) }
        }
        val entry = submission.restore(draftKey.orEmpty())
        val owner = submission.drafts.owner
        if (restoration.isRecreation && !restoration.belongsTo(owner, draftKey)) {
            savedStateHandle.remove<String>(DRAFT_RESTORE_OFFER_FINGERPRINT_KEY)
            savedStateHandle.remove<Int>(QUOTE_FAILURES_KEY)
            _state.update { it.copy(quoteFetchFailures = 0) }
        }
        restoration.bind(owner, draftKey)
        val restore = restoration.resolve(entry, owner, draftKey, allowResume = !reconcile)
        if (restore == EditorSessionRestore.None && entry?.attempt == null &&
            entry?.draft?.bodyProvenance?.isQuoteOnly(entry.draft.body) == true &&
            !restoration.isRecreation && !reconcile && !request.resumeSharedDraft
        ) {
            // The field stays empty on fresh openings; persist that decision before any autosave.
            submission.resolveDraftWrite(submission.drafts.discardQuoteOnly())
        }
        if (entry != null && !submission.blocksMutation && restore != EditorSessionRestore.None) {
            if (restore == EditorSessionRestore.Uncertain) {
                restoreAttempt(entry.draft)
                if (restoration.matches(entry, owner, draftKey)) restoreViewport(entry.draft.body.length)
            } else if (restore == EditorSessionRestore.Resume) {
                submission.drafts.resumeContent()
                restoreSubject(entry.draft)
                replaceBody(entry.draft.body)
                previousBody = entry.draft.body
                restoreViewport(entry.draft.body.length)
                resetUndoBaseline()
                _state.update { it.copy(draftHydratedFromForm = true, restorableDraft = null) }
                restoration.decide(EditorSessionField.Body)
            } else {
                restoreOrdinaryDraft(entry.draft, reconcile)
            }
        }
        // An explicit quick-reply escalation continues C even when it contains only a delivered quote.
        // The fresh-opening quote-only rule applies to a new composition, not to this handoff.
        if (entry != null && entry.attempt == null && submission.drafts.owner != null &&
            restore == EditorSessionRestore.None && !submission.blocksMutation &&
            entry.draft.bodyProvenance?.isQuoteOnly(entry.draft.body) == true &&
            request.mode == PostEditorMode.Reply && request.resumeSharedDraft &&
            !restoration.isRecreation && !reconcile
        ) resumeSharedDraft(entry.draft.body)
        if (request.mode == PostEditorMode.Reply) refreshDraftOffers()
        else if (_state.value.restorableDraft != null && entry != null) {
            _state.update { it.copy(draftOffers = listOf(EditorDraftStore.DraftOffer.from(entry))) }
        }
        val pending = submission.drafts.pendingInlineQuoteRequest
        if (pending != deliveredInlineQuoteRequest) {
            deliveredInlineQuoteRequest = null
            deliveredQuoteBody = null
        }
        val requestResolved = submission.isInitialized && pending == null
        if (requestResolved) savedStateHandle.remove<Int>(QUOTE_FAILURES_KEY)
        updateChromeState {
            val unreadable = submission.drafts.hasUnreadableInlineQuoteRequest
            it.copy(
                quoteStorageUnavailable = !submission.isInitialized, isLoadingForm = false,
                unreadableQuoteRequest = unreadable,
                // A successful Room reread is authoritative, including a request consumed elsewhere.
                pendingQuotes = if (submission.isInitialized) pending?.quotes?.map { quote ->
                    QuoteSelection(QuoteLocator(quote.page, quote.numreponse, quote.ref), "", "", quote.truncate)
                }.orEmpty() else it.pendingQuotes,
                quoteFetchFailures = if (requestResolved) 0 else it.quoteFetchFailures,
                submitError = when {
                    unreadable -> EditorErrors.unreadableQuoteRequest
                    it.submitError == EditorErrors.unreadableQuoteRequest -> null
                    requestResolved && it.submitError?.cause == EditorCause.QuoteFetchFailed -> null
                    else -> it.submitError
                },
            )
        }
    }

    private fun refreshDraftOffers() {
        if (request.mode != PostEditorMode.Reply) return
        // ED-121: an offer identical to the field is hidden from the list, not deleted (see visibleOver).
        val offers = submission.drafts.availableOffers.takeUnless { submission.drafts.invalidated }.orEmpty()
            .visibleOver(bodyFieldState.text.toString())
        updateChromeState { it.copy(draftOffers = offers, restorableDraft = offers.firstOrNull()?.draft?.body) }
        if (selectedDraftOffer != null && submission.drafts.available(selectedDraftOffer) == null) {
            selectedDraftOffer = null
            mergedDraftOffer = null
        }
    }

    private fun restoreOrdinaryDraft(draft: EditorDraftStore.Draft, reconcile: Boolean) {
        val body = draft.body
        if (body.isBlank() && draft.subject.isNullOrBlank()) return
        // #1574 keeps the shared resume to Reply; ED-81 never replays it on a recreation.
        val resumesSharedReply = request.mode == PostEditorMode.Reply && request.resumeSharedDraft
        if (resumesSharedReply && !reconcile && !restoration.isRecreation) {
            resumeSharedDraft(body)
        } else {
            val fingerprint = draft.restoreOfferFingerprint()
            val ignored = savedStateHandle.get<String>(DRAFT_RESTORE_OFFER_FINGERPRINT_KEY) == fingerprint
            val shouldOffer = if (reconcile) bodyFieldState.text.toString() != body else {
                !ignored && (_state.value.canOfferDraftRestore(body, bodyFieldState.text.toString()) ||
                    canOfferPrivateSubject(draft))
            }
            if (shouldOffer) _state.update { it.copy(restorableDraft = body) }
            // A reconciled row equal to the field is this composition's own C, never an offered D.
            else if (reconcile) submission.drafts.resumeContent()
        }
    }

    private fun resumeSharedDraft(body: String) {
        // The body lives in the draft store, not SavedStateHandle. A new instance MUST reload it.
        if (sharedDraftResumed || submission.blocksMutation) return
        submission.drafts.resumeContent()
        val liveBody = bodyFieldState.text.toString()
        val combined = if (liveBody.isBlank() || liveBody == body) body else liveBody.trimEnd() + "\n\n" + body
        replaceBody(combined)
        previousBody = combined
        resetUndoBaseline()
        _state.update { it.copy(draftHydratedFromForm = true) }
        sharedDraftResumed = true
    }

    private fun canOfferPrivateSubject(draft: EditorDraftStore.Draft): Boolean =
        request.target is PostEditorTarget.PrivateMessage && draft.subject != null &&
            draft.subject != _state.value.subject

    private fun restoreSubject(draft: EditorDraftStore.Draft) {
        if (request.target !is PostEditorTarget.PrivateMessage || draft.subject == null) return
        _state.update { it.copy(subject = draft.subject.orEmpty(), subjectHydrated = true) }
        restoration.decide(EditorSessionField.Subject)
    }

    private fun restoreAttempt(draft: EditorDraftStore.Draft) {
        if (submission.blocksMutation) return
        restoreSubject(draft)
        val liveBody = bodyFieldState.text.toString()
        if (liveBody.isBlank()) {
            replaceBody(draft.body)
            previousBody = draft.body
            resetUndoBaseline()
        }
        updateChromeState {
            it.copy(
                draftHydratedFromForm = it.draftHydratedFromForm ||
                    request.mode == PostEditorMode.Reply || bodyFieldState.text.isNotBlank(),
                restorableDraft = null, submitError = EditorErrors.uncertain,
            )
        }
        // S2: a blank uncertain edit stays unknown until a successful form GET (#1574).
        if (_state.value.draftHydratedFromForm) restoration.decide(EditorSessionField.Body)
    }

    private fun PostEditorState.canOfferDraftRestore(cachedBody: String, liveBody: String): Boolean =
        liveBody != cachedBody && (liveBody.isBlank() || liveBody == draftHydratedContent)

    private fun restoreViewport(length: Int) {
        chromeMemory.focus.clearToolSelection()
        bodyFieldState.edit { selection = restoration.selection(length) }
    }

    /** Called after the hydrated field's layout settles; rotation retains the VM's live scroll. */
    suspend fun restoreBodyScroll() {
        restoration.scrollToRestore?.let { position ->
            val target = position.coerceAtMost(bodyScrollState.maxValue)
            bodyScrollState.scrollTo(target)
            if (bodyScrollState.value == target) restoration.scrollRestored()
        }
    }

    /** ED-81: capture the live field synchronously, before launching or awaiting any storage work. */
    private fun persistDraftNow() {
        if (!draftRestored || submission.blocksClose || submission.drafts.invalidated) return
        val draft = draftSnapshot() ?: return
        // This live revision is checkpointed now; its delayed mirror must not rearm autosave.
        previousBody = draft.body
        // Several stops/upload completions may queue behind the same in-flight CAS or autosave join.
        val version = maxOf(submission.drafts.nextContentVersion, lastCheckpointVersion + 1)
        lastCheckpointVersion = version
        pendingCheckpoints++
        stoppedBody = draft.body
        captureSessionCheckpoint(draft, version)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = submission.flush(::draftSnapshot)
                restoration.completed(draft, version, result)
            } finally {
                pendingCheckpoints--
                if (pendingCheckpoints == 0) lastCheckpointVersion = 0L
            }
        }
    }

    /** ED-170: CAS autosave clears blank content without deleting any newer generation or marker. */
    private fun scheduleAutosave() {
        if (!draftRestored) return
        if (screenStopped) {
            // A delayed mirror must not rewrite the just-flushed revision; upload inserts still persist.
            if (stoppedBody != bodyFieldState.text.toString()) persistDraftNow()
        } else {
            submission.autosave(::draftSnapshot)
        }
    }

    private fun checkpointScroll(): Int = restoration.scrollToRestore ?: bodyScrollState.value

    private fun captureCheckpoint(draft: EditorDraftStore.Draft, version: Long) {
        // A queued write may start after a later ON_STOP has already reserved its revision.
        if (version < lastCheckpointVersion) return
        if (bodyFieldState.text.toString() == draft.body && draftSubject() == draft.subject) {
            captureSessionCheckpoint(draft, version)
        }
    }

    private fun captureSessionCheckpoint(draft: EditorDraftStore.Draft, version: Long) {
        restoration.capture(
            EditorSessionCheckpoint(draft, version, bodyFieldState.selection, checkpointScroll()),
            bodyKnown = !_state.value.isAwaitingInitialBody,
        )
    }

    // Preserve a restored subject while the form is unavailable; never checkpoint a served hidden subject.
    private fun draftSubject(snapshot: PostEditorState = _state.value): String? = snapshot.subject.takeIf {
        snapshot.target is PostEditorTarget.PrivateMessage && snapshot.subjectHydrated &&
            servedSubjectEditable != false
    }

    private fun draftSnapshot(): EditorDraftStore.Draft? {
        val snapshot = _state.value
        if (deliveredInlineQuoteRequest != null) return null
        val liveBody = bodyFieldState.text.toString()
        // ED-121: a blank placeholder cannot replace an undecided D; other writes protect D transactionally.
        val undecidedBlank = liveBody.isBlank() && snapshot.restorableDraft != null
        // #1574: only a known body can resume ED-81, including an intentional user clearing.
        return if (restoration.canCheckpoint(!snapshot.isAwaitingInitialBody, undecidedBlank)) {
            val provenance = submission.drafts.provenanceFor(liveBody)
            EditorDraftStore.Draft(body = liveBody, subject = draftSubject(), bodyProvenance = provenance,
                offerToResolve = pendingOfferResolution())
        } else null
    }

    /**
     * ED-121: the D merged into the field by a Restore whose write has not succeeded yet. Every later
     * write of C (autosave, quote delivery, send) carries it, so a merged D never stays offered.
     */
    private fun pendingOfferResolution(): EditorDraftStore.DraftOffer? {
        val restoring = restoredOfferAwaitingWrite ?: mergedDraftOffer
        return selectedDraftOffer?.takeIf { restoring != null } ?: restoring?.let(submission.drafts::offered)
    }

    /** The field as delivered with its quote: C, its provenance and any pending Restore (ED-121/132). */
    private fun deliveredDraft(): EditorDraftStore.Draft {
        val body = bodyFieldState.text.toString()
        return EditorDraftStore.Draft(body, bodyProvenance = submission.drafts.provenanceFor(body),
            offerToResolve = pendingOfferResolution())
    }

    /** #604 lot 4a — one-shot latch : a committed close is never re-emitted (gate #803). */
    private var closeRequested = false

    /** The informed choice of the unsaved-close dialog; leaving pops exactly once, without a write. */
    private fun onUnsavedCloseDecided(leave: Boolean) {
        if (!_state.value.showUnsavedCloseDialog) return
        _state.update { it.copy(showUnsavedCloseDialog = false) }
        if (leave && !closeRequested) {
            closeRequested = true
            viewModelScope.launch { _effects.send(PostEditorEffect.CloseCommitted) }
        }
    }

    /**
     * #604 lot 4a — dirty close : flush the pending debounce so the last keystrokes reach the
     * #405 row, THEN let the UI pop (CloseCommitted). Without this, a system back < 750 ms
     * after typing cancelled the debounce with the ViewModel and silently dropped the tail of
     * the draft. Mirrors the quick-reply escalation (save awaited before the effect).
     *
     * Three guards (gate #803, NO-GO findings ; ED-10) :
     * - INERT while a POST is in flight — popping would cancel the submit with the
     *   viewModelScope and leave the server state unknown with a repostable draft (the sheet
     *   blocks its dismiss the same way, gate #788) ; on failure `isSubmitting` drops and the
     *   back works again, on success SubmitSucceeded pops anyway ;
     * - ONE-SHOT — a second back racing the first CloseCommitted must not emit a second
     *   effect (each `onClose` pops blindly : the second pop would remove the screen BELOW) ;
     * - NEVER SILENT — when the field cannot be saved the editor stays open and asks. The latch
     *   is released so a later back retries the save ; only "Quitter sans enregistrer" abandons.
     */
    private fun onCloseRequested() {
        if (submission.blocksClose || closeRequested) return
        if (_state.value.showSubmitConfirmation) {
            submission.disarm()
            return
        }
        closeRequested = true
        viewModelScope.launch {
            if (saveForClose()) {
                _effects.send(PostEditorEffect.CloseCommitted)
            } else {
                closeRequested = false
                _state.update { it.copy(showUnsavedCloseDialog = true) }
            }
        }
    }

    /**
     * True when closing abandons nothing. A storage failure never leaves the screen unclosable (the
     * dialog offers the way out) and never closes it silently either. Cancellation propagates.
     */
    private suspend fun saveForClose(): Boolean = try {
        // ED-132: a delivered quote whose C/Q transaction is pending or failed seals the autosave.
        // Settle it first, then save the field as it is now, typing made since included.
        if (deliveredInlineQuoteRequest != null) commitInlineQuoteDelivery()
        // Still not durable: nothing typed since the delivery can be saved without risking a
        // duplicate quote. Otherwise the last write of the field must itself succeed.
        deliveredInlineQuoteRequest == null && submission.flushForClose(
            live = { EditorDraftStore.Draft(body = bodyFieldState.text.toString(), subject = draftSubject()) },
            snapshot = ::draftSnapshot,
        )
    } catch (_: IOException) {
        false
    }

    @Suppress("CyclomaticComplexMethod") // MVI when-dispatch over the PostEditorIntent variants ; flat by design.
    fun submit(intent: PostEditorIntent) {
        // Unknown seed must refuse restoration before disarming an existing confirmation.
        val restoring = intent == PostEditorIntent.DraftRestoreRequested ||
            intent is PostEditorIntent.DraftOfferRestored
        if (restoring && !_state.value.canRestoreDraft) return
        if (intent.mutatesEditor && !acceptMutation(blockDuringUpload = intent.mutatesBody)) return
        when (intent) {
            PostEditorIntent.Started -> { screenStopped = false }
            PostEditorIntent.Stopped -> {
                screenStopped = true
                persistDraftNow()
            }
            is PostEditorIntent.ToolbarActionClicked -> onToolbarActionClicked(intent.action)
            PostEditorIntent.TogglePreview -> onTogglePreview()
            is PostEditorIntent.SubmitClicked -> onSubmitClicked(intent)
            is PostEditorIntent.SubmitConfirmed -> onSubmitConfirmed(intent)
            PostEditorIntent.SubmitConfirmationDismissed -> submission.disarm()
            PostEditorIntent.ErrorDismissed -> {
                if (!_state.value.hasDormantQuote) submission.acknowledge {
                    scheduleAutosave()
                    if (loadedForm == null && request.mode == PostEditorMode.Reply) {
                        loadReplyFormIfPossible(preserveDraft = true)
                    }
                }
            }
            PostEditorIntent.DraftWriteFailureDismissed -> submission.acknowledgeLocal()
            PostEditorIntent.ReloadRequested -> reloadForm()
            PostEditorIntent.PendingQuoteIgnored -> ignorePendingQuote()
            PostEditorIntent.PendingQuoteResumed -> resumeDormantQuote()
            PostEditorIntent.LegacyQuoteNoticeDismissed -> _state.update { it.copy(legacyQuoteRequestDropped = false) }
            is PostEditorIntent.SubjectChanged -> onSubjectChanged(intent.value)
            is PostEditorIntent.ToggleSignature ->
                _state.update { it.copy(signatureEnabled = intent.enabled) }
            is PostEditorIntent.ToggleSmileyDisabled ->
                _state.update { it.copy(smileyDisabled = intent.disabled) }
            is PostEditorIntent.ToggleEmailNotification ->
                _state.update { it.copy(emailNotificationEnabled = intent.enabled) }
            is PostEditorIntent.MsgIconSelected ->
                intent.n.takeIf { it in EDITOR_MSG_ICONS }?.let { selected ->
                    _state.update { it.copy(msgIcon = selected) }
                }
            is PostEditorIntent.SmileySelected -> onSmileySelected(intent.token)
            is PostEditorIntent.ImageUrlInserted -> onImageUrlInserted(intent.url)
            is PostEditorIntent.ImagePicked -> onImagePicked(intent.uri)
            is PostEditorIntent.ImagesPicked -> onImagesPicked(intent.uris)
            is PostEditorIntent.ImagePickerEventReceived -> onImagePickerEvent(intent.event)
            PostEditorIntent.UploadErrorDismissed -> _state.update { it.copy(uploadError = null) }
            PostEditorIntent.DraftRestoreRequested -> onDraftRestoreRequested()
            is PostEditorIntent.DraftOfferRestored -> onDraftRestoreRequested(intent.id)
            is PostEditorIntent.DraftOfferDiscarded -> onDraftDiscardRequested(intent.id)
            PostEditorIntent.DraftDiscardRequested -> onDraftDiscardRequested()
            is PostEditorIntent.QuoteRemoved -> onQuoteRemoved(intent.numreponse, intent.quote)
            is PostEditorIntent.QuoteMoved -> onQuoteMoved(intent.numreponse, intent.delta, intent.quote)
            PostEditorIntent.QuotesCleared -> _state.update { it.copy(quotes = emptyList()) }
            PostEditorIntent.CloseRequested -> onCloseRequested()
            PostEditorIntent.UnsavedCloseDismissed -> onUnsavedCloseDecided(leave = false)
            PostEditorIntent.UnsavedCloseConfirmed -> onUnsavedCloseDecided(leave = true)
        }
    }

    private fun onSubjectChanged(value: String) {
        if (!_state.value.isSubjectEditable || _state.value.isLoadingForm) return
        restoration.decide(EditorSessionField.Subject)
        _state.update { it.copy(subject = value.take(70), subjectHydrated = true) }
        scheduleAutosave()
    }

    /** An exact snapshot distinguishes two portions of one post; legacy callers use numreponse. */
    private fun onQuoteRemoved(numreponse: Int, quote: QuoteSelection?) {
        _state.update { current ->
            val index = current.quotes.indexOfFirst { if (quote == null) it.numreponse == numreponse else it == quote }
            current.copy(quotes = current.quotes.filterIndexed { position, _ -> position != index })
        }
    }

    /** #604 lot 3 — move the card one slot up ([delta] = -1) or down (+1) ; out-of-range = no-op. */
    private fun onQuoteMoved(numreponse: Int, delta: Int, quote: QuoteSelection?) {
        _state.update { current ->
            val index = current.quotes.indexOfFirst { if (quote == null) it.numreponse == numreponse else it == quote }
            val target = index + delta
            if (index < 0 || target < 0 || target > current.quotes.lastIndex) return@update current
            val reordered = current.quotes.toMutableList().apply {
                add(target, removeAt(index))
            }
            current.copy(quotes = reordered)
        }
    }

    /**
     * ED-89: one undoable merge. A reply gets D, the delivered quotes, then the typing; an edit seed
     * keeps its rule (replace untouched, keep the typed suffix, else append D). Q never waits for it.
     */
    @Suppress("CyclomaticComplexMethod") // Selected offer, ED-89 merge and the durable, retryable resolution.
    private fun onDraftRestoreRequested(offerId: String? = null) {
        val snapshot = _state.value
        if (!snapshot.canRestoreDraft || snapshot.isUploading || draftRestoreDeciding) return
        val selected = if (offerId == null) snapshot.draftOffers.firstOrNull()
            else snapshot.draftOffers.firstOrNull { it.id == offerId }
        val legacy = snapshot.restorableDraft.takeIf { offerId == null && snapshot.draftOffers.isEmpty() }
        val offeredBody = (selected?.draft?.body ?: legacy)?.takeIf {
            (it.isNotBlank() || request.target is PostEditorTarget.PrivateMessage &&
                !selected?.draft?.subject.isNullOrBlank()) &&
                submission.drafts.canDecideOffer(selected, pendingOfferResolution())
        } ?: return
        selectedDraftOffer = selected.takeIf { request.mode == PostEditorMode.Reply }
        val seed = snapshot.draftRestoreSeed.orEmpty()
        val liveBody = bodyFieldState.text.toString()
        val merge = mergeEditorBody(offeredBody, liveBody, seed,
            submission.drafts.provenanceFor(liveBody))
        draftRestoreDeciding = true
        draftDecisionCompleted = CompletableDeferred()
        // A failed write reoffers the decision, but C already contains the restored D. Retrying
        // persists the live field (including newer typing), without replaying the ED-89 merge.
        val needsMerge = mergedDraftOffer != offeredBody
        mergedDraftOffer = offeredBody
        restoredOfferAwaitingWrite = offeredBody
        if (needsMerge) {
            replaceBody(merge.body)
            // An untouched server subject is still a restore seed. Without a form, retain the
            // offered subject too: consuming the offer must not replace it with null on disk.
            // A blank offered subject never replaces anything (same rule as the Topic first post).
            if (snapshot.subject.isBlank() || snapshot.subject == loadedForm?.sujet) {
                selected?.draft?.takeUnless { it.subject.isNullOrBlank() }?.let(::restoreSubject)
            }
        }
        if (needsMerge) submission.drafts.stageBodyMerge(merge)
        updateChromeState { it.copy(restorableDraft = null, draftHydratedFromForm = true) }
        viewModelScope.launch {
            try {
                submission.flush(null)
                val draft = draftSnapshot()
                val offer = selectedDraftOffer ?: submission.drafts.offered(offeredBody)
                val result = if (offer != null && draft != null) {
                    submission.persist(draft.copy(offerToResolve = offer))
                } else submission.persist(draft)
                submission.resolveDraftWrite(result)
                val durable = result is EditorDraftStore.StoreWriteResult.Written && !submission.drafts.invalidated
                if (!durable) {
                    if (!submission.drafts.invalidated) updateChromeState { it.copy(restorableDraft = offeredBody) }
                    return@launch
                }
                restoredOfferAwaitingWrite = null
                mergedDraftOffer = null
                updateChromeState { it.copy(draftOffers = emptyList()) }
                refreshDraftOffers()
            } finally {
                restoredOfferAwaitingWrite = null
                draftRestoreDeciding = false
                draftDecisionCompleted?.complete(Unit)
                draftDecisionCompleted = null
                scheduleAutosave()
            }
            servePendingInlineQuoteIfEligible()
        }
    }

    /** Discard only the offered body; retain current typing and the session-owned request. */
    private fun onDraftDiscardRequested(offerId: String? = null) {
        if (draftRestoreDeciding || deliveredInlineQuoteRequest != null) return
        val snapshot = _state.value
        val selected = if (offerId == null) snapshot.draftOffers.firstOrNull()
            else snapshot.draftOffers.firstOrNull { it.id == offerId }
        val legacy = snapshot.restorableDraft.takeIf { offerId == null && snapshot.draftOffers.isEmpty() }
        val body = (selected?.draft?.body ?: legacy)?.takeIf {
            submission.drafts.canDecideOffer(selected, pendingOfferResolution())
        } ?: return
        draftRestoreDeciding = true
        draftDecisionCompleted = CompletableDeferred()
        updateChromeState { it.copy(restorableDraft = null) }
        viewModelScope.launch {
            try {
                submission.flush(null)
                val draft = EditorDraftStore.Draft(body = bodyFieldState.text.toString(), subject = draftSubject())
                val offer = selected.takeIf { request.mode == PostEditorMode.Reply } ?: submission.drafts.offered(body)
                val result = if (offer != null) {
                    submission.drafts.resolveOffer(offer, draft)
                } else submission.persist(draft)
                submission.resolveDraftWrite(result)
                if (result !is EditorDraftStore.StoreWriteResult.Written || submission.drafts.invalidated) {
                    if (!submission.drafts.invalidated) updateChromeState { it.copy(restorableDraft = body) }
                    return@launch
                }
                markDraftRestoreOfferIgnored(body)
                restoredOfferAwaitingWrite = null
                mergedDraftOffer = null
                updateChromeState { it.copy(draftOffers = emptyList()) }
                refreshDraftOffers()
            } finally {
                draftRestoreDeciding = false
                draftDecisionCompleted?.complete(Unit)
                draftDecisionCompleted = null
            }
            // Finish the discard before any insertion/autosave can repopulate the row.
            servePendingInlineQuoteIfEligible()
        }
    }

    /**
     * One idempotent delivery entry point. ED-132: an undecided offer never defers it; a decision
     * being written or an upload keeps the request until it ends. Consume it before starting work:
     * failures need an explicit Reload, never an automatic retry on an unrelated completion.
     */
    @Suppress("ReturnCount") // Independent lifecycle guards retain one pending delivery, without retrying failures.
    private fun servePendingInlineQuoteIfEligible(
        requestDelivery: Boolean = false,
    ) {
        if (requestDelivery) inlineQuoteDeliveryRequested = true
        val snapshot = _state.value
        if (!inlineQuoteDeliveryRequested || inlineQuoteLoadBlocked(insertInline = true)) return
        if (draftRestoreDeciding) return
        // An explicit Reload may fetch during upload; insertion and CAS still wait for its end.
        if (snapshot.isUploading && (!requestDelivery || deliveredInlineQuoteRequest != null)) return
        inlineQuoteDeliveryRequested = false
        loadReplyFormWithInlineQuotes(snapshot.pendingQuotes)
    }

    private fun markDraftRestoreOfferIgnored(body: String) {
        savedStateHandle[DRAFT_RESTORE_OFFER_FINGERPRINT_KEY] =
            EditorDraftStore.Draft(body = body).restoreOfferFingerprint()
    }

    /** Hydration/restore and its reveal baseline must be observed atomically, including equal bodies. */
    private fun replaceBody(body: String) {
        chromeMemory.focus.clearToolSelection()
        Snapshot.withMutableSnapshot {
            bodyCaretRevealResetKey++
            bodyFieldState.edit {
                replace(0, length, body)
                selection = TextRange(length)
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun resetUndoBaseline() {
        if (!bodyEditedBeforeHydration) bodyFieldState.undoState.clearHistory()
    }

    private fun onSmileySelected(token: String) {
        if (_state.value.isAwaitingInitialBody) return
        bodyEditedBeforeHydration = true
        chromeMemory.focus.clearToolSelection()
        bodyFieldState.editBbcode { text, start, end -> insertBbcodeToken(token, text, start, end) }
        smileyPicker.dismiss(SmileyPickerCloseCause.INSERTION)
        scheduleAutosave()
    }

    /**
     * #189 / #459 — a user-pasted image URL. The BBCode is shaped by the [EditorImageInsert]
     * preference ([imageInsertBbcodeOrNull] validates the http(s) scheme and applies full / linked /
     * reduced — a pasted URL has no reduced variant, so REDUCED degrades to LINKED). Reads the cached
     * [imageInsertMode] synchronously so the draft mutates in the same frame as the user action
     * (no suspend between the intent and the caret insertion — Codex PR2 review).
     */
    private fun onImageUrlInserted(url: String) {
        if (_state.value.isAwaitingInitialBody) return
        val bbcode = imageInsertBbcodeOrNull(fullUrl = url, mode = imageInsertMode) ?: return
        insertImageBbcodeAtCaret(bbcode, leadingNewline = false)
    }

    /**
     * #459 — inserts an already-built image BBCode fragment at the caret. Same cursor contract as the
     * smiley path ([insertBbcodeToken] preserves the selection, the preview is refreshed), WITHOUT
     * surrounding spaces (an image is self-contained). [leadingNewline] prefixes a newline when the
     * caret is not already at a line start, so consecutive uploads land on their own lines instead of
     * running together.
     */
    private fun insertImageBbcodeAtCaret(bbcode: String, leadingNewline: Boolean) {
        // Only validated upload callbacks reach this path during isUploading.
        if (_state.value.isAwaitingInitialBody || !acceptMutation()) return
        bodyEditedBeforeHydration = true
        chromeMemory.focus.clearToolSelection()
        bodyFieldState.editBbcode { text, start, end ->
            val caret = start.coerceIn(0, text.length)
            val needsNewline = leadingNewline && caret > 0 && text[caret - 1] != '\n'
            insertBbcodeToken(
                token = if (needsNewline) "\n$bbcode" else bbcode,
                text = text, selectionStart = start, selectionEnd = end, surroundWithSpaces = false,
            )
        }
        persistDraftNow()
    }

    /**
     * #459 PR2 — pick→read→upload→insert. Reads the picked Uri's bytes (off-platform via
     * [ImageUploadReader]), uploads to the host of the current preference scoped to the active
     * [activeUserId] (lowercased pseudo), and on success inserts `[img]imageUrl[/img]` at the caret
     * via [insertImageBbcodeAtCaret] (same cursor contract as the smiley / URL paths). A typed
     * [UploadException] is mapped onto [EditorError] ; an anonymous client (no userId) is ignored —
     * the providers require an HFR session for the trace, and surfacing « connectez-vous » here
     * would duplicate the submit-time LoginRequired surface.
     */
    private fun onImagePicked(uri: String) = onImagesPicked(listOf(uri))

    /** #1573 — cancellation only records diagnostics; a successful empty callback retains #1128 feedback. */
    private fun onImagePickerEvent(event: ImagePickerEvent) {
        if (submission.blocksMutation) return
        diagnostics.recordImagePickerEvent(event)
        when (event) {
            is ImagePickerEvent.Launched, is ImagePickerEvent.Cancelled -> Unit
            is ImagePickerEvent.Unavailable -> {
                chromeEvents.pickerProblem(EditorErrors.pickerAbsent)
                _state.update { it.copy(uploadError = EditorErrors.pickerAbsent) }
            }
            is ImagePickerEvent.Result -> {
                val pickedImages = pickedImagesForUpload(event.contract, event.uris)
                val showEmptyPickerBanner = pickedImages.isEmpty() && uploadJob?.isActive != true
                onImagesPicked(event.uris)
                if (showEmptyPickerBanner) {
                    chromeEvents.pickerProblem(EditorErrors.noImageReceived)
                    _state.update { it.copy(uploadError = EditorErrors.noImageReceived) }
                }
            }
        }
    }

    /**
     * Multi-image upload — uploads the picked [uris] sequentially (one in-flight at a time, same
     * job/gate as the single path) and inserts `[img]url[/img]` at the caret for each success, in
     * pick order (each insertion advances the caret so the next lands after it). The batch stops at
     * the first failure: the already-inserted images stay and the typed [EditorError] is surfaced —
     * the user fixes the cause and re-picks the rest. A blank list, an anonymous client (no userId),
     * or an upload already in flight are ignored. [PostEditorState.uploadProgress] carries an « n/N »
     * counter while more than one image is in the batch (null for a single image).
     */
    private fun canStartUpload(userId: String?, targets: List<String>): Boolean {
        if (_state.value.isAwaitingInitialBody) return false
        return !submission.blocksMutation && uploadJob?.isActive != true && userId != null && targets.isNotEmpty()
    }

    private fun canApplyUpload(userId: String?): Boolean =
        !_state.value.isAwaitingInitialBody && !submission.blocksMutation && activeUserId == userId

    private fun onImagesPicked(uris: List<String>) {
        diagnostics.recordImagesPicked(uris.size)
        val userId = activeUserId
        val received = uris.filter { it.isNotBlank() }
        val targets = received.take(MAX_IMAGES_PER_UPLOAD)
        // One guard (ReturnCount): nothing in flight already, an authenticated owner, a non-empty pick.
        if (!canStartUpload(userId, targets)) return
        val multiple = targets.size > 1
        updateChromeState {
            it.copy(
                isUploading = true,
                uploadError = if (received.size > MAX_IMAGES_PER_UPLOAD) EditorErrors.imageLimit else null,
                uploadProgress = if (multiple) UploadProgress(completed = 0, total = targets.size) else null,
            )
        }
        uploadJob = viewModelScope.launch {
            // Snapshot the cached preference once at batch start so all N inserts use a consistent
            // mode even if the user flips the setting mid-upload. Reading the cached field (not the
            // flow) keeps the upload free of an extra suspend point.
            val mode = imageInsertMode
            var completed = 0
            for (uri in targets) {
                val outcome = runCatching {
                    val image = imageUploadReader.read(uri)
                    uploadRepository.uploadWithCurrentProvider(image, checkNotNull(userId))
                }
                val uploaded = outcome.getOrElse { error ->
                    handleUploadFailure(error)
                    return@launch
                }
                // BBCode shaped by the preference (full / linked / reduced) ; the reduced URL is the
                // host's smaller variant when it exposes one, else the full URL.
                val bbcode = imageInsertBbcodeOrNull(
                    fullUrl = uploaded.imageUrl,
                    displayUrl = uploaded.resizedUrl ?: uploaded.imageUrl,
                    mode = mode,
                )
                if (!canApplyUpload(userId)) break
                if (bbcode != null) {
                    // Each image after the first lands on its own line (no more run-together uploads).
                    insertImageBbcodeAtCaret(bbcode, leadingNewline = completed > 0)
                }
                completed += 1
                if (multiple) {
                    updateChromeState { it.copy(uploadProgress = UploadProgress(completed, targets.size)) }
                }
            }
            updateChromeState { it.copy(isUploading = false, uploadProgress = null) }
            servePendingInlineQuoteIfEligible()
        }
    }

    private fun handleUploadFailure(error: Throwable) {
        if (error is CancellationException) {
            updateChromeState { it.copy(isUploading = false, uploadProgress = null) }
            throw error
        }
        val mapped = EditorErrors.stopped(error.toEditorUploadError(), _state.value.uploadProgress)
        diagnostics.record(
            DiagnosticsLog.Level.WARN,
            LOG_TAG_VM,
            UploadFailureDiagnostics.describe(error, mapped.toString()),
        )
        updateChromeState { it.copy(isUploading = false, uploadError = mapped, uploadProgress = null) }
        servePendingInlineQuoteIfEligible()
    }

    private fun onToolbarActionClicked(action: BbcodeAction) {
        if (_state.value.isAwaitingInitialBody) return
        bodyEditedBeforeHydration = true
        chromeMemory.focus.clearToolSelection()
        bodyFieldState.editBbcode { text, start, end -> applyBbcodeAction(action, text, start, end) }
        if (action == BbcodeAction.Image) persistDraftNow() else scheduleAutosave()
    }

    private fun onTogglePreview() {
        _state.update {
            it.copy(isPreviewVisible = !it.isPreviewVisible, preview = PostContent(blocks = emptyList()))
        }
    }

    private fun loadReplyFormIfPossible(preserveDraft: Boolean = false) {
        val context = buildReplyContext() ?: run {
            updateChromeState { it.copy(submitError = EditorErrors.missingSubcat) }
            return
        }
        // #604 lot 3 (mockup P3) — with cards ON (or no citations at all) the open-time fetch is
        // the PLAIN reply form : citations live as cards in [PostEditorState.quotes] and their
        // [quotemsg] blocks are materialised fresh at submit (see [onSubmitClicked]), exactly
        // like the quick-reply sheet. This fetch only warms the hash and hydrates the per-post
        // options. Inline requests also use this ordinary GET while their draft offer waits (#1450).
        launchFormFetch(preserveDraft = preserveDraft) {
            val form = replyRepository.fetchReplyForm(context)
            if (preserveDraft) form.copy(initialContent = "") else form
        }
    }

    private fun loadEditFormIfPossible() {
        when (request.target) {
            PostEditorTarget.Public -> {
                val context = buildEditPostContext() ?: return missingEditContext()
                launchFormFetch { editPostRepository.fetchEditPostForm(context) }
            }
            is PostEditorTarget.PrivateMessage -> {
                val context = buildPrivateEditContext() ?: return missingEditContext()
                launchFormFetch { editPostRepository.fetchEditPostForm(context) }
            }
        }
    }

    private fun missingEditContext() {
        updateChromeState { it.copy(submitError = EditorErrors.missingSubcat, draftRestoreSeed = "") }
    }

    /** Form hydration and metadata are separate from the single asynchronous preview pipeline. */
    private fun launchFormFetch(preserveDraft: Boolean = false, fetch: suspend () -> ReplyForm) {
        val opening = submission.session
        val favoriteStamp = smileyPicker.favoriteFormStamp()
        updateChromeState { it.copy(isLoadingForm = true, isFetchingQuote = false) }
        viewModelScope.launch {
            val outcome = runCatching { fetch() }
            if (submission.session != opening) return@launch
            outcome.fold(
                onSuccess = { form ->
                    loadedForm = form
                    smileyPicker.refreshFavorites(favoriteStamp)
                    applyFormHydration(form, preserveDraft)
                },
                onFailure = { error -> handleFetchFailure(error) },
            )
            // A draft decision can finish during either a successful or a failed ordinary GET.
            servePendingInlineQuoteIfEligible()
        }
    }

    private fun applyFormHydration(form: ReplyForm, preserveDraft: Boolean = false) {
        val snapshot = _state.value
        val hydrate = !preserveDraft && !submission.blocksMutation && snapshot.shouldHydrateBody(form)
        if (hydrate) {
            replaceBody(form.initialContent)
            previousBody = form.initialContent
            resetUndoBaseline()
        }
        val liveBody = bodyFieldState.text.toString()
        updateChromeState {
            val hydrated = it.withFormHydration(form, hydrate, liveBody)
            if (!preserveDraft) hydrated else {
                hydrated.copy(restorableDraft = it.restorableDraft, draftOffers = it.draftOffers)
            }
        }
    }

    /** An empty edit textarea is a known server seed; a missing textarea is rejected by the parser. */
    private fun PostEditorState.shouldHydrateBody(form: ReplyForm): Boolean =
        !draftHydratedFromForm && restoration.canHydrate(EditorSessionField.Body, bodyFieldState.text.isBlank()) &&
            (mode == PostEditorMode.Edit || form.initialContent.isNotBlank())

    /** Pure metadata reduction; refetches preserve the live field and the user's option choices. */
    private fun PostEditorState.withFormHydration(
        form: ReplyForm,
        hydrate: Boolean,
        liveBody: String,
    ): PostEditorState {
        if (this@PostEditorViewModel.submission.blocksMutation) return copy(isLoadingForm = false)
        val hydrateOptions = !optionsHydratedFromForm
        val (visibleOffers, firstOffer) = visibleFormOffers(form, liveBody)
        return copy(
            isLoadingForm = false,
            draftOffers = visibleOffers,
            restorableDraft = firstOffer,
            draftHydratedFromForm = draftHydratedFromForm || hydrate,
            draftHydratedContent = if (hydrate) liveBody else draftHydratedContent,
            draftRestoreSeed = if (mode == PostEditorMode.Edit && hydrate) liveBody else draftRestoreSeed.orEmpty(),
            signatureEnabled = if (hydrateOptions) form.options.signatureEnabled else signatureEnabled,
            smileyDisabled = if (hydrateOptions) form.options.smileyDisabled else smileyDisabled,
            emailNotificationEnabled = if (hydrateOptions) {
                form.options.emailNotificationEnabled
            } else {
                emailNotificationEnabled
            },
            hasMessageTone = target !is PostEditorTarget.PrivateMessage || form.msgIcon != null,
            msgIcon = if (hydrateOptions) form.msgIcon.toEditorMsgIcon() else msgIcon,
            optionsHydratedFromForm = true,
            // Phase 2F-B (#11) — keep the parsed userId around for the wiki smiley search.
            // Anti-clobber : do not overwrite once set, so a silent `InvalidHashCheck`
            // refetch on an anonymous fallback does not erase a previously known id.
            userId = userId ?: form.userId,
            submitError = submitError.afterFormLoaded() ?: EditorErrors.loginRequired.takeIf { form.isAnonymous },
        ).withSubjectHydration(form)
    }

    private fun PostEditorState.withSubjectHydration(form: ReplyForm): PostEditorState {
        if (target !is PostEditorTarget.PrivateMessage) return this
        return copy(isSubjectEditable = form.isSubjectEditable,
            subject = if (subjectHydrated) subject else form.sujet, subjectHydrated = true)
    }

    private fun PostEditorState.visibleFormOffers(
        form: ReplyForm,
        liveBody: String,
    ): Pair<List<EditorDraftStore.DraftOffer>, String?> {
        if (target == PostEditorTarget.Public) return narrowDraftOffers(draftOffers, restorableDraft, liveBody)
        val nextSubject = if (subjectHydrated) subject else form.sujet
        val visible = draftOffers.filterNot {
            it.draft.body == liveBody && (it.draft.subject == null || it.draft.subject == nextSubject)
        }
        return visible to if (restorableDraft != null) visible.firstOrNull()?.draft?.body else null
    }

    private fun handleFetchFailure(error: Throwable, quoteFetch: Boolean = false) {
        chromeEvents.readFailed()
        if (error is CancellationException) {
            // Symmetry with `handleSubmitFailure` : reset the in-flight flag before the throw so
            // a parent ViewModel that survives the cancellation (or any state observer reading
            // the snapshot before death) does not see `isLoadingForm = true` forever.
            updateChromeState { it.copy(isLoadingForm = false) }
            throw error
        }
        if (quoteFetch && _state.value.pendingQuotes.isNotEmpty()) {
            val failures = (_state.value.quoteFetchFailures + 1).coerceAtMost(2)
            savedStateHandle[QUOTE_FAILURES_KEY] = failures
            _state.update { it.copy(quoteFetchFailures = failures) }
        }
        // Map known transport-level failures to a typed EditorError ; classify the
        // rest as Unknown rather than letting the exception bubble up and crash the
        // process. The UI surfaces the same "unexpected response" message either way.
        val mapped = when (error) {
            is SessionExpiredException -> EditorErrors.sessionExpired
            else -> when {
                quoteFetch ->
                    EditorErrors.quoteFetchFailed(inline = _state.value.pendingQuotes.isNotEmpty())
                error is IOException -> EditorErrors.formNetwork
                else -> EditorErrors.invalidForm
            }
        }
        diagnostics.record(
            DiagnosticsLog.Level.WARN,
            LOG_TAG_VM,
            "fetch bubbled: ${error::class.simpleName} " +
                "→ ${mapped::class.simpleName}",
        )
        updateChromeState {
            it.copy(
                isLoadingForm = false,
                draftRestoreSeed = it.draftRestoreSeed.orEmpty(),
                submitError = if (it.submitError == EditorErrors.uncertain) it.submitError else mapped,
            )
        }
    }

    /** One live capture drives validation, eligibility, persistence and the request payload. */
    private fun submissionSnapshot(): PostEditorState {
        val body = bodyFieldState.text.toString()
        return _state.value.copy(bodyText = body, validation = validateBbcodeDraft(body))
    }

    /** ED-164/166: confirmation carries the exact arm identity and never bypasses the controller. */
    private fun onSubmitConfirmed(intent: PostEditorIntent.SubmitConfirmed) {
        val snapshot = submissionSnapshot()
        if (!snapshot.canSubmit || closeRequested) return
        val form = loadedForm?.takeUnless { it.isAnonymous } ?: return
        val permit = submission.confirm(intent.token)
        if (permit is SubmissionRequest.Accepted) launchSubmission(permit, snapshot, form)
    }

    private fun onSubmitClicked(intent: PostEditorIntent.SubmitClicked) {
        val snapshot = submissionSnapshot()
        val form = loadedForm
        if (!snapshot.canSubmit || form == null || closeRequested) return
        if (form.isAnonymous) {
            updateChromeState { it.copy(submitError = null) }
            updateChromeState { it.copy(submitError = ReplyFailureReason.LoginRequired.toEditorError()) }
        } else {
            val permit = submission.request(intent.timeoutMillis, snapshot.canSubmit && !closeRequested)
            if (permit is SubmissionRequest.Accepted) launchSubmission(permit, snapshot, form)
        }
    }

    /** ED-169/170: every field used below belongs to this immutable, durably awaited snapshot. */
    private fun launchSubmission(
        permit: SubmissionRequest.Accepted,
        snapshot: PostEditorState,
        form: ReplyForm,
    ) {
        val draft = EditorDraftStore.Draft(
            body = snapshot.bodyText, subject = draftSubject(snapshot), offerToResolve = pendingOfferResolution(),
        )
        submission.launch(permit, draft) {
            val options = ReplyFormOptions(
                signatureEnabled = snapshot.signatureEnabled,
                smileyDisabled = snapshot.smileyDisabled,
                emailNotificationEnabled = snapshot.emailNotificationEnabled,
            )
            val result = when (snapshot.mode) {
                PostEditorMode.Reply -> submitReply(
                    checkNotNull(buildReplyContext()), form, snapshot, options,
                )
                PostEditorMode.Edit -> submitEdit(form, snapshot, options)
            }
            submissionDelivery(snapshot, result)
        }
    }

    private suspend fun submitEdit(
        form: ReplyForm,
        snapshot: PostEditorState,
        options: ReplyFormOptions,
    ): ReplySubmitResult {
        val editedForm = form.copy(
            sujet = if (snapshot.isSubjectEditable) snapshot.subject else form.sujet,
            msgIcon = if (snapshot.hasMessageTone) snapshot.msgIcon.toString() else form.msgIcon,
        )
        return when (snapshot.target) {
            PostEditorTarget.Public -> editPostRepository.submitEditPost(
                checkNotNull(buildEditPostContext()), editedForm, snapshot.bodyText, options,
            )
            is PostEditorTarget.PrivateMessage -> editPostRepository.submitEditPost(
                checkNotNull(buildPrivateEditContext()), editedForm, snapshot.bodyText, options,
            )
        }
    }

    @Suppress("ReturnCount") // Reject unknown private coordinates before any network request.
    private fun buildPrivateEditContext(): PrivateMessageEditContext? {
        val target = request.target as? PostEditorTarget.PrivateMessage ?: return null
        val page = request.page?.takeIf { it > 0 } ?: return null
        val numreponse = request.numreponse?.takeIf { it > 0 } ?: return null
        return PrivateMessageEditContext(target.threadId, page, numreponse)
    }

    private fun submissionDelivery(
        snapshot: PostEditorState,
        result: ReplySubmitResult,
    ): EditorDelivery<PostEditorEffect> = when (result) {
        is ReplySubmitResult.Success -> EditorDelivery(
            effect = PostEditorEffect.SubmitSucceeded(
                targetPage = result.targetPage,
                scrollTo = result.numreponse ?: snapshot.numreponse.takeIf { snapshot.mode == PostEditorMode.Edit },
                quotedNumreponses = quotedNumreponsesFor(snapshot),
                operation = if (snapshot.mode == PostEditorMode.Edit) {
                    PostSubmitOperation.Edit
                } else {
                    PostSubmitOperation.Publish
                },
            ),
        )
        is ReplySubmitResult.Failure -> {
            EditorDelivery(
                outcome = if (result.reason == ReplyFailureReason.Unknown) {
                    EditorSendOutcome.UNCERTAIN
                } else {
                    EditorSendOutcome.CERTAIN_FAILURE
                },
                error = if (result.reason == ReplyFailureReason.Unknown) {
                    EditorErrors.uncertain
                } else {
                    result.reason.toEditorError()
                },
                afterSettled = if (result.reason == ReplyFailureReason.InvalidHashCheck) {
                    {
                        loadedForm = null
                        when (snapshot.mode) {
                            PostEditorMode.Reply -> loadReplyFormIfPossible()
                            PostEditorMode.Edit -> loadEditFormIfPossible()
                        }
                    }
                } else { null },
            )
        }
    }

    /**
     * #974 - Reply success carries quote landing hints from armed cards plus inline `[quotemsg]`
     * tags. Edit returns none: there are no quote cards there, and `scrollTo` owns the landing.
     */
    private fun quotedNumreponsesFor(snapshot: PostEditorState): List<Int> =
        if (snapshot.mode == PostEditorMode.Edit) {
            emptyList()
        } else {
            QuotedNumreponses.of(snapshot.bodyText, snapshot.quotes)
        }

    /**
     * #604 lot 3 (mockup P3) — the Reply POST, quotes-aware. Without cards the cached plain form
     * is submitted as before. With cards the [quotemsg] blocks are materialised FRESH here (never
     * the cached plain form : the quote form carries the prefills AND the hash the submit rides),
     * card order = citation order, the typed body follows the quotes ; a quotes-only reply keeps
     * no trailing blank lines (same pinned BBCode as the quick-reply sheet). A failure anywhere
     * leaves body AND cards untouched — the state only mutates on success. One divergence from
     * the sheet : [options] comes from the editor's user-editable toggles, not the form defaults.
     */
    private suspend fun submitReply(
        context: ReplyContext,
        form: ReplyForm,
        snapshot: PostEditorState,
        options: ReplyFormOptions,
    ): ReplySubmitResult {
        val quotes = snapshot.quotes
        if (quotes.isEmpty()) {
            return replyRepository.submitReply(
                context = context,
                form = form.copy(msgIcon = snapshot.msgIcon.toString()),
                bbcodeContent = snapshot.bodyText,
                options = options,
            )
        }
        val quoteContext = context.copy(
            quotedNumreponse = quotes.first().numreponse,
            quoteRef = null,
        )
        val quoteForm = fetchReplyQuoteForm(quoteContext, quotes)
        val body = snapshot.bodyText
        // Compare materialised content, not card excerpts: keep another portion of the same post.
        val prefills = filterInlineQuoteDelivery(
            checkNotNull(request.cat), checkNotNull(request.cat), quoteForm.initialContent.trimEnd(), body,
        ).bbcode
        val content = listOf(prefills, body).filter { it.isNotBlank() }.joinToString("\n\n")
        return replyRepository.submitReply(
            context = quoteContext,
            form = quoteForm.copy(msgIcon = snapshot.msgIcon.toString()),
            bbcodeContent = content,
            options = options,
        )
    }

    private suspend fun fetchReplyQuoteForm(context: ReplyContext, quotes: List<QuoteSelection>): ReplyForm {
        val reason = try {
            return quoteMaterializer.fetchFormWithQuotes(
                context = context,
                extraQuoteNumreponses = quotes.drop(1).map { it.numreponse },
                truncate = quotes.any { it.truncate },
            )
        } catch (_: SessionExpiredException) {
            EditorPreparationException.Reason.SESSION_EXPIRED
        } catch (_: IOException) {
            EditorPreparationException.Reason.QUOTE_FETCH
        } catch (_: IllegalStateException) {
            EditorPreparationException.Reason.QUOTE_FETCH
        }
        throw EditorPreparationException(reason)
    }

    @Suppress("ReturnCount") // Each guard returns null with a distinct reason
    private fun buildEditPostContext(): EditPostContext? {
        val snapshot = state.value
        if (snapshot.target != PostEditorTarget.Public) return null
        val page = snapshot.page ?: return null
        val subcat = snapshot.subcat ?: return null
        val topicId = snapshot.topicId ?: return null
        val numreponse = snapshot.numreponse ?: return null
        // #213 — `subcat = 0` is postable (cat without sub-category) ; only the
        // SUBCAT_UNKNOWN sentinel (-1) is blocking. Keep `> 0` for topicId / numreponse.
        if (subcat < 0 || topicId <= 0 || numreponse <= 0) return null
        return EditPostContext(
            cat = checkNotNull(snapshot.cat),
            subcat = subcat,
            topicId = topicId,
            page = page,
            numreponse = numreponse,
        )
    }

    @Suppress("ReturnCount") // Each guard returns null with a distinct reason
    private fun buildReplyContext(): ReplyContext? {
        val snapshot = state.value
        if (snapshot.target != PostEditorTarget.Public) return null
        val page = snapshot.page ?: return null
        val subcat = snapshot.subcat ?: return null
        val topicId = snapshot.topicId ?: return null
        // #213 — mirror the `ReplyContext.init` rule : reject only the SUBCAT_UNKNOWN
        // sentinel (-1). `subcat = 0` is postable (cat without sub-category, e.g. IA).
        if (subcat < 0) return null
        // #604 lot 3 — always the PLAIN context : citations are cards, and [submitReply]
        // derives the quote context (first card's numreponse) when materialising.
        return ReplyContext(
            cat = checkNotNull(snapshot.cat),
            subcat = subcat,
            topicId = topicId,
            page = page,
        )
    }

    /**
     * #459 PR2 — cancel any in-flight image upload when the editor is torn down. `viewModelScope`
     * is already cancelled by the lifecycle, so this is belt-and-braces (the read/upload is then a
     * no-op against a dead state) — but it makes the « one upload, no leak » contract explicit and
     * lets a future non-viewModelScope job stay covered.
     */
    override fun onCleared() {
        uploadJob?.cancel()
        super.onCleared()
    }

    @AssistedFactory
    interface Factory {
        fun create(request: PostEditorRequest): PostEditorViewModel
    }

    private companion object {
        const val QUOTE_FAILURES_KEY = "postEditor.quoteFailures"
        // Distinct from the repository's "ReplyRepository" tag so the diagnostics
        // panel makes it obvious which layer recorded an entry.
        private const val LOG_TAG_VM = "PostEditorVM"
        private const val DRAFT_RESTORE_OFFER_FINGERPRINT_KEY = "draft_restore_offer_fingerprint"
    }
}
