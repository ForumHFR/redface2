package fr.forumhfr.redface2.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorDelivery
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftSession
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionBinding
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionCallbacks
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionEnvironment
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionErrors
import fr.forumhfr.redface2.core.domain.editor.SubmissionRequest
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Hilt collaborators shared by successive account-bound sessions of this screen. */
class ReportDependencies @Inject constructor(
    val repository: PrivateMessageWriteRepository,
    val auth: AuthRepository,
    val preferences: UserPreferencesRepository,
    val drafts: EditorDraftStore,
    val diagnostics: DiagnosticsLog,
    val clock: DiagnosticsClock,
)

@HiltViewModel(assistedFactory = ReportContentViewModel.Factory::class)
class ReportContentViewModel @AssistedInject constructor(
    @Assisted private val initial: ReportInitialContent,
    private val dependencies: ReportDependencies,
) : ViewModel() {
    private val _state = MutableStateFlow(ReportContentUiState(draft = ReportDraft(link = initial.topicLink)))
    val state = _state.asStateFlow()
    private val _effects = Channel<ReportContentEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()
    private var confirmPreference: Boolean? = null
    private var submission = newSubmission()
    private var activeOwner: String? = null
    private var authObserved = false
    private var generation = 0
    private var formJob: Job? = null
    private var loadedForm: ReplyForm? = null
    private var closeRequested = false

    init {
        viewModelScope.launch {
            dependencies.preferences.observeConfirmBeforePosting().catch { emit(true) }.collect {
                confirmPreference = it
                submission.setPreference(it)
            }
        }
        viewModelScope.launch {
            dependencies.auth.observeAuthState().collect(::authChanged)
        }
    }

    private fun newSubmission() = EditorSubmissionBinding(
        environment = EditorSubmissionEnvironment(viewModelScope, dependencies.clock, dependencies.diagnostics),
        drafts = EditorDraftSession(dependencies.drafts, EditorDraftKey.report()),
        callbacks = EditorSubmissionCallbacks<ReportContentEffect, ReportError>(
            state = { value -> _state.update { it.copy(submission = value) } },
            error = { value -> _state.update { it.copy(error = value) } },
            success = { value -> _effects.trySend(value) },
        ),
        errors = EditorSubmissionErrors(
            uncertain = ReportError.UNCERTAIN, draftWriteFailed = ReportError.DRAFT_WRITE_FAILED,
            sessionExpired = ReportError.SESSION_EXPIRED, unknown = ReportError.UNCERTAIN,
            network = ReportError.NETWORK,
        ),
    ).also { it.setPreference(confirmPreference) }

    private fun authChanged(auth: AuthState) {
        val owner = (auth as? AuthState.Authenticated)?.pseudo?.lowercase()
        if (authObserved && owner == activeOwner) return
        val topicLink = if (activeOwner == null) _state.value.draft.link else ""
        authObserved = true
        activeOwner = owner
        generation++
        formJob?.cancel()
        submission.accountChanged()
        closeRequested = false
        loadedForm = null
        _state.value = ReportContentUiState(
            session = if (owner == null) ReportSession.ANONYMOUS else ReportSession.AUTHENTICATED,
            draft = ReportDraft(link = topicLink),
        )
        if (owner != null) {
            submission = newSubmission()
            val opening = generation
            formJob = viewModelScope.launch { restoreAndLoad(opening) }
        }
    }

    private suspend fun restoreAndLoad(opening: Int) {
        val binding = submission
        val entry = binding.restore(EditorDraftKey.report())
        if (opening != generation) return
        if (entry == null && _state.value.error == ReportError.DRAFT_WRITE_FAILED) return
        if (binding.drafts.owner == activeOwner) {
            acceptDraft(entry, opening)
        } else {
            binding.accountChanged()
            sessionExpired()
        }
    }

    private suspend fun acceptDraft(entry: EditorDraftStore.DraftEntry?, opening: Int) {
        val draft = entry?.let { ReportDraftCodec.decode(it.draft) }
        if (entry != null && draft == null) {
            _state.update { it.copy(error = ReportError.DRAFT_WRITE_FAILED) }
        } else {
            _state.update { it.copy(draft = draft ?: it.draft, draftReady = true) }
            fetchForm(opening)
        }
    }

    private suspend fun fetchForm(opening: Int) {
        _state.update { it.copy(isLoadingForm = true, formAvailable = false, formError = false) }
        val binding = submission
        try {
            val owned = binding.drafts.ownsCurrentAccount()
            if (opening != generation) return
            if (owned) {
                val form = dependencies.repository.fetchComposeForm(REPORT_RECIPIENT)
                acceptOwnedForm(form, binding, opening)
            } else {
                sessionExpired()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SessionExpiredException) {
            if (opening == generation) sessionExpired()
        } catch (_: IOException) {
            if (opening == generation) formFailed()
        } catch (_: IllegalStateException) {
            if (opening == generation) formFailed()
        } catch (_: IllegalArgumentException) {
            if (opening == generation) formFailed()
        }
    }

    private suspend fun acceptOwnedForm(
        form: ReplyForm,
        binding: EditorSubmissionBinding<ReportContentEffect, ReportError>,
        opening: Int,
    ) {
        val owned = binding.drafts.ownsCurrentAccount()
        if (opening == generation) {
            if (owned) acceptForm(form) else sessionExpired()
        }
    }

    private fun acceptForm(form: ReplyForm) {
        if (form.isAnonymous) {
            sessionExpired()
        } else {
            loadedForm = form
            _state.update { it.copy(isLoadingForm = false, formAvailable = true) }
        }
    }

    private fun sessionExpired() {
        loadedForm = null
        _state.update { it.copy(isLoadingForm = false, formAvailable = false, error = ReportError.SESSION_EXPIRED) }
    }

    private fun formFailed() {
        loadedForm = null
        _state.update { it.copy(isLoadingForm = false, formAvailable = false, formError = true) }
    }

    fun onIntent(intent: ReportContentIntent) {
        when (intent) {
            is ReportContentIntent.ReasonChanged -> edit { copy(reason = intent.reason) }
            is ReportContentIntent.DescriptionChanged -> edit { copy(description = intent.description) }
            is ReportContentIntent.LinkChanged -> edit { copy(link = intent.link) }
            ReportContentIntent.RetryForm -> retryForm()
            ReportContentIntent.Submit -> submit()
            is ReportContentIntent.SubmitWithTimeout -> submit(timeoutMillis = intent.timeoutMillis)
            is ReportContentIntent.Confirm -> confirm(intent.token)
            ReportContentIntent.DismissConfirmation -> submission.disarm()
            ReportContentIntent.Close -> close()
            ReportContentIntent.Login -> login()
            ReportContentIntent.Email -> email()
            ReportContentIntent.CheckMessages -> checkMessages()
        }
    }

    private fun login() {
        val current = _state.value
        val loginRequired = current.session == ReportSession.ANONYMOUS || current.error == ReportError.SESSION_EXPIRED
        if (loginRequired && !submission.blocksClose) _effects.trySend(ReportContentEffect.Login)
    }

    private fun email() {
        if (_state.value.session == ReportSession.ANONYMOUS) _effects.trySend(ReportContentEffect.Email)
    }

    private fun confirm(token: ArmToken?) {
        if (token != null) submit(token)
    }

    private fun edit(change: ReportDraft.() -> ReportDraft) {
        if (!_state.value.canEdit || submission.blocksMutation || closeRequested) return
        submission.disarm()
        _state.update { it.copy(draft = it.draft.change()) }
        submission.autosave(::draftSnapshot)
    }

    private fun draftSnapshot(): EditorDraftStore.Draft? =
        _state.value.takeIf { it.draftReady }?.draft?.let(ReportDraftCodec::encode)

    private fun retryForm() {
        if (_state.value.session != ReportSession.AUTHENTICATED || submission.blocksMutation) return
        val opening = generation
        formJob?.cancel()
        loadedForm = null
        if (_state.value.draftReady) {
            formJob = viewModelScope.launch { fetchForm(opening) }
        } else {
            submission.accountChanged()
            submission = newSubmission()
            formJob = viewModelScope.launch { restoreAndLoad(opening) }
        }
    }

    private fun submit(token: ArmToken? = null, timeoutMillis: Long = 4_000L) {
        val snapshot = _state.value
        val form = loadedForm
        if (!snapshot.canSubmit || closeRequested || form == null) return
        val permit = if (token == null) {
            submission.request(timeoutMillis, snapshot.canSubmit)
        } else {
            submission.confirm(token)
        }
        if (permit is SubmissionRequest.Accepted) {
            submission.launch(permit, ReportDraftCodec.encode(snapshot.draft)) {
                delivery(dependencies.repository.submitNewMessage(
                    form = form, recipients = REPORT_RECIPIENT, subject = REPORT_SUBJECT,
                    bbcodeContent = ReportMessageFormatter.format(snapshot.draft, initial.build),
                    options = ReplyFormOptions(
                        signatureEnabled = false, smileyDisabled = true, emailNotificationEnabled = false,
                    ),
                ))
            }
        }
    }

    private fun delivery(result: ReplySubmitResult): EditorDelivery<ReportContentEffect, ReportError> = when (result) {
        is ReplySubmitResult.Success -> EditorDelivery(effect = ReportContentEffect.SubmitSucceeded)
        is ReplySubmitResult.Failure -> EditorDelivery(
            error = result.reason.reportError(),
            afterSettled = if (result.reason == ReplyFailureReason.InvalidHashCheck) ({ retryForm() }) else null,
        )
    }

    private fun checkMessages() {
        val opening = generation
        val binding = submission
        if (!submission.blocksClose) viewModelScope.launch {
            val result = binding.flush(draftSnapshot())
            if (opening == generation && result is EditorDraftStore.StoreWriteResult.Written) {
                _effects.send(ReportContentEffect.CheckMessages)
            }
        }
    }

    private fun close() {
        if (submission.blocksClose || closeRequested) return
        submission.disarm()
        closeRequested = true
        val opening = generation
        val binding = submission
        viewModelScope.launch {
            val result = binding.flush(draftSnapshot())
            if (opening == generation) {
                if (result == null || result is EditorDraftStore.StoreWriteResult.Written) {
                    _effects.send(ReportContentEffect.CloseCommitted)
                } else {
                    closeRequested = false
                }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(initial: ReportInitialContent): ReportContentViewModel
    }
}

private fun ReplyFailureReason.reportError(): ReportError = when (this) {
    ReplyFailureReason.EmptyMessage -> ReportError.EMPTY_MESSAGE
    ReplyFailureReason.InvalidHashCheck -> ReportError.INVALID_HASH
    ReplyFailureReason.AntiFlood -> ReportError.ANTI_FLOOD
    ReplyFailureReason.UnknownRecipient -> ReportError.UNKNOWN_RECIPIENT
    ReplyFailureReason.LoginRequired -> ReportError.SESSION_EXPIRED
    ReplyFailureReason.TopicLocked -> ReportError.REFUSED
    ReplyFailureReason.Unknown -> ReportError.UNCERTAIN
}
