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
import fr.forumhfr.redface2.core.model.write.PrivateMessageReplyContext
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
 * ViewModel for replying to a private-message conversation (#301). Receives its route arguments via
 * Hilt assisted injection ([PrivateMessageReplyRequest]). On creation it GETs the conversation page
 * to parse the embedded `bddpost.php` reply form (fresh `hash_check` + hidden fields), then lets the
 * user compose a BBCode reply with the shared toolbar/preview and submit it.
 *
 * Two private-message specifics drive the design (cf. the #301 design notes / independent review):
 *  - The HFR POST success sentence for a private reply is not pinned by a live fixture, so an
 *    unrecognised response maps to [PrivateMessageReplyError.Unexpected] (non-destructive: the draft
 *    is kept and the banner invites the user to verify the conversation), never a hard failure.
 *  - An expired `hash_check` is recovered by silently refetching the form so the user can re-submit,
 *    mirroring the post editor.
 */
@HiltViewModel(assistedFactory = PrivateMessageReplyViewModel.Factory::class)
@Suppress("LongParameterList") // Hilt ctor — one dependency per collaborator (#459 added the upload trio).
class PrivateMessageReplyViewModel @AssistedInject constructor(
    @Assisted private val request: PrivateMessageReplyRequest,
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

    private val _state = MutableStateFlow(PrivateMessageReplyUiState())
    val state: StateFlow<PrivateMessageReplyUiState> = _state.asStateFlow()

    private val _effects: Channel<PrivateMessageReplyEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<PrivateMessageReplyEffect> = _effects.receiveAsFlow()

    private val context = PrivateMessageReplyContext(
        threadId = request.threadId,
        page = request.page.coerceAtLeast(1),
    )

    private var loadedForm: ReplyForm? = null
    private var formJob: Job? = null
    private var submitJob: Job? = null

    /** #405 — content-free draft key for this conversation (private → wiped on logout). */
    private val draftKey: String = EditorDraftKey.mpReply(request.threadId)

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
     * #405 — account that owned this MP editor when it opened, snapshotted from [draftStore] so a
     * mid-edit account switch can't write this session's PRIVATE draft under another account
     * (Codex beta review). Captured in [restoreDraftIfAny]; null until then / when anonymous.
     */
    private var draftOwner: String? = null

    /**
     * #312 — mirror of the persisted « Confirmation avant publication » preference. Collected on
     * init (same DataStore-consumption shape as `TopicViewModel.observeTopicTopBarAutoHide`) and
     * read synchronously at submit time, identical to the post editor.
     */
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

    /** #405 — surface a cached draft on the banner (never auto-applied). Empty drafts are ignored. */
    private fun restoreDraftIfAny() {
        viewModelScope.launch {
            draftOwner = draftStore.currentOwner()
            val body = draftStore.load(draftOwner, draftKey)?.body
            if (!body.isNullOrBlank()) {
                _state.update { it.copy(restorableDraft = body) }
            }
        }
    }

    /**
     * #405 — debounced autosave of the body, flagged `isPrivate = true` so the logout purge wipes
     * it. Blank body → delete the row. The store is a no-op without an active session.
     */
    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            persistDraftNow()
        }
    }

    /**
     * Immediate write of the current body (blank = delete the row, cf. [scheduleAutosave]). Reads
     * [_state] AFTER the debounce delay — the previous shape captured a snapshot at scheduling
     * time, which the #803 dirty-close flush would have re-persisted stale (state-hygiene audit
     * 2026-07-05). Mirrors `PostEditorViewModel.persistDraftNow`.
     */
    private suspend fun persistDraftNow() {
        val body = _state.value.draft.text
        if (body.isBlank()) {
            draftStore.delete(draftOwner, draftKey)
        } else {
            draftStore.save(draftOwner, draftKey, EditorDraftStore.Draft(body = body, isPrivate = true))
        }
    }

    /** #803 pattern — one-shot latch : a committed close is never re-emitted. */
    private var closeRequested = false

    /**
     * #803 pattern (ported from `PostEditorViewModel.onCloseRequested`, state-hygiene audit
     * 2026-07-05) — dirty close : flush the pending debounce so the last keystrokes reach the #405
     * row, THEN let the UI pop (CloseCommitted). Without this, closing the reply editor < 750 ms
     * after typing (system back or the header's back arrow) cancelled the debounce with the
     * ViewModel and silently dropped the tail of the PRIVATE draft.
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
            persistDraftNow()
            _effects.send(PrivateMessageReplyEffect.CloseCommitted)
        }
    }

    /** #405 — apply the cached body to the draft and clear the banner. */
    fun onDraftRestoreRequested() {
        val body = _state.value.restorableDraft ?: return
        _state.update {
            it.withDraftPreview(TextFieldValue(text = body, selection = TextRange(body.length)))
                .copy(restorableDraft = null)
        }
        scheduleAutosave()
    }

    /** #405 — discard the cached draft : delete the row and clear the banner. */
    fun onDraftDiscardRequested() {
        _state.update { it.copy(restorableDraft = null) }
        viewModelScope.launch { draftStore.delete(draftOwner, draftKey) }
    }

    private fun loadForm() {
        formJob?.cancel()
        _state.update { it.copy(isLoadingForm = true, formError = false) }
        formJob = viewModelScope.launch {
            try {
                // #618 — when the user came specifically to MANAGE recipients, the form MUST be the
                // message.php one carrying `newdest` (owner) ; refuse the forum2.php quick-reply
                // fallback (no newdest → no member editor → silently lands on a plain composer). A
                // failed message.php GET then surfaces as a form error (retry) rather than a dead end.
                val form = repository.fetchReplyForm(
                    context,
                    allowEmbeddedFallback = !request.openRecipientManager,
                )
                if (form.isAnonymous) {
                    // Session vanished between opening the thread and replying — treat like a form
                    // error so the user re-authenticates (the reply route is only reachable while
                    // authenticated, so this is rare).
                    loadedForm = null
                    _state.update { it.copy(isLoadingForm = false, formAvailable = false, formError = true) }
                    return@launch
                }
                loadedForm = form
                _state.update { current ->
                    // HFR's private "quick reply" form carries the per-post options as plain hidden
                    // inputs (e.g. `signature=1`), not checkboxes — so `ReplyForm.options` (read from
                    // `checked` attributes) is all-false here. Hydrate from the hidden fields too,
                    // OR'ing with the checkbox view, so the user keeps HFR's default (signature on).
                    //
                    // Hydrate ONLY on the first successful load: a silent refetch after an expired
                    // `hash_check` (InvalidHashCheck) must not reset a toggle the user changed in
                    // between — mirror the post editor's `optionsHydratedFromForm` guard.
                    val hydrateOptions = !current.optionsHydratedFromForm
                    current.copy(
                        isLoadingForm = false,
                        formAvailable = true,
                        formError = false,
                        signatureEnabled = if (hydrateOptions) {
                            form.options.signatureEnabled || form.hiddenFields["signature"] == "1"
                        } else {
                            current.signatureEnabled
                        },
                        smileyDisabled = if (hydrateOptions) {
                            form.options.smileyDisabled || form.hiddenFields["smiley"] == "1"
                        } else {
                            current.smileyDisabled
                        },
                        emailNotificationEnabled = if (hydrateOptions) {
                            form.options.emailNotificationEnabled || form.hiddenFields["emaill"] == "1"
                        } else {
                            current.emailNotificationEnabled
                        },
                        optionsHydratedFromForm = true,
                        // #606 — owner-only member editor. Hydrate the working list from HFR's
                        // `newdest` CSV on the FIRST load only : a silent refetch after an expired
                        // hash_check (InvalidHashCheck) must not clobber a member the user added /
                        // removed in between — same `hydrateOptions` guard as the option toggles.
                        canManageRecipients = form.canManageRecipients,
                        // Re-hydrate from the freshly-parsed `newdest` on the first load AND on any
                        // silent refetch where the user has NOT edited the list : that way a
                        // concurrent membership change made elsewhere is picked up, and — since a
                        // non-dirty submit sends `recipientsOverride = null` — the original `newdest`
                        // is still forwarded verbatim. Only an in-progress edit (`recipientsDirty`)
                        // is preserved across the refetch.
                        recipients = if (hydrateOptions || !current.recipientsDirty) {
                            RecipientCsv.parse(form.manageableRecipients)
                        } else {
                            current.recipients
                        },
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception,
            ) {
                // No raw message reaches the UI (#316): a private form GET can embed the private URL.
                loadedForm = null
                _state.update { it.copy(isLoadingForm = false, formAvailable = false, formError = true) }
            }
        }
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
     * userId comes from the loaded reply form ([ReplyForm.userId], HFR's `find_smilies_timer`
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

    /**
     * #606 — owner adds a member to the DT/MultiMP. The pseudo is appended at the end (HFR keeps
     * insertion order), trimmed of leading / trailing whitespace but otherwise verbatim (case,
     * accents, `+`, internal spaces preserved). A blank entry or an exact-trimmed duplicate of an
     * existing member is refused (no-op). Ignored entirely when the user is not the owner.
     */
    fun onAddRecipient(pseudo: String) {
        if (!_state.value.canManageRecipients) return
        val trimmed = pseudo.trim()
        if (trimmed.isEmpty()) return
        _state.update { current ->
            if (current.recipients.any { it == trimmed }) {
                current
            } else {
                current.copy(recipients = current.recipients + trimmed, recipientsDirty = true)
            }
        }
    }

    /**
     * #606 — owner removes a member. Exact match after trim (`bob` never removes `bob2`). The last
     * remaining member can't be removed — HFR enforces « un destinataire au minimum ». Ignored when
     * the user is not the owner.
     */
    fun onRemoveRecipient(pseudo: String) {
        if (!_state.value.canManageRecipients) return
        val target = pseudo.trim()
        _state.update { current ->
            if (current.recipients.size <= 1 || current.recipients.none { it == target }) {
                current
            } else {
                current.copy(
                    recipients = current.recipients.filterNot { it == target },
                    recipientsDirty = true,
                )
            }
        }
    }

    fun onErrorDismissed() = _state.update { it.copy(submitError = null) }

    /**
     * #312 — confirm path. Closes the dialog and re-runs the submit pipeline with
     * `bypassConfirmation = true` so the real submission executes directly (re-checking the
     * preference here would loop « confirmation → confirmation » forever). The validation
     * guards run again on the latest snapshot, which is safe because the dialog is modal.
     */
    fun onSubmitConfirmed() {
        _state.update { it.copy(showSubmitConfirmation = false) }
        onSubmit(bypassConfirmation = true)
    }

    /** #312 — dismiss path: close the dialog, send nothing, keep the draft. */
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
        // #312 — AFTER every validation gate (we never confirm an unsendable form), BEFORE the
        // real POST: when the preference is on, park the submit behind the confirmation dialog.
        // [onSubmitConfirmed] re-enters with `bypassConfirmation = true`.
        if (!bypassConfirmation && confirmBeforePosting) {
            _state.update { it.copy(showSubmitConfirmation = true) }
            return
        }

        val options = ReplyFormOptions(
            signatureEnabled = snapshot.signatureEnabled,
            smileyDisabled = snapshot.smileyDisabled,
            emailNotificationEnabled = snapshot.emailNotificationEnabled,
        )
        // #606 — only an owner who ACTUALLY edited the member list overrides it. Without an edit
        // (`recipientsDirty == false`) — including every non-owner reply — we pass null so the
        // repository forwards HFR's original `newdest` VERBATIM : a normal owner reply must never
        // round-trip the list through parse → join (which normalises whitespace / drops entries and
        // could silently lose members). The CSV is recomposed with HFR's own `, ` separator.
        val recipientsOverride = if (snapshot.canManageRecipients && snapshot.recipientsDirty) {
            RecipientCsv.join(snapshot.recipients)
        } else {
            null
        }
        _state.update { it.copy(isSubmitting = true, submitError = null) }
        submitJob = viewModelScope.launch {
            val outcome = runCatching {
                repository.submitReply(
                    context = context,
                    form = form,
                    bbcodeContent = snapshot.draft.text,
                    options = options,
                    recipientsOverride = recipientsOverride,
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
                // #405 — the reply reached HFR ; the cached draft is now obsolete. Cancel any
                // pending autosave first so a debounced save can't resurrect the row after delete.
                // AWAITED (not launched) so the nav pop on SubmitSucceeded can't cancel it (Codex).
                autosaveJob?.cancel()
                draftStore.delete(draftOwner, draftKey)
                _state.update { it.copy(isSubmitting = false, submitError = null) }
                _effects.trySend(
                    PrivateMessageReplyEffect.SubmitSucceeded(
                        threadId = context.threadId,
                        page = context.page,
                    ),
                )
            }
            is ReplySubmitResult.Failure -> {
                if (result.reason == ReplyFailureReason.InvalidHashCheck) {
                    // Cached form's hash_check expired — refetch silently and let the user re-submit.
                    loadedForm = null
                    loadForm()
                }
                _state.update { it.copy(isSubmitting = false, submitError = result.reason.toReplyError()) }
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
            // Any other throwable is surfaced as the non-destructive "unexpected response" banner;
            // the draft is preserved so nothing is lost.
            else -> PrivateMessageReplyError.Unexpected
        }
        _state.update { it.copy(isSubmitting = false, submitError = mapped) }
    }

    private fun ReplyFailureReason.toReplyError(): PrivateMessageReplyError = when (this) {
        ReplyFailureReason.EmptyMessage -> PrivateMessageReplyError.Empty
        ReplyFailureReason.InvalidHashCheck -> PrivateMessageReplyError.InvalidHashCheck
        ReplyFailureReason.AntiFlood -> PrivateMessageReplyError.AntiFlood
        ReplyFailureReason.LoginRequired -> PrivateMessageReplyError.LoginRequired
        // TopicLocked has no private-message analogue, and Unknown is the unrecognised-response
        // fallback: both map to the non-destructive "verify the conversation" banner.
        ReplyFailureReason.TopicLocked, ReplyFailureReason.Unknown -> PrivateMessageReplyError.Unexpected
    }

    private fun PrivateMessageReplyUiState.withDraftPreview(updated: TextFieldValue): PrivateMessageReplyUiState =
        copy(
            draft = updated,
            preview = if (isPreviewVisible) previewParser.parsePreview(updated.text) else preview,
            // #459 — a fresh text edit dismisses a stale upload banner — parity with PostEditorState.
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
        fun create(request: PrivateMessageReplyRequest): PrivateMessageReplyViewModel
    }

    private companion object {
        // #405 — idle window after the last edit before the draft is persisted (cf. PostEditorViewModel).
        private const val AUTOSAVE_DEBOUNCE_MS = 750L

        // #459 — diagnostics tag of the upload failures (shared shape with the topic-side editors).
        private const val LOG_TAG_UPLOAD = "MpReplyVM"
    }
}

/**
 * One-shot effects from [PrivateMessageReplyViewModel]. [SubmitSucceeded] tells the navigation host
 * to pop the editor and re-open the conversation (force-reload) so the freshly-sent message shows.
 *
 * [page] is the page the reply was composed from (the live thread page). v1 limitation: HFR's MP POST
 * response is not parsed for a landing page (its refresh URL is not topic-shaped), so a reply that
 * overflows onto a brand-new last page lands the user back on the source page — they page forward to
 * see it. MP threads are short, so this is rare; a topic-style overflow redirect (#226) is not ported.
 *
 * Note: an unrecognised POST response (`Unknown`) deliberately does NOT raise this effect — it keeps
 * the draft and surfaces a "verify the conversation" banner instead of bouncing the user away, since
 * the MP success sentence is not pinned by a live fixture.
 */
sealed interface PrivateMessageReplyEffect {
    data class SubmitSucceeded(val threadId: Int, val page: Int) : PrivateMessageReplyEffect

    /**
     * #803 pattern — the draft is persisted, the editor may now actually pop (the save is AWAITED
     * before the effect, so navigation can never cancel it). Mirrors
     * `PostEditorEffect.CloseCommitted`.
     */
    data object CloseCommitted : PrivateMessageReplyEffect
}

/**
 * #606 — pure CSV codec for the DT/MultiMP member list HFR ships in `newdest`. Kept separate from
 * the ViewModel so it is trivially unit-testable and shares no mutable state.
 *
 * - [parse] splits on `,`, trims each element's surrounding whitespace and drops empties, while
 *   preserving order, case, accents, `+` and any internal spaces (« Bébé Yoda », « stitch+ »). A
 *   null / blank CSV (a non-owner form, or HFR sending an empty value) yields an empty list.
 * - [join] recomposes with HFR's own `, ` separator so the POST `newdest` mirrors the field HFR
 *   prefills.
 */
internal object RecipientCsv {
    private const val SEPARATOR = ", "

    fun parse(csv: String?): List<String> =
        csv.orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun join(recipients: List<String>): String = recipients.joinToString(SEPARATOR)
}
