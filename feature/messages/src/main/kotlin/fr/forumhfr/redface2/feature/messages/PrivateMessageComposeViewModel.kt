package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.coroutines.DefaultDispatcher
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
import fr.forumhfr.redface2.core.domain.editor.EditorSendOutcome
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
import fr.forumhfr.redface2.core.domain.upload.UploadFailureDiagnostics
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.editor.EditorPreviewState
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.EditorErrorSurface
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorRecipientInput
import fr.forumhfr.redface2.core.ui.editor.EditorRecipientState
import fr.forumhfr.redface2.core.ui.editor.EditorSessionField
import fr.forumhfr.redface2.core.ui.editor.EditorSubjectInput
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
 * ED-87/88/161/170/172: new MP with frozen recipients, subject and body before durable submission.
 * [initialRecipient] seeds routing once; uncertainty retains every field and the list check (ED-112).
 */
@HiltViewModel(assistedFactory = PrivateMessageComposeViewModel.Factory::class)
@Suppress("LongParameterList") // Hilt ctor — one dependency per collaborator (#459 added the upload trio).
class PrivateMessageComposeViewModel @AssistedInject constructor(
    @Assisted private val initialRecipient: String?,
    private val repository: PrivateMessageWriteRepository,
    private val previewParser: BbcodePreviewParser,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
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
    savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    /** Body and IME state belong to this ViewModel, never to a Bundle saver. */
    val bodyFieldState: TextFieldState get() = draftSession.bodyFieldState
    val bodyScrollState: ScrollState get() = draftSession.bodyScrollState
    private val _state = MutableStateFlow(PrivateMessageComposeUiState(isInitializing = true))
    val state: StateFlow<PrivateMessageComposeUiState> = _state.asStateFlow()
    /** C2 input, consumed by the header at C4; C1 remains the sole content/persistence owner. */
    val recipientInput = EditorRecipientInput({ state.value.recipientState }, ::onRecipientStateChanged)
    val subjectInput = EditorSubjectInput(
        PrivateMessageComposeUiState.SUBJECT_MAX_LENGTH, { state.value.subject }, ::onSubjectChanged,
    )
    internal val editorEvents = PrivateMessageComposeEvents()

    /** Deliver transitions synchronously, including identical refusals before recomposition. */
    private fun updateState(transform: (PrivateMessageComposeUiState) -> PrivateMessageComposeUiState) {
        val previous = _state.value
        _state.update(transform)
        editorEvents.open(submission.session)
        editorEvents.observe(previous, _state.value)
    }

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
        callbacks = EditorSubmissionCallbacks<PrivateMessageComposeEffect, EditorError>(
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
        errors = EditorSubmissionErrors(
            uncertain = EditorErrors.uncertain(EditorErrorSurface.MessageCompose),
            draftWriteFailed = EditorErrors.draftWriteFailed,
            draftSaveFailed = EditorErrors.draftSaveFailed,
            sessionExpired = EditorErrors.sessionExpired,
            unknown = EditorErrors.uncertain(EditorErrorSurface.MessageCompose),
            network = EditorErrors.network,
            preparationFailed = EditorErrors.invalidForm,
        ),
    )

    private val draftSession: PrivateMessageComposeDraftSession = PrivateMessageComposeDraftSession(
        saved = savedStateHandle, submission = submission, scope = viewModelScope, draftKey = draftKey,
        state = PrivateMessageDraftState(read = { _state.value }, update = ::updateState),
        clearToolSelection = { chromeMemory.host.focus.clearToolSelection() },
    )
    internal val bodyCaretRevealResetKey get() = draftSession.bodyCaretRevealResetKey
    // ED-63/C8: VM lifetime only; visibility remains in the existing preview pipeline.
    internal val previewLayout by lazy { EditorPreviewState(userPreferencesRepository, viewModelScope) }
    // Draft-session construction only registers callbacks; restore/hydrate run from init below.
    internal val chromeMemory: PrivateMessageComposeChromeMemory =
        PrivateMessageComposeChromeMemory(savedStateHandle, draftSession.isRecreation)

    /**
     * #459 — lowercased pseudo of the authenticated session, or null when anonymous. The upload
     * providers require an HFR session for the trace ; an anonymous pick is silently ignored.
     * Mirrors `PostEditorViewModel.activeUserId`.
     */
    private var activeUserId: String? = null
    private var authObserved = false

    /** #459 — in-flight image upload job ; one at a time, cancelled on [onCleared]. */
    private var uploadJob: Job? = null

    /**
     * #459 — mirror of the persisted [EditorImageInsert] preference, read synchronously at insert
     * time (cf. `PostEditorViewModel.imageInsertMode`). Default mirrors the repository's REDUCED
     * default until the first emission lands.
     */
    private var imageInsertMode: EditorImageInsert = EditorImageInsert.REDUCED

    /** Read the live controller on every input, including the frame before readOnly is recomposed. */
    val bodyInputTransformation = InputTransformation {
        // Only synchronous submission metadata changes here; no body edit or intent is dispatched.
        val changesBody = asCharSequence().toString() != bodyFieldState.text.toString()
        if (!acceptMutation(blockDuringUpload = true)) revertAllChanges()
        else if (changesBody) draftSession.decide(EditorSessionField.Body)
    }

    private fun acceptMutation(blockDuringUpload: Boolean = false): Boolean {
        val resuming = _state.value.isInitializing && draftSession.isRecreation
        val uploadBlocked = blockDuringUpload && _state.value.isUploading
        if (resuming || submission.blocksMutation || uploadBlocked) return false
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
                // and pending autosave so this session's image URL / PRIVATE draft is never
                // attributed to the new account (same Codex-reviewed rule as PostEditorViewModel).
                if (authObserved && newUserId != activeUserId) {
                    uploadJob?.cancel()
                    submission.accountChanged()
                    chromeMemory.reset()
                    formJob?.cancel()
                    loadedForm = null
                    draftSession.invalidate()
                    updateState {
                        // This owner-bound editor is revoked; navigation opens the next account's editor.
                        it.copy(isLoadingForm = false, formAvailable = false,
                            isUploading = false, uploadProgress = null,
                            restorableDraft = null, draftOffers = emptyList(), restorableSubject = null, restorableRecipients = null)
                    }
                }
                authObserved = true
                activeUserId = newUserId
            }
        }
        viewModelScope.launch {
            draftSession.restore(initialRecipient)
            loadForm()
        }
    }

    private fun observeBody() {
        viewModelScope.launch {
            snapshotFlow { bodyFieldState.text.toString() }.collect { body ->
                draftSession.onBodyChanged(body)
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

    fun retryFormLoad() = loadForm()

    private fun scheduleAutosave() = draftSession.scheduleAutosave()
    fun onStarted() = draftSession.onStarted()
    fun onStopped() = draftSession.onStopped()
    suspend fun restoreBodyScroll() = draftSession.restoreBodyScroll()

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
                submission.flush(draftSession::draftSnapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                // Best effort — the previous debounced write is what remains.
            }
            _effects.send(PrivateMessageComposeEffect.CloseCommitted)
        }
    }

    fun onDraftRestoreRequested() {
        if (!acceptMutation(blockDuringUpload = true)) return
        draftSession.restoreOfferedDraft()
    }

    fun onDraftDiscardRequested() {
        if (!acceptMutation()) return
        draftSession.discardOfferedDraft()
    }

    private fun loadForm() {
        if (!submission.isInitialized) return
        formJob?.cancel()
        val favoriteStamp = smileyPicker.favoriteFormStamp()
        updateState { it.copy(isLoadingForm = true, formError = false) }
        formJob = viewModelScope.launch {
            try {
                val form = repository.fetchComposeForm(prefilledRecipient = initialRecipient)
                if (!submission.isInitialized) return@launch
                if (form.isAnonymous) {
                    loadedForm = null
                    updateState { it.copy(isLoadingForm = false, formAvailable = false, formError = true) }
                    return@launch
                }
                loadedForm = form
                smileyPicker.refreshFavorites(favoriteStamp)
                draftSession.hydrate(form)
                val liveBody = bodyFieldState.text.toString()
                updateState { it.withLoadedForm(form, liveBody) }
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

    private fun PrivateMessageComposeUiState.withLoadedForm(
        form: ReplyForm,
        liveBody: String,
    ): PrivateMessageComposeUiState {
        // Hydrate ONLY on the first successful load — a silent refetch after an expired
        // hash_check must not clobber toggles (or a recipient edit) the user changed in between.
        // Mirror of the reply editor's guard.
        if (this@PrivateMessageComposeViewModel.submission.blocksMutation) return copy(isLoadingForm = false)
        val hydrate = !optionsHydratedFromForm
        val nextRecipients = recipients
        val keepRestoreOffer = restoreOfferDiffersFrom(liveBody)
        return copy(
            isLoadingForm = false,
            formAvailable = true,
            formError = false,
            submitError = submitError.afterFormLoaded(),
            // The composer's `dest` text input comes back through hiddenFields ; a
            // server-side prefill (dest= in the GET) seeds the local field once.
            recipients = nextRecipients,
            restorableDraft = restorableDraft.takeIf { keepRestoreOffer },
            restorableSubject = restorableSubject.takeIf { keepRestoreOffer },
            restorableRecipients = restorableRecipients.takeIf { keepRestoreOffer },
            signatureEnabled = if (hydrate) {
                form.options.signatureEnabled || form.hiddenFields["signature"] == "1"
            } else {
                signatureEnabled
            },
            smileyDisabled = if (hydrate) {
                form.options.smileyDisabled || form.hiddenFields["smiley"] == "1"
            } else {
                smileyDisabled
            },
            emailNotificationEnabled = if (hydrate) {
                form.options.emailNotificationEnabled || form.hiddenFields["emaill"] == "1"
            } else {
                emailNotificationEnabled
            },
            optionsHydratedFromForm = true,
        )
    }

    private fun PrivateMessageComposeUiState.restoreOfferDiffersFrom(
        liveBody: String,
    ): Boolean =
        restorableDraft != null &&
            (
                restorableDraft != liveBody ||
                    restorableSubject.orEmpty() != subject ||
                    EditorRecipientState.decode(restorableRecipients) != recipientState
                )

    fun onRecipientStateChanged(value: EditorRecipientState) {
        if (!acceptMutation()) return
        draftSession.changeRecipients(value)
    }

    fun onSubjectChanged(value: String) {
        if (!acceptMutation()) return
        draftSession.changeSubject(value.take(PrivateMessageComposeUiState.SUBJECT_MAX_LENGTH))
    }

    private fun formLoadFailed() {
        if (!submission.isInitialized) return
        loadedForm = null
        updateState { it.copy(isLoadingForm = false, formAvailable = false, formError = true) }
    }

    fun onToolbarAction(action: BbcodeAction) {
        if (!acceptMutation(blockDuringUpload = true)) return
        draftSession.decide(EditorSessionField.Body)
        chromeMemory.host.focus.clearToolSelection()
        bodyFieldState.editBbcode { text, start, end -> applyBbcodeAction(action, text, start, end) }
        if (action == BbcodeAction.Image) draftSession.persistDraftNow() else scheduleAutosave()
    }

    fun onSmileySelected(token: String) {
        if (!acceptMutation(blockDuringUpload = true)) return
        draftSession.decide(EditorSessionField.Body)
        chromeMemory.host.focus.clearToolSelection()
        bodyFieldState.editBbcode { text, start, end -> insertBbcodeToken(token, text, start, end) }
        smileyPicker.dismiss(SmileyPickerCloseCause.INSERTION)
        scheduleAutosave()
    }

    fun onTogglePreview() {
        updateState {
            it.copy(
                isPreviewVisible = !it.isPreviewVisible,
                preview = if (it.isPreviewVisible) PostContent(blocks = emptyList()) else it.preview,
            )
        }
    }

    fun onToggleSignature(enabled: Boolean) {
        if (!acceptMutation()) return
        updateState { it.copy(signatureEnabled = enabled) }
    }

    fun onToggleSmileyDisabled(disabled: Boolean) {
        if (!acceptMutation()) return
        updateState { it.copy(smileyDisabled = disabled) }
    }

    fun onToggleEmailNotification(enabled: Boolean) {
        if (!acceptMutation()) return
        updateState { it.copy(emailNotificationEnabled = enabled) }
    }

    /** ED-45/170: reached only from the host's explicit Masquer of the bound uncertain delivery. */
    fun onErrorDismissed() = submission.acknowledge(::scheduleAutosave)

    /** ED-45/170: explicit Masquer of a failed attempt write; releases deferred draft information, no write. */
    fun onDraftWriteFailureDismissed() = submission.acknowledgeLocal()

    /** ED-164/166: callbacks carry the identity displayed by the current composition. */
    fun onSubmitConfirmed(token: ArmToken?) {
        val snapshot = _state.value.copy(bodyText = bodyFieldState.text.toString())
        val form = loadedForm ?: return
        if (!snapshot.canSubmit || closeRequested) return
        val permit = submission.confirm(token)
        if (permit is SubmissionRequest.Accepted) launchSubmission(permit, snapshot, form)
    }

    fun onSubmitConfirmationDismissed() {
        submission.disarm()
    }

    fun onSubmit(timeoutMillis: Long = 4_000L) {
        val snapshot = _state.value.copy(bodyText = bodyFieldState.text.toString())
        val form = loadedForm ?: return
        if (!snapshot.sendActivatable || closeRequested || submission.blocksMutation) return
        val error = snapshot.headerError ?: EditorErrors.loginRequired.takeIf { form.isAnonymous }
        if (error != null) {
            // ED-35/170: only a local refusal resets the slot, so a repeat is a new event and arming keeps the warning.
            updateState { it.copy(submitError = null) }
            updateState { it.copy(submitError = error) }
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
            body = snapshot.bodyText, subject = snapshot.subject,
            recipients = snapshot.recipientState.encode(), isPrivate = true,
        )) {
            val options = ReplyFormOptions(
                signatureEnabled = snapshot.signatureEnabled, smileyDisabled = snapshot.smileyDisabled,
                emailNotificationEnabled = snapshot.emailNotificationEnabled,
            )
            val result = repository.submitNewMessage(
                form = form, recipients = snapshot.recipientState.csv.trim(), subject = snapshot.subject.trim(),
                bbcodeContent = snapshot.bodyText, options = options,
            )
            submissionDelivery(result)
        }
    }

    private fun submissionDelivery(
        result: ReplySubmitResult,
    ): EditorDelivery<PrivateMessageComposeEffect, EditorError> =
        when (result) {
            is ReplySubmitResult.Success -> EditorDelivery(
                effect = PrivateMessageComposeEffect.SubmitSucceeded,
            )
            is ReplySubmitResult.Failure -> {
                EditorDelivery(
                    outcome = if (result.reason == ReplyFailureReason.Unknown ||
                        result.reason == ReplyFailureReason.TopicLocked) {
                        EditorSendOutcome.UNCERTAIN
                    } else {
                        EditorSendOutcome.CERTAIN_FAILURE
                    },
                    error = result.reason.toComposeError(),
                    afterSettled = if (result.reason == ReplyFailureReason.InvalidHashCheck) {
                        {
                            loadedForm = null
                            loadForm()
                        }
                    } else { null },
                )
            }
        }

    private fun ReplyFailureReason.toComposeError(): EditorError = toEditorError(EditorErrorSurface.MessageCompose)

    /** #1573 — cancellation only records diagnostics; a successful empty callback retains #1128 feedback. */
    fun onImagePickerEvent(event: ImagePickerEvent) {
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
     * #459 — pick→read→upload→insert for this MP composer, same proven contract as
     * `PostEditorViewModel.onImagesPicked` (multi-image #490): sequential batch, one `[img]` per
     * success in pick order (leading newline from the 2nd on), autosave after each insertion, stop
     * at the first failure with the typed [EditorError] surfaced. A blank list, an anonymous
     * session or an upload already in flight are ignored.
     */
    private fun canStartUpload(userId: String?, targets: List<String>): Boolean =
        !submission.blocksMutation && uploadJob?.isActive != true && userId != null && targets.isNotEmpty()

    fun onImagesPicked(uris: List<String>) {
        diagnostics.recordImagesPicked(uris.size)
        val userId = activeUserId
        val received = uris.filter { it.isNotBlank() }
        val targets = received.take(MAX_IMAGES_PER_UPLOAD)
        // One guard (ReturnCount): nothing in flight already, an authenticated owner, a non-empty pick.
        if (!canStartUpload(userId, targets)) return
        if (!acceptMutation()) return
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
                if (submission.blocksMutation || activeUserId != userId) break
                val bbcode = imageInsertBbcodeOrNull(
                    fullUrl = uploaded.imageUrl,
                    displayUrl = uploaded.resizedUrl ?: uploaded.imageUrl,
                    mode = mode,
                )
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

    /**
     * #459 — inserts an already-built image BBCode fragment at the caret, WITHOUT surrounding
     * spaces (an image is self-contained). [leadingNewline] prefixes a newline when the caret is
     * not already at a line start, so consecutive uploads land on their own lines. Mirrors
     * `PostEditorViewModel.insertImageBbcodeAtCaret`.
     */
    private fun insertImageBbcodeAtCaret(bbcode: String, leadingNewline: Boolean) {
        // Authorized upload callbacks may insert during their own upload, unlike user input.
        if (!acceptMutation()) return
        draftSession.decide(EditorSessionField.Body)
        chromeMemory.host.focus.clearToolSelection()
        bodyFieldState.editBbcode { text, start, end ->
            val caret = start.coerceIn(0, text.length)
            val needsNewline = leadingNewline && caret > 0 && text[caret - 1] != '\n'
            insertBbcodeToken(
                token = if (needsNewline) "\n" + bbcode else bbcode,
                text = text,
                selectionStart = start,
                selectionEnd = end,
                surroundWithSpaces = false,
            )
        }
        draftSession.persistDraftNow()
    }

    private fun handleUploadFailure(error: Throwable) {
        if (error is CancellationException) {
            updateState { it.copy(isUploading = false, uploadProgress = null) }
            throw error
        }
        val mapped = EditorErrors.stopped(error.toEditorUploadError(), _state.value.uploadProgress)
        diagnostics.record(
            DiagnosticsLog.Level.WARN,
            LOG_TAG_UPLOAD,
            UploadFailureDiagnostics.describe(error, mapped.toString()),
        )
        updateState { it.copy(isUploading = false, uploadError = mapped, uploadProgress = null) }
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
