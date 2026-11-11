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
import androidx.compose.ui.text.input.TextFieldValue
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
import fr.forumhfr.redface2.core.domain.editor.EditorSendOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionErrors
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.SubmissionRequest
import fr.forumhfr.redface2.core.domain.editor.validateBbcodeDraft
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.smiley.HfrFavoriteSmileyRepository
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollections
import fr.forumhfr.redface2.core.domain.smiley.SmileyRepository
import fr.forumhfr.redface2.core.domain.upload.ImageUploadReader
import fr.forumhfr.redface2.core.domain.upload.UploadFailureDiagnostics
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import fr.forumhfr.redface2.core.domain.write.TopicFormRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.model.write.EditFirstPostContext
import fr.forumhfr.redface2.core.model.write.MissingFirstPostSubcategoryException
import fr.forumhfr.redface2.core.model.write.NewTopicContext
import fr.forumhfr.redface2.core.model.write.NewTopicSubmitResult
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.model.write.TopicForm
import fr.forumhfr.redface2.core.ui.editor.EditorPreviewState
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorSessionCheckpoint
import fr.forumhfr.redface2.core.ui.editor.EditorSessionField
import fr.forumhfr.redface2.core.ui.editor.EditorSessionRestoration
import fr.forumhfr.redface2.core.ui.editor.EditorSessionRestore
import fr.forumhfr.redface2.core.ui.editor.EditorSessionSubcategory
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.withContext

/**
 * ViewModel for the topic-level form. [TopicFormMode.EditFirstPost] edits an
 * existing first post (Phase 2D #148) and [TopicFormMode.New] creates a topic
 * (Phase 2E #149). Architecture mirrors [PostEditorViewModel] :
 *
 * 1. On init, fetch the topic form via [TopicFormRepository] using the
 *    mode-specific request shape.
 * 2. Hydrate `subject`, `draft`, the per-post options/message tone, and the parsed
 *    subcategory selection ONCE — subsequent silent refetches (e.g. after
 *    `InvalidHashCheck`) must never overwrite user edits.
 * 3. On [TopicFormIntent.SubmitClicked], POST via the matching repository method
 *    with the user's final values.
 * 4. On success, emit [TopicFormEffect.SubmitSucceeded] for edit FP or
 *    [TopicFormEffect.NewTopicCreated] for create-topic navigation.
 */
@HiltViewModel(assistedFactory = TopicFormViewModel.Factory::class)
// LongParameterList : Hilt ctor — one dependency per collaborator (#405 added draftStore).
// LargeClass : gère deux modes (newTopic + editFirstPost) ET le câblage des brouillons (#405) ;
// l'extraction d'un contrôleur de brouillon partagé est prévue avec la migration éditeur #441.
@Suppress("LongParameterList", "LargeClass")
class TopicFormViewModel @AssistedInject constructor(
    @Assisted private val request: TopicFormRequest,
    private val previewParser: BbcodePreviewParser,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val topicFormRepository: TopicFormRepository,
    private val smileyRepository: SmileyRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val draftStore: EditorDraftStore,
    private val diagnostics: DiagnosticsLog,
    // #459 — image upload wiring, same trio as PostEditorViewModel: auth scopes the upload to the
    // active HFR session, the reader turns a picked Uri into bytes, the repository posts to the
    // host of the current preference.
    private val authRepository: AuthRepository,
    private val uploadRepository: UploadRepository,
    private val imageUploadReader: ImageUploadReader,
    hfrFavoriteSmileyRepository: HfrFavoriteSmileyRepository,
    smileyLocalCollections: SmileyLocalCollections,
    clock: DiagnosticsClock,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    /** Live body and IME state belong to the ViewModel, outside the Bundle. */
    val bodyFieldState = TextFieldState()
    val bodyScrollState = ScrollState(0)
    /** ED-14: hydration/restoration resets reveal without taking ownership of ED-81 scroll. */
    var bodyCaretRevealResetKey by mutableIntStateOf(0)
        private set
    private val restoration = EditorSessionRestoration(savedStateHandle, when (request.mode) {
        TopicFormMode.New -> EditorSessionSurface.TopicCreation
        TopicFormMode.EditFirstPost -> EditorSessionSurface.TopicFirstPost
    })
    private var previousBody = bodyFieldState.text.toString()
    private var draftRestored = false
    private var screenStopped = false
    private var stoppedDraft: EditorDraftStore.Draft? = null
    private var pendingCheckpoints = 0
    private var lastCheckpointVersion = 0L
    private var bodyEditedBeforeHydration = false

    private val _state: MutableStateFlow<TopicFormState> = MutableStateFlow(
        TopicFormState(
            isInitializing = true,
            isRestoringSession = restoration.isRecreation,
            mode = request.mode,
            cat = request.cat,
            subcat = request.subcat,
            topicId = request.topicId,
            page = request.page,
            numreponse = request.numreponse,
            // New-topic has nothing useful to hydrate into `subject` / `draft`
            // from the server (the user is writing from scratch). Lock both
            // hydration flags to `true` from the start so a silent
            // `InvalidHashCheck` refetch can never clobber what the user
            // already typed. Edit FP keeps the default `false` until the
            // fetched body is known, even when it is blank.
            subjectHydratedFromServer = request.mode == TopicFormMode.New,
            draftHydratedFromServer = request.mode == TopicFormMode.New,
        ),
    )
    val state: StateFlow<TopicFormState> = _state.asStateFlow()

    internal val editorEvents = TopicFormEvents()
    // ED-63/C8: VM lifetime only; visibility remains in the existing preview pipeline.
    internal val previewLayout by lazy { EditorPreviewState(userPreferencesRepository, viewModelScope) }
    internal val chromeMemory = TopicFormChromeMemory()

    /** Deliver transitions before StateFlow can conflate repeated failures or synchronous operations. */
    private fun updateState(transform: (TopicFormState) -> TopicFormState) {
        val previous = _state.value
        _state.update(transform)
        editorEvents.open(submission.session)
        editorEvents.observe(previous, _state.value)
    }

    private val _effects: Channel<TopicFormEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<TopicFormEffect> = _effects.receiveAsFlow()

    private var loadedForm: TopicForm? = null
        set(value) {
            field = value
            updateState { it.copy(hasLoadedForm = value != null) }
            if (value == null) smileyPicker.refreshFavorites(null)
        }

    /**
     * #441 — smiley picker (Favorites + Standard + Wiki), delegated to the shared
     * [SmileyPickerController] exactly like the MP composers (#387/#440) and
     * `PostEditorViewModel` : the controller owns open/dismiss/query, the 300 ms debounce
     * and its race guards, and the #824 restore-search-on-reopen contract. This ViewModel
     * keeps only the insertion ([onSmileySelected] — a draft mutation, so it stays an MVI
     * intent) ; the mixed wiring is deliberate, do not re-route it for « consistency ».
     *
     * Lifecycle (réserve Codex #441) : the editorial context is frozen at construction
     * (`@Assisted request` ; one `TopicFormRoute` nav entry = one ViewModel), so the
     * controller can never be re-armed for another topic/form — no targeted reset needed
     * beyond the ViewModel's own death.
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
     * #405 — stable, content-free draft key. New uses the category key ; EditFirstPost needs the
     * numreponse of the first post. Null when the routing args can't identify a target.
     */
    private val draftKey: String? = when (request.mode) {
        TopicFormMode.New -> request.cat?.let { EditorDraftKey.newTopic(it) }
        // Require BOTH cat and numreponse : the edit key carries `cat` for global uniqueness
        // (numreponse is per-category), so falling back to cat=0 could collide. Null = no autosave,
        // mirroring the New/Reply branches when routing args can't identify a unique target.
        TopicFormMode.EditFirstPost -> request.cat?.let { cat ->
            request.numreponse?.let { EditorDraftKey.editFirstPost(cat, it) }
        }
    }

    /** #405 — debounced autosave coroutine ; relaunched (cancelling the previous) on each edit. */
    private val submission = EditorSubmissionBinding(
        environment = EditorSubmissionEnvironment(viewModelScope, clock, diagnostics),
        drafts = EditorDraftSession(draftStore, draftKey),
        errors = EditorSubmissionErrors(
            uncertain = EditorErrors.uncertain, draftWriteFailed = EditorErrors.draftWriteFailed,
            sessionExpired = EditorErrors.sessionExpired, unknown = EditorErrors.uncertain,
            network = EditorErrors.network, preparationFailed = EditorErrors.invalidForm,
            quoteFetchFailed = EditorErrors.quoteFetchFailed,
            draftSaveFailed = EditorErrors.draftSaveFailed,
        ),
        callbacks = EditorSubmissionCallbacks<TopicFormEffect>(
            state = { value ->
                updateState {
                    it.copy(
                        submission = value,
                        isSubmitting = value is EditorSubmissionState.PersistingAttempt ||
                            value is EditorSubmissionState.Sending,
                        showSubmitConfirmation = value is EditorSubmissionState.Armed,
                    )
                }
            },
            error = { value -> updateState { it.copy(submitError = value) } },
            success = { value -> _effects.trySend(value) },
            clearError = { error -> updateState { it.copy(submitError = it.submitError.takeUnless { it == error }) } },
            durableWriteSucceeded = { editorEvents.durableWriteSucceeded() },
        ),
    )

    /**
     * #459 — lowercased pseudo of the authenticated session, or null when anonymous. The upload
     * providers require an HFR session for the trace ; an anonymous pick is silently ignored
     * (the submit-time anonymous guard already surfaces the login requirement). Mirrors
     * `PostEditorViewModel.activeUserId`.
     */
    private var activeUserId: String? = null
    private var authObserved = false

    /** #459 — in-flight image upload job ; one at a time, cancelled on [onCleared]. */
    private var uploadJob: Job? = null

    /**
     * #459 PR2 — mirror of the persisted [EditorImageInsert] preference, read synchronously at insert
     * time so [onImageUrlInserted] mutates the draft in the same frame as the user action (cf.
     * `PostEditorViewModel.imageInsertMode`). A pasted URL has no reduced variant, so REDUCED degrades
     * to LINKED. Default mirrors the repository's REDUCED default until the first emission lands.
     */
    private var imageInsertMode: EditorImageInsert = EditorImageInsert.REDUCED

    /** Protect the frame before readOnly is recomposed, including typing and paste. */
    val bodyInputTransformation = InputTransformation {
        if (_state.value.isAwaitingInitialBody || !acceptMutation(blockDuringUpload = true)) {
            revertAllChanges()
        } else if (asCharSequence().toString() != bodyFieldState.text.toString()) {
            decideBody()
        }
    }

    private fun acceptMutation(blockDuringUpload: Boolean = false): Boolean {
        val uploadBlocked = blockDuringUpload && _state.value.isUploading
        val resuming = _state.value.isInitializing && restoration.isRecreation
        if (resuming || submission.blocksMutation || uploadBlocked) return false
        return submission.tryDisarm()
    }

    init {
        submission.drafts.checkpointObserver = { draft, version, result ->
            if (!submission.drafts.invalidated) {
                if (result == null) captureCheckpoint(draft, version)
                else restoration.completed(draft, version, result)
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
                updateState { it.copy(editorShortcut = shortcut) }
            }
        }
        viewModelScope.launch {
            userPreferencesRepository.observeImagePickerMode().collect { mode ->
                updateState { it.copy(imagePickerMode = mode) }
            }
        }
        viewModelScope.launch {
            authRepository.observeAuthState().collect { authState ->
                val newUserId = when (authState) {
                    AuthState.Anonymous -> null
                    is AuthState.Authenticated -> authState.pseudo.lowercase()
                }
                // #459 — account switched / logged out mid-session: cancel any in-flight upload
                // and pending autosave so this session's image URL / draft is never attributed to
                // the new account (same Codex-reviewed rule as PostEditorViewModel).
                if (authObserved && newUserId != activeUserId) {
                    uploadJob?.cancel()
                    submission.accountChanged()
                    loadedForm = null
                    restoration.invalidate()
                    chromeMemory.reset()
                    updateState { it.copy(isUploading = false, uploadProgress = null) }
                }
                authObserved = true
                activeUserId = newUserId
            }
        }
        viewModelScope.launch {
            val contentRestored = restoreDraftIfAny()
            draftRestored = true
            if (!contentRestored && bodyEditedBeforeHydration && _state.value.restorableDraft == null) {
                scheduleAutosave()
            }
            updateState { it.copy(isInitializing = false, isRestoringSession = false) }
            if (submission.isInitialized) {
                when (request.mode) {
                    TopicFormMode.EditFirstPost -> loadEditFirstPostFormIfPossible()
                    TopicFormMode.New -> loadNewTopicFormIfPossible()
                }
            }
        }
    }

    private fun observeBody() {
        viewModelScope.launch {
            snapshotFlow { bodyFieldState.text.toString() }.collect { body ->
                updateState { it.copy(bodyText = body, validation = validateBbcodeDraft(body)) }
                if (body != previousBody) {
                    decideBody()
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
                    if (parsed != null && _state.value.isPreviewVisible &&
                        bodyFieldState.text.toString() == parsed.first) {
                        updateState { it.copy(preview = parsed.second) }
                    }
                }
        }
    }

    /** ED-81/170: the owner-bound durable row is authoritative before GET or any offer. */
    private suspend fun restoreDraftIfAny(): Boolean {
        val entry = submission.restore(draftKey.orEmpty())
        val owner = submission.drafts.owner
        if (restoration.isRecreation && !restoration.belongsTo(owner, draftKey)) {
            savedStateHandle.remove<String>(DRAFT_RESTORE_OFFER_FINGERPRINT_KEY)
        }
        restoration.bind(owner, draftKey)
        val resolution = restoration.resolve(entry, owner, draftKey)
        if (entry == null || submission.blocksMutation) return false
        return when (resolution) {
            EditorSessionRestore.Resume, EditorSessionRestore.Uncertain -> {
                val matching = restoration.matches(entry, owner, draftKey)
                restoreSessionContent(entry.draft, resolution == EditorSessionRestore.Resume || matching)
                if (matching) bodyFieldState.edit { selection = restoration.selection(length) }
                true
            }
            EditorSessionRestore.Offer -> {
                offerOrdinaryDraft(entry.draft)
                false
            }
            EditorSessionRestore.None -> false
        }
    }

    private fun offerOrdinaryDraft(draft: EditorDraftStore.Draft) {
        val hasContent = draft.body.isNotBlank() || !draft.subject.isNullOrBlank()
        val fingerprint = draft.restoreOfferFingerprint()
        val ignored = savedStateHandle.get<String>(DRAFT_RESTORE_OFFER_FINGERPRINT_KEY) == fingerprint
        if (hasContent && !ignored && _state.value.canOfferDraftRestore(draft)) {
            updateState { it.copy(restorableDraft = draft.body, restorableSubject = draft.subject) }
        }
    }

    private fun restoreSessionContent(draft: EditorDraftStore.Draft, certified: Boolean) {
        replaceBody(draft.body)
        previousBody = draft.body
        resetUndoBaseline()
        val bodyKnown = certified || request.mode == TopicFormMode.New || draft.body.isNotBlank()
        val subjectKnown = certified || !draft.subject.isNullOrBlank()
        if (bodyKnown) restoration.decide(EditorSessionField.Body)
        if (subjectKnown) restoration.decide(EditorSessionField.Subject)
        updateState {
            it.copy(
                subject = TextFieldValue(draft.subject.orEmpty()),
                draftHydratedFromServer = bodyKnown, subjectHydratedFromServer = subjectKnown,
                restorableDraft = null, restorableSubject = null,
            )
        }
    }

    private fun TopicFormState.canOfferDraftRestore(cached: EditorDraftStore.Draft): Boolean =
        !matches(cached) &&
            (bodyFieldState.text.isBlank() || bodyFieldState.text.toString() == draftHydratedContent) &&
            (subject.text.isBlank() || subject.text == subjectHydratedContent)

    private fun TopicFormState.matches(cached: EditorDraftStore.Draft): Boolean =
        bodyFieldState.text.toString() == cached.body && subject.text == cached.subject.orEmpty()

    /** Called after layout settles; a retained ViewModel already owns its scroll across rotation. */
    suspend fun restoreBodyScroll() {
        restoration.scrollToRestore?.let { position ->
            val target = position.coerceAtMost(bodyScrollState.maxValue)
            bodyScrollState.scrollTo(target)
            if (bodyScrollState.value == target) restoration.scrollRestored()
        }
    }

    /** Capture the live body/title before suspension, including several queued ON_STOP writes. */
    private fun persistDraftNow() {
        if (!draftRestored || submission.blocksClose || submission.drafts.invalidated) return
        val draft = draftSnapshot() ?: return
        // Consume the body decision before suppressing this revision's delayed mirror.
        if (draft.body != previousBody) decideBody()
        // This live revision is checkpointed now; its delayed mirror must not rearm autosave.
        previousBody = draft.body
        val version = maxOf(submission.drafts.nextContentVersion, lastCheckpointVersion + 1)
        lastCheckpointVersion = version
        pendingCheckpoints++
        stoppedDraft = draft
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

    private fun captureCheckpoint(draft: EditorDraftStore.Draft, version: Long) {
        if (version >= lastCheckpointVersion && draft == draftSnapshot()) captureSessionCheckpoint(draft, version)
    }

    private fun captureSessionCheckpoint(draft: EditorDraftStore.Draft, version: Long) {
        val snapshot = _state.value
        val subcategory = EditorSessionSubcategory(snapshot.selectedSubcat).takeIf {
            snapshot.hasLoadedForm || !restoration.canHydrate(EditorSessionField.Subcategory, liveIsBlank = true)
        }
        restoration.capture(
            EditorSessionCheckpoint(draft, version, bodyFieldState.selection,
                restoration.scrollToRestore ?: bodyScrollState.value,
                subcategory),
            bodyKnown = !_state.value.isAwaitingInitialBody,
        )
    }

    private fun scheduleAutosave() {
        if (!draftRestored) return
        if (screenStopped) {
            if (stoppedDraft != draftSnapshot()) persistDraftNow()
        } else submission.autosave(::draftSnapshot)
    }

    private fun draftSnapshot(): EditorDraftStore.Draft? {
        val snapshot = _state.value
        val liveBody = bodyFieldState.text.toString()
        // A title debounce can already be pending when the server seed lands. Preserve the
        // offered row until an actual body edit or an explicit restore, including on close.
        val undecidedOffer = snapshot.restorableDraft != null &&
            (liveBody.isBlank() || liveBody == snapshot.draftHydratedContent)
        // #1574: a failed GET cannot certify or overwrite the unknown first-post body.
        return if (restoration.canCheckpoint(!snapshot.isAwaitingInitialBody, undecidedOffer)) {
            EditorDraftStore.Draft(body = liveBody, subject = snapshot.subject.text.ifBlank { null })
        } else null
    }

    /** #803 pattern — one-shot latch : a committed close is never re-emitted. */
    private var closeRequested = false

    /**
     * #803 pattern (ported from `PostEditorViewModel.onCloseRequested`, state-hygiene audit
     * 2026-07-05) — dirty close : flush the pending debounce so the last keystrokes reach the
     * #405 row, THEN let the UI pop (CloseCommitted). Without this, a system back < 750 ms after
     * typing cancelled the debounce with the ViewModel and silently dropped the tail of the draft.
     *
     * Two guards (gate #803) :
     * - INERT while a POST is in flight — popping would cancel the submit with the viewModelScope
     *   and leave the server state unknown with a repostable draft ; on failure `isSubmitting`
     *   drops and the back works again, on success SubmitSucceeded / NewTopicCreated pops anyway ;
     * - ONE-SHOT — a second back racing the first CloseCommitted must not emit a second effect
     *   (each `onClose` pops blindly : the second pop would remove the screen BELOW).
     */
    private fun onCloseRequested() {
        if (submission.blocksClose || closeRequested) return
        if (_state.value.showSubmitConfirmation) {
            submission.disarm()
            return
        }
        closeRequested = true
        viewModelScope.launch {
            // The close must NEVER stay blocked on a failing flush (Room is not contractually
            // non-throwing — disk full, corrupted store) : the one-shot latch is already set, so
            // a swallowed failure here only costs the last <750 ms of typing (the debounced
            // autosave already persisted the rest) while a rethrow would leave the screen
            // unclosable. CancellationException still propagates (scope teardown is not an error).
            try {
                submission.flush(draftSnapshot())
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                // Best effort — the previous debounced write is what remains.
            }
            _effects.send(TopicFormEffect.CloseCommitted)
        }
    }

    /** Block user body intents during uploads while keeping headers and picker diagnostics available. */
    private val TopicFormIntent.mutatesBody: Boolean
        get() = when (this) {
            is TopicFormIntent.ToolbarActionClicked,
            is TopicFormIntent.SmileySelected,
            is TopicFormIntent.ImageUrlInserted,
            is TopicFormIntent.ImagesPicked,
            TopicFormIntent.DraftRestoreRequested -> true
            else -> false
        }

    @Suppress("CyclomaticComplexMethod") // MVI when-dispatch over the TopicFormIntent variants ; flat by design.
    fun submit(intent: TopicFormIntent) {
        // Unknown seed: refuse the restore before any guard side effect (same order as the PM reply).
        if (intent == TopicFormIntent.DraftRestoreRequested && _state.value.isLoadingForm) return
        if (intent.mutatesEditor && !acceptMutation(blockDuringUpload = intent.mutatesBody)) return
        when (intent) {
            TopicFormIntent.Started -> { screenStopped = false }
            TopicFormIntent.Stopped -> {
                screenStopped = true
                persistDraftNow()
            }
            is TopicFormIntent.SubjectChanged -> onSubjectChanged(intent.value)
            is TopicFormIntent.ToolbarActionClicked -> onToolbarActionClicked(intent.action)
            TopicFormIntent.TogglePreview -> onTogglePreview()
            is TopicFormIntent.SubmitClicked -> onSubmitClicked(intent)
            is TopicFormIntent.SubmitConfirmed -> onSubmitConfirmed(intent)
            TopicFormIntent.SubmitConfirmationDismissed -> submission.disarm()
            TopicFormIntent.ErrorDismissed -> submission.acknowledge(::scheduleAutosave)
            TopicFormIntent.DraftWriteFailureDismissed -> submission.acknowledgeLocal()
            TopicFormIntent.ReloadRequested -> reloadForm()
            is TopicFormIntent.SubcatSelected -> {
                restoration.decide(EditorSessionField.Subcategory)
                updateState { it.copy(selectedSubcat = intent.id) }
                scheduleAutosave()
            }
            is TopicFormIntent.ToggleSignature ->
                updateState { it.copy(signatureEnabled = intent.enabled) }
            is TopicFormIntent.ToggleSmileyDisabled ->
                updateState { it.copy(smileyDisabled = intent.disabled) }
            is TopicFormIntent.ToggleEmailNotification ->
                updateState { it.copy(emailNotificationEnabled = intent.enabled) }
            is TopicFormIntent.MsgIconSelected ->
                intent.n.takeIf { it in EDITOR_MSG_ICONS }?.let { selected ->
                    updateState { it.copy(msgIcon = selected) }
                }
            is TopicFormIntent.SmileySelected -> onSmileySelected(intent.token)
            is TopicFormIntent.ImageUrlInserted -> onImageUrlInserted(intent.url)
            is TopicFormIntent.ImagesPicked -> onImagesPicked(intent.uris)
            is TopicFormIntent.ImagePickerEventReceived -> onImagePickerEvent(intent.event)
            TopicFormIntent.UploadErrorDismissed -> updateState { it.copy(uploadError = null) }
            TopicFormIntent.DraftRestoreRequested -> onDraftRestoreRequested()
            TopicFormIntent.DraftDiscardRequested -> onDraftDiscardRequested()
            TopicFormIntent.CloseRequested -> onCloseRequested()
        }
    }

    /**
     * Preserve the live body for a title-only offer. Otherwise replace a blank field or the untouched seed
     * (a blank suffix counts as untouched), keep text typed after the seed after the draft, or append the
     * draft after the trimmed field otherwise.
     * Same rule as the post editor and the private message editors (R0c, #1512 / #1553).
     */
    private fun onDraftRestoreRequested() {
        val snapshot = _state.value
        if (snapshot.isLoadingForm) return
        val offeredBody = snapshot.restorableDraft ?: return
        val body = restoredBody(bodyFieldState.text.toString(), offeredBody, snapshot.draftHydratedContent)
        // A title-only offer leaves the body (and its caret) alone; any other restore is one edit, caret at the end.
        if (offeredBody.isNotBlank()) replaceBody(body)
        updateState { current ->
            // A live subject wins, including for a title-only offer.
            val subject = current.subject.text.ifBlank { current.restorableSubject.orEmpty() }
            current.copy(
                subject = TextFieldValue(text = subject, selection = TextRange(subject.length)),
                restorableDraft = null,
                restorableSubject = null,
                draftHydratedFromServer = current.draftHydratedFromServer || body.isNotBlank(),
                // A blank title after a restore (offline form failure) stays open to the server title on reload.
                subjectHydratedFromServer = current.subjectHydratedFromServer || subject.isNotBlank(),
            )
        }
        if (body.isNotBlank()) restoration.decide(EditorSessionField.Body)
        if (_state.value.subject.text.isNotBlank()) restoration.decide(EditorSessionField.Subject)
        scheduleAutosave()
    }

    /** ED-89 body rule, extracted from [onDraftRestoreRequested] to keep it under the detekt complexity threshold. */
    private fun restoredBody(liveBody: String, offeredBody: String, seed: String): String {
        val typedAfterSeed = liveBody.takeIf { seed.isNotEmpty() && it.startsWith(seed) }?.substring(seed.length)
        return when {
            offeredBody.isBlank() -> liveBody
            liveBody.isBlank() || liveBody == offeredBody || liveBody == seed -> offeredBody
            typedAfterSeed != null && typedAfterSeed.isBlank() -> offeredBody
            typedAfterSeed != null -> offeredBody + "\n\n" + typedAfterSeed.trimStart()
            else -> liveBody.trimEnd() + "\n\n" + offeredBody
        }
    }

    /** Explicit restore/hydration is one undoable edit, independent of the current selection. */
    private fun replaceBody(body: String) {
        chromeMemory.host.focus.clearToolSelection()
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
        // New-topic input can precede a delayed attempt restore; preserve its undo history.
        // EditFirstPost input stays locked until the seed is known, so initial hydration starts clean.
        if (!bodyEditedBeforeHydration) bodyFieldState.undoState.clearHistory()
    }

    /** #405 — discard the cached draft : delete the row and clear the banner. */
    private fun onDraftDiscardRequested() {
        val offered = _state.value.restorableDraft ?: return
        markDraftRestoreOfferIgnored(
            EditorDraftStore.Draft(body = offered, subject = _state.value.restorableSubject),
        )
        updateState { it.copy(restorableDraft = null, restorableSubject = null) }
        viewModelScope.launch { submission.flush(EditorDraftStore.Draft(body = "")) }
    }

    private fun markDraftRestoreOfferIgnored(draft: EditorDraftStore.Draft) {
        savedStateHandle[DRAFT_RESTORE_OFFER_FINGERPRINT_KEY] = draft.restoreOfferFingerprint()
    }

    private fun onSmileySelected(token: String) {
        if (_state.value.isAwaitingInitialBody) return
        decideBody()
        chromeMemory.host.focus.clearToolSelection()
        bodyFieldState.editBbcode { text, start, end -> insertBbcodeToken(token, text, start, end) }
        // Close the picker on successful insertion (#824 restores the search on the next
        // open) — same contract as PostEditorViewModel.onSmileySelected.
        smileyPicker.dismiss(SmileyPickerCloseCause.INSERTION)
        scheduleAutosave()
    }

    /**
     * #189 / #459 — a user-pasted image URL, shaped by the cached [EditorImageInsert] preference
     * ([imageInsertBbcodeOrNull] validates the http(s) scheme ; a pasted URL has no reduced variant,
     * so REDUCED degrades to LINKED). Read synchronously so the draft mutates in the same frame as
     * the intent (Codex PR2 review — the topic composer must honour the preference too).
     */
    private fun onImageUrlInserted(url: String) {
        if (_state.value.isAwaitingInitialBody) return
        val token = imageInsertBbcodeOrNull(fullUrl = url, mode = imageInsertMode) ?: return
        insertImageBbcodeAtCaret(token, leadingNewline = false)
    }

    /**
     * #459 — inserts an already-built image BBCode fragment at the caret. Same cursor contract as
     * the smiley path ([insertBbcodeToken] preserves the selection, the preview is refreshed),
     * WITHOUT surrounding spaces (an image is self-contained). [leadingNewline] prefixes a newline
     * when the caret is not already at a line start, so consecutive uploads land on their own lines.
     * Mirrors `PostEditorViewModel.insertImageBbcodeAtCaret`.
     */
    private fun insertImageBbcodeAtCaret(bbcode: String, leadingNewline: Boolean) {
        if (submission.blocksMutation || _state.value.isAwaitingInitialBody) return
        decideBody()
        chromeMemory.host.focus.clearToolSelection()
        bodyFieldState.editBbcode { text, start, end ->
            val caret = start.coerceIn(0, text.length)
            val needsNewline = leadingNewline && caret > 0 && text[caret - 1] != '\n'
            val token = if (needsNewline) "\n$bbcode" else bbcode
            insertBbcodeToken(token, text, start, end, surroundWithSpaces = false)
        }
        persistDraftNow()
    }

    /** #1573 — cancellation only records diagnostics; a successful empty callback retains #1128 feedback. */
    private fun onImagePickerEvent(event: ImagePickerEvent) {
        if (submission.blocksMutation) return
        diagnostics.recordImagePickerEvent(event)
        when (event) {
            is ImagePickerEvent.Launched, is ImagePickerEvent.Cancelled -> Unit
            is ImagePickerEvent.Unavailable -> {
                updateState { it.copy(uploadError = EditorErrors.pickerAbsent) }
            }
            is ImagePickerEvent.Result -> {
                val pickedImages = pickedImagesForUpload(event.contract, event.uris)
                val showEmptyPickerBanner = pickedImages.isEmpty() && uploadJob?.isActive != true
                onImagesPicked(event.uris)
                if (showEmptyPickerBanner) {
                    updateState { it.copy(uploadError = EditorErrors.noImageReceived) }
                }
            }
        }
    }

    /**
     * #459 — pick→read→upload→insert for the topic composer, copied from the proven
     * `PostEditorViewModel.onImagesPicked` contract (multi-image #490): the picked [uris] are read
     * and uploaded sequentially (one in-flight at a time), each success inserts its `[img]` at the
     * caret in pick order (with a leading newline from the second image on) and autosaves, and the
     * batch stops at the first failure — the already-inserted images stay, the typed [EditorError]
     * is surfaced. A blank list, an anonymous session (no [activeUserId]) or an upload already in
     * flight are ignored. [TopicFormState.uploadProgress] carries « n/N » when the batch has more
     * than one image.
     */
    private fun canStartUpload(userId: String?, targets: List<String>): Boolean {
        if (_state.value.isAwaitingInitialBody) return false
        return !submission.blocksMutation && uploadJob?.isActive != true && userId != null && targets.isNotEmpty()
    }

    private fun onImagesPicked(uris: List<String>) {
        diagnostics.recordImagesPicked(uris.size)
        val userId = activeUserId
        val received = uris.filter { it.isNotBlank() }
        val targets = received.take(MAX_IMAGES_PER_UPLOAD)
        // One guard (ReturnCount): nothing in flight already, an authenticated owner, a non-empty pick.
        if (!canStartUpload(userId, targets)) return
        val multiple = targets.size > 1
        updateState {
            it.copy(
                isUploading = true,
                uploadError = if (received.size > MAX_IMAGES_PER_UPLOAD) EditorErrors.imageLimit else null,
                uploadProgress = if (multiple) UploadProgress(completed = 0, total = targets.size) else null,
            )
        }
        uploadJob = viewModelScope.launch {
            // Snapshot the cached preference once at batch start so all N inserts use a consistent
            // mode even if the user flips the setting mid-upload.
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
                val bbcode = imageInsertBbcodeOrNull(
                    fullUrl = uploaded.imageUrl,
                    displayUrl = uploaded.resizedUrl ?: uploaded.imageUrl,
                    mode = mode,
                )
                if (submission.blocksMutation || activeUserId != userId) break
                if (bbcode != null) {
                    insertImageBbcodeAtCaret(bbcode, leadingNewline = completed > 0)
                }
                completed += 1
                if (multiple) {
                    updateState { it.copy(uploadProgress = UploadProgress(completed, targets.size)) }
                }
            }
            updateState { it.copy(isUploading = false, uploadProgress = null) }
        }
    }

    private fun handleUploadFailure(error: Throwable) {
        if (error is CancellationException) {
            updateState { it.copy(isUploading = false, uploadProgress = null) }
            throw error
        }
        val mapped = EditorErrors.stopped(error.toEditorUploadError(), _state.value.uploadProgress)
        diagnostics.record(
            DiagnosticsLog.Level.WARN,
            LOG_TAG_VM,
            UploadFailureDiagnostics.describe(error, mapped.toString()),
        )
        updateState { it.copy(isUploading = false, uploadError = mapped, uploadProgress = null) }
    }

    private fun onSubjectChanged(value: TextFieldValue) {
        restoration.decide(EditorSessionField.Subject)
        updateState { current ->
            current.copy(
                subject = value,
                restorableSubject = if (current.restorableDraft != null) {
                    value.text.ifBlank { null }
                } else {
                    current.restorableSubject
                },
            )
        }
        scheduleAutosave()
    }

    private fun decideBody() {
        bodyEditedBeforeHydration = true
        restoration.decide(EditorSessionField.Body)
    }

    private fun onToolbarActionClicked(action: BbcodeAction) {
        if (_state.value.isAwaitingInitialBody) return
        decideBody()
        chromeMemory.host.focus.clearToolSelection()
        bodyFieldState.editBbcode { text, start, end -> applyBbcodeAction(action, text, start, end) }
        if (action == BbcodeAction.Image) persistDraftNow() else scheduleAutosave()
    }

    private fun onTogglePreview() {
        updateState {
            it.copy(
                isPreviewVisible = !it.isPreviewVisible,
                preview = if (it.isPreviewVisible) PostContent(blocks = emptyList()) else it.preview,
            )
        }
    }

    private fun loadEditFirstPostFormIfPossible() {
        val context = buildEditFirstPostContext() ?: run {
            missingContext()
            return
        }
        updateState { it.copy(isLoadingForm = true) }
        val favoriteStamp = smileyPicker.favoriteFormStamp()
        viewModelScope.launch {
            val outcome = runCatching { topicFormRepository.fetchEditFirstPostForm(context) }
            outcome.fold(
                onSuccess = { form ->
                    loadedForm = form
                    smileyPicker.refreshFavorites(favoriteStamp)
                    applyFormHydration(form)
                },
                onFailure = { error -> handleFetchFailure(error) },
            )
        }
    }

    private fun missingContext() {
        updateState { it.copy(submitError = null) }
        updateState { it.copy(submitError = EditorErrors.missingSubcat) }
    }

    private fun reloadForm() {
        if (_state.value.isLoadingForm || submission.blocksMutation) return
        submission.disarm()
        when (request.mode) {
            TopicFormMode.New -> loadNewTopicFormIfPossible()
            TopicFormMode.EditFirstPost -> loadEditFirstPostFormIfPossible()
        }
    }

    private fun handleFetchFailure(error: Throwable) {
        if (error is CancellationException) {
            updateState { it.copy(isLoadingForm = false) }
            throw error
        }
        editorEvents.readFailed()
        val mapped = when (error) {
            is SessionExpiredException -> EditorErrors.sessionExpired
            is IOException -> EditorErrors.formNetwork
            else -> EditorErrors.invalidForm
        }
        diagnostics.record(
            DiagnosticsLog.Level.WARN,
            LOG_TAG_VM,
            "FP fetch bubbled: ${error::class.simpleName}: ${error.message ?: "(no message)"} " +
                "→ ${mapped::class.simpleName}",
        )
        updateState {
            it.copy(
                isLoadingForm = false,
                firstPostSubcategoryRefused = it.firstPostSubcategoryRefused ||
                    error is MissingFirstPostSubcategoryException,
                submitError = if (it.submitError == EditorErrors.uncertain) it.submitError else mapped,
            )
        }
    }

    /** ED-164/166: only the captured, unexpired arm can reserve a durable attempt. */
    private fun onSubmitConfirmed(intent: TopicFormIntent.SubmitConfirmed) {
        val snapshot = _state.value.copy(bodyText = bodyFieldState.text.toString())
        val form = loadedForm
        if (!snapshot.canSubmit || form == null || closeRequested) return
        val permit = submission.confirm(intent.token)
        if (permit is SubmissionRequest.Accepted) launchSubmission(permit, snapshot, form)
    }

    private fun onSubmitClicked(intent: TopicFormIntent.SubmitClicked) {
        val snapshot = _state.value.copy(bodyText = bodyFieldState.text.toString())
        val form = loadedForm
        if (form == null || closeRequested || submission.blocksMutation) return
        if (!snapshot.sendActivatable) return
        val headerError = snapshot.headerError
        if (headerError != null) {
            // ED-35: deliver every refusal, without allocating an arm or durable attempt.
            updateState { it.copy(submitError = null) }
            updateState { it.copy(submitError = headerError) }
        } else if (form.isAnonymous) {
            updateState { it.copy(submitError = null) }
            updateState { it.copy(submitError = ReplyFailureReason.LoginRequired.toEditorError()) }
        } else if (snapshot.canSubmit) {
            val permit = submission.request(intent.timeoutMillis, snapshot.canSubmit && !closeRequested)
            if (permit is SubmissionRequest.Accepted) launchSubmission(permit, snapshot, form)
        }
    }

    /** ED-169/170: snapshot headers, options and body before the first suspension. */
    private fun launchSubmission(
        permit: SubmissionRequest.Accepted,
        snapshot: TopicFormState,
        form: TopicForm,
    ) {
        val draft = EditorDraftStore.Draft(body = snapshot.bodyText, subject = snapshot.subject.text)
        submission.launch(permit, draft) { sendSnapshot(snapshot, form) }
    }

    private suspend fun sendSnapshot(snapshot: TopicFormState, form: TopicForm): EditorDelivery<TopicFormEffect> {
        val options = ReplyFormOptions(
            signatureEnabled = snapshot.signatureEnabled,
            smileyDisabled = snapshot.smileyDisabled,
            emailNotificationEnabled = snapshot.emailNotificationEnabled,
        )
        val selectedSubcat = snapshot.selectedSubcat ?: 0
        val stampedForm = form.copy(msgIcon = snapshot.msgIcon.toString())
        return when (snapshot.mode) {
            TopicFormMode.EditFirstPost -> {
                val result = topicFormRepository.submitEditFirstPost(
                    context = checkNotNull(buildEditFirstPostContext()), form = stampedForm,
                    subject = snapshot.subject.text, bbcodeContent = snapshot.bodyText,
                    selectedSubcat = selectedSubcat, options = options,
                )
                editDelivery(snapshot, result)
            }
            TopicFormMode.New -> {
                val result = topicFormRepository.submitNewTopic(
                    context = checkNotNull(buildNewTopicContext()), form = stampedForm,
                    subject = snapshot.subject.text, bbcodeContent = snapshot.bodyText,
                    selectedSubcat = selectedSubcat, options = options,
                )
                newTopicDelivery(snapshot, selectedSubcat, result)
            }
        }
    }

    private fun loadNewTopicFormIfPossible() {
        val context = buildNewTopicContext() ?: run {
            missingContext()
            return
        }
        updateState { it.copy(isLoadingForm = true) }
        val favoriteStamp = smileyPicker.favoriteFormStamp()
        viewModelScope.launch {
            val outcome = runCatching { topicFormRepository.fetchNewTopicForm(context) }
            outcome.fold(
                onSuccess = { form ->
                    loadedForm = form
                    smileyPicker.refreshFavorites(favoriteStamp)
                    applyFormHydration(form)
                },
                onFailure = { error -> handleFetchFailure(error) },
            )
        }
    }

    private fun newTopicDelivery(
        snapshot: TopicFormState,
        selectedSubcat: Int,
        result: NewTopicSubmitResult,
    ): EditorDelivery<TopicFormEffect> = when (result) {
        is NewTopicSubmitResult.Success -> EditorDelivery(
            effect = TopicFormEffect.NewTopicCreated(
                cat = checkNotNull(snapshot.cat), subcat = selectedSubcat,
                newTopicId = result.newTopicId, newNumreponse = result.newNumreponse,
                subject = snapshot.subject.text,
            ),
        )
        is NewTopicSubmitResult.Failure -> failureDelivery(result.reason)
    }

    private fun editDelivery(
        snapshot: TopicFormState,
        result: ReplySubmitResult,
    ): EditorDelivery<TopicFormEffect> = when (result) {
        is ReplySubmitResult.Success -> EditorDelivery(
            effect = TopicFormEffect.SubmitSucceeded(
                targetPage = result.targetPage, scrollTo = result.numreponse ?: snapshot.numreponse,
            ),
        )
        is ReplySubmitResult.Failure -> failureDelivery(result.reason)
    }

    private fun failureDelivery(reason: ReplyFailureReason): EditorDelivery<TopicFormEffect> {
        return EditorDelivery(
            outcome = if (reason == ReplyFailureReason.Unknown) {
                EditorSendOutcome.UNCERTAIN
            } else {
                EditorSendOutcome.CERTAIN_FAILURE
            },
            error = if (reason == ReplyFailureReason.Unknown) {
                EditorErrors.uncertain
            } else {
                reason.toEditorError()
            },
            afterSettled = if (reason == ReplyFailureReason.InvalidHashCheck) {
                {
                    loadedForm = null
                    when (request.mode) {
                        TopicFormMode.New -> loadNewTopicFormIfPossible()
                        TopicFormMode.EditFirstPost -> loadEditFirstPostFormIfPossible()
                    }
                }
            } else { null },
        )
    }

    /**
     * Builds the [NewTopicContext] when the routing state has enough data to
     * fetch the create-topic form. `entrySubcat` is the chip the user came
     * from (nullable on the « Toutes » view) ; the final subcat lands at
     * submit time via `selectedSubcat` from the dropdown, never through this
     * context.
     */
    private fun buildNewTopicContext(): NewTopicContext? {
        val snapshot = _state.value
        val cat = snapshot.cat?.takeIf { it > 0 } ?: return null
        val entrySubcat = snapshot.subcat?.takeIf { it > 0 }
        return NewTopicContext(cat = cat, entrySubcat = entrySubcat)
    }

    @Suppress("ReturnCount", "ComplexCondition") // Each guard returns null with a distinct reason ; the
    // composite check enforces the four invariants of `EditFirstPostContext.init` before construction.
    private fun buildEditFirstPostContext(): EditFirstPostContext? {
        val snapshot = state.value
        val cat = snapshot.cat ?: return null
        val subcat = snapshot.subcat ?: return null
        val topicId = snapshot.topicId ?: return null
        val page = snapshot.page ?: return null
        val numreponse = snapshot.numreponse ?: return null
        if (subcat <= 0 || topicId <= 0 || numreponse <= 0 || page != 1) return null
        return EditFirstPostContext(
            cat = cat,
            subcat = subcat,
            topicId = topicId,
            page = page,
            numreponse = numreponse,
        )
    }

    private fun TopicFormState.shouldHydrateSubjectFrom(form: TopicForm): Boolean =
        !subjectHydratedFromServer && form.subject.isNotBlank() &&
            restoration.canHydrate(EditorSessionField.Subject, subject.text.isBlank())

    private fun TopicFormState.shouldHydrateDraft(): Boolean =
        !draftHydratedFromServer && restoration.canHydrate(EditorSessionField.Body, bodyFieldState.text.isBlank())

    /**
     * Server value takes over only when [hydrate] is true, caret parked at the end. Extracted from
     * `withFormHydration` to keep that function under the detekt CyclomaticComplexMethod threshold.
     */
    private fun TextFieldValue.hydratedWith(serverText: String, hydrate: Boolean): TextFieldValue =
        if (hydrate) {
            TextFieldValue(text = serverText, selection = TextRange(serverText.length))
        } else {
            this
        }

    private fun <T> T.hydratedValue(serverValue: T, hydrate: Boolean): T =
        if (hydrate) serverValue else this

    private fun TopicFormState.restoreOfferDiffersFrom(
        nextSubject: TextFieldValue,
        nextBody: String,
    ): Boolean = restorableDraft != nextBody || restorableSubject.orEmpty() != nextSubject.text

    private fun applyFormHydration(form: TopicForm) {
        if (submission.blocksMutation) {
            updateState { it.copy(isLoadingForm = false) }
            return
        }
        val snapshot = _state.value
        val hydrateBody = snapshot.shouldHydrateDraft()
        if (hydrateBody) {
            // A successfully parsed blank body is also a known seed; only fetch failure keeps it locked.
            // Programmatic edit bypasses bodyInputTransformation, just like the upload callback.
            replaceBody(form.initialContent)
            previousBody = form.initialContent
            resetUndoBaseline()
        }
        val selectedSubcat = snapshot.subcategoryForForm(form)
        updateState { it.withFormHydration(form, hydrateBody, selectedSubcat) }
    }

    /** D3: decide once before StateFlow.update, whose transform may be retried. */
    private fun TopicFormState.subcategoryForForm(form: TopicForm): Int? {
        val default = if (restoration.canHydrate(EditorSessionField.Subcategory, liveIsBlank = true)) {
            form.selectedSubcat ?: subcat
        } else selectedSubcat
        return restoration.subcategoryForForm(form.subcategoryChoices.map { it.id }.toSet(), default)
    }

    private fun TopicFormState.withFormHydration(
        form: TopicForm,
        hydrateDraft: Boolean,
        selectedSubcat: Int?,
    ): TopicFormState {
        // Hydrate each field independently : a slow fetch that lands after the
        // user started typing in only one of the two fields must still hydrate
        // the other one without clobbering the user's edit.
        val hydrateSubject = shouldHydrateSubjectFrom(form)
        val nextSubject = subject.hydratedWith(form.subject, hydrateSubject)
        val nextBody = bodyFieldState.text.toString()
        val hydrateOptions = !optionsHydratedFromForm
        val keepRestoreOffer = restoreOfferDiffersFrom(nextSubject, nextBody)
        return copy(
            isLoadingForm = false,
            subject = nextSubject,
            restorableDraft = restorableDraft.takeIf { keepRestoreOffer },
            restorableSubject = restorableSubject.takeIf { keepRestoreOffer },
            subjectHydratedFromServer = subjectHydratedFromServer || hydrateSubject,
            subjectHydratedContent = subjectHydratedContent.hydratedValue(nextSubject.text, hydrateSubject),
            draftHydratedFromServer = draftHydratedFromServer || hydrateDraft,
            draftHydratedContent = draftHydratedContent.hydratedValue(form.initialContent, hydrateDraft),
            selectedSubcat = selectedSubcat,
            subcategoryChoices = form.subcategoryChoices,
            // #213 — propagate whether HFR served a <select name=subcat>. A cat
            // without sub-category (false) is submittable with subcat=0 ; a cat
            // with sub-categories (true) keeps requiring an explicit pick.
            hasSubcategorySelect = form.hasSubcategorySelect,
            firstPostSubcategoryRefused = false,
            pollPresent = form.poll.present,
            pollEditable = form.poll.editableInThisVersion,
            signatureEnabled = signatureEnabled.hydratedValue(form.options.signatureEnabled, hydrateOptions),
            smileyDisabled = smileyDisabled.hydratedValue(form.options.smileyDisabled, hydrateOptions),
            emailNotificationEnabled = emailNotificationEnabled.hydratedValue(
                form.options.emailNotificationEnabled,
                hydrateOptions,
            ),
            msgIcon = msgIcon.hydratedValue(form.msgIcon.toEditorMsgIcon(), hydrateOptions),
            optionsHydratedFromForm = true,
            // Propagate the parsed anonymous flag so `canSubmit` can refuse
            // the POST locally (the wire would refuse too, but we don't want
            // to leak attempt artefacts in the diagnostics buffer either).
            isAnonymous = form.isAnonymous,
            // Phase 2F-C (#11) — keep the parsed userId around for the wiki smiley search.
            // Anti-clobber : do not overwrite once set, so a silent `InvalidHashCheck`
            // refetch on an anonymous fallback does not erase a previously known id.
            userId = userId ?: form.userId,
            // An unchanged anonymous reload retains this fact without delivering another refusal.
            // Each send tap observes its own null reset in onSubmitClicked (EditorEventProjection contract).
            submitError = submitError.afterFormLoaded() ?: EditorErrors.loginRequired.takeIf { form.isAnonymous },
        )
    }

    /**
     * #459 — belt-and-braces upload cancellation on ViewModel death (viewModelScope is already
     * cancelled by the lifecycle; this makes the « one upload, no leak » contract explicit).
     * Mirrors `PostEditorViewModel.onCleared`.
     */
    override fun onCleared() {
        uploadJob?.cancel()
        super.onCleared()
    }

    @AssistedFactory
    interface Factory {
        fun create(request: TopicFormRequest): TopicFormViewModel
    }

    private companion object {
        private const val LOG_TAG_VM = "TopicFormVM"
        private const val DRAFT_RESTORE_OFFER_FINGERPRINT_KEY = "draft_restore_offer_fingerprint"
    }
}
