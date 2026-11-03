package fr.forumhfr.redface2.feature.settings

import app.cash.turbine.test
import androidx.lifecycle.viewModelScope
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.EditorWriteOwner
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReportContentViewModelTest {
    private val auth = MutableStateFlow<AuthState>(AuthState.Authenticated("tester"))
    private val repository = mockk<PrivateMessageWriteRepository>()
    private val store = VersionedEditorDraftStore()
    private val viewModels = mutableListOf<ReportContentViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        coEvery { repository.fetchComposeForm(any()) } returns reportComposeForm()
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(null, null)
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun newReportStartsWithAnEmptyLinkAndAcceptsAManualLink() = runTest {
        val vm = create()
        assertEquals("", vm.state.value.draft.link)
        runCurrent()
        assertEquals("", vm.state.value.draft.link)
        vm.onIntent(ReportContentIntent.LinkChanged("https://forum.hardware.fr/"))
        assertEquals("https://forum.hardware.fr/", vm.state.value.draft.link)
    }

    @Test
    fun anonymousSessionNeverFetchesOrSubmits() = runTest {
        auth.value = AuthState.Anonymous
        val vm = create()
        runCurrent()
        vm.onIntent(ReportContentIntent.Submit)
        runCurrent()
        assertEquals(ReportSession.ANONYMOUS, vm.state.value.session)
        assertEquals("", vm.state.value.draft.link)
        coVerify(exactly = 0) { repository.fetchComposeForm(any()) }
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
        auth.value = AuthState.Authenticated("tester")
        runCurrent()
        assertTrue(vm.state.value.formAvailable)
        assertEquals("", vm.state.value.draft.link)
    }

    @Test
    fun checkingSessionNeverFetchesOrSubmits() = runTest {
        val pendingAuth = MutableSharedFlow<AuthState>()
        val vm = create(pendingAuth)
        runCurrent()
        vm.onIntent(ReportContentIntent.Submit)
        runCurrent()
        assertEquals(ReportSession.CHECKING, vm.state.value.session)
        coVerify(exactly = 0) { repository.fetchComposeForm(any()) }
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun formLoadFailureCanBeRetriedWithoutLosingInput() = runTest {
        coEvery { repository.fetchComposeForm(any()) } throws IllegalStateException("no form")
        val vm = create()
        runCurrent()
        fill(vm)
        val before = vm.state.value.draft
        assertTrue(vm.state.value.formError)
        assertFalse(vm.state.value.canSubmit)
        coEvery { repository.fetchComposeForm(any()) } returns reportComposeForm()
        vm.onIntent(ReportContentIntent.RetryForm)
        runCurrent()
        assertEquals(before, vm.state.value.draft)
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun anonymousFormRequiresLoginWithoutAllowingPost() = runTest {
        coEvery { repository.fetchComposeForm(any()) } returns reportComposeForm().copy(isAnonymous = true)
        val vm = create()
        runCurrent()
        fill(vm)
        vm.onIntent(ReportContentIntent.Submit)
        runCurrent()
        assertEquals(ReportError.SESSION_EXPIRED, vm.state.value.error)
        assertFalse(vm.state.value.canSubmit)
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun authenticatedFormAfterSameAccountLoginClearsOnlyTheSessionError() = runTest {
        coEvery { repository.fetchComposeForm(any()) } returns reportComposeForm().copy(isAnonymous = true)
        val vm = create()
        runCurrent()
        fill(vm)
        val before = vm.state.value.draft
        assertEquals(ReportError.SESSION_EXPIRED, vm.state.value.error)
        vm.effects.test {
            vm.onIntent(ReportContentIntent.Login)
            assertEquals(ReportContentEffect.Login, awaitItem())
            // Même compte : le StateFlow ne réémet pas, ON_RESUME demande RetryForm.
            auth.value = AuthState.Authenticated("tester")
            coEvery { repository.fetchComposeForm(any()) } returns reportComposeForm()
            vm.onIntent(ReportContentIntent.RetryForm)
            runCurrent()
            expectNoEvents()
        }
        assertEquals(null, vm.state.value.error)
        assertEquals(before, vm.state.value.draft)
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun authenticatedFormReloadPreservesUncertainty() = runTest {
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        val vm = create()
        runCurrent()
        fill(vm)
        vm.onIntent(ReportContentIntent.Submit)
        runCurrent()
        val before = vm.state.value.draft

        vm.onIntent(ReportContentIntent.RetryForm)
        runCurrent()

        assertTrue(vm.state.value.formAvailable)
        assertEquals(ReportError.UNCERTAIN, vm.state.value.error)
        assertEquals(before, vm.state.value.draft)
        coVerify(exactly = 1) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun sessionRecoveryKeepsTheWarningForAnUnresolvedAttempt() = runTest {
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        val vm = create()
        runCurrent()
        fill(vm)
        vm.onIntent(ReportContentIntent.Submit)
        runCurrent()
        val before = vm.state.value.draft
        coEvery { repository.fetchComposeForm(any()) } returns reportComposeForm().copy(isAnonymous = true)
        vm.onIntent(ReportContentIntent.RetryForm)
        runCurrent()
        assertEquals(ReportError.UNCERTAIN, vm.state.value.error)
        assertTrue(vm.state.value.requiresLogin)

        coEvery { repository.fetchComposeForm(any()) } returns reportComposeForm()
        vm.onIntent(ReportContentIntent.RetryForm)
        runCurrent()

        assertTrue(vm.state.value.formAvailable)
        assertFalse(vm.state.value.requiresLogin)
        assertEquals(ReportError.UNCERTAIN, vm.state.value.error)
        assertEquals(before, vm.state.value.draft)
        coVerify(exactly = 1) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun sessionExpiryAfterSendPersistsAndRestoresUncertaintyWithoutResending() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
            throw SessionExpiredException("https://forum.hardware.fr/login.php")
        }
        val vm = create()
        runCurrent()
        fill(vm)
        val before = vm.state.value.draft
        vm.effects.test {
            vm.onIntent(ReportContentIntent.Submit)
            runCurrent()
            assertTrue(vm.state.value.submission is EditorSubmissionState.Sending)
            assertEquals(EditorDraftStore.AttemptStatus.IN_PROGRESS, store.entries.values.single().attempt?.status)
            gate.complete(Unit)
            runCurrent()
            expectNoEvents()
        }
        val attempt = store.entries.getValue(EditorDraftKey.report()).attempt
        assertEquals(EditorDraftStore.AttemptStatus.UNCERTAIN, attempt?.status)
        assertEquals(ReportError.UNCERTAIN, vm.state.value.error)
        assertTrue(vm.state.value.requiresLogin)
        assertFalse(vm.state.value.canSubmit)
        assertEquals(before, vm.state.value.draft)
        assertEquals(before, ReportDraftCodec.decode(store.entries.values.single().draft))
        vm.viewModelScope.cancel()

        coEvery { repository.fetchComposeForm(any()) } throws
            SessionExpiredException("https://forum.hardware.fr/login.php")
        val restored = create()
        runCurrent()
        restored.effects.test {
            restored.onIntent(ReportContentIntent.Submit)
            runCurrent()
            expectNoEvents()
        }
        assertTrue(restored.state.value.submission is EditorSubmissionState.Uncertain)
        assertEquals(ReportError.UNCERTAIN, restored.state.value.error)
        assertTrue(restored.state.value.requiresLogin)
        assertEquals(before, restored.state.value.draft)
        assertEquals(attempt, store.entries.getValue(EditorDraftKey.report()).attempt)
        coVerify(exactly = 1) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun loginAfterSessionExpiryDuringSendPreservesTheUnresolvedAttempt() = runTest {
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } throws
            SessionExpiredException("https://forum.hardware.fr/login.php")
        val vm = create()
        runCurrent()
        fill(vm)
        val before = vm.state.value.draft
        vm.effects.test {
            vm.onIntent(ReportContentIntent.Submit)
            runCurrent()
            expectNoEvents()
            val attempt = store.entries.getValue(EditorDraftKey.report()).attempt
            assertEquals(EditorDraftStore.AttemptStatus.UNCERTAIN, attempt?.status)
            assertTrue(vm.state.value.requiresLogin)
            vm.onIntent(ReportContentIntent.Login)
            assertEquals(ReportContentEffect.Login, awaitItem())
            assertEquals(ReportError.UNCERTAIN, vm.state.value.error)
            assertEquals(attempt, store.entries.getValue(EditorDraftKey.report()).attempt)

            // Même compte : ON_RESUME recharge le formulaire sans réémettre le POST.
            auth.value = AuthState.Authenticated("tester")
            vm.onIntent(ReportContentIntent.RetryForm)
            runCurrent()
            expectNoEvents()
            assertFalse(vm.state.value.requiresLogin)
            assertTrue(vm.state.value.canSubmit)
            assertTrue(vm.state.value.submission is EditorSubmissionState.Uncertain)
            assertEquals(ReportError.UNCERTAIN, vm.state.value.error)
            assertEquals(before, vm.state.value.draft)
            assertEquals(attempt, store.entries.getValue(EditorDraftKey.report()).attempt)
        }
        coVerify(exactly = 2) { repository.fetchComposeForm("XaTelitte") }
        coVerify(exactly = 1) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun transportGuardRefusalBeforeEmissionRemainsCertain() = runTest {
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } coAnswers {
            checkNotNull(currentCoroutineContext()[EditorWriteOwner]).rejectBeforeEmission()
        }
        val vm = create()
        runCurrent()
        fill(vm)
        val before = vm.state.value.draft
        vm.effects.test {
            vm.onIntent(ReportContentIntent.Submit)
            runCurrent()
            expectNoEvents()
        }
        assertEquals(ReportError.SESSION_EXPIRED, vm.state.value.error)
        assertEquals(EditorSubmissionState.Ready, vm.state.value.submission)
        assertEquals(null, store.entries.getValue(EditorDraftKey.report()).attempt)
        assertEquals(before, vm.state.value.draft)
        coVerify(exactly = 1) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun lateSessionExpiryAfterAccountChangeIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } coAnswers {
            withContext(NonCancellable) {
                gate.await()
                throw SessionExpiredException("https://forum.hardware.fr/login.php")
            }
        }
        val vm = create()
        runCurrent()
        fill(vm)
        vm.effects.test {
            vm.onIntent(ReportContentIntent.Submit)
            runCurrent()
            store.activeOwner = "other"
            auth.value = AuthState.Authenticated("other")
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            expectNoEvents()
        }
        assertEquals(ReportDraft(), vm.state.value.draft)
        assertTrue(vm.state.value.formAvailable)
        assertFalse(vm.state.value.requiresLogin)
        assertEquals(null, vm.state.value.error)
    }

    @Test
    fun submitUsesFixedRecipientAndOptions() = runTest {
        val vm = create()
        runCurrent()
        fill(vm)
        vm.effects.test {
            vm.onIntent(ReportContentIntent.Submit)
            runCurrent()
            assertEquals(ReportContentEffect.SubmitSucceeded, awaitItem())
            expectNoEvents()
        }
        coVerify(exactly = 1) { repository.fetchComposeForm("XaTelitte") }
        coVerify(exactly = 1) {
            repository.submitNewMessage(
                any(), "XaTelitte", "Redface 2 — Signalement", any(),
                ReplyFormOptions(signatureEnabled = false, smileyDisabled = true, emailNotificationEnabled = false),
            )
        }
        assertFalse(vm.state.value.canSubmit)
    }

    @Test
    fun unknownKeepsEveryFieldAndEmitsNoSuccess() = runTest {
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        val vm = create()
        runCurrent()
        fill(vm)
        val before = vm.state.value.draft
        vm.effects.test {
            vm.onIntent(ReportContentIntent.Submit)
            runCurrent()
            expectNoEvents()
        }
        assertEquals(before, vm.state.value.draft)
        assertEquals(ReportError.UNCERTAIN, vm.state.value.error)
        assertTrue(store.entries.values.single().draft.isPrivate)
    }

    @Test
    fun autosaveFailureAndRecoveryPreserveAnUnknownDelivery() = runTest {
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        val vm = create()
        runCurrent()
        fill(vm)
        vm.onIntent(ReportContentIntent.Submit)
        runCurrent()
        val attempt = store.entries.getValue(EditorDraftKey.report()).attempt
        assertEquals(EditorDraftStore.AttemptStatus.UNCERTAIN, attempt?.status)

        vm.effects.test {
            store.saveFailure = IOException("storage unavailable")
            vm.onIntent(ReportContentIntent.DescriptionChanged("Edited after unknown delivery"))
            advanceTimeBy(751)
            runCurrent()
            assertTrue(vm.state.value.draftWriteFailed)
            assertEquals(ReportError.UNCERTAIN, vm.state.value.error)
            assertTrue(vm.state.value.submission is EditorSubmissionState.Uncertain)
            assertTrue(vm.state.value.canSubmit)
            assertEquals(attempt, store.entries.getValue(EditorDraftKey.report()).attempt)
            expectNoEvents()

            store.saveFailure = null
            vm.onIntent(ReportContentIntent.DescriptionChanged("Saved after storage recovery"))
            advanceTimeBy(751)
            runCurrent()
            assertEquals(vm.state.value.draft, ReportDraftCodec.decode(store.entries.values.single().draft))
            assertFalse(vm.state.value.draftWriteFailed)
            assertEquals(ReportError.UNCERTAIN, vm.state.value.error)
            assertTrue(vm.state.value.submission is EditorSubmissionState.Uncertain)
            assertTrue(vm.state.value.canSubmit)
            assertEquals(attempt, store.entries.getValue(EditorDraftKey.report()).attempt)
            expectNoEvents()
        }
        coVerify(exactly = 1) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun invalidHashReloadPreservesInput() = runTest {
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)
        val vm = create()
        runCurrent()
        fill(vm)
        val before = vm.state.value.draft
        vm.onIntent(ReportContentIntent.Submit)
        runCurrent()
        assertEquals(before, vm.state.value.draft)
        assertEquals(ReportError.INVALID_HASH, vm.state.value.error)
        coVerify(exactly = 2) { repository.fetchComposeForm("XaTelitte") }
        coVerify(exactly = 1) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun knownRefusalsPreserveEveryFieldAndResolveTheAttempt() = runTest {
        val refusals = listOf(
            ReplyFailureReason.AntiFlood to ReportError.ANTI_FLOOD,
            ReplyFailureReason.UnknownRecipient to ReportError.UNKNOWN_RECIPIENT,
        )
        refusals.forEach { (reason, error) ->
            coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
                ReplySubmitResult.Failure(reason)
            val vm = create()
            runCurrent()
            fill(vm)
            val before = vm.state.value.draft
            vm.effects.test {
                vm.onIntent(ReportContentIntent.Submit)
                runCurrent()
                expectNoEvents()
            }
            assertEquals(error, vm.state.value.error)
            assertEquals(before, vm.state.value.draft)
            assertEquals(null, store.entries.getValue(EditorDraftKey.report()).attempt)
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun reasonDescriptionAndLinkAreFrozenBeforeDurableSaveCompletes() = runTest {
        val gate = CompletableDeferred<Unit>()
        store.beginGate = gate
        val vm = create()
        runCurrent()
        fill(vm)
        val before = vm.state.value.draft
        vm.onIntent(ReportContentIntent.Submit)
        runCurrent()
        vm.onIntent(ReportContentIntent.ReasonChanged(ReportReason.OTHER))
        vm.onIntent(ReportContentIntent.DescriptionChanged("Late text"))
        vm.onIntent(ReportContentIntent.LinkChanged("https://example.org/late"))
        assertEquals(before, vm.state.value.draft)
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
        gate.complete(Unit)
        runCurrent()
        coVerify(exactly = 1) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun closeFlushesEveryFieldToThePrivateReportKey() = runTest {
        val vm = create()
        runCurrent()
        fill(vm)
        val before = vm.state.value.draft
        vm.effects.test {
            vm.onIntent(ReportContentIntent.Close)
            runCurrent()
            assertEquals(ReportContentEffect.CloseCommitted, awaitItem())
        }
        assertEquals(setOf(EditorDraftKey.report()), store.entries.keys)
        val stored = store.entries.values.single().draft
        assertTrue(stored.isPrivate)
        assertEquals(before, ReportDraftCodec.decode(stored))
    }

    @Test
    fun messageCheckReservesBeforeFlushAndBlocksEditsRetryAndDuplicateNavigation() = runTest {
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        val vm = create()
        runCurrent()
        fill(vm)
        vm.onIntent(ReportContentIntent.Submit)
        runCurrent()
        val before = vm.state.value.draft
        val gate = CompletableDeferred<Unit>()
        store.saveReturnGate = gate
        vm.effects.test {
            vm.onIntent(ReportContentIntent.CheckMessages)
            // Aucun dispatch entre la réservation et ces actions concurrentes.
            fillWithLateEdits(vm)
            vm.onIntent(ReportContentIntent.Submit)
            runCurrent()
            vm.onIntent(ReportContentIntent.CheckMessages)
            vm.onIntent(ReportContentIntent.Close)
            fillWithLateEdits(vm)
            vm.onIntent(ReportContentIntent.Submit)
            runCurrent()
            assertEquals(before, vm.state.value.draft)
            expectNoEvents()
            coVerify(exactly = 1) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
            gate.complete(Unit)
            runCurrent()
            assertEquals(ReportContentEffect.CheckMessages, awaitItem())
            expectNoEvents()
        }
        assertEquals(before, ReportDraftCodec.decode(store.entries.values.single().draft))
        assertEquals(ReportError.UNCERTAIN, vm.state.value.error)
    }

    @Test
    fun failedMessageCheckKeepsTheFormOpenAndReleasesTheReservation() = runTest {
        val vm = create()
        runCurrent()
        fill(vm)
        store.saveFailure = IOException("storage unavailable")
        vm.effects.test {
            vm.onIntent(ReportContentIntent.CheckMessages)
            runCurrent()
            expectNoEvents()
            assertTrue(vm.state.value.draftWriteFailed)
            assertEquals(null, vm.state.value.error)
            store.saveFailure = null
            vm.onIntent(ReportContentIntent.DescriptionChanged("Updated after failed save"))
            assertEquals("Updated after failed save", vm.state.value.draft.description)
            vm.onIntent(ReportContentIntent.CheckMessages)
            runCurrent()
            assertEquals(ReportContentEffect.CheckMessages, awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun messageCheckFlushFailureAndRecoveryPreserveAnUnknownDelivery() = runTest {
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        val vm = create()
        runCurrent()
        fill(vm)
        vm.onIntent(ReportContentIntent.Submit)
        runCurrent()
        val before = vm.state.value.draft
        val attempt = store.entries.getValue(EditorDraftKey.report()).attempt
        assertEquals(EditorDraftStore.AttemptStatus.UNCERTAIN, attempt?.status)

        vm.effects.test {
            store.saveFailure = IOException("storage unavailable")
            vm.onIntent(ReportContentIntent.CheckMessages)
            runCurrent()
            expectNoEvents()
            assertTrue(vm.state.value.draftWriteFailed)
            assertEquals(ReportError.UNCERTAIN, vm.state.value.error)
            assertTrue(vm.state.value.submission is EditorSubmissionState.Uncertain)
            assertTrue(vm.state.value.canSubmit)
            assertEquals(before, vm.state.value.draft)
            assertEquals(attempt, store.entries.getValue(EditorDraftKey.report()).attempt)

            store.saveFailure = null
            vm.onIntent(ReportContentIntent.CheckMessages)
            runCurrent()
            assertEquals(ReportContentEffect.CheckMessages, awaitItem())
            expectNoEvents()
        }
        assertEquals(before, ReportDraftCodec.decode(store.entries.values.single().draft))
        assertFalse(vm.state.value.draftWriteFailed)
        assertEquals(ReportError.UNCERTAIN, vm.state.value.error)
        assertTrue(vm.state.value.submission is EditorSubmissionState.Uncertain)
        assertEquals(attempt, store.entries.getValue(EditorDraftKey.report()).attempt)
        coVerify(exactly = 1) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun accountChangeWhileMessageCheckFlushesDoesNotNavigate() = runTest {
        val vm = create()
        runCurrent()
        fill(vm)
        val gate = CompletableDeferred<Unit>()
        store.saveReturnGate = gate
        vm.effects.test {
            vm.onIntent(ReportContentIntent.CheckMessages)
            runCurrent()
            store.activeOwner = "other"
            auth.value = AuthState.Authenticated("other")
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            expectNoEvents()
        }
        assertEquals(ReportDraft(), vm.state.value.draft)
        assertTrue(vm.state.value.formAvailable)
    }

    @Test
    fun messageCheckDuringPostDoesNotNavigate() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        }
        val vm = create()
        runCurrent()
        fill(vm)
        vm.effects.test {
            vm.onIntent(ReportContentIntent.Submit)
            runCurrent()
            vm.onIntent(ReportContentIntent.CheckMessages)
            runCurrent()
            expectNoEvents()
            gate.complete(Unit)
            runCurrent()
            expectNoEvents()
        }
        assertEquals(ReportError.UNCERTAIN, vm.state.value.error)
    }

    @Test
    fun processLossRestoresAllFieldsAsUncertainWithoutResending() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
            ReplySubmitResult.Success(null, null)
        }
        val vm = create()
        runCurrent()
        fill(vm)
        val before = vm.state.value.draft
        vm.onIntent(ReportContentIntent.Submit)
        runCurrent()
        assertEquals(EditorDraftStore.AttemptStatus.IN_PROGRESS, store.entries.values.single().attempt?.status)
        vm.viewModelScope.cancel()
        val restored = create()
        runCurrent()
        assertEquals(before, restored.state.value.draft)
        assertEquals(ReportError.UNCERTAIN, restored.state.value.error)
        coVerify(exactly = 1) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun lateFormAfterAccountChangeIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { repository.fetchComposeForm(any()) } coAnswers {
            val thisCall = ++calls
            if (thisCall == 1) withContext(NonCancellable) { gate.await() }
            if (thisCall == 1) reportComposeForm() else throw IOException("new form unavailable")
        }
        val vm = create()
        runCurrent()
        fill(vm)
        store.activeOwner = "other"
        auth.value = AuthState.Authenticated("other")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(ReportDraft(), vm.state.value.draft)
        assertFalse(vm.state.value.formAvailable)
        assertTrue(vm.state.value.formError)
    }

    @Test
    fun draftOfAnAccountNotYetObservedIsNeverDisplayed() = runTest {
        store.activeOwner = "other"
        store.preload(EditorDraftKey.report(), ReportDraftCodec.encode(
            ReportDraft(ReportReason.OTHER, "Other account private description"),
        ))
        val vm = create()
        runCurrent()
        assertEquals(ReportDraft(), vm.state.value.draft)
        assertEquals(ReportError.SESSION_EXPIRED, vm.state.value.error)
        coVerify(exactly = 0) { repository.fetchComposeForm(any()) }
    }

    private fun TestScope.create(authStates: Flow<AuthState> = auth): ReportContentViewModel {
        val authRepository = mockk<AuthRepository>()
        every { authRepository.observeAuthState() } returns authStates
        val preferences = mockk<UserPreferencesRepository>()
        every { preferences.observeConfirmBeforePosting() } returns flowOf(false)
        return ReportContentViewModel(
            ReportBuildInfo("0.65.0", 123, "beta"),
            ReportDependencies(repository, authRepository, preferences, store, DiagnosticsLog(),
                DiagnosticsClock { testScheduler.currentTime * 1_000_000L }),
        ).also { viewModels += it }
    }

    private fun fill(vm: ReportContentViewModel) {
        vm.onIntent(ReportContentIntent.ReasonChanged(ReportReason.CHILD_SAFETY))
        vm.onIntent(ReportContentIntent.DescriptionChanged("Description privée"))
        vm.onIntent(ReportContentIntent.LinkChanged("https://forum.hardware.fr/"))
    }

    private fun fillWithLateEdits(vm: ReportContentViewModel) {
        vm.onIntent(ReportContentIntent.ReasonChanged(ReportReason.OTHER))
        vm.onIntent(ReportContentIntent.DescriptionChanged("Late description"))
        vm.onIntent(ReportContentIntent.LinkChanged("https://example.org/late"))
    }
}

internal fun reportComposeForm() = ReplyForm(
    sujet = "", hashCheck = "test-token",
    hiddenFields = mapOf("cat" to "prive"), isAnonymous = false,
)
