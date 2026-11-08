package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
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
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.model.write.EditFirstPostContext
import fr.forumhfr.redface2.core.model.write.NewTopicContext
import fr.forumhfr.redface2.core.model.write.NewTopicSubmitResult
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.model.write.TopicForm
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
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
    private var previousBody = bodyFieldState.text.toString()
    private var bodyEditedBeforeHydration = false

    private val _state: MutableStateFlow<TopicFormState> = MutableStateFlow(
        TopicFormState(
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

    private val _effects: Channel<TopicFormEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<TopicFormEffect> = _effects.receiveAsFlow()

    private var loadedForm: TopicForm? = null
        set(value) {
            field = value
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
                _state.update {
                    it.copy(
                        submission = value,
                        isSubmitting = value is EditorSubmissionState.PersistingAttempt ||
                            value is EditorSubmissionState.Sending,
                        showSubmitConfirmation = value is EditorSubmissionState.Armed,
                    )
                }
            },
            error = { value -> _state.update { it.copy(submitError = value) } },
            success = { value -> _effects.trySend(value) },
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
            bodyEditedBeforeHydration = true
        }
    }

    private fun acceptMutation(blockDuringUpload: Boolean = false): Boolean {
        if (submission.blocksMutation || (blockDuringUpload && _state.value.isUploading)) return false
        return submission.tryDisarm()
    }

    init {
        observeBody()
        observePreview()
        submission.observePreference(userPreferencesRepository.observeConfirmBeforePosting())
        viewModelScope.launch {
            userPreferencesRepository.observeEditorImageInsert().collect { mode ->
                imageInsertMode = mode
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
                // #459 — account switched / logged out mid-session: cancel any in-flight upload
                // and pending autosave so this session's image URL / draft is never attributed to
                // the new account (same Codex-reviewed rule as PostEditorViewModel).
                if (authObserved && newUserId != activeUserId) {
                    uploadJob?.cancel()
                    submission.accountChanged()
                    _state.update { it.copy(isUploading = false, uploadProgress = null) }
                }
                authObserved = true
                activeUserId = newUserId
            }
        }
        viewModelScope.launch {
            val attemptRestored = restoreDraftIfAny()
            // The body mirror may have consumed user input while the initial draft read was suspended.
            // Retry user edits before loading the form; an automatic attempt restore must not resave itself.
            val earlyEditPending = bodyEditedBeforeHydration && bodyFieldState.text.isNotBlank()
            if (!attemptRestored && earlyEditPending && _state.value.restorableDraft == null) {
                scheduleAutosave()
            }
            when (request.mode) {
                TopicFormMode.EditFirstPost -> loadEditFirstPostFormIfPossible()
                TopicFormMode.New -> loadNewTopicFormIfPossible()
            }
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
                    if (parsed != null && _state.value.isPreviewVisible &&
                        bodyFieldState.text.toString() == parsed.first) {
                        _state.update { it.copy(preview = parsed.second) }
                    }
                }
        }
    }

    /**
     * #405/#1415 — offer each cached subject/body version until the user handles it. Untouched
     * EditFirstPost hydration remains eligible, while a real user edit suppresses the initial
     * offer. The fingerprint is recorded only after Ignore, so a restored row remains recoverable
     * if process death loses the live editor state before autosave.
     * @return whether an unresolved attempt replaced the live buffer.
     */
    private suspend fun restoreDraftIfAny(): Boolean {
        val entry = submission.restore(draftKey.orEmpty())
        if (entry == null || submission.blocksMutation) return false
        return if (entry.attempt != null) {
            restoreAttempt(entry.draft)
        } else {
            offerOrdinaryDraft(entry.draft)
            false
        }
    }

    private fun offerOrdinaryDraft(draft: EditorDraftStore.Draft) {
        val hasContent = draft.body.isNotBlank() || !draft.subject.isNullOrBlank()
        val fingerprint = draft.restoreOfferFingerprint()
        val ignored = savedStateHandle.get<String>(DRAFT_RESTORE_OFFER_FINGERPRINT_KEY) == fingerprint
        if (hasContent && !ignored && _state.value.canOfferDraftRestore(draft)) {
            _state.update { it.copy(restorableDraft = draft.body, restorableSubject = draft.subject) }
        }
    }

    private fun restoreAttempt(draft: EditorDraftStore.Draft): Boolean {
        if (!acceptMutation()) return false
        replaceBody(draft.body)
        previousBody = draft.body
        resetUndoBaseline()
        _state.update {
            it.copy(
                subject = TextFieldValue(draft.subject.orEmpty()),
                draftHydratedFromServer = true, subjectHydratedFromServer = true,
                restorableDraft = null, restorableSubject = null, submitError = EditorErrors.uncertain,
            )
        }
        return true
    }

    private fun TopicFormState.canOfferDraftRestore(cached: EditorDraftStore.Draft): Boolean =
        !matches(cached) &&
            (bodyFieldState.text.isBlank() || bodyFieldState.text.toString() == draftHydratedContent) &&
            (subject.text.isBlank() || subject.text == subjectHydratedContent)

    private fun TopicFormState.matches(cached: EditorDraftStore.Draft): Boolean =
        bodyFieldState.text.toString() == cached.body && subject.text == cached.subject.orEmpty()

    /** ED-170: clear blank content by CAS; never delete a newer generation or a published marker. */
    private fun scheduleAutosave() = submission.autosave(::draftSnapshot)

    private fun draftSnapshot(): EditorDraftStore.Draft? {
        val snapshot = _state.value
        val liveBody = bodyFieldState.text.toString()
        // A title debounce can already be pending when the server seed lands. Preserve the
        // offered row until an actual body edit or an explicit restore, including on close.
        if (snapshot.restorableDraft != null &&
            (liveBody.isBlank() || liveBody == snapshot.draftHydratedContent)) return null
        return EditorDraftStore.Draft(body = liveBody, subject = snapshot.subject.text.ifBlank { null })
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
            is TopicFormIntent.SubjectChanged -> onSubjectChanged(intent.value)
            is TopicFormIntent.ToolbarActionClicked -> onToolbarActionClicked(intent.action)
            TopicFormIntent.TogglePreview -> onTogglePreview()
            is TopicFormIntent.SubmitClicked -> onSubmitClicked(intent)
            is TopicFormIntent.SubmitConfirmed -> onSubmitConfirmed(intent)
            TopicFormIntent.SubmitConfirmationDismissed -> submission.disarm()
            TopicFormIntent.ErrorDismissed -> submission.acknowledge(::scheduleAutosave)
            TopicFormIntent.ReloadRequested -> reloadForm()
            is TopicFormIntent.SubcatSelected ->
                _state.update { it.copy(selectedSubcat = intent.id) }
            is TopicFormIntent.ToggleSignature ->
                _state.update { it.copy(signatureEnabled = intent.enabled) }
            is TopicFormIntent.ToggleSmileyDisabled ->
                _state.update { it.copy(smileyDisabled = intent.disabled) }
            is TopicFormIntent.ToggleEmailNotification ->
                _state.update { it.copy(emailNotificationEnabled = intent.enabled) }
            is TopicFormIntent.MsgIconSelected ->
                intent.n.takeIf { it in EDITOR_MSG_ICONS }?.let { selected ->
                    _state.update { it.copy(msgIcon = selected) }
                }
            is TopicFormIntent.SmileySelected -> onSmileySelected(intent.token)
            is TopicFormIntent.ImageUrlInserted -> onImageUrlInserted(intent.url)
            is TopicFormIntent.ImagesPicked -> onImagesPicked(intent.uris)
            is TopicFormIntent.ImagePickerEventReceived -> onImagePickerEvent(intent.event)
            TopicFormIntent.UploadErrorDismissed -> _state.update { it.copy(uploadError = null) }
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
        _state.update { current ->
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
        bodyFieldState.edit {
            replace(0, length, body)
            selection = TextRange(length)
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
        _state.update { it.copy(restorableDraft = null, restorableSubject = null) }
        viewModelScope.launch { submission.flush(EditorDraftStore.Draft(body = "")) }
    }

    private fun markDraftRestoreOfferIgnored(draft: EditorDraftStore.Draft) {
        savedStateHandle[DRAFT_RESTORE_OFFER_FINGERPRINT_KEY] = draft.restoreOfferFingerprint()
    }

    private fun onSmileySelected(token: String) {
        if (_state.value.isAwaitingInitialBody) return
        bodyEditedBeforeHydration = true
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
        scheduleAutosave()
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
        bodyEditedBeforeHydration = true
        bodyFieldState.editBbcode { text, start, end ->
            val caret = start.coerceIn(0, text.length)
            val needsNewline = leadingNewline && caret > 0 && text[caret - 1] != '\n'
            val token = if (needsNewline) "\n$bbcode" else bbcode
            insertBbcodeToken(token, text, start, end, surroundWithSpaces = false)
        }
    }

    /** #988/#1420 — records the picker boundary and surfaces a callback without images. */
    private fun onImagePickerEvent(event: ImagePickerEvent) {
        if (submission.blocksMutation) return
        diagnostics.recordImagePickerEvent(event)
        if (event is ImagePickerEvent.Unavailable) {
            _state.update { it.copy(uploadError = EditorErrors.pickerAbsent) }
        }
        if (event is ImagePickerEvent.Result) {
            val pickedImages = pickedImagesForUpload(event.contract, event.uris)
            val showEmptyPickerBanner = pickedImages.isEmpty() && uploadJob?.isActive != true
            onImagesPicked(event.uris)
            if (showEmptyPickerBanner) {
                _state.update { it.copy(uploadError = EditorErrors.noImageReceived) }
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
        _state.update {
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
                    // Persist after EACH insert: a later image failing must not lose the images
                    // already inserted into the draft (Codex review #490).
                    scheduleAutosave()
                }
                completed += 1
                if (multiple) {
                    _state.update { it.copy(uploadProgress = UploadProgress(completed, targets.size)) }
                }
            }
            _state.update { it.copy(isUploading = false, uploadProgress = null) }
        }
    }

    private fun handleUploadFailure(error: Throwable) {
        if (error is CancellationException) {
            _state.update { it.copy(isUploading = false, uploadProgress = null) }
            throw error
        }
        val mapped = EditorErrors.stopped(error.toEditorUploadError(), _state.value.uploadProgress)
        diagnostics.record(
            DiagnosticsLog.Level.WARN,
            LOG_TAG_VM,
            UploadFailureDiagnostics.describe(error, mapped.toString()),
        )
        _state.update { it.copy(isUploading = false, uploadError = mapped, uploadProgress = null) }
    }

    private fun onSubjectChanged(value: TextFieldValue) {
        _state.update { current ->
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

    private fun onToolbarActionClicked(action: BbcodeAction) {
        if (_state.value.isAwaitingInitialBody) return
        bodyEditedBeforeHydration = true
        bodyFieldState.editBbcode { text, start, end -> applyBbcodeAction(action, text, start, end) }
        scheduleAutosave()
    }

    private fun onTogglePreview() {
        _state.update {
            it.copy(
                isPreviewVisible = !it.isPreviewVisible,
                preview = if (it.isPreviewVisible) PostContent(blocks = emptyList()) else it.preview,
            )
        }
    }

    private fun loadEditFirstPostFormIfPossible() {
        val context = buildEditFirstPostContext() ?: run {
            _state.update { it.copy(submitError = EditorErrors.missingSubcat) }
            return
        }
        _state.update { it.copy(isLoadingForm = true) }
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
            _state.update { it.copy(isLoadingForm = false) }
            throw error
        }
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
        _state.update {
            it.copy(
                isLoadingForm = false,
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
        if (!snapshot.canSubmit || form == null || closeRequested) return
        if (form.isAnonymous) {
            _state.update { it.copy(submitError = ReplyFailureReason.LoginRequired.toEditorError()) }
        } else {
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
            _state.update { it.copy(submitError = EditorErrors.missingSubcat) }
            return
        }
        _state.update { it.copy(isLoadingForm = true) }
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
        !subjectHydratedFromServer && subject.text.isBlank() && form.subject.isNotBlank()

    private fun TopicFormState.shouldHydrateDraft(): Boolean =
        !draftHydratedFromServer && bodyFieldState.text.isBlank()

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
        val snapshot = _state.value
        val hydrateBody = !submission.blocksMutation && snapshot.shouldHydrateDraft()
        if (hydrateBody) {
            // A successfully parsed blank body is also a known seed; only fetch failure keeps it locked.
            // Programmatic edit bypasses bodyInputTransformation, just like the upload callback.
            replaceBody(form.initialContent)
            previousBody = form.initialContent
            resetUndoBaseline()
        }
        _state.update { it.withFormHydration(form, hydrateBody) }
    }

    private fun TopicFormState.withFormHydration(form: TopicForm, hydrateDraft: Boolean): TopicFormState {
        if (this@TopicFormViewModel.submission.blocksMutation) return copy(isLoadingForm = false)
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
            // The form's `selectedSubcat` is `Int?` post-#149 :
            //  - Edit FP : non-null by `parseEditFirstPost` contract, kept as-is.
            //  - New : HFR serves no pre-selection, so `form.selectedSubcat` is
            //    null. We fall back to `subcat` (the entry chip from the
            //    request), letting the user override via the dropdown later.
            selectedSubcat = selectedSubcat.hydratedValue(form.selectedSubcat ?: subcat, hydrateOptions),
            subcategoryChoices = form.subcategoryChoices,
            // #213 — propagate whether HFR served a <select name=subcat>. A cat
            // without sub-category (false) is submittable with subcat=0 ; a cat
            // with sub-categories (true) keeps requiring an explicit pick.
            hasSubcategorySelect = form.hasSubcategorySelect,
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
