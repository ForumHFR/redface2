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
import fr.forumhfr.redface2.core.domain.editor.PendingInlineQuoteRequest
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSession
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
import fr.forumhfr.redface2.core.domain.write.MaterializedPrivateMessageReplyForm
import fr.forumhfr.redface2.core.domain.write.PrivateMessageReplyQuoteMaterializer
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.model.write.PrivateMessageReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.editor.EditorPreviewState
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.EditorErrorSurface
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.MAX_IMAGES_PER_UPLOAD
import fr.forumhfr.redface2.core.ui.editor.SmileyFavoriteMutations
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerFavoriteSources
import fr.forumhfr.redface2.core.ui.editor.SmileyWikiKeywordsSource
import fr.forumhfr.redface2.core.ui.editor.UploadProgress
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
 * ED-88/161/169/170/172: private reply with the existing success relay and a durable frozen snapshot.
 * Draft status is read before quote hydration; uncertainty retains text and the conversation check (ED-112).
 * Invalid hashes refresh the form after the certain outcome, without replaying the POST.
 */
@HiltViewModel(assistedFactory = PrivateMessageReplyViewModel.Factory::class)
@Suppress("LongParameterList") // Hilt ctor — one dependency per collaborator (#459 added the upload trio).
class PrivateMessageReplyViewModel @AssistedInject constructor(
    @Assisted private val request: PrivateMessageReplyRequest,
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
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    /** Body and IME state belong to this ViewModel, never to a Bundle saver. */
    val bodyFieldState: TextFieldState get() = draftSession.bodyFieldState
    val bodyScrollState: ScrollState get() = draftSession.bodyScrollState
    internal val bodyCaretRevealResetKey: Int get() = draftSession.bodyCaretRevealResetKey

    private val _state = MutableStateFlow(PrivateMessageReplyUiState(isInitializing = true))
    val state: StateFlow<PrivateMessageReplyUiState> = _state.asStateFlow()

    internal val editorEvents = PrivateMessageReplyEvents()
    // ED-63/C8: VM lifetime only; visibility remains in the existing preview pipeline.
    internal val previewLayout by lazy { EditorPreviewState(userPreferencesRepository, viewModelScope) }
    internal val chromeMemory = PrivateMessageReplyChromeMemory()

    /** Observe synchronously: StateFlow may conflate two identical local refusals. */
    private fun updateState(transform: (PrivateMessageReplyUiState) -> PrivateMessageReplyUiState) {
        val previous = _state.value
        _state.update(transform)
        if (editorEvents.state.session != submission.session) chromeMemory.reset()
        editorEvents.open(submission.session)
        editorEvents.observe(previous, _state.value)
    }

    private val _effects: Channel<PrivateMessageReplyEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<PrivateMessageReplyEffect> = _effects.receiveAsFlow()

    private val context = PrivateMessageReplyContext(
        threadId = request.threadId,
        page = request.page.coerceAtLeast(1),
        quote = null,
    )
    private val quoteMaterializer = PrivateMessageReplyQuoteMaterializer(repository)

    private var loadedForm: ReplyForm? = null
        set(value) {
            field = value
            // The silent InvalidHashCheck refetch keeps the field and its focus composed.
            // GET outcomes and account changes update formAvailable explicitly.
            if (value == null) smileyPicker.refreshFavorites(null)
        }

    /**
     * #387 — shared smiley picker (Favorites + Standard + Wiki). The controller owns
     * the picker state machine (debounce, race guards) ; this ViewModel only handles insertion.
     * userId comes from the loaded reply form ([ReplyForm.userId], HFR's `find_smilies_timer`
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

    private var loadedSubmitContext: PrivateMessageReplyContext? = null
    private var formJob: Job? = null

    /** #405 — content-free draft key for this conversation (private → wiped on logout). */
    private val draftKey: String = EditorDraftKey.mpReply(request.threadId)

    /** #405 — debounced autosave coroutine ; relaunched (cancelling the previous) on each edit. */
    private val submission: EditorSubmissionBinding<PrivateMessageReplyEffect, EditorError> = EditorSubmissionBinding(
        environment = EditorSubmissionEnvironment(viewModelScope, clock, diagnostics),
        drafts = EditorDraftSession(draftStore, draftKey),
        callbacks = EditorSubmissionCallbacks<PrivateMessageReplyEffect, EditorError>(
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
            durableWriteSucceeded = {
                editorEvents.durableWriteSucceeded()
                draftSession.refreshOffers()
            },
        ),
        errors = EditorSubmissionErrors(
            uncertain = EditorErrors.uncertain(EditorErrorSurface.MessageReply),
            draftWriteFailed = EditorErrors.draftWriteFailed,
            draftSaveFailed = EditorErrors.draftSaveFailed,
            sessionExpired = EditorErrors.sessionExpired,
            unknown = EditorErrors.uncertain(EditorErrorSurface.MessageReply),
            network = EditorErrors.network,
            preparationFailed = EditorErrors.invalidForm,
        ),
    )

    private val draftSession: PrivateMessageReplyDraftSession = PrivateMessageReplyDraftSession(
        saved = savedStateHandle, submission = submission, scope = viewModelScope, draftKey = draftKey,
        state = PrivateMessageDraftState(read = { _state.value }, update = ::updateState),
        clearToolSelection = { chromeMemory.host.focus.clearToolSelection() },
    )

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

    /** Protect real input before readOnly is recomposed; selection alone still disarms. */
    val bodyInputTransformation = InputTransformation {
        val changesBody = mutatesBody(asCharSequence())
        if (!acceptMutation(blockDuringUpload = changesBody)) {
            revertAllChanges()
        } else if (changesBody) {
            draftSession.decideBody()
        }
    }

    private fun mutatesBody(text: CharSequence): Boolean = text.toString() != bodyFieldState.text.toString()

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
                    formJob?.cancel()
                    loadedForm = null
                    loadedSubmitContext = null
                    draftSession.invalidate()
                    updateState {
                        it.copy(formAvailable = false, isUploading = false, uploadProgress = null,
                            restorableDraft = null, draftOffers = emptyList(), conversationTitle = "",
                            canManageRecipients = false,
                            recipients = emptyList(), recipientsDirty = false)
                    }
                }
                authObserved = true
                activeUserId = newUserId
            }
        }
        viewModelScope.launch {
            draftSession.restore()
            refreshQuoteState()
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

    fun retryFormLoad() {
        viewModelScope.launch {
            if (!submission.isInitialized && !submission.drafts.invalidated) draftSession.restore()
            refreshQuoteState()
            loadForm()
        }
    }

    private fun refreshQuoteState() {
        val pending = submission.drafts.pendingInlineQuoteRequest != null
        val unreadable = submission.drafts.hasUnreadableInlineQuoteRequest
        val dormant = pending && _state.value.submission is EditorSubmissionState.Uncertain
        val recovery = unreadable || dormant
        updateState { it.copy(quotePending = pending || unreadable, unreadableQuoteRequest = unreadable,
            quoteStorageUnavailable = !submission.isInitialized,
            formError = it.formError || recovery, isLoadingForm = if (recovery) false else it.isLoadingForm,
            quoteFetchFailures = if (pending) savedStateHandle[QUOTE_FAILURES_KEY] ?: 0 else 0) }
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
            _effects.send(PrivateMessageReplyEffect.CloseCommitted)
        }
    }

    fun onStarted() = draftSession.onStarted()
    fun onStopped() = draftSession.onStopped()
    suspend fun restoreBodyScroll() = draftSession.restoreBodyScroll()

    fun onDraftRestoreRequested(offerId: String? = null) {
        if (!acceptMutation(blockDuringUpload = true)) return
        draftSession.restoreOfferedDraft(offerId)
    }

    fun onDraftDiscardRequested(offerId: String? = null) {
        if (!acceptMutation()) return
        draftSession.discardOfferedDraft(offerId)
    }

    private var deliveredRequest: PendingInlineQuoteRequest? = null
    private var deliveredBody = ""

    private fun pendingSelections(request: PendingInlineQuoteRequest): List<QuoteSelection> = request.quotes.map {
        QuoteSelection(QuoteLocator(it.page, it.numreponse, it.ref), "", "", it.truncate)
    }

    fun onPendingQuoteResumed() {
        if (!_state.value.hasDormantQuote || !acceptMutation()) return
        submission.acknowledge { loadForm() }
    }

    fun onPendingQuoteIgnored() {
        if (!_state.value.canIgnorePendingQuote || !acceptMutation()) return
        viewModelScope.launch {
            val pending = submission.drafts.pendingInlineQuoteRequest
            val result = if (submission.drafts.hasUnreadableInlineQuoteRequest) {
                submission.drafts.discardUnreadableInlineQuoteRequest()
            } else if (pending != null && draftSession.deliveryPending) {
                draftSession.commitQuote(pending, deliveredBody)
            } else pending?.let { submission.drafts.consumeInlineQuoteRequest(it) }
            submission.resolveDraftWrite(result)
            if (result is EditorDraftStore.StoreWriteResult.Written) {
                savedStateHandle.remove<Int>(QUOTE_FAILURES_KEY)
                updateState { it.copy(quotePending = false, quoteFetchFailures = 0, quoteStorageUnavailable = false) }
                if (!submission.isInitialized) draftSession.restore()
                loadForm()
            }
        }
    }

    private fun loadForm() {
        if (!submission.isInitialized) return
        val pending = submission.drafts.pendingInlineQuoteRequest
        if (pending != null && _state.value.submission is EditorSubmissionState.Uncertain) return
        formJob?.cancel()
        val favoriteStamp = smileyPicker.favoriteFormStamp()
        updateState { it.copy(isLoadingForm = true, formError = false) }
        val opening = submission.session
        updateState { it.copy(quotePending = pending != null) }
        formJob = viewModelScope.launch {
            try {
                // #618 — when the user came specifically to MANAGE recipients, the form MUST be the
                // message.php one carrying `newdest` (owner) ; refuse the forum2.php quick-reply
                // fallback (no newdest → no member editor → silently lands on a plain composer). A
                // failed message.php GET then surfaces as a form error (retry) rather than a dead end.
                val materialized = quoteMaterializer.fetchFormWithQuotes(
                    context = if (pending != null) context else loadedSubmitContext ?: context,
                    selections = pending?.let(::pendingSelections).orEmpty(),
                    allowEmbeddedFallback = !request.openRecipientManager,
                )
                acceptLoadedForm(materialized, pending, opening, favoriteStamp)
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

    private suspend fun acceptLoadedForm(
        materialized: MaterializedPrivateMessageReplyForm,
        pending: PendingInlineQuoteRequest?,
        opening: EditorSession,
        favoriteStamp: SmileyPickerController.FavoriteFormStamp,
    ) {
        if (!submission.isInitialized || submission.session != opening) return
        val form = materialized.form
        if (form.isAnonymous) {
            formLoadFailed()
            return
        }
        loadedForm = form
        smileyPicker.refreshFavorites(favoriteStamp)
        loadedSubmitContext = materialized.submitContext
        if (pending == null) applyFormHydration(form) else deliverQuote(form, pending, opening)
    }

    private suspend fun deliverQuote(form: ReplyForm, pending: PendingInlineQuoteRequest, opening: EditorSession) {
        uploadJob?.join()
        draftSession.awaitOfferDecision()
        submission.flush(null)
        if (!submission.isInitialized || submission.session != opening) return
        if (deliveredRequest != pending) {
            draftSession.insertQuote(form.initialContent)
            deliveredRequest = pending
            deliveredBody = form.initialContent
        }
        val committed = draftSession.commitQuote(pending, deliveredBody)
        if (!submission.isInitialized || submission.session != opening) return
        if (committed is EditorDraftStore.StoreWriteResult.Written) {
            savedStateHandle.remove<Int>(QUOTE_FAILURES_KEY)
            applyFormHydration(form.copy(initialContent = ""))
            updateState { it.copy(quotePending = false, quoteFetchFailures = 0) }
        } else formLoadFailed()
    }

    private fun formLoadFailed() {
        if (!submission.isInitialized) return
        loadedForm = null
        loadedSubmitContext = null
        val failures = if (_state.value.quotePending) (_state.value.quoteFetchFailures + 1).coerceAtMost(2) else 0
        savedStateHandle[QUOTE_FAILURES_KEY] = failures
        updateState { it.copy(isLoadingForm = false, formAvailable = false, formError = true,
            quoteFetchFailures = failures) }
    }

    fun onToolbarAction(action: BbcodeAction) {
        if (!acceptMutation(blockDuringUpload = true)) return
        val before = bodyFieldState.text.toString()
        chromeMemory.host.focus.clearToolSelection()
        bodyFieldState.editBbcode { text, start, end -> applyBbcodeAction(action, text, start, end) }
        if (mutatesBody(before)) draftSession.decideBody()
        if (action == BbcodeAction.Image) draftSession.persistDraftNow() else draftSession.scheduleAutosave()
    }

    fun onSmileySelected(token: String) {
        if (!acceptMutation(blockDuringUpload = true)) return
        val before = bodyFieldState.text.toString()
        chromeMemory.host.focus.clearToolSelection()
        bodyFieldState.editBbcode { text, start, end -> insertBbcodeToken(token, text, start, end) }
        if (mutatesBody(before)) draftSession.decideBody()
        smileyPicker.dismiss(SmileyPickerCloseCause.INSERTION)
        draftSession.scheduleAutosave()
    }

    fun onTogglePreview() {
        updateState { current ->
            current.copy(isPreviewVisible = !current.isPreviewVisible, preview = PostContent(emptyList()))
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

    /**
     * #606 — owner adds a member to the DT/MultiMP. The pseudo is appended at the end (HFR keeps
     * insertion order), trimmed of leading / trailing whitespace but otherwise verbatim (case,
     * accents, `+`, internal spaces preserved). A blank entry or an exact-trimmed duplicate of an
     * existing member is refused (no-op). Ignored entirely when the user is not the owner.
     */
    fun onAddRecipient(pseudo: String) {
        if (!_state.value.canManageRecipients || !acceptMutation()) return
        val trimmed = pseudo.trim()
        if (trimmed.isEmpty()) return
        updateState { current ->
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
        if (!acceptMutation()) return
        if (!_state.value.canManageRecipients) return
        val target = pseudo.trim()
        updateState { current ->
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

    /** ED-45/170: reached only by the host's explicit Masquer of the bound uncertain delivery, never by Back. */
    fun onErrorDismissed() = submission.acknowledge(draftSession::scheduleAutosave)

    /**
     * ED-45: reached only by the host's explicit Masquer of « Brouillon non écrit », never by Back. Releases the
     * deferred draft-save information without storage; the binding guards a real Ready/Armed state and lets a
     * displayed uncertainty win.
     */
    fun onDraftWriteFailureDismissed() = submission.acknowledgeLocal()

    /** ED-164/166: callbacks carry the identity displayed by the current composition. */
    fun onSubmitConfirmed(token: ArmToken?) {
        val snapshot = _state.value.copy(bodyText = bodyFieldState.text.toString())
        val form = loadedForm ?: return
        if (!snapshot.canSubmit || closeRequested) return
        val permit = submission.confirm(token)
        if (permit is SubmissionRequest.Accepted) launchSubmission(permit, snapshot, form)
    }

    fun onSubmitConfirmationDismissed() = submission.disarm()

    fun onSubmit(timeoutMillis: Long = 4_000L) {
        val snapshot = _state.value.copy(bodyText = bodyFieldState.text.toString())
        val form = loadedForm ?: return
        if (!snapshot.canSubmit || closeRequested || submission.blocksMutation) return
        if (form.isAnonymous) {
            updateState { it.copy(submitError = null) }
            updateState { it.copy(submitError = EditorErrors.loginRequired) }
        } else {
            val permit = submission.request(timeoutMillis, snapshot.canSubmit)
            if (permit is SubmissionRequest.Accepted) launchSubmission(permit, snapshot, form)
        }
    }

    /** ED-88/161/169/170/172: frozen headers/body, awaited attempt, existing effect exactly once. */
    private fun launchSubmission(
        permit: SubmissionRequest.Accepted,
        snapshot: PrivateMessageReplyUiState,
        form: ReplyForm,
    ) {
        val submitContext = checkNotNull(loadedSubmitContext)
        submission.launch(permit, EditorDraftStore.Draft(body = snapshot.bodyText, isPrivate = true)) {
            val options = ReplyFormOptions(
                signatureEnabled = snapshot.signatureEnabled, smileyDisabled = snapshot.smileyDisabled,
                emailNotificationEnabled = snapshot.emailNotificationEnabled,
            )
            val result = repository.submitReply(
                context = submitContext, form = form,
                bbcodeContent = snapshot.bodyText, options = options,
                recipientsOverride = if (snapshot.canManageRecipients && snapshot.recipientsDirty) {
                    RecipientCsv.join(snapshot.recipients)
                } else { null },
            )
            submissionDelivery(result)
        }
    }

    private fun submissionDelivery(
        result: ReplySubmitResult,
    ): EditorDelivery<PrivateMessageReplyEffect, EditorError> =
        when (result) {
            is ReplySubmitResult.Success -> EditorDelivery(
                effect = PrivateMessageReplyEffect.SubmitSucceeded(
                    threadId = context.threadId, page = context.page,
                ),
            )
            is ReplySubmitResult.Failure -> {
                EditorDelivery(
                    outcome = if (result.reason == ReplyFailureReason.Unknown ||
                        result.reason == ReplyFailureReason.TopicLocked) {
                        EditorSendOutcome.UNCERTAIN
                    } else {
                        EditorSendOutcome.CERTAIN_FAILURE
                    },
                    error = result.reason.toReplyError(),
                    afterSettled = if (result.reason == ReplyFailureReason.InvalidHashCheck) {
                        {
                            loadedForm = null
                            loadForm()
                        }
                    } else { null },
                )
            }
        }

    private fun ReplyFailureReason.toReplyError(): EditorError = toEditorError(EditorErrorSurface.MessageReply)

    /** Consume the exact server quote once, even when a GET finishes during an upload. */
    private fun applyFormHydration(form: ReplyForm) {
        if (submission.blocksMutation) {
            updateState { it.copy(isLoadingForm = false) }
            return
        }
        draftSession.hydrateBody(form.initialContent)
        val liveBody = bodyFieldState.text.toString()
        // An offer identical to the field has nothing to restore; a later divergence reoffers it (ED-121).
        updateState { current ->
            current.withLoadedForm(form, submission.blocksMutation)
                .copy(restorableDraft = current.restorableDraft.takeIf { it != liveBody })
        }
    }

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
        if (!canStartUpload(userId, targets) || !acceptMutation(blockDuringUpload = true)) return
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
        if (submission.blocksMutation) return
        chromeMemory.host.focus.clearToolSelection()
        bodyFieldState.editBbcode { text, start, end ->
            val caret = start.coerceIn(0, text.length)
            val needsNewline = leadingNewline && caret > 0 && text[caret - 1] != '\n'
            val token = if (needsNewline) "\n" + bbcode else bbcode
            insertBbcodeToken(token, text, start, end, surroundWithSpaces = false)
        }
        draftSession.decideBody()
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
        fun create(request: PrivateMessageReplyRequest): PrivateMessageReplyViewModel
    }

    private companion object {
        const val QUOTE_FAILURES_KEY = "mpReply.quoteFailures"
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

/** Form metadata, options and recipients of a served reply form; the body is hydrated elsewhere. */
private fun PrivateMessageReplyUiState.withLoadedForm(
    form: ReplyForm,
    blocksMutation: Boolean,
): PrivateMessageReplyUiState {
    // Body hydration is already applied outside this state update. A silent refetch
    // refreshes only the form metadata, options and unchanged recipient list below.
    if (blocksMutation) return copy(isLoadingForm = false)
    val hydrated = this
    // HFR's private "quick reply" form carries the per-post options as plain hidden
    // inputs (e.g. `signature=1`), not checkboxes — so `ReplyForm.options` (read from
    // `checked` attributes) is all-false here. Hydrate from the hidden fields too,
    // OR'ing with the checkbox view, so the user keeps HFR's default (signature on).
    //
    // Hydrate ONLY on the first successful load: a silent refetch after an expired
    // `hash_check` (InvalidHashCheck) must not reset a toggle the user changed in
    // between — mirror the post editor's `optionsHydratedFromForm` guard.
    val hydrateOptions = !hydrated.optionsHydratedFromForm
    return hydrated.copy(
        isLoadingForm = false,
        formAvailable = true,
        conversationTitle = form.sujet,
        formError = false,
        // InvalidForm here is a submission preparation failure, never a failed GET (ED-44).
        // A GET clears neither submitError nor its event. Masquer retires the event; only an explicit Masquer of
        // the bound uncertainty also acknowledges its attempt durably, which then clears submitError (ED-170),
        // and an explicit Masquer of a failed attempt write clears it locally, without storage.
        signatureEnabled = if (hydrateOptions) {
            form.options.signatureEnabled || form.hiddenFields["signature"] == "1"
        } else {
            hydrated.signatureEnabled
        },
        smileyDisabled = if (hydrateOptions) {
            form.options.smileyDisabled || form.hiddenFields["smiley"] == "1"
        } else {
            hydrated.smileyDisabled
        },
        emailNotificationEnabled = if (hydrateOptions) {
            form.options.emailNotificationEnabled || form.hiddenFields["emaill"] == "1"
        } else {
            hydrated.emailNotificationEnabled
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
        recipients = if (hydrateOptions || !hydrated.recipientsDirty) {
            RecipientCsv.parse(form.manageableRecipients)
        } else {
            hydrated.recipients
        },
    )
}
