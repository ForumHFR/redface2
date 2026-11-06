package fr.forumhfr.redface2.feature.settings

import androidx.lifecycle.viewModelScope
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionAdapter
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionContract
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionScenario
import fr.forumhfr.redface2.core.domain.editor.SubmissionBodyMutation
import fr.forumhfr.redface2.core.domain.editor.SubmissionErrorProbe
import fr.forumhfr.redface2.core.domain.editor.SubmissionProbe
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** Runs the shared durable-send contract against the real report ViewModel, without a network. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportContentSubmissionContractTest : EditorSubmissionContract() {
    override fun createAdapter(
        scope: TestScope,
        preferences: Flow<Boolean>,
        delayedSharedRestore: Boolean,
    ): EditorSubmissionAdapter = ReportSubmissionAdapter(scope, preferences, submissionScenario)
}

@OptIn(ExperimentalCoroutinesApi::class)
private class ReportSubmissionAdapter(
    private val scope: TestScope,
    preferences: Flow<Boolean>,
    private val scenario: EditorSubmissionScenario,
) : EditorSubmissionAdapter {
    private val auth = MutableStateFlow<AuthState>(AuthState.Authenticated("tester"))
    private val gate = CompletableDeferred<Unit>()
    private val effects = mutableListOf<ReportContentEffect>()
    private val arms = mutableMapOf<Long, ArmToken>()
    private val dependencies = dependencies(preferences)
    private var vm = createViewModel()
    private var collector = collectEffects()
    override var postCalls = 0
        private set
    override var formFetchCalls = 0
        private set

    init {
        scenario.recreate = {
            collector.cancel()
            vm.viewModelScope.cancel()
            vm = createViewModel()
            collector = collectEffects()
        }
        scenario.replaceCollector = {
            collector.cancel()
            collector = collectEffects()
        }
        scenario.changeAccount = {
            scenario.store.activeOwner = "other"
            auth.value = AuthState.Authenticated("other")
        }
    }

    private fun dependencies(preferences: Flow<Boolean>): ReportDependencies {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } coAnswers {
            formFetchCalls++
            reportComposeForm()
        }
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } coAnswers {
            scenario.prepare()
            postCalls++
            scenario.awaitPost(gate)
            scenario.postFailure?.let { throw it }
            scenario.postRejection?.let { ReplySubmitResult.Failure(it) } ?: ReplySubmitResult.Success(null, null)
        }
        val authRepository = mockk<AuthRepository>()
        every { authRepository.observeAuthState() } returns auth
        val userPreferences = mockk<UserPreferencesRepository>()
        every { userPreferences.observeConfirmBeforePosting() } returns preferences
        return ReportDependencies(repository, authRepository, userPreferences, scenario.store, DiagnosticsLog(),
            DiagnosticsClock { scope.testScheduler.currentTime * 1_000_000L })
    }

    private fun createViewModel() = ReportContentViewModel(
        ReportBuildInfo("0.65.0", 123, "prod"), dependencies,
    )

    private fun collectEffects() = scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
        vm.effects.collect { effects += it }
    }

    override val unsupportedMutations = SubmissionBodyMutation.entries.filter { it != SubmissionBodyMutation.TYPING }
        .associateWith { "le formulaire de signalement n'expose ni toolbar, ni upload, ni offre de restauration" }
    override val uploadUnsupportedReason = "aucun upload dans le signalement"
    override val uploadCalls = 0
    override val draftSaves get() = scenario.store.saveCount
    override val draftWrites get() = scenario.store.writes
    override val successEffects get() = effects.count { it == ReportContentEffect.SubmitSucceeded }
    override val closeEffects get() = effects.count { it == ReportContentEffect.CloseCommitted }

    override fun editBody(body: String) {
        vm.onIntent(ReportContentIntent.ReasonChanged(ReportReason.OTHER))
        vm.onIntent(ReportContentIntent.DescriptionChanged(body))
    }

    override fun clickSubmit() = vm.onIntent(ReportContentIntent.Submit)
    override fun confirm() = vm.onIntent(ReportContentIntent.Confirm(vm.state.value.armToken))
    override fun confirmIdentity(id: Long?) = vm.onIntent(ReportContentIntent.Confirm(arms[id]))
    override fun dismissConfirmation() = vm.onIntent(ReportContentIntent.DismissConfirmation)
    override fun close() = vm.onIntent(ReportContentIntent.Close)
    override fun completePost() { gate.complete(Unit) }
    override fun dispose() = vm.viewModelScope.cancel()
    override fun startUpload() = Unit
    override fun completeUpload() = Unit
    override fun deliverLateUploadCallback() = Unit
    override fun mutateBody(mutation: SubmissionBodyMutation) {
        check(mutation == SubmissionBodyMutation.TYPING) { "unsupported mutation" }
        editBody("changed during POST")
    }

    override fun snapshot(): SubmissionProbe = vm.state.value.let {
        it.armToken?.let { token -> arms[token.id] = token }
        SubmissionProbe(
            body = it.draft.description, canSubmit = it.canSubmit, isSending = it.isSending,
            isUploading = false, confirmationVisible = it.armToken != null, armId = it.armToken?.id,
            error = when (it.error) {
                ReportError.REFUSED -> SubmissionErrorProbe.PREPARATION_FAILED
                ReportError.NETWORK -> SubmissionErrorProbe.NETWORK
                ReportError.SESSION_EXPIRED -> SubmissionErrorProbe.SESSION_EXPIRED
                ReportError.UNCERTAIN -> SubmissionErrorProbe.UNCERTAIN
                else -> if (it.draftWriteFailed) SubmissionErrorProbe.DRAFT_WRITE_FAILED else null
            },
            blocksClose = it.submission.blocksBodyMutation,
        )
    }
}
