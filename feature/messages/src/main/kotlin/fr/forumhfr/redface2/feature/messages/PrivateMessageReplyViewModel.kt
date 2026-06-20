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
class PrivateMessageReplyViewModel @AssistedInject constructor(
    @Assisted private val request: PrivateMessageReplyRequest,
    private val repository: PrivateMessageWriteRepository,
    private val previewParser: BbcodePreviewParser,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val draftStore: EditorDraftStore,
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
        val body = _state.value.draft.text
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            if (body.isBlank()) {
                draftStore.delete(draftOwner, draftKey)
            } else {
                draftStore.save(draftOwner, draftKey, EditorDraftStore.Draft(body = body, isPrivate = true))
            }
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
                val form = repository.fetchReplyForm(context)
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
        )

    @AssistedFactory
    interface Factory {
        fun create(request: PrivateMessageReplyRequest): PrivateMessageReplyViewModel
    }

    private companion object {
        // #405 — idle window after the last edit before the draft is persisted (cf. PostEditorViewModel).
        private const val AUTOSAVE_DEBOUNCE_MS = 750L
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
