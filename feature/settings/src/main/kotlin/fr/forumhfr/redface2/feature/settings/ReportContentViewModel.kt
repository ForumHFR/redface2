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
import fr.forumhfr.redface2.core.domain.editor.EditorSendOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionBinding
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionCallbacks
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionEnvironment
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionErrors
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
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
    @Assisted private val build: ReportBuildInfo,
    private val dependencies: ReportDependencies,
) : ViewModel() {
    private val _state = MutableStateFlow(ReportContentUiState())
    val state = _state.asStateFlow()
    private val _effects = Channel<ReportContentEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()
    private var confirmPreference: Boolean? = null
    private var generation = 0
    private var submission = newSubmission()
    private var activeOwner: String? = null
    private var authObserved = false
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
        drafts = EditorDraftSession(reportDraftStore(), EditorDraftKey.report()),
        callbacks = EditorSubmissionCallbacks<ReportContentEffect, ReportError>(
            state = { value -> _state.update { it.copy(submission = value) } },
            error = ::submissionError,
            success = { value -> _effects.trySend(value) },
        ),
        errors = EditorSubmissionErrors(
            uncertain = ReportError.UNCERTAIN, draftWriteFailed = ReportError.DRAFT_WRITE_FAILED,
            sessionExpired = ReportError.SESSION_EXPIRED, unknown = ReportError.UNCERTAIN,
            network = ReportError.NETWORK, preparationFailed = ReportError.INVALID_FORM,
        ),
    ).also { it.setPreference(confirmPreference) }

    // Observe content-write results separately: storage and uncertain delivery can both be displayed.
    private fun reportDraftStore(): EditorDraftStore {
        val opening = generation
        return object : EditorDraftStore by dependencies.drafts {
            override suspend fun saveIfVersion(
                owner: String?, key: String, draft: EditorDraftStore.Draft, expectedVersion: Long,
            ) = dependencies.drafts.saveIfVersion(owner, key, draft, expectedVersion).also {
                draftWritten(it, opening)
            }

            override suspend fun saveUnresolved(
                token: EditorDraftStore.AttemptToken, draft: EditorDraftStore.Draft, expectedVersion: Long,
            ) = dependencies.drafts.saveUnresolved(token, draft, expectedVersion).also {
                draftWritten(it, opening)
            }

            override suspend fun startNewComposition(
                owner: String?, key: String, draft: EditorDraftStore.Draft, expectedVersion: Long,
            ) = dependencies.drafts.startNewComposition(owner, key, draft, expectedVersion).also {
                draftWritten(it, opening)
            }
        }
    }

    private fun draftWritten(result: EditorDraftStore.StoreWriteResult, opening: Int) {
        if (opening == generation) {
            _state.update { it.copy(draftWriteFailed = result !is EditorDraftStore.StoreWriteResult.Written) }
        }
    }

    private fun submissionError(error: ReportError?) {
        _state.update {
            // A storage failure cannot resolve an attempt whose delivery is still unknown.
            if (error == ReportError.DRAFT_WRITE_FAILED) {
                it.copy(draftWriteFailed = true)
            } else {
                it.copy(error = error, draftWriteFailed = false)
            }
        }
    }

    private fun authChanged(auth: AuthState) {
        val owner = (auth as? AuthState.Authenticated)?.pseudo?.lowercase()
        if (authObserved && owner == activeOwner) return
        authObserved = true
        activeOwner = owner
        generation++
        formJob?.cancel()
        submission.accountChanged()
        closeRequested = false
        loadedForm = null
        val session = when {
            owner == null -> ReportSession.ANONYMOUS
            owner.trim().equals(REPORT_RECIPIENT, ignoreCase = true) -> ReportSession.RECIPIENT
            else -> ReportSession.AUTHENTICATED
        }
        _state.value = ReportContentUiState(session = session)
        if (session == ReportSession.AUTHENTICATED) {
            submission = newSubmission()
            val opening = generation
            formJob = viewModelScope.launch { restoreAndLoad(opening) }
        }
    }

    private suspend fun restoreAndLoad(opening: Int) {
        val binding = submission
        val entry = binding.restore(EditorDraftKey.report())
        if (opening != generation) return
        if (entry == null && _state.value.draftWriteFailed) return
        if (binding.drafts.owner == activeOwner) {
            acceptDraft(entry, opening)
        } else {
            binding.accountChanged()
            sessionExpired()
        }
    }

    private suspend fun acceptDraft(entry: EditorDraftStore.DraftEntry?, opening: Int) {
        val draft = entry?.let { ReportDraftCodec.decode(it) }
        if (entry != null && draft == null) {
            _state.update { it.copy(draftWriteFailed = true) }
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
            _state.update {
                it.copy(
                    isLoadingForm = false, formAvailable = true, sessionExpired = false,
                    error = when {
                        it.error != ReportError.SESSION_EXPIRED -> it.error
                        it.submission is EditorSubmissionState.Uncertain -> ReportError.UNCERTAIN
                        else -> null
                    },
                )
            }
        }
    }

    private fun sessionExpired() {
        loadedForm = null
        _state.update {
            it.copy(
                isLoadingForm = false, formAvailable = false, sessionExpired = true,
                error = if (it.submission is EditorSubmissionState.Uncertain) {
                    ReportError.UNCERTAIN
                } else {
                    ReportError.SESSION_EXPIRED
                },
            )
        }
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
        if (current.requiresLogin && !submission.blocksClose && !closeRequested) {
            _effects.trySend(ReportContentEffect.Login)
        }
    }

    private fun email() {
        val session = _state.value.session
        if (session == ReportSession.ANONYMOUS || session == ReportSession.RECIPIENT) {
            _effects.trySend(ReportContentEffect.Email)
        }
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
        if (closeRequested || _state.value.session != ReportSession.AUTHENTICATED || submission.blocksMutation) return
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
                sendReport(form, snapshot.draft)
            }
        }
    }

    private suspend fun sendReport(
        form: ReplyForm,
        draft: ReportDraft,
    ): EditorDelivery<ReportContentEffect, ReportError> {
        val opening = generation
        return try {
            delivery(dependencies.repository.submitNewMessage(
                form = form, recipients = REPORT_RECIPIENT, subject = REPORT_SUBJECT,
                bbcodeContent = ReportMessageFormatter.format(draft, build),
                options = ReplyFormOptions(
                    signatureEnabled = false, smileyDisabled = true, emailNotificationEnabled = false,
                ),
            ))
        } catch (expired: SessionExpiredException) {
            if (opening == generation) sessionExpired()
            throw expired
        }
    }

    private fun delivery(result: ReplySubmitResult): EditorDelivery<ReportContentEffect, ReportError> = when (result) {
        is ReplySubmitResult.Success -> EditorDelivery(effect = ReportContentEffect.SubmitSucceeded)
        is ReplySubmitResult.Failure -> EditorDelivery(
            outcome = if (result.reason == ReplyFailureReason.Unknown) {
                EditorSendOutcome.UNCERTAIN
            } else {
                EditorSendOutcome.CERTAIN_FAILURE
            },
            error = result.reason.reportError(),
            afterSettled = if (result.reason == ReplyFailureReason.InvalidHashCheck) ({ retryForm() }) else null,
        )
    }

    private fun checkMessages() {
        if (submission.blocksClose || closeRequested) return
        submission.disarm()
        closeRequested = true
        val opening = generation
        val binding = submission
        val snapshot = draftSnapshot()
        viewModelScope.launch {
            val result = binding.flush(snapshot)
            if (opening == generation) {
                if (result is EditorDraftStore.StoreWriteResult.Written && !binding.blocksClose) {
                    _effects.send(ReportContentEffect.CheckMessages)
                } else {
                    closeRequested = false
                }
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
        fun create(build: ReportBuildInfo): ReportContentViewModel
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
