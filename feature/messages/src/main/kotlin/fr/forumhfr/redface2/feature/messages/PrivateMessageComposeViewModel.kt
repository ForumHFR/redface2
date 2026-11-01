package fr.forumhfr.redface2.feature.messages

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
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.SmileyPickerCloseCause
import fr.forumhfr.redface2.core.domain.diagnostics.recordImagePickerEvent
import fr.forumhfr.redface2.core.domain.diagnostics.recordImagesPicked
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.editor.EditorDelivery
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftSession
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionBinding
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionCallbacks
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionEnvironment
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionErrors
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.SubmissionRequest
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.smiley.HfrFavoriteSmileyRepository
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollections
import fr.forumhfr.redface2.core.domain.smiley.SmileyRepository
import fr.forumhfr.redface2.core.domain.upload.ImageUploadReader
import fr.forumhfr.redface2.core.domain.upload.UploadException
import fr.forumhfr.redface2.core.domain.upload.UploadFailureDiagnostics
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.SmileyFavoriteMutations
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerFavoriteSources
import fr.forumhfr.redface2.core.ui.editor.SmileyWikiKeywordsSource
import fr.forumhfr.redface2.core.ui.editor.UploadError
import fr.forumhfr.redface2.core.ui.editor.UploadProgress
import fr.forumhfr.redface2.core.ui.editor.applyBbcodeAction
import fr.forumhfr.redface2.core.ui.editor.imageInsertBbcodeOrNull
import fr.forumhfr.redface2.core.ui.editor.insertBbcodeToken
import fr.forumhfr.redface2.core.ui.editor.pickedImagesForUpload
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * ED-87/88/161/170/172: new MP with frozen recipients, subject and body before durable submission.
 * [initialRecipient] seeds routing once; uncertainty retains every field and the list check (ED-112).
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
    private val clock: DiagnosticsClock,
    smileyRepository: SmileyRepository,
    hfrFavoriteSmileyRepository: HfrFavoriteSmileyRepository,
    smileyLocalCollections: SmileyLocalCollections,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val initialRecipients = initialRecipient.orEmpty().trim()
    private val _state = MutableStateFlow(
        PrivateMessageComposeUiState(
            recipients = initialRecipients,
            recipientsHydratedContent = initialRecipients.takeIf { it.isNotBlank() },
        ),
    )
    val state: StateFlow<PrivateMessageComposeUiState> = _state.asStateFlow()

    private val _effects: Channel<PrivateMessageComposeEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<PrivateMessageComposeEffect> = _effects.receiveAsFlow()

    private var loadedForm: ReplyForm? = null
        set(value) {
            field = value
            if (value == null) smileyPicker.refreshFavorites(null)
        }

    /**
     * #387 — shared smiley picker (Favorites + Standard + Wiki). The controller owns
     * the picker state machine (debounce, race guards) ; this ViewModel only handles insertion.
     * userId comes from the loaded compose form ([ReplyForm.userId], HFR's `find_smilies_timer`
     * second argument). The controller falls back to 0 while the form is still loading
     * (Codex review, PR #440).
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
        userId = { loadedForm?.userId },
    )

    private var formJob: Job? = null

    /** #405 — single content-free key for the new-conversation composer (private → wiped on logout). */
    private val draftKey: String = EditorDraftKey.mpCompose()

    /** #405 — debounced autosave coroutine ; relaunched (cancelling the previous) on each edit. */
    private val submission = EditorSubmissionBinding(
        environment = EditorSubmissionEnvironment(viewModelScope, clock, diagnostics),
        drafts = EditorDraftSession(draftStore, draftKey),
        callbacks = EditorSubmissionCallbacks<PrivateMessageComposeEffect, PrivateMessageReplyError>(
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
        errors = EditorSubmissionErrors(
            uncertain = PrivateMessageReplyError.Uncertain,
            draftWriteFailed = PrivateMessageReplyError.DraftWriteFailed,
            sessionExpired = PrivateMessageReplyError.SessionExpired,
            unknown = PrivateMessageReplyError.Uncertain,
        ),
    )

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

    init {
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
                // and pending autosave so this session's image URL / PRIVATE draft is never
                // attributed to the new account (same Codex-reviewed rule as PostEditorViewModel).
                if (activeUserId != null && newUserId != activeUserId) {
                    uploadJob?.cancel()
                    submission.accountChanged()
                    _state.update { it.copy(isUploading = false, uploadProgress = null) }
                }
                activeUserId = newUserId
            }
        }
        viewModelScope.launch {
            restoreDraftIfAny()
            loadForm()
        }
    }

    fun retryFormLoad() = loadForm()

    /**
     * #405/#1415 — offer each cached body/subject/recipient version until the user handles it. A
     * routing or server-side `dest` prefill remains eligible while untouched; a real edit suppresses
     * the initial offer. The fingerprint is recorded only after Ignore, so a restored row remains
     * recoverable if process death loses the live editor state before autosave.
     * ED-81/170: durable status wins over every offer and server prefill.
     */
    private suspend fun restoreDraftIfAny() {
        val entry = submission.restore(draftKey) ?: return
        if (submission.blocksMutation) return
        val draft = entry.draft
        if (entry.attempt != null) {
            _state.update {
                it.withDraftPreview(TextFieldValue(draft.body, TextRange(draft.body.length))).copy(
                    subject = draft.subject.orEmpty(), recipients = draft.recipients.orEmpty(),
                    restorableSubject = null, restorableRecipients = null,
                    restorableDraft = null, submitError = PrivateMessageReplyError.Uncertain,
                )
            }
        } else {
            val hasContent = draft.body.isNotBlank() ||
                !draft.subject.isNullOrBlank() || !draft.recipients.isNullOrBlank()
            val fingerprint = draft.restoreOfferFingerprint()
            val ignored = savedStateHandle.get<String>(DRAFT_RESTORE_OFFER_FINGERPRINT_KEY) == fingerprint
            if (hasContent && !ignored && _state.value.canOfferDraftRestore(draft)) {
                _state.update {
                    it.copy(
                        restorableDraft = draft.body, restorableSubject = draft.subject,
                        restorableRecipients = draft.recipients,
                    )
                }
            }
        }
    }

    private fun PrivateMessageComposeUiState.canOfferDraftRestore(
        cached: EditorDraftStore.Draft,
    ): Boolean =
        !matches(cached) &&
            draft.text.isBlank() &&
            subject.isBlank() &&
            (recipients.isBlank() || recipients == recipientsHydratedContent)

    private fun PrivateMessageComposeUiState.matches(cached: EditorDraftStore.Draft): Boolean =
        draft.text == cached.body &&
            subject == cached.subject.orEmpty() &&
            recipients == cached.recipients.orEmpty()

    /**
     * #405 — debounced autosave of recipients + subject + body, flagged `isPrivate = true` so the
     * logout purge wipes it. ED-170: empty content is cleared through CAS, never a blind delete.
     */
    private fun scheduleAutosave() = submission.autosave(::draftSnapshot)

    private fun draftSnapshot(): EditorDraftStore.Draft? {
        val snapshot = _state.value
        if (snapshot.draft.text.isBlank() && snapshot.restorableDraft != null) return null
        return EditorDraftStore.Draft(
            body = snapshot.draft.text, subject = snapshot.subject.ifBlank { null },
            recipients = snapshot.recipients.ifBlank { null }, isPrivate = true,
        )
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
        if (submission.blocksClose || closeRequested) return
        submission.disarm()
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
            _effects.send(PrivateMessageComposeEffect.CloseCommitted)
        }
    }

    /** #405 — append the cached body, fill only blank routing fields, and clear the banner. */
    fun onDraftRestoreRequested() {
        if (submission.blocksMutation) return
        submission.disarm()
        val offeredBody = _state.value.restorableDraft ?: return
        _state.update { current ->
            val body = if (current.draft.text.isBlank() || current.draft.text == offeredBody) {
                offeredBody
            } else {
                current.draft.text.trimEnd() + "\n\n" + offeredBody
            }
            current.withDraftPreview(TextFieldValue(text = body, selection = TextRange(body.length)))
                .copy(
                    subject = current.subject.ifBlank { current.restorableSubject.orEmpty() },
                    recipients = current.recipients.ifBlank { current.restorableRecipients.orEmpty() },
                    restorableDraft = null,
                    restorableSubject = null,
                    restorableRecipients = null,
                )
        }
        scheduleAutosave()
    }

    /** ED-170: clear the offered content through CAS, preserving a newer generation. */
    fun onDraftDiscardRequested() {
        if (submission.blocksMutation) return
        submission.disarm()
        val snapshot = _state.value
        val body = snapshot.restorableDraft ?: return
        markDraftRestoreOfferIgnored(
            EditorDraftStore.Draft(
                body = body,
                subject = snapshot.restorableSubject,
                recipients = snapshot.restorableRecipients,
                isPrivate = true,
            ),
        )
        _state.update {
            it.copy(restorableDraft = null, restorableSubject = null, restorableRecipients = null)
        }
        viewModelScope.launch { submission.flush(EditorDraftStore.Draft(body = "", isPrivate = true)) }
    }

    private fun markDraftRestoreOfferIgnored(draft: EditorDraftStore.Draft) {
        savedStateHandle[DRAFT_RESTORE_OFFER_FINGERPRINT_KEY] = draft.restoreOfferFingerprint()
    }

    private fun loadForm() {
        if (!submission.isInitialized) return
        formJob?.cancel()
        val favoriteStamp = smileyPicker.favoriteFormStamp()
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
                smileyPicker.refreshFavorites(favoriteStamp)
                _state.update { current ->
                    // Hydrate ONLY on the first successful load — a silent refetch after an
                    // expired hash_check must not clobber toggles (or a recipient edit) the user
                    // changed in between. Mirror of the reply editor's guard.
                    if (submission.blocksMutation) return@update current.copy(isLoadingForm = false)
                    val hydrate = !current.optionsHydratedFromForm
                    val nextRecipients = if (hydrate && current.recipients.isBlank() &&
                        current.submission !is EditorSubmissionState.Uncertain) {
                        form.hiddenFields["dest"].orEmpty()
                    } else {
                        current.recipients
                    }
                    val keepRestoreOffer = current.restoreOfferDiffersFrom(nextRecipients)
                    current.copy(
                        isLoadingForm = false,
                        formAvailable = true,
                        formError = false,
                        // The composer's `dest` text input comes back through hiddenFields ; a
                        // server-side prefill (dest= in the GET) seeds the local field once.
                        recipients = nextRecipients,
                        recipientsHydratedContent = current.hydratedRecipientsContent(
                            nextRecipients = nextRecipients,
                            hydrate = hydrate,
                        ),
                        restorableDraft = current.restorableDraft.takeIf { keepRestoreOffer },
                        restorableSubject = current.restorableSubject.takeIf { keepRestoreOffer },
                        restorableRecipients = current.restorableRecipients.takeIf { keepRestoreOffer },
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
            } catch (_: IOException) {
                formLoadFailed()
            } catch (_: IllegalStateException) {
                formLoadFailed()
            } catch (_: IllegalArgumentException) {
                formLoadFailed()
            }
        }
    }

    private fun PrivateMessageComposeUiState.restoreOfferDiffersFrom(nextRecipients: String): Boolean =
        restorableDraft != null &&
            (
                restorableDraft != draft.text ||
                    restorableSubject.orEmpty() != subject ||
                    restorableRecipients.orEmpty() != nextRecipients
                )

    private fun PrivateMessageComposeUiState.hydratedRecipientsContent(
        nextRecipients: String,
        hydrate: Boolean,
    ): String? = if (hydrate && recipients.isBlank() && nextRecipients.isNotBlank()) {
        nextRecipients
    } else {
        recipientsHydratedContent
    }

    fun onRecipientsChanged(value: String) {
        if (submission.blocksMutation) return
        submission.disarm()
        _state.update {
            it.copy(
                recipients = value,
                restorableRecipients = if (it.restorableDraft != null) {
                    value.ifBlank { null }
                } else {
                    it.restorableRecipients
                },
            )
        }
        scheduleAutosave()
    }

    fun onSubjectChanged(value: String) {
        if (submission.blocksMutation) return
        submission.disarm()
        _state.update {
            // HFR's maxlength=70 — truncate instead of erroring so pasted text just clips.
            val subject = value.take(PrivateMessageComposeUiState.SUBJECT_MAX_LENGTH)
            it.copy(
                subject = subject,
                restorableSubject = if (it.restorableDraft != null) {
                    subject.ifBlank { null }
                } else {
                    it.restorableSubject
                },
            )
        }
        scheduleAutosave()
    }

    private fun formLoadFailed() {
        loadedForm = null
        _state.update { it.copy(isLoadingForm = false, formAvailable = false, formError = true) }
    }

    fun onContentChanged(value: TextFieldValue) {
        if (submission.blocksMutation) return
        submission.disarm()
        val textChanged = value.text != _state.value.draft.text
        _state.update { it.withDraftPreview(value) }
        if (textChanged) scheduleAutosave()
    }

    fun onToolbarAction(action: BbcodeAction) {
        if (submission.blocksMutation) return
        submission.disarm()
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

    fun onSmileySelected(token: String) {
        if (submission.blocksMutation) return
        submission.disarm()
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
        smileyPicker.dismiss(SmileyPickerCloseCause.INSERTION)
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

    fun onToggleSignature(enabled: Boolean) {
        if (submission.blocksMutation) return
        submission.disarm()
        _state.update { it.copy(signatureEnabled = enabled) }
    }

    fun onToggleSmileyDisabled(disabled: Boolean) {
        if (submission.blocksMutation) return
        submission.disarm()
        _state.update { it.copy(smileyDisabled = disabled) }
    }

    fun onToggleEmailNotification(enabled: Boolean) {
        if (submission.blocksMutation) return
        submission.disarm()
        _state.update { it.copy(emailNotificationEnabled = enabled) }
    }

    fun onErrorDismissed() = submission.acknowledge(::scheduleAutosave)

    /** ED-164/166: callbacks carry the identity displayed by the current composition. */
    fun onSubmitConfirmed(token: ArmToken?) {
        val snapshot = _state.value
        val form = loadedForm ?: return
        if (!snapshot.canSubmit || closeRequested) return
        val permit = submission.confirm(token)
        if (permit is SubmissionRequest.Accepted) launchSubmission(permit, snapshot, form)
    }

    fun onSubmitConfirmationDismissed() = submission.disarm()

    fun onSubmit(timeoutMillis: Long = 4_000L) {
        val snapshot = _state.value
        val form = loadedForm ?: return
        if (!snapshot.canSubmit || closeRequested) return
        if (form.isAnonymous) {
            _state.update { it.copy(submitError = PrivateMessageReplyError.LoginRequired) }
        } else {
            val permit = submission.request(timeoutMillis, snapshot.canSubmit)
            if (permit is SubmissionRequest.Accepted) launchSubmission(permit, snapshot, form)
        }
    }

    /** ED-88/161/169/170/172: frozen headers/body, awaited attempt, existing effect exactly once. */
    private fun launchSubmission(
        permit: SubmissionRequest.Accepted,
        snapshot: PrivateMessageComposeUiState,
        form: ReplyForm,
    ) {
        submission.launch(permit, EditorDraftStore.Draft(
            body = snapshot.draft.text, subject = snapshot.subject, recipients = snapshot.recipients, isPrivate = true,
        )) {
            val options = ReplyFormOptions(
                signatureEnabled = snapshot.signatureEnabled, smileyDisabled = snapshot.smileyDisabled,
                emailNotificationEnabled = snapshot.emailNotificationEnabled,
            )
            val result = repository.submitNewMessage(
                form = form, recipients = snapshot.recipients.trim(), subject = snapshot.subject.trim(),
                bbcodeContent = snapshot.draft.text, options = options,
            )
            submissionDelivery(result)
        }
    }

    private fun submissionDelivery(
        result: ReplySubmitResult,
    ): EditorDelivery<PrivateMessageComposeEffect, PrivateMessageReplyError> =
        when (result) {
            is ReplySubmitResult.Success -> EditorDelivery(
                effect = PrivateMessageComposeEffect.SubmitSucceeded,
            )
            is ReplySubmitResult.Failure -> {
                if (result.reason == ReplyFailureReason.InvalidHashCheck) {
                    loadedForm = null
                    viewModelScope.launch {
                        yield()
                        loadForm()
                    }
                }
                EditorDelivery(error = result.reason.toComposeError())
            }
        }

    private fun ReplyFailureReason.toComposeError(): PrivateMessageReplyError = when (this) {
        ReplyFailureReason.EmptyMessage -> PrivateMessageReplyError.Empty
        ReplyFailureReason.InvalidHashCheck -> PrivateMessageReplyError.InvalidHashCheck
        ReplyFailureReason.AntiFlood -> PrivateMessageReplyError.AntiFlood
        ReplyFailureReason.LoginRequired -> PrivateMessageReplyError.LoginRequired
        // Unknown covers every unpinned server answer — including a rejected recipient — and
        // must stay non-destructive (cf. class KDoc). TopicLocked has no composer analogue.
        ReplyFailureReason.TopicLocked -> PrivateMessageReplyError.Unexpected
        ReplyFailureReason.Unknown -> PrivateMessageReplyError.Uncertain
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


    /** #988/#1420 — records the picker boundary and surfaces a callback without images. */
    fun onImagePickerEvent(event: ImagePickerEvent) {
        if (submission.blocksMutation) return
        diagnostics.recordImagePickerEvent(event)
        if (event is ImagePickerEvent.Result) {
            val pickedImages = pickedImagesForUpload(event.contract, event.uris)
            val showEmptyPickerBanner = pickedImages.isEmpty() && uploadJob?.isActive != true
            onImagesPicked(pickedImages)
            if (showEmptyPickerBanner) {
                _state.update { it.copy(uploadError = UploadError.NoImageReceived) }
            }
        }
    }

    /**
     * #459 — pick→read→upload→insert for this MP composer, same proven contract as
     * `PostEditorViewModel.onImagesPicked` (multi-image #490): sequential batch, one `[img]` per
     * success in pick order (leading newline from the 2nd on), autosave after each insertion, stop
     * at the first failure with the typed [UploadError] surfaced. A blank list, an anonymous
     * session or an upload already in flight are ignored.
     */
    private fun canStartUpload(userId: String?, targets: List<String>): Boolean =
        !submission.blocksMutation && uploadJob?.isActive != true && userId != null && targets.isNotEmpty()

    fun onImagesPicked(uris: List<String>) {
        diagnostics.recordImagesPicked(uris.size)
        val userId = activeUserId
        val targets = uris.filter { it.isNotBlank() }
        // One guard (ReturnCount): nothing in flight already, an authenticated owner, a non-empty pick.
        if (!canStartUpload(userId, targets)) return
        submission.disarm()
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
                    uploadRepository.uploadWithCurrentProvider(image, checkNotNull(userId))
                }
                val uploaded = outcome.getOrElse { error ->
                    handleUploadFailure(error)
                    return@launch
                }
                if (submission.blocksMutation || activeUserId != userId) break
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
        if (submission.blocksMutation) return
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
            UploadFailureDiagnostics.describe(error, mapped.toString()),
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
        private const val DRAFT_RESTORE_OFFER_FINGERPRINT_KEY = "draft_restore_offer_fingerprint"


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
