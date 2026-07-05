package fr.forumhfr.redface2.feature.editor
import fr.forumhfr.redface2.core.ui.editor.UploadError
import fr.forumhfr.redface2.core.ui.editor.UploadProgress

import fr.forumhfr.redface2.core.ui.editor.WikiSearchState
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.smiley.SmileyRepository
import fr.forumhfr.redface2.core.domain.upload.ImageUploadReader
import fr.forumhfr.redface2.core.domain.upload.UploadException
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import fr.forumhfr.redface2.core.domain.write.TopicFormRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.write.EditFirstPostContext
import fr.forumhfr.redface2.core.model.write.NewTopicContext
import fr.forumhfr.redface2.core.model.write.NewTopicSubmitResult
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.model.write.TopicForm
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.applyBbcodeAction
import fr.forumhfr.redface2.core.ui.editor.imageInsertBbcodeOrNull
import fr.forumhfr.redface2.core.ui.editor.insertBbcodeToken
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the topic-level form. [TopicFormMode.EditFirstPost] edits an
 * existing first post (Phase 2D #148) and [TopicFormMode.New] creates a topic
 * (Phase 2E #149). Architecture mirrors [PostEditorViewModel] :
 *
 * 1. On init, fetch the topic form via [TopicFormRepository] using the
 *    mode-specific request shape.
 * 2. Hydrate `subject`, `draft`, the three per-post options, and the parsed
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
) : ViewModel() {

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
            // fetched form actually carries content.
            subjectHydratedFromServer = request.mode == TopicFormMode.New,
            draftHydratedFromServer = request.mode == TopicFormMode.New,
        ),
    )
    val state: StateFlow<TopicFormState> = _state.asStateFlow()

    private val _effects: Channel<TopicFormEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<TopicFormEffect> = _effects.receiveAsFlow()

    private var loadedForm: TopicForm? = null
    private var submitJob: Job? = null
    /** In-flight wiki smiley search ; cancelled on next query change / picker close / selection. */
    private var smileySearchJob: Job? = null

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
    private var autosaveJob: Job? = null

    /**
     * #459 — lowercased pseudo of the authenticated session, or null when anonymous. The upload
     * providers require an HFR session for the trace ; an anonymous pick is silently ignored
     * (the submit-time anonymous guard already surfaces the login requirement). Mirrors
     * `PostEditorViewModel.activeUserId`.
     */
    private var activeUserId: String? = null

    /** #459 — in-flight image upload job ; one at a time, cancelled on [onCleared]. */
    private var uploadJob: Job? = null

    /**
     * #405 — account that owned this editor when it opened, snapshotted from [draftStore] so a
     * mid-edit account switch can't write this session's draft under another account (Codex beta
     * review). Captured in [restoreDraftIfAny]; null until then / for an anonymous session.
     */
    private var draftOwner: String? = null

    /**
     * #312 — mirror of the persisted « Confirmation avant publication » preference. Collected on
     * init (same DataStore-consumption shape as `TopicViewModel.observeTopicTopBarAutoHide`) and
     * read synchronously at submit time, identical to `PostEditorViewModel`.
     */
    private var confirmBeforePosting: Boolean = false

    /**
     * #459 PR2 — mirror of the persisted [EditorImageInsert] preference, read synchronously at insert
     * time so [onImageUrlInserted] mutates the draft in the same frame as the user action (cf.
     * `PostEditorViewModel.imageInsertMode`). A pasted URL has no reduced variant, so REDUCED degrades
     * to LINKED. Default mirrors the repository's REDUCED default until the first emission lands.
     */
    private var imageInsertMode: EditorImageInsert = EditorImageInsert.REDUCED

    init {
        viewModelScope.launch {
            userPreferencesRepository.observeConfirmBeforePosting().collect { enabled ->
                confirmBeforePosting = enabled
            }
        }
        viewModelScope.launch {
            userPreferencesRepository.observeEditorImageInsert().collect { mode ->
                imageInsertMode = mode
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
                if (activeUserId != null && newUserId != activeUserId) {
                    uploadJob?.cancel()
                    autosaveJob?.cancel()
                    _state.update { it.copy(isUploading = false, uploadProgress = null) }
                }
                activeUserId = newUserId
            }
        }
        restoreDraftIfAny()
        when (request.mode) {
            TopicFormMode.EditFirstPost -> loadEditFirstPostFormIfPossible()
            TopicFormMode.New -> loadNewTopicFormIfPossible()
        }
    }

    /**
     * #405 — surface a cached draft (subject + body) on the banner, never auto-applied (a server
     * EditFirstPost prefill would otherwise be clobbered). Empty drafts (blank body AND subject)
     * are ignored.
     */
    private fun restoreDraftIfAny() {
        val key = draftKey ?: return
        viewModelScope.launch {
            draftOwner = draftStore.currentOwner()
            val draft = draftStore.load(draftOwner, key) ?: return@launch
            if (draft.body.isNotBlank() || !draft.subject.isNullOrBlank()) {
                _state.update {
                    it.copy(restorableDraft = draft.body, restorableSubject = draft.subject)
                }
            }
        }
    }

    /**
     * #405 — debounced autosave of subject + body. Blank body AND blank subject → delete the row.
     * The store stamps `updatedAt` and is a no-op without an active session.
     */
    private fun scheduleAutosave() {
        if (draftKey == null) return
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            persistDraftNow()
        }
    }

    /**
     * Immediate write of the current subject + body (both blank = delete the row, cf.
     * [scheduleAutosave]). Reads [_state] AFTER the debounce delay — the previous shape captured
     * a snapshot at scheduling time, which the #803 dirty-close flush would have re-persisted
     * stale (state-hygiene audit 2026-07-05). Mirrors `PostEditorViewModel.persistDraftNow`.
     */
    private suspend fun persistDraftNow() {
        val key = draftKey ?: return
        val snapshot = _state.value
        val body = snapshot.draft.text
        val subject = snapshot.subject.text
        if (body.isBlank() && subject.isBlank()) {
            draftStore.delete(draftOwner, key)
        } else {
            draftStore.save(
                draftOwner,
                key,
                EditorDraftStore.Draft(body = body, subject = subject.ifBlank { null }),
            )
        }
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
        if (_state.value.isSubmitting || closeRequested) return
        closeRequested = true
        autosaveJob?.cancel()
        viewModelScope.launch {
            persistDraftNow()
            _effects.send(TopicFormEffect.CloseCommitted)
        }
    }

    @Suppress("CyclomaticComplexMethod") // MVI when-dispatch over 16 intent variants ; flat by design.
    fun submit(intent: TopicFormIntent) {
        when (intent) {
            is TopicFormIntent.SubjectChanged -> onSubjectChanged(intent.value)
            is TopicFormIntent.ContentChanged -> onContentChanged(intent.value)
            is TopicFormIntent.ToolbarActionClicked -> onToolbarActionClicked(intent.action)
            TopicFormIntent.TogglePreview -> onTogglePreview()
            TopicFormIntent.SubmitClicked -> onSubmitClicked()
            TopicFormIntent.SubmitConfirmed -> onSubmitConfirmed()
            TopicFormIntent.SubmitConfirmationDismissed ->
                _state.update { it.copy(showSubmitConfirmation = false) }
            TopicFormIntent.ErrorDismissed -> _state.update { it.copy(submitError = null) }
            is TopicFormIntent.SubcatSelected ->
                _state.update { it.copy(selectedSubcat = intent.id) }
            is TopicFormIntent.ToggleSignature ->
                _state.update { it.copy(signatureEnabled = intent.enabled) }
            is TopicFormIntent.ToggleSmileyDisabled ->
                _state.update { it.copy(smileyDisabled = intent.disabled) }
            is TopicFormIntent.ToggleEmailNotification ->
                _state.update { it.copy(emailNotificationEnabled = intent.enabled) }
            TopicFormIntent.SmileyPickerOpened -> onSmileyPickerOpened()
            TopicFormIntent.SmileyPickerDismissed -> onSmileyPickerDismissed()
            is TopicFormIntent.SmileySearchQueryChanged -> onSmileySearchQueryChanged(intent.query)
            is TopicFormIntent.SmileySelected -> onSmileySelected(intent.token)
            is TopicFormIntent.ImageUrlInserted -> onImageUrlInserted(intent.url)
            is TopicFormIntent.ImagesPicked -> onImagesPicked(intent.uris)
            TopicFormIntent.UploadErrorDismissed -> _state.update { it.copy(uploadError = null) }
            TopicFormIntent.DraftRestoreRequested -> onDraftRestoreRequested()
            TopicFormIntent.DraftDiscardRequested -> onDraftDiscardRequested()
            TopicFormIntent.CloseRequested -> onCloseRequested()
        }
    }

    /**
     * #405 — apply the cached subject + body (caret at the end, like form hydration) and clear the
     * banner. Marks both fields hydrated so a late EditFirstPost form fetch cannot overwrite them.
     */
    private fun onDraftRestoreRequested() {
        _state.update { current ->
            val body = current.restorableDraft.orEmpty()
            // Keep the live subject when the draft has none (it was autosaved before the server form
            // populated the subject) : restoring must never blank a server-provided subject.
            val subject = current.restorableSubject ?: current.subject.text
            current
                .withDraft(TextFieldValue(text = body, selection = TextRange(body.length)))
                .copy(
                    subject = TextFieldValue(text = subject, selection = TextRange(subject.length)),
                    restorableDraft = null,
                    restorableSubject = null,
                    draftHydratedFromServer = true,
                    subjectHydratedFromServer = true,
                )
        }
        scheduleAutosave()
    }

    /** #405 — discard the cached draft : delete the row and clear the banner. */
    private fun onDraftDiscardRequested() {
        _state.update { it.copy(restorableDraft = null, restorableSubject = null) }
        val key = draftKey ?: return
        viewModelScope.launch { draftStore.delete(draftOwner, key) }
    }

    /**
     * #405 — the topic reached HFR ; the cached draft is now obsolete. Cancel any pending autosave
     * first so a debounced save can't resurrect the row after delete. AWAITED (not launched) so the
     * nav pop driven by the success effect can't cancel the delete before the row is gone (Codex).
     */
    private suspend fun deleteDraftOnSuccess() {
        autosaveJob?.cancel()
        draftKey?.let { key -> draftStore.delete(draftOwner, key) }
    }

    private fun onSmileyPickerOpened() {
        _state.update { current ->
            if (current.smileyPicker is SmileyPickerState.Open) current
            else current.copy(smileyPicker = SmileyPickerState.Open())
        }
    }

    private fun onSmileyPickerDismissed() {
        smileySearchJob?.cancel()
        smileySearchJob = null
        _state.update { it.copy(smileyPicker = SmileyPickerState.Hidden) }
    }

    private fun onSmileySearchQueryChanged(query: String) {
        _state.update { current ->
            val open = current.smileyPicker as? SmileyPickerState.Open ?: return@update current
            current.copy(smileyPicker = open.copy(query = query))
        }
        // Cancel in-flight searches so an older response can't overwrite a newer query.
        smileySearchJob?.cancel()
        if (query.length <= 2) {
            // Mirrors the HFR web composer's `query.length > 2` gate. Below threshold we
            // reset the wiki branch to Idle so the picker can render the Standard tab.
            _state.update { current ->
                val open = current.smileyPicker as? SmileyPickerState.Open ?: return@update current
                current.copy(smileyPicker = open.copy(wiki = WikiSearchState.Idle))
            }
            return
        }
        smileySearchJob = viewModelScope.launch {
            // 300 ms matches the JS `find_smilies_timer` debounce embedded in HFR's
            // /compressed/message.js, identical to `PostEditorViewModel`. Flip to
            // `Loading` AFTER the debounce so a fast burst of keystrokes does not
            // flash the spinner before the actual network call.
            delay(SMILEY_SEARCH_DEBOUNCE_MS)
            // Identity guard against the « same query typed twice in a 300 ms window »
            // race : if a second `onSmileySearchQueryChanged` arrived with the same
            // query while the first was still in its debounce, the older job's
            // `coroutineContext[Job]` is no longer the active `smileySearchJob`.
            if (coroutineContext[Job] !== smileySearchJob) return@launch
            _state.update { current ->
                val open = current.smileyPicker as? SmileyPickerState.Open ?: return@update current
                if (open.query != query) return@update current
                current.copy(smileyPicker = open.copy(wiki = WikiSearchState.Loading))
            }
            val effectiveUserId = _state.value.userId ?: 0
            val outcome = runCatching { smileyRepository.searchWiki(effectiveUserId, query) }
            outcome.fold(
                onSuccess = { items ->
                    _state.update { current ->
                        val open = current.smileyPicker as? SmileyPickerState.Open
                            ?: return@update current
                        // Drop the result if the user closed the picker or typed a different
                        // query while we were waiting on the network.
                        if (open.query != query) return@update current
                        current.copy(smileyPicker = open.copy(wiki = WikiSearchState.Results(items)))
                    }
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    diagnostics.record(
                        DiagnosticsLog.Level.WARN,
                        LOG_TAG_VM,
                        "wiki smiley search failed: ${error::class.simpleName}",
                    )
                    _state.update { current ->
                        val open = current.smileyPicker as? SmileyPickerState.Open
                            ?: return@update current
                        if (open.query != query) return@update current
                        current.copy(smileyPicker = open.copy(wiki = WikiSearchState.Error))
                    }
                },
            )
        }
    }

    private fun onSmileySelected(token: String) {
        smileySearchJob?.cancel()
        smileySearchJob = null
        _state.update { current ->
            val draft = current.draft
            val selection = draft.selection
            val outcome = insertBbcodeToken(
                token = token,
                text = draft.text,
                selectionStart = selection.start,
                selectionEnd = selection.end,
            )
            val updatedDraft = TextFieldValue(
                text = outcome.text,
                selection = TextRange(outcome.selectionStart, outcome.selectionEnd),
            )
            val withDraft = current.withDraft(updatedDraft)
            val withPreview = if (withDraft.isPreviewVisible) {
                withDraft.copy(preview = previewParser.parsePreview(withDraft.draft.text))
            } else {
                withDraft
            }
            withPreview.copy(smileyPicker = SmileyPickerState.Hidden)
        }
        scheduleAutosave()
    }

    /**
     * #189 / #459 — a user-pasted image URL, shaped by the cached [EditorImageInsert] preference
     * ([imageInsertBbcodeOrNull] validates the http(s) scheme ; a pasted URL has no reduced variant,
     * so REDUCED degrades to LINKED). Read synchronously so the draft mutates in the same frame as
     * the intent (Codex PR2 review — the topic composer must honour the preference too).
     */
    private fun onImageUrlInserted(url: String) {
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
        _state.update { current ->
            val draft = current.draft
            val selection = draft.selection
            val caret = selection.start.coerceIn(0, draft.text.length)
            val needsNewline = leadingNewline && caret > 0 && draft.text[caret - 1] != '\n'
            val token = if (needsNewline) "\n$bbcode" else bbcode
            val outcome = insertBbcodeToken(
                token = token,
                text = draft.text,
                selectionStart = selection.start,
                selectionEnd = selection.end,
                surroundWithSpaces = false,
            )
            val updatedDraft = TextFieldValue(
                text = outcome.text,
                selection = TextRange(outcome.selectionStart, outcome.selectionEnd),
            )
            val withDraft = current.withDraft(updatedDraft)
            if (withDraft.isPreviewVisible) {
                withDraft.copy(preview = previewParser.parsePreview(withDraft.draft.text))
            } else {
                withDraft
            }
        }
    }

    /**
     * #459 — pick→read→upload→insert for the topic composer, copied from the proven
     * `PostEditorViewModel.onImagesPicked` contract (multi-image #490): the picked [uris] are read
     * and uploaded sequentially (one in-flight at a time), each success inserts its `[img]` at the
     * caret in pick order (with a leading newline from the second image on) and autosaves, and the
     * batch stops at the first failure — the already-inserted images stay, the typed [UploadError]
     * is surfaced. A blank list, an anonymous session (no [activeUserId]) or an upload already in
     * flight are ignored. [TopicFormState.uploadProgress] carries « n/N » when the batch has more
     * than one image.
     */
    private fun onImagesPicked(uris: List<String>) {
        val userId = activeUserId
        val targets = uris.filter { it.isNotBlank() }
        // One guard (ReturnCount): nothing in flight already, an authenticated owner, a non-empty pick.
        if (uploadJob?.isActive == true || userId == null || targets.isEmpty()) return
        val multiple = targets.size > 1
        _state.update {
            it.copy(
                isUploading = true,
                uploadError = null,
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
                    uploadRepository.uploadWithCurrentProvider(image, userId)
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
            _state.update { it.copy(isUploading = false, uploadError = null, uploadProgress = null) }
        }
    }

    private fun handleUploadFailure(error: Throwable) {
        if (error is CancellationException) {
            _state.update { it.copy(isUploading = false, uploadProgress = null) }
            throw error
        }
        val mapped = when (error) {
            is UploadException.TooLarge -> UploadError.TooLarge
            is UploadException.UnsupportedType -> UploadError.UnsupportedType
            is UploadException.Server -> UploadError.Server(code = error.code, providerId = error.providerId)
            is UploadException.Malformed -> UploadError.Malformed(providerId = error.providerId)
            is UploadException.Configuration -> UploadError.Configuration
            is UploadException.Network -> UploadError.Network
            else -> UploadError.Network
        }
        diagnostics.record(
            DiagnosticsLog.Level.WARN,
            LOG_TAG_VM,
            "image upload failed: ${error::class.simpleName} → ${mapped::class.simpleName}",
        )
        _state.update { it.copy(isUploading = false, uploadError = mapped, uploadProgress = null) }
    }

    private fun onSubjectChanged(value: TextFieldValue) {
        _state.update { current ->
            current.copy(
                subject = value,
                submitError = if (value.text != current.subject.text) null else current.submitError,
            )
        }
        scheduleAutosave()
    }

    private fun onContentChanged(value: TextFieldValue) {
        _state.update { current ->
            val refreshed = current.withDraft(value)
            if (refreshed.isPreviewVisible) {
                refreshed.copy(preview = previewParser.parsePreview(refreshed.draft.text))
            } else {
                refreshed
            }
        }
        scheduleAutosave()
    }

    private fun onToolbarActionClicked(action: BbcodeAction) {
        _state.update { current ->
            val draft = current.draft
            val selection = draft.selection
            val outcome = applyBbcodeAction(
                action = action,
                text = draft.text,
                selectionStart = selection.start,
                selectionEnd = selection.end,
            )
            val updatedDraft = TextFieldValue(
                text = outcome.text,
                selection = TextRange(outcome.selectionStart, outcome.selectionEnd),
            )
            val withDraft = current.withDraft(updatedDraft)
            if (withDraft.isPreviewVisible) {
                withDraft.copy(preview = previewParser.parsePreview(withDraft.draft.text))
            } else {
                withDraft
            }
        }
        scheduleAutosave()
    }

    private fun onTogglePreview() {
        _state.update { current ->
            val nextVisible = !current.isPreviewVisible
            current.copy(
                isPreviewVisible = nextVisible,
                preview = if (nextVisible) previewParser.parsePreview(current.draft.text) else current.preview,
            )
        }
    }

    private fun loadEditFirstPostFormIfPossible() {
        val context = buildEditFirstPostContext() ?: run {
            _state.update { it.copy(submitError = SubmitError.MissingSubcat) }
            return
        }
        _state.update { it.copy(isLoadingForm = true, submitError = null) }
        viewModelScope.launch {
            val outcome = runCatching { topicFormRepository.fetchEditFirstPostForm(context) }
            outcome.fold(
                onSuccess = { form ->
                    loadedForm = form
                    val snapshot = _state.value
                    // Pre-compute the preview off the state lambda — same
                    // dispatcher rationale as `PostEditorViewModel`. Only matters
                    // if the draft is going to be hydrated AND the preview pane
                    // is open ; otherwise we keep the current preview.
                    val nextPreview = if (snapshot.shouldHydrateDraftFrom(form) && snapshot.isPreviewVisible) {
                        previewParser.parsePreview(form.initialContent)
                    } else {
                        snapshot.preview
                    }
                    _state.update { current -> current.withFormHydration(form, nextPreview) }
                },
                onFailure = { error -> handleFetchFailure(error) },
            )
        }
    }

    private fun handleFetchFailure(error: Throwable) {
        if (error is CancellationException) {
            _state.update { it.copy(isLoadingForm = false) }
            throw error
        }
        val mapped = when (error) {
            is SessionExpiredException -> SubmitError.SessionExpired
            is IOException -> SubmitError.Network
            else -> SubmitError.Hfr(ReplyFailureReason.Unknown)
        }
        diagnostics.record(
            DiagnosticsLog.Level.WARN,
            LOG_TAG_VM,
            "FP fetch bubbled: ${error::class.simpleName}: ${error.message ?: "(no message)"} " +
                "→ ${mapped::class.simpleName}",
        )
        _state.update { it.copy(isLoadingForm = false, submitError = mapped) }
    }

    /**
     * #312 — confirm path. Closes the dialog and re-runs the submit dispatch with
     * `bypassConfirmation = true` so the real submission executes directly (re-checking the
     * preference here would loop « confirmation → confirmation » forever). The mode-specific
     * guards run again on the latest snapshot, which is safe because the dialog is modal.
     */
    private fun onSubmitConfirmed() {
        _state.update { it.copy(showSubmitConfirmation = false) }
        onSubmitClicked(bypassConfirmation = true)
    }

    @Suppress("ReturnCount") // Guard clauses
    private fun onSubmitClicked(bypassConfirmation: Boolean = false) {
        // #312 — single interception point covering BOTH submit paths (New + EditFirstPost).
        // The `canSubmit` gate runs first so we never confirm an invalid form; the deeper
        // per-mode guards (form not loaded, anonymous, in-flight job) are re-checked after the
        // confirmation by the dispatched handler, exactly as on the direct path.
        if (!_state.value.canSubmit) return
        // Same guard order as PostEditorViewModel / PrivateMessageReplyViewModel: never show
        // the confirmation dialog over an in-flight submit. Redundant with canSubmit's
        // !isSubmitting today, but keeps the three VMs symmetric if canSubmit is relaxed.
        if (submitJob?.isActive == true) return
        if (!bypassConfirmation && confirmBeforePosting) {
            _state.update { it.copy(showSubmitConfirmation = true) }
            return
        }
        when (_state.value.mode) {
            TopicFormMode.EditFirstPost -> onSubmitEditFirstPostClicked()
            TopicFormMode.New -> onSubmitNewTopicClicked()
        }
    }

    @Suppress("ReturnCount") // Guard clauses
    private fun onSubmitEditFirstPostClicked() {
        val snapshot = _state.value
        if (!snapshot.canSubmit) return
        val context = buildEditFirstPostContext() ?: run {
            _state.update { it.copy(submitError = SubmitError.MissingSubcat) }
            return
        }
        val form = loadedForm ?: run {
            loadEditFirstPostFormIfPossible()
            return
        }
        if (form.isAnonymous) {
            _state.update { it.copy(submitError = SubmitError.Hfr(ReplyFailureReason.LoginRequired)) }
            return
        }
        if (submitJob?.isActive == true) return

        val options = ReplyFormOptions(
            signatureEnabled = snapshot.signatureEnabled,
            smileyDisabled = snapshot.smileyDisabled,
            emailNotificationEnabled = snapshot.emailNotificationEnabled,
        )
        val selectedSubcat = snapshot.selectedSubcat ?: error("canSubmit lied about selectedSubcat")
        _state.update { it.copy(isSubmitting = true, submitError = null) }
        submitJob = viewModelScope.launch {
            val outcome = runCatching {
                topicFormRepository.submitEditFirstPost(
                    context = context,
                    form = form,
                    subject = snapshot.subject.text,
                    bbcodeContent = snapshot.draft.text,
                    selectedSubcat = selectedSubcat,
                    options = options,
                )
            }
            outcome.fold(
                onSuccess = { result -> handleSubmitOutcome(snapshot.numreponse, result) },
                onFailure = ::handleSubmitFailure,
            )
        }
    }

    @Suppress("ReturnCount") // Guard clauses
    private fun onSubmitNewTopicClicked() {
        val snapshot = _state.value
        if (!snapshot.canSubmit) return
        val context = buildNewTopicContext() ?: run {
            _state.update { it.copy(submitError = SubmitError.MissingSubcat) }
            return
        }
        val form = loadedForm ?: run {
            loadNewTopicFormIfPossible()
            return
        }
        if (form.isAnonymous) {
            _state.update { it.copy(submitError = SubmitError.Hfr(ReplyFailureReason.LoginRequired)) }
            return
        }
        if (submitJob?.isActive == true) return

        val options = ReplyFormOptions(
            signatureEnabled = snapshot.signatureEnabled,
            smileyDisabled = snapshot.smileyDisabled,
            emailNotificationEnabled = snapshot.emailNotificationEnabled,
        )
        // #213 — a category WITHOUT a sub-category posts with `subcat=0`. In that
        // case `selectedSubcat` is legitimately null (no <select> to pick from) and
        // `canSubmit` already allowed the POST, so we resolve to 0 rather than
        // erroring. A cat WITH sub-categories keeps the invariant : `canSubmit`
        // guarantees a non-null `selectedSubcat`, so a null here is a real bug.
        val selectedSubcat = if (!snapshot.hasSubcategorySelect) {
            snapshot.selectedSubcat ?: 0
        } else {
            snapshot.selectedSubcat ?: error("canSubmit lied about selectedSubcat")
        }
        _state.update { it.copy(isSubmitting = true, submitError = null) }
        submitJob = viewModelScope.launch {
            val outcome = runCatching {
                topicFormRepository.submitNewTopic(
                    context = context,
                    form = form,
                    subject = snapshot.subject.text,
                    bbcodeContent = snapshot.draft.text,
                    selectedSubcat = selectedSubcat,
                    options = options,
                )
            }
            outcome.fold(
                onSuccess = { result ->
                    handleNewTopicOutcome(context, selectedSubcat, snapshot.subject.text, result)
                },
                onFailure = ::handleSubmitFailure,
            )
        }
    }

    private fun loadNewTopicFormIfPossible() {
        val context = buildNewTopicContext() ?: run {
            _state.update { it.copy(submitError = SubmitError.MissingSubcat) }
            return
        }
        _state.update { it.copy(isLoadingForm = true, submitError = null) }
        viewModelScope.launch {
            val outcome = runCatching { topicFormRepository.fetchNewTopicForm(context) }
            outcome.fold(
                onSuccess = { form ->
                    loadedForm = form
                    // The new-topic flow never hydrates subject/draft from the
                    // server (init already locked both flags to `true`), so we
                    // don't need to recompute the preview here. We do still
                    // need to land options + subcategory choices in state.
                    _state.update { current -> current.withFormHydration(form, current.preview) }
                },
                onFailure = { error -> handleFetchFailure(error) },
            )
        }
    }

    private suspend fun handleNewTopicOutcome(
        context: NewTopicContext,
        selectedSubcat: Int,
        subject: String,
        result: NewTopicSubmitResult,
    ) {
        when (result) {
            is NewTopicSubmitResult.Success -> {
                deleteDraftOnSuccess()
                _effects.trySend(
                    TopicFormEffect.NewTopicCreated(
                        cat = context.cat,
                        subcat = selectedSubcat,
                        newTopicId = result.newTopicId,
                        newNumreponse = result.newNumreponse,
                        // Carry the posted title so the listing we land on can highlight
                        // the fresh row by exact-title match (#206 workaround). The live
                        // create success response never returns a topic id (#214), so this
                        // is the only handle we have.
                        subject = subject,
                    ),
                )
                _state.update { it.copy(isSubmitting = false, submitError = null) }
            }
            is NewTopicSubmitResult.Failure -> {
                if (result.reason == ReplyFailureReason.InvalidHashCheck) {
                    loadedForm = null
                    loadNewTopicFormIfPossible()
                }
                _state.update {
                    it.copy(isSubmitting = false, submitError = SubmitError.Hfr(result.reason))
                }
            }
        }
    }

    private suspend fun handleSubmitOutcome(
        numreponse: Int?,
        result: ReplySubmitResult,
    ) {
        when (result) {
            is ReplySubmitResult.Success -> {
                deleteDraftOnSuccess()
                _effects.trySend(
                    TopicFormEffect.SubmitSucceeded(
                        targetPage = result.targetPage,
                        // Issue #200 — prefer the parser-extracted numreponse over the local
                        // hint. For Edit FP the two should agree (HFR anchors `#t{N}` on the
                        // FP id we're editing), but the parser stays authoritative if HFR
                        // ever decides to anchor on a different post. Falls back to the
                        // locally-known numreponse so existing tests / quote-style paths
                        // that don't populate the parser numreponse still scroll correctly.
                        scrollTo = result.numreponse ?: numreponse,
                    ),
                )
                _state.update { it.copy(isSubmitting = false, submitError = null) }
            }
            is ReplySubmitResult.Failure -> {
                if (result.reason == ReplyFailureReason.InvalidHashCheck) {
                    loadedForm = null
                    loadEditFirstPostFormIfPossible()
                }
                _state.update {
                    it.copy(isSubmitting = false, submitError = SubmitError.Hfr(result.reason))
                }
            }
        }
    }

    private fun handleSubmitFailure(error: Throwable) {
        if (error is CancellationException) {
            _state.update { it.copy(isSubmitting = false) }
            throw error
        }
        val mapped = when (error) {
            is SessionExpiredException -> SubmitError.SessionExpired
            is IOException -> SubmitError.Network
            else -> SubmitError.Hfr(ReplyFailureReason.Unknown)
        }
        diagnostics.record(
            DiagnosticsLog.Level.WARN,
            LOG_TAG_VM,
            "FP submit bubbled: ${error::class.simpleName}: ${error.message ?: "(no message)"} " +
                "→ ${mapped::class.simpleName}",
        )
        _state.update { it.copy(isSubmitting = false, submitError = mapped) }
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

    private fun TopicFormState.shouldHydrateDraftFrom(form: TopicForm): Boolean =
        !draftHydratedFromServer && draft.text.isBlank() && form.initialContent.isNotBlank()

    private fun TopicFormState.withFormHydration(
        form: TopicForm,
        nextPreview: PostContent,
    ): TopicFormState {
        // Hydrate each field independently : a slow fetch that lands after the
        // user started typing in only one of the two fields must still hydrate
        // the other one without clobbering the user's edit.
        val hydrateSubject = shouldHydrateSubjectFrom(form)
        val hydrateDraft = shouldHydrateDraftFrom(form)
        val nextSubject = if (hydrateSubject) {
            TextFieldValue(
                text = form.subject,
                selection = TextRange(form.subject.length),
            )
        } else {
            subject
        }
        val nextDraft = if (hydrateDraft) {
            TextFieldValue(
                text = form.initialContent,
                selection = TextRange(form.initialContent.length),
            )
        } else {
            draft
        }
        val hydrateOptions = !optionsHydratedFromForm
        return copy(
            isLoadingForm = false,
            subject = nextSubject,
            draft = nextDraft,
            preview = if (hydrateDraft && isPreviewVisible) nextPreview else preview,
            subjectHydratedFromServer = subjectHydratedFromServer || hydrateSubject,
            draftHydratedFromServer = draftHydratedFromServer || hydrateDraft,
            // The form's `selectedSubcat` is `Int?` post-#149 :
            //  - Edit FP : non-null by `parseEditFirstPost` contract, kept as-is.
            //  - New : HFR serves no pre-selection, so `form.selectedSubcat` is
            //    null. We fall back to `subcat` (the entry chip from the
            //    request), letting the user override via the dropdown later.
            selectedSubcat = if (hydrateOptions) form.selectedSubcat ?: subcat else selectedSubcat,
            subcategoryChoices = form.subcategoryChoices,
            // #213 — propagate whether HFR served a <select name=subcat>. A cat
            // without sub-category (false) is submittable with subcat=0 ; a cat
            // with sub-categories (true) keeps requiring an explicit pick.
            hasSubcategorySelect = form.hasSubcategorySelect,
            pollPresent = form.poll.present,
            pollEditable = form.poll.editableInThisVersion,
            signatureEnabled = if (hydrateOptions) form.options.signatureEnabled else signatureEnabled,
            smileyDisabled = if (hydrateOptions) form.options.smileyDisabled else smileyDisabled,
            emailNotificationEnabled = if (hydrateOptions) {
                form.options.emailNotificationEnabled
            } else {
                emailNotificationEnabled
            },
            optionsHydratedFromForm = true,
            // Propagate the parsed anonymous flag so `canSubmit` can refuse
            // the POST locally (the wire would refuse too, but we don't want
            // to leak attempt artefacts in the diagnostics buffer either).
            isAnonymous = form.isAnonymous,
            // Phase 2F-C (#11) — keep the parsed userId around for the wiki smiley search.
            // Anti-clobber : do not overwrite once set, so a silent `InvalidHashCheck`
            // refetch on an anonymous fallback does not erase a previously known id.
            userId = userId ?: form.userId,
            submitError = if (form.isAnonymous) {
                SubmitError.Hfr(ReplyFailureReason.LoginRequired)
            } else {
                submitError
            },
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

        // HFR's web composer waits 300 ms after the last keystroke before calling
        // `find_smilies`, cf. `/compressed/message.js`. Mirror it so the wiki endpoint
        // sees roughly the same query rate as the web client.
        private const val SMILEY_SEARCH_DEBOUNCE_MS = 300L

        // #405 — idle window after the last edit before the draft is persisted (cf. PostEditorViewModel).
        private const val AUTOSAVE_DEBOUNCE_MS = 750L
    }
}
