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
import fr.forumhfr.redface2.core.domain.write.PrivateMessageReplyQuoteMaterializer
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.model.write.PrivateMessageReplyContext
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

    private val _state = MutableStateFlow(PrivateMessageReplyUiState())
    val state: StateFlow<PrivateMessageReplyUiState> = _state.asStateFlow()

    private val _effects: Channel<PrivateMessageReplyEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<PrivateMessageReplyEffect> = _effects.receiveAsFlow()

    private val context = PrivateMessageReplyContext(
        threadId = request.threadId,
        page = request.page.coerceAtLeast(1),
        quote = request.quote,
    )
    private val quoteMaterializer = PrivateMessageReplyQuoteMaterializer(repository)

    private var loadedForm: ReplyForm? = null
        set(value) {
            field = value
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
    private val submission = EditorSubmissionBinding(
        environment = EditorSubmissionEnvironment(viewModelScope, clock, diagnostics),
        drafts = EditorDraftSession(draftStore, draftKey),
        callbacks = EditorSubmissionCallbacks<PrivateMessageReplyEffect, PrivateMessageReplyError>(
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

    /** ED-81/170: durable status wins over every offer and server prefill. */
    private suspend fun restoreDraftIfAny() {
        val entry = submission.restore(draftKey) ?: return
        if (submission.blocksMutation) return
        val draft = entry.draft
        if (entry.attempt != null) {
            _state.update {
                it.withDraftPreview(TextFieldValue(draft.body, TextRange(draft.body.length))).copy(
                    draftHydratedFromForm = true,
                    restorableDraft = null, submitError = PrivateMessageReplyError.Uncertain,
                )
            }
        } else {
            val body = draft.body
            if (body.isNotBlank()) {
                val fingerprint = draft.restoreOfferFingerprint()
                val ignored = savedStateHandle.get<String>(DRAFT_RESTORE_OFFER_FINGERPRINT_KEY) == fingerprint
                if (!ignored && _state.value.canOfferDraftRestore(body)) {
                    _state.update { it.copy(restorableDraft = body) }
                }
            }
        }
    }

    private fun PrivateMessageReplyUiState.canOfferDraftRestore(cachedBody: String): Boolean =
        draft.text != cachedBody &&
            (draft.text.isBlank() || draft.text == draftHydratedContent)

    /**
     * #405 — debounced autosave of the body, flagged `isPrivate = true` so the logout purge wipes
     * it. ED-170: blank content is cleared through CAS; the store refuses a stale owner.
     */
    private fun scheduleAutosave() = submission.autosave(::draftSnapshot)

    private fun draftSnapshot(): EditorDraftStore.Draft? {
        val snapshot = _state.value
        if (snapshot.draft.text.isBlank() && snapshot.restorableDraft != null) return null
        return EditorDraftStore.Draft(body = snapshot.draft.text, isPrivate = true)
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
                submission.flush(draftSnapshot())
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                // Best effort — the previous debounced write is what remains.
            }
            _effects.send(PrivateMessageReplyEffect.CloseCommitted)
        }
    }

    /** #405 — append the cached body after any live text and clear the banner. */
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
                .copy(restorableDraft = null)
        }
        scheduleAutosave()
    }

    /** ED-170: clear the offered content through CAS, preserving a newer generation. */
    fun onDraftDiscardRequested() {
        if (submission.blocksMutation) return
        submission.disarm()
        val body = _state.value.restorableDraft ?: return
        markDraftRestoreOfferIgnored(body)
        _state.update { it.copy(restorableDraft = null) }
        viewModelScope.launch { submission.flush(EditorDraftStore.Draft(body = "", isPrivate = true)) }
    }

    private fun markDraftRestoreOfferIgnored(body: String) {
        savedStateHandle[DRAFT_RESTORE_OFFER_FINGERPRINT_KEY] =
            EditorDraftStore.Draft(body = body, isPrivate = true).restoreOfferFingerprint()
    }

    private fun loadForm() {
        if (!submission.isInitialized) return
        formJob?.cancel()
        val favoriteStamp = smileyPicker.favoriteFormStamp()
        _state.update { it.copy(isLoadingForm = true, formError = false) }
        formJob = viewModelScope.launch {
            try {
                // #618 — when the user came specifically to MANAGE recipients, the form MUST be the
                // message.php one carrying `newdest` (owner) ; refuse the forum2.php quick-reply
                // fallback (no newdest → no member editor → silently lands on a plain composer). A
                // failed message.php GET then surfaces as a form error (retry) rather than a dead end.
                val materialized = quoteMaterializer.fetchFormWithQuotes(
                    context = context,
                    selections = request.initialQuotes,
                    allowEmbeddedFallback = !request.openRecipientManager,
                )
                val form = materialized.form
                if (form.isAnonymous) {
                    // Session vanished between opening the thread and replying — treat like a form
                    // error so the user re-authenticates (the reply route is only reachable while
                    // authenticated, so this is rare).
                    loadedForm = null
                    loadedSubmitContext = null
                    _state.update { it.copy(isLoadingForm = false, formAvailable = false, formError = true) }
                    return@launch
                }
                loadedForm = form
                smileyPicker.refreshFavorites(favoriteStamp)
                loadedSubmitContext = materialized.submitContext
                _state.update { it.withLoadedForm(form) }
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

    private fun PrivateMessageReplyUiState.withLoadedForm(form: ReplyForm): PrivateMessageReplyUiState {
        // #1074 — consume HFR's quote BBCode exactly once. If the user typed while the
        // GET was in flight, keep that text below the verbatim server prefill. A silent
        // InvalidHashCheck refetch sees draftHydratedFromForm=true and cannot duplicate
        // the block or clobber later edits.
        if (this@PrivateMessageReplyViewModel.submission.blocksMutation) return copy(isLoadingForm = false)
        val hydrated = withFormInitialContent(form)
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
            formError = false,
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

    private fun formLoadFailed() {
        loadedForm = null
        loadedSubmitContext = null
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

    /**
     * #606 — owner adds a member to the DT/MultiMP. The pseudo is appended at the end (HFR keeps
     * insertion order), trimmed of leading / trailing whitespace but otherwise verbatim (case,
     * accents, `+`, internal spaces preserved). A blank entry or an exact-trimmed duplicate of an
     * existing member is refused (no-op). Ignored entirely when the user is not the owner.
     */
    fun onAddRecipient(pseudo: String) {
        if (submission.blocksMutation || !_state.value.canManageRecipients) return
        submission.disarm()
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
        if (submission.blocksMutation) return
        submission.disarm()
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
        snapshot: PrivateMessageReplyUiState,
        form: ReplyForm,
    ) {
        val submitContext = checkNotNull(loadedSubmitContext)
        submission.launch(permit, EditorDraftStore.Draft(body = snapshot.draft.text, isPrivate = true)) {
            val options = ReplyFormOptions(
                signatureEnabled = snapshot.signatureEnabled, smileyDisabled = snapshot.smileyDisabled,
                emailNotificationEnabled = snapshot.emailNotificationEnabled,
            )
            val result = repository.submitReply(
                context = submitContext, form = form,
                bbcodeContent = snapshot.draft.text, options = options,
                recipientsOverride = if (snapshot.canManageRecipients && snapshot.recipientsDirty) {
                    RecipientCsv.join(snapshot.recipients)
                } else { null },
            )
            submissionDelivery(result)
        }
    }

    private fun submissionDelivery(
        result: ReplySubmitResult,
    ): EditorDelivery<PrivateMessageReplyEffect, PrivateMessageReplyError> =
        when (result) {
            is ReplySubmitResult.Success -> EditorDelivery(
                effect = PrivateMessageReplyEffect.SubmitSucceeded(
                    threadId = context.threadId, page = context.page,
                ),
            )
            is ReplySubmitResult.Failure -> {
                if (result.reason == ReplyFailureReason.InvalidHashCheck) {
                    loadedForm = null
                    viewModelScope.launch {
                        yield()
                        loadForm()
                    }
                }
                EditorDelivery(error = result.reason.toReplyError())
            }
        }

    private fun ReplyFailureReason.toReplyError(): PrivateMessageReplyError = when (this) {
        ReplyFailureReason.EmptyMessage -> PrivateMessageReplyError.Empty
        ReplyFailureReason.InvalidHashCheck -> PrivateMessageReplyError.InvalidHashCheck
        ReplyFailureReason.AntiFlood -> PrivateMessageReplyError.AntiFlood
        ReplyFailureReason.LoginRequired -> PrivateMessageReplyError.LoginRequired
        // TopicLocked has no private-message analogue, and Unknown is the unrecognised-response
        // fallback: both map to the non-destructive "verify the conversation" banner.
        ReplyFailureReason.TopicLocked -> PrivateMessageReplyError.Unexpected
        ReplyFailureReason.Unknown -> PrivateMessageReplyError.Uncertain
    }

    private fun PrivateMessageReplyUiState.withDraftPreview(updated: TextFieldValue): PrivateMessageReplyUiState =
        copy(
            draft = updated,
            preview = if (isPreviewVisible) previewParser.parsePreview(updated.text) else preview,
            // #459 — a fresh text edit dismisses a stale upload banner — parity with PostEditorState.
            uploadError = if (updated.text != draft.text) null else uploadError,
        )

    /**
     * Applies a server prefill once without reconstructing it. A late form prefixes the untouched
     * prefill to text already entered while loading; an explicit #405 restore may append after it.
     */
    private fun PrivateMessageReplyUiState.withFormInitialContent(form: ReplyForm): PrivateMessageReplyUiState {
        if (draftHydratedFromForm) return this
        val initial = form.initialContent
        return if (initial.isBlank()) {
            copy(draftHydratedFromForm = true)
        } else {
            val existing = draft.text
            val combined = if (existing.isBlank()) {
                initial
            } else {
                val separator = if (initial.endsWith('\n')) "\n" else "\n\n"
                initial + separator + existing
            }
            val hydrated = withDraftPreview(
                TextFieldValue(text = combined, selection = TextRange(combined.length)),
            )
            hydrated.copy(
                    draftHydratedFromForm = true,
                draftHydratedContent = combined.takeIf { existing.isBlank() },
                restorableDraft = restorableDraft.takeIf { it != combined },
            )
        }
    }

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
        fun create(request: PrivateMessageReplyRequest): PrivateMessageReplyViewModel
    }

    private companion object {
        private const val DRAFT_RESTORE_OFFER_FINGERPRINT_KEY = "draft_restore_offer_fingerprint"


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
