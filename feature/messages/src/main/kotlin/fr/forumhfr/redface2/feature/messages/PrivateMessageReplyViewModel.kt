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
        loadForm()
    }

    fun retryFormLoad() = loadForm()

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
        _state.update { it.copy(isSubmitting = true, submitError = null) }
        submitJob = viewModelScope.launch {
            val outcome = runCatching {
                repository.submitReply(
                    context = context,
                    form = form,
                    bbcodeContent = snapshot.draft.text,
                    options = options,
                )
            }
            outcome.fold(
                onSuccess = ::handleSubmitOutcome,
                onFailure = ::handleSubmitFailure,
            )
        }
    }

    private fun handleSubmitOutcome(result: ReplySubmitResult) {
        when (result) {
            is ReplySubmitResult.Success -> {
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
