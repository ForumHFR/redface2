package fr.forumhfr.redface2.feature.messages

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.upload.ImageUploadReader
import fr.forumhfr.redface2.core.domain.upload.UploadException
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.ui.editor.UploadError
import fr.forumhfr.redface2.core.ui.editor.UploadProgress
import fr.forumhfr.redface2.core.ui.editor.imageInsertBbcodeOrNull
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.domain.smiley.SmileyRepository
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.applyBbcodeAction
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
 * ViewModel for composing a NEW private conversation (#301 follow-up). Same pipeline as
 * [PrivateMessageReplyViewModel] — fetch the `bddpost.php` form (fresh `hash_check`), compose with
 * the shared toolbar/preview, submit, classify — with the standalone composer's two extra
 * user-typed routing fields (`dest` recipients + `sujet` subject) and no conversation context.
 *
 * [initialRecipient] supports future « envoyer un MP à ce membre » entry points : it rides the
 * composer GET (`dest=` query) so HFR pre-fills the field server-side, AND seeds [recipients]
 * locally on the first load. The MP-list entry point passes `null`.
 *
 * The POST response of a new conversation was deliberately never exercised live (no test send to a
 * third party) : an unrecognised response maps to [PrivateMessageReplyError.Unexpected] and keeps
 * every field intact — the banner invites the user to check their messages list instead.
 */
@HiltViewModel(assistedFactory = PrivateMessageComposeViewModel.Factory::class)
@Suppress("LongParameterList") // Hilt ctor — one dependency per collaborator (#459 added the upload trio).
class PrivateMessageComposeViewModel @AssistedInject constructor(
    @Assisted private val initialRecipient: String?,
    private val repository: PrivateMessageWriteRepository,
    private val previewParser: BbcodePreviewParser,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val draftStore: EditorDraftStore,
    // #459 — image upload wiring, same trio + diagnostics as the topic-side editors.
    private val authRepository: AuthRepository,
    private val uploadRepository: UploadRepository,
    private val imageUploadReader: ImageUploadReader,
    private val diagnostics: DiagnosticsLog,
    smileyRepository: SmileyRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        PrivateMessageComposeUiState(recipients = initialRecipient.orEmpty().trim()),
    )
    val state: StateFlow<PrivateMessageComposeUiState> = _state.asStateFlow()

    private val _effects: Channel<PrivateMessageComposeEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<PrivateMessageComposeEffect> = _effects.receiveAsFlow()

    private var loadedForm: ReplyForm? = null
    private var formJob: Job? = null
    private var submitJob: Job? = null

    /** #405 — single content-free key for the new-conversation composer (private → wiped on logout). */
    private val draftKey: String = EditorDraftKey.mpCompose()

    /** #405 — debounced autosave coroutine ; relaunched (cancelling the previous) on each edit. */
    private var autosaveJob: Job? = null

    /**
     * #459 — lowercased pseudo of the authenticated session, or null when anonymous. The upload
     * providers require an HFR session for the trace ; an anonymous pick is silently ignored.
     * Mirrors `PostEditorViewModel.activeUserId`.
     */
    private var activeUserId: String? = null

    /** #459 — in-flight image upload job ; one at a time, cancelled on [onCleared]. */
    private var uploadJob: Job? = null

    /**
     * #459 — mirror of the persisted [EditorImageInsert] preference, read synchronously at insert
     * time (cf. `PostEditorViewModel.imageInsertMode`). Default mirrors the repository's REDUCED
     * default until the first emission lands.
     */
    private var imageInsertMode: EditorImageInsert = EditorImageInsert.REDUCED

    /**
     * #405 — account that owned this MP composer when it opened, snapshotted from [draftStore] so a
     * mid-edit account switch can't write this session's PRIVATE draft (recipients + body) under
     * another account (Codex beta review). Captured in [restoreDraftIfAny]; null until then.
     */
    private var draftOwner: String? = null

    /** #312 — mirror of the persisted « Confirmation avant publication » preference. */
    private var confirmBeforePosting: Boolean = false

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
                // and pending autosave so this session's image URL / PRIVATE draft is never
                // attributed to the new account (same Codex-reviewed rule as PostEditorViewModel).
                if (activeUserId != null && newUserId != activeUserId) {
                    uploadJob?.cancel()
                    autosaveJob?.cancel()
                    _state.update { it.copy(isUploading = false, uploadProgress = null) }
                }
                activeUserId = newUserId
            }
        }
        restoreDraftIfAny()
        loadForm()
    }

    fun retryFormLoad() = loadForm()

    /**
     * #405 — surface a cached draft (body + subject + recipients) on the banner, never auto-applied
     * (a server-side `dest` prefill or seeded recipient would otherwise be clobbered). A draft is
     * considered restorable when any of the three fields is non-blank.
     */
    private fun restoreDraftIfAny() {
        viewModelScope.launch {
            draftOwner = draftStore.currentOwner()
            val draft = draftStore.load(draftOwner, draftKey) ?: return@launch
            val hasContent = draft.body.isNotBlank() ||
                !draft.subject.isNullOrBlank() ||
                !draft.recipients.isNullOrBlank()
            if (hasContent) {
                _state.update {
                    it.copy(
                        restorableDraft = draft.body,
                        restorableSubject = draft.subject,
                        restorableRecipients = draft.recipients,
                    )
                }
            }
        }
    }

    /**
     * #405 — debounced autosave of recipients + subject + body, flagged `isPrivate = true` so the
     * logout purge wipes it. Empty across all three fields → delete the row. No-op without a session.
     */
    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            persistDraftNow()
        }
    }

    /**
     * Immediate write of the current recipients + subject + body (all blank = delete the row, cf.
     * [scheduleAutosave]). Reads [_state] AFTER the debounce delay — the previous shape captured a
     * snapshot at scheduling time, which the #803 dirty-close flush would have re-persisted stale
     * (state-hygiene audit 2026-07-05). Mirrors `PostEditorViewModel.persistDraftNow`.
     */
    private suspend fun persistDraftNow() {
        val snapshot = _state.value
        val body = snapshot.draft.text
        val subject = snapshot.subject
        val recipients = snapshot.recipients
        if (body.isBlank() && subject.isBlank() && recipients.isBlank()) {
            draftStore.delete(draftOwner, draftKey)
        } else {
            draftStore.save(
                draftOwner,
                draftKey,
                EditorDraftStore.Draft(
                    body = body,
                    subject = subject.ifBlank { null },
                    recipients = recipients.ifBlank { null },
                    isPrivate = true,
                ),
            )
        }
    }

    /** #803 pattern — one-shot latch : a committed close is never re-emitted. */
    private var closeRequested = false

    /**
     * #803 pattern (ported from `PostEditorViewModel.onCloseRequested`, state-hygiene audit
     * 2026-07-05) — dirty close : flush the pending debounce so the last keystrokes reach the #405
     * row, THEN let the UI pop (CloseCommitted). Without this, closing the composer < 750 ms after
     * typing (system back or the header's back arrow) cancelled the debounce with the ViewModel
     * and silently dropped the tail of the PRIVATE draft.
     *
     * Two guards (gate #803) :
     * - INERT while a POST is in flight — popping would cancel the submit with the viewModelScope
     *   and leave the server state unknown ; on failure `isSubmitting` drops and the back works
     *   again, on success SubmitSucceeded pops anyway ;
     * - ONE-SHOT — a second close racing the first CloseCommitted must not emit a second effect
     *   (the pop is blind : a second one would remove the screen BELOW).
     */
    fun onCloseRequested() {
        if (_state.value.isSubmitting || closeRequested) return
        closeRequested = true
        autosaveJob?.cancel()
        viewModelScope.launch {
            // The close must NEVER stay blocked on a failing flush (Room is not contractually
            // non-throwing — disk full, corrupted store) : the one-shot latch is already set, so
            // a swallowed failure here only costs the last <750 ms of typing (the debounced
            // autosave already persisted the rest) while a rethrow would leave the screen
            // unclosable. CancellationException still propagates (scope teardown is not an error).
            try {
                persistDraftNow()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best effort — the previous debounced write is what remains.
            }
            _effects.send(PrivateMessageComposeEffect.CloseCommitted)
        }
    }

    /** #405 — apply the cached recipients + subject + body and clear the banner. */
    fun onDraftRestoreRequested() {
        val snapshot = _state.value
        val body = snapshot.restorableDraft.orEmpty()
        _state.update {
            it.withDraftPreview(TextFieldValue(text = body, selection = TextRange(body.length)))
                .copy(
                    subject = snapshot.restorableSubject.orEmpty(),
                    recipients = snapshot.restorableRecipients.orEmpty(),
                    restorableDraft = null,
                    restorableSubject = null,
                    restorableRecipients = null,
                )
        }
        scheduleAutosave()
    }

    /** #405 — discard the cached draft : delete the row and clear the banner. */
    fun onDraftDiscardRequested() {
        _state.update {
            it.copy(restorableDraft = null, restorableSubject = null, restorableRecipients = null)
        }
        viewModelScope.launch { draftStore.delete(draftOwner, draftKey) }
    }

    private fun loadForm() {
        formJob?.cancel()
        _state.update { it.copy(isLoadingForm = true, formError = false) }
        formJob = viewModelScope.launch {
            try {
                val form = repository.fetchComposeForm(prefilledRecipient = initialRecipient)
                if (form.isAnonymous) {
                    loadedForm = null
                    _state.update { it.copy(isLoadingForm = false, formAvailable = false, formError = true) }
                    return@launch
                }
                loadedForm = form
                _state.update { current ->
                    // Hydrate ONLY on the first successful load — a silent refetch after an
                    // expired hash_check must not clobber toggles (or a recipient edit) the user
                    // changed in between. Mirror of the reply editor's guard.
                    val hydrate = !current.optionsHydratedFromForm
                    current.copy(
                        isLoadingForm = false,
                        formAvailable = true,
                        formError = false,
                        // The composer's `dest` text input comes back through hiddenFields ; a
                        // server-side prefill (dest= in the GET) seeds the local field once.
                        recipients = if (hydrate && current.recipients.isBlank()) {
                            form.hiddenFields["dest"].orEmpty()
                        } else {
                            current.recipients
                        },
                        signatureEnabled = if (hydrate) {
                            form.options.signatureEnabled || form.hiddenFields["signature"] == "1"
                        } else {
                            current.signatureEnabled
                        },
                        smileyDisabled = if (hydrate) {
                            form.options.smileyDisabled || form.hiddenFields["smiley"] == "1"
                        } else {
                            current.smileyDisabled
                        },
                        emailNotificationEnabled = if (hydrate) {
                            form.options.emailNotificationEnabled || form.hiddenFields["emaill"] == "1"
                        } else {
                            current.emailNotificationEnabled
                        },
                        optionsHydratedFromForm = true,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception,
            ) {
                // No raw message reaches the UI (#316) : the GET URL can embed a recipient pseudo.
                loadedForm = null
                _state.update { it.copy(isLoadingForm = false, formAvailable = false, formError = true) }
            }
        }
    }

    fun onRecipientsChanged(value: String) {
        _state.update { it.copy(recipients = value) }
        scheduleAutosave()
    }

    fun onSubjectChanged(value: String) {
        _state.update {
            // HFR's maxlength=70 — truncate instead of erroring so pasted text just clips.
            it.copy(subject = value.take(PrivateMessageComposeUiState.SUBJECT_MAX_LENGTH))
        }
        scheduleAutosave()
    }

    fun onContentChanged(value: TextFieldValue) {
        _state.update { it.withDraftPreview(value) }
        scheduleAutosave()
    }

    fun onToolbarAction(action: BbcodeAction) {
        _state.update { current ->
            val outcome = applyBbcodeAction(
                action = action,
                text = current.draft.text,
                selectionStart = current.draft.selection.start,
                selectionEnd = current.draft.selection.end,
            )
            current.withDraftPreview(
                TextFieldValue(
                    text = outcome.text,
                    selection = TextRange(outcome.selectionStart, outcome.selectionEnd),
                ),
            )
        }
        scheduleAutosave()
    }

    /**
     * #387 — smiley picker (Standard + Wiki), same sheet as the post editors. The controller owns
     * the picker state machine (debounce, race guards) ; this ViewModel only handles insertion.
     * userId comes from the loaded compose form ([ReplyForm.userId], HFR's `find_smilies_timer`
     * second argument) so the wiki search prioritizes the user's own smileys exactly like the
     * post editors do ; the controller falls back to 0 while the form is still loading
     * (Codex review, PR #440).
     */
    val smileyPicker = SmileyPickerController(
        scope = viewModelScope,
        searchWiki = smileyRepository::searchWiki,
        userId = { loadedForm?.userId },
    )

    fun onSmileySelected(token: String) {
        _state.update { current ->
            val outcome = insertBbcodeToken(
                token = token,
                text = current.draft.text,
                selectionStart = current.draft.selection.start,
                selectionEnd = current.draft.selection.end,
            )
            current.withDraftPreview(
                TextFieldValue(
                    text = outcome.text,
                    selection = TextRange(outcome.selectionStart, outcome.selectionEnd),
                ),
            )
        }
        smileyPicker.dismiss()
        scheduleAutosave()
    }

    fun onTogglePreview() {
        _state.update { current ->
            val nextVisible = !current.isPreviewVisible
            current.copy(
                isPreviewVisible = nextVisible,
                preview = if (nextVisible) previewParser.parsePreview(current.draft.text) else current.preview,
            )
        }
    }

    fun onToggleSignature(enabled: Boolean) = _state.update { it.copy(signatureEnabled = enabled) }

    fun onToggleSmileyDisabled(disabled: Boolean) = _state.update { it.copy(smileyDisabled = disabled) }

    fun onToggleEmailNotification(enabled: Boolean) =
        _state.update { it.copy(emailNotificationEnabled = enabled) }

    fun onErrorDismissed() = _state.update { it.copy(submitError = null) }

    fun onSubmitConfirmed() {
        _state.update { it.copy(showSubmitConfirmation = false) }
        onSubmit(bypassConfirmation = true)
    }

    fun onSubmitConfirmationDismissed() = _state.update { it.copy(showSubmitConfirmation = false) }

    fun onSubmit() = onSubmit(bypassConfirmation = false)

    @Suppress("ReturnCount") // Guard clauses are the natural shape of the submit dispatcher.
    private fun onSubmit(bypassConfirmation: Boolean) {
        val snapshot = _state.value
        if (!snapshot.canSubmit) return
        val form = loadedForm ?: run {
            loadForm()
            return
        }
        if (form.isAnonymous) {
            _state.update { it.copy(submitError = PrivateMessageReplyError.LoginRequired) }
            return
        }
        if (submitJob?.isActive == true) return
        if (!bypassConfirmation && confirmBeforePosting) {
            _state.update { it.copy(showSubmitConfirmation = true) }
            return
        }

        val options = ReplyFormOptions(
            signatureEnabled = snapshot.signatureEnabled,
            smileyDisabled = snapshot.smileyDisabled,
            emailNotificationEnabled = snapshot.emailNotificationEnabled,
        )
        _state.update { it.copy(isSubmitting = true, submitError = null) }
        submitJob = viewModelScope.launch {
            val outcome = runCatching {
                repository.submitNewMessage(
                    form = form,
                    recipients = snapshot.recipients.trim(),
                    subject = snapshot.subject.trim(),
                    bbcodeContent = snapshot.draft.text,
                    options = options,
                )
            }
            outcome.fold(
                onSuccess = { handleSubmitOutcome(it) },
                onFailure = ::handleSubmitFailure,
            )
        }
    }

    private suspend fun handleSubmitOutcome(result: ReplySubmitResult) {
        when (result) {
            is ReplySubmitResult.Success -> {
                // #405 — the conversation reached HFR ; the cached draft is now obsolete. Cancel any
                // pending autosave first so a debounced save can't resurrect the row after delete.
                // AWAITED (not launched) so the nav pop on SubmitSucceeded can't cancel it (Codex).
                autosaveJob?.cancel()
                draftStore.delete(draftOwner, draftKey)
                _state.update { it.copy(isSubmitting = false, submitError = null) }
                // The success response of a NEW conversation is not topic-shaped, so the created
                // threadId is unknown — the host pops back to the MP list and refreshes it.
                _effects.trySend(PrivateMessageComposeEffect.SubmitSucceeded)
            }
            is ReplySubmitResult.Failure -> {
                if (result.reason == ReplyFailureReason.InvalidHashCheck) {
                    loadedForm = null
                    loadForm()
                }
                _state.update { it.copy(isSubmitting = false, submitError = result.reason.toComposeError()) }
            }
        }
    }

    private fun handleSubmitFailure(error: Throwable) {
        if (error is CancellationException) {
            _state.update { it.copy(isSubmitting = false) }
            throw error
        }
        val mapped = when (error) {
            is SessionExpiredException -> PrivateMessageReplyError.SessionExpired
            is IOException -> PrivateMessageReplyError.Network
            else -> PrivateMessageReplyError.Unexpected
        }
        _state.update { it.copy(isSubmitting = false, submitError = mapped) }
    }

    private fun ReplyFailureReason.toComposeError(): PrivateMessageReplyError = when (this) {
        ReplyFailureReason.EmptyMessage -> PrivateMessageReplyError.Empty
        ReplyFailureReason.InvalidHashCheck -> PrivateMessageReplyError.InvalidHashCheck
        ReplyFailureReason.AntiFlood -> PrivateMessageReplyError.AntiFlood
        ReplyFailureReason.LoginRequired -> PrivateMessageReplyError.LoginRequired
        // Unknown covers every unpinned server answer — including a rejected recipient — and
        // must stay non-destructive (cf. class KDoc). TopicLocked has no composer analogue.
        ReplyFailureReason.TopicLocked, ReplyFailureReason.Unknown -> PrivateMessageReplyError.Unexpected
    }

    private fun PrivateMessageComposeUiState.withDraftPreview(
        updated: TextFieldValue,
    ): PrivateMessageComposeUiState = copy(
        draft = updated,
        preview = if (isPreviewVisible) previewParser.parsePreview(updated.text) else preview,
        // #459 — a fresh text edit dismisses a stale upload banner (a successful upload INSERTS
        // text via this path, which also clears any prior error) — parity with PostEditorState.
        uploadError = if (updated.text != draft.text) null else uploadError,
    )


    /**
     * #459 — pick→read→upload→insert for this MP composer, same proven contract as
     * `PostEditorViewModel.onImagesPicked` (multi-image #490): sequential batch, one `[img]` per
     * success in pick order (leading newline from the 2nd on), autosave after each insertion, stop
     * at the first failure with the typed [UploadError] surfaced. A blank list, an anonymous
     * session or an upload already in flight are ignored.
     */
    fun onImagesPicked(uris: List<String>) {
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

    /** #459 — dismiss the upload-error banner. */
    fun onUploadErrorDismissed() {
        _state.update { it.copy(uploadError = null) }
    }

    /**
     * #459 — inserts an already-built image BBCode fragment at the caret, WITHOUT surrounding
     * spaces (an image is self-contained). [leadingNewline] prefixes a newline when the caret is
     * not already at a line start, so consecutive uploads land on their own lines. Mirrors
     * `PostEditorViewModel.insertImageBbcodeAtCaret`.
     */
    private fun insertImageBbcodeAtCaret(bbcode: String, leadingNewline: Boolean) {
        _state.update { current ->
            val draft = current.draft
            val selection = draft.selection
            val caret = selection.start.coerceIn(0, draft.text.length)
            val needsNewline = leadingNewline && caret > 0 && draft.text[caret - 1] != '\n'
            val token = if (needsNewline) "\n" + bbcode else bbcode
            val outcome = insertBbcodeToken(
                token = token,
                text = draft.text,
                selectionStart = selection.start,
                selectionEnd = selection.end,
                surroundWithSpaces = false,
            )
            current.withDraftPreview(
                TextFieldValue(
                    text = outcome.text,
                    selection = TextRange(outcome.selectionStart, outcome.selectionEnd),
                ),
            )
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
            LOG_TAG_UPLOAD,
            "image upload failed: " + (error::class.simpleName ?: "?") + " -> " + (mapped::class.simpleName ?: "?"),
        )
        _state.update { it.copy(isUploading = false, uploadError = mapped, uploadProgress = null) }
    }

    /**
     * #459 — belt-and-braces upload cancellation on ViewModel death (viewModelScope is already
     * cancelled by the lifecycle). Mirrors `PostEditorViewModel.onCleared`.
     */
    override fun onCleared() {
        uploadJob?.cancel()
        super.onCleared()
    }

    @AssistedFactory
    interface Factory {
        fun create(initialRecipient: String?): PrivateMessageComposeViewModel
    }

    private companion object {
        // #405 — idle window after the last edit before the draft is persisted (cf. PostEditorViewModel).
        private const val AUTOSAVE_DEBOUNCE_MS = 750L

        // #459 — diagnostics tag of the upload failures (shared shape with the topic-side editors).
        private const val LOG_TAG_UPLOAD = "MpComposeVM"
    }
}

/**
 * One-shot effects from [PrivateMessageComposeViewModel]. [SubmitSucceeded] carries no thread id :
 * the bddpost.php success response of a new conversation is not topic-shaped (no `sujet_X_PAGE`
 * refresh URL), so the navigation host pops back to the MP list and triggers a refresh — the new
 * conversation appears at the top.
 */
sealed interface PrivateMessageComposeEffect {
    data object SubmitSucceeded : PrivateMessageComposeEffect

    /**
     * #803 pattern — the draft is persisted, the composer may now actually pop (the save is
     * AWAITED before the effect, so navigation can never cancel it). Mirrors
     * `PostEditorEffect.CloseCommitted`.
     */
    data object CloseCommitted : PrivateMessageComposeEffect
}
