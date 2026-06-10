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
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.applyBbcodeAction
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
class PrivateMessageComposeViewModel @AssistedInject constructor(
    @Assisted private val initialRecipient: String?,
    private val repository: PrivateMessageWriteRepository,
    private val previewParser: BbcodePreviewParser,
    private val userPreferencesRepository: UserPreferencesRepository,
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

    /** #312 — mirror of the persisted « Confirmation avant publication » preference. */
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

    fun onRecipientsChanged(value: String) = _state.update { it.copy(recipients = value) }

    fun onSubjectChanged(value: String) = _state.update {
        // HFR's maxlength=70 — truncate instead of erroring so pasted text just clips.
        it.copy(subject = value.take(PrivateMessageComposeUiState.SUBJECT_MAX_LENGTH))
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
                onSuccess = ::handleSubmitOutcome,
                onFailure = ::handleSubmitFailure,
            )
        }
    }

    private fun handleSubmitOutcome(result: ReplySubmitResult) {
        when (result) {
            is ReplySubmitResult.Success -> {
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
    )

    @AssistedFactory
    interface Factory {
        fun create(initialRecipient: String?): PrivateMessageComposeViewModel
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
}
