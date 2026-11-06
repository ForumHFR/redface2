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
    fun recipientSessionNeverFetchesOrSubmitsRegardlessOfCaseAndWhitespace() = runTest {
        listOf("XaTriX", "xatrix", " \tXaTrIx \n").forEach { pseudo ->
            store.activeOwner = "xatrix"
            auth.value = AuthState.Authenticated(pseudo)
            val vm = create()
            runCurrent()
            fill(vm)
            vm.onIntent(ReportContentIntent.RetryForm)
            vm.onIntent(ReportContentIntent.Submit)
            vm.onIntent(ReportContentIntent.SubmitWithTimeout(4_000L))
            runCurrent()
            assertEquals(ReportSession.RECIPIENT, vm.state.value.session)
            assertEquals(ReportDraft(), vm.state.value.draft)
            assertFalse(vm.state.value.requiresLogin)
            assertFalse(vm.state.value.canEdit)
            assertFalse(vm.state.value.canSubmit)
            assertFalse(vm.state.value.formAvailable)
            vm.viewModelScope.cancel()
        }
        coVerify(exactly = 0) { repository.fetchComposeForm(any()) }
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun recipientCanEmailAndCloseWithoutChangingTheStoredDraft() = runTest {
        store.activeOwner = "xatrix"
        auth.value = AuthState.Authenticated("XaTriX")
        store.preload(EditorDraftKey.report(), ReportDraftCodec.encode(
            ReportDraft(ReportReason.OTHER, "Description privée du destinataire"),
        ))
        val before = store.entries.getValue(EditorDraftKey.report())
        val vm = create()
        runCurrent()
        vm.effects.test {
            vm.onIntent(ReportContentIntent.Email)
            assertEquals(ReportContentEffect.Email, awaitItem())
            vm.onIntent(ReportContentIntent.Close)
            runCurrent()
            assertEquals(ReportContentEffect.CloseCommitted, awaitItem())
            expectNoEvents()
        }
        assertEquals(before, store.entries.getValue(EditorDraftKey.report()))
        coVerify(exactly = 0) { repository.fetchComposeForm(any()) }
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun accountChangesToggleRecipientBlockAndPurgeTheOtherAccountsDraft() = runTest {
        store.activeOwner = "xatrix"
        auth.value = AuthState.Authenticated("XaTriX")
        val vm = create()
        runCurrent()
        assertEquals(ReportSession.RECIPIENT, vm.state.value.session)

        switchAccount("tester")
        runCurrent()
        fill(vm)
        advanceTimeBy(751)
        runCurrent()
        assertTrue(store.entries.containsKey(EditorDraftKey.report()))
        assertTrue(vm.state.value.canSubmit)

        switchAccount("xatrix")
        runCurrent()
        assertEquals(ReportSession.RECIPIENT, vm.state.value.session)
        assertEquals(ReportDraft(), vm.state.value.draft)
        assertFalse(vm.state.value.canSubmit)
        fillWithLateEdits(vm)
        vm.onIntent(ReportContentIntent.RetryForm)
        vm.onIntent(ReportContentIntent.Submit)
        runCurrent()
        coVerify(exactly = 1) { repository.fetchComposeForm("XaTriX") }

        switchAccount("tester")
        runCurrent()
        assertEquals(ReportSession.AUTHENTICATED, vm.state.value.session)
        assertEquals(ReportDraft(), vm.state.value.draft)
        assertFalse(store.entries.containsKey(EditorDraftKey.report()))
        assertTrue(vm.state.value.canEdit)
        assertTrue(vm.state.value.formAvailable)
        assertFalse(vm.state.value.canSubmit)
        coVerify(exactly = 2) { repository.fetchComposeForm("XaTriX") }
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun uncertainAttemptPurgedOnSwitchToRecipientRestoresAUsableFormAfterMessageCheckAndReopening() = runTest {
        assertPurgedAttemptRecovery("XaTriX", ReportSession.RECIPIENT)
    }

    @Test
    fun uncertainAttemptPurgedOnSwitchToAnotherAccountRestoresAUsableFormAfterMessageCheckAndReopening() = runTest {
        assertPurgedAttemptRecovery("other", ReportSession.AUTHENTICATED)
    }

    @Test
    fun certainFailureAfterPurgeOnSwitchToRecipientRestoresAnEmptyUsableForm() = runTest {
        assertPurgedCertainFailureRecovery("XaTriX")
    }

    @Test
    fun certainFailureAfterPurgeOnSwitchToAnotherAccountRestoresAnEmptyUsableForm() = runTest {
        assertPurgedCertainFailureRecovery("other")
    }

    @Test
    fun corruptDraftWithContentRemainsRejectedAndCannotBeOverwritten() = runTest {
        val key = EditorDraftKey.report()
        store.preload(key, EditorDraftStore.Draft("Private content", isPrivate = true))
        val before = store.entries.getValue(key)
        val vm = create()
        runCurrent()
        assertTrue(vm.state.value.draftWriteFailed)
        assertFalse(vm.state.value.draftReady)
        assertFalse(vm.state.value.canEdit)

        vm.effects.test {
            fillWithLateEdits(vm)
            vm.onIntent(ReportContentIntent.RetryForm)
            runCurrent()
            vm.onIntent(ReportContentIntent.Submit)
            vm.onIntent(ReportContentIntent.CheckMessages)
            advanceTimeBy(751)
            runCurrent()
            expectNoEvents()
        }
        assertTrue(vm.state.value.draftWriteFailed)
        assertFalse(vm.state.value.draftReady)
        assertEquals(before, store.entries.getValue(key))
        assertEquals(0, store.saveCount)
        coVerify(exactly = 0) { repository.fetchComposeForm(any()) }
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun purgedDraftRewriteCannotOverwriteANewerContentRevision() = runTest {
        val key = EditorDraftKey.report()
        store.preload(key, EditorDraftStore.Draft("", isPrivate = true))
        val vm = create()
        runCurrent()
        assertTrue(vm.state.value.canEdit)
        val newer = ReportDraftCodec.encode(ReportDraft(ReportReason.OTHER, "Newer private content"))
        assertEquals(
            EditorDraftStore.StoreWriteResult.Written(1),
            store.saveIfVersion("tester", key, newer, expectedVersion = 0),
        )
        val before = store.entries.getValue(key)

        vm.effects.test {
            vm.onIntent(ReportContentIntent.Close)
            runCurrent()
            expectNoEvents()
        }
        assertTrue(vm.state.value.draftWriteFailed)
        assertEquals(before, store.entries.getValue(key))
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
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
            checkNotNull(currentCoroutineContext()[EditorWriteOwner]).markEmissionPossible()
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
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } coAnswers {
            checkNotNull(currentCoroutineContext()[EditorWriteOwner]).markEmissionPossible()
            throw SessionExpiredException("https://forum.hardware.fr/login.php")
        }
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
        coVerify(exactly = 2) { repository.fetchComposeForm("XaTriX") }
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
            switchAccount("other")
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
        store.activeOwner = "xatelitte"
        auth.value = AuthState.Authenticated("XaTelitte")
        val vm = create()
        runCurrent()
        fill(vm)
        vm.effects.test {
            vm.onIntent(ReportContentIntent.Submit)
            runCurrent()
            assertEquals(ReportContentEffect.SubmitSucceeded, awaitItem())
            expectNoEvents()
        }
        coVerify(exactly = 1) { repository.fetchComposeForm("XaTriX") }
        coVerify(exactly = 1) {
            repository.submitNewMessage(
                any(), "XaTriX", "Redface 2 — Signalement", any(),
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
        coVerify(exactly = 2) { repository.fetchComposeForm("XaTriX") }
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
            switchAccount("other")
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
            checkNotNull(currentCoroutineContext()[EditorWriteOwner]).markEmissionPossible()
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
        switchAccount("other")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(ReportDraft(), vm.state.value.draft)
        assertFalse(vm.state.value.formAvailable)
        assertTrue(vm.state.value.formError)
    }

    @Test
    fun lateFormAfterSwitchingToRecipientDoesNotReactivateTheForm() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.fetchComposeForm(any()) } coAnswers {
            withContext(NonCancellable) { gate.await() }
            reportComposeForm()
        }
        val vm = create()
        runCurrent()
        switchAccount("XaTriX")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        vm.onIntent(ReportContentIntent.RetryForm)
        vm.onIntent(ReportContentIntent.Submit)
        runCurrent()
        assertEquals(ReportSession.RECIPIENT, vm.state.value.session)
        assertEquals(ReportDraft(), vm.state.value.draft)
        assertFalse(vm.state.value.formAvailable)
        assertFalse(vm.state.value.canSubmit)
        assertEquals(null, vm.state.value.error)
        coVerify(exactly = 1) { repository.fetchComposeForm("XaTriX") }
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun lateSuccessAfterSwitchingToRecipientEmitsNoResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } coAnswers {
            withContext(NonCancellable) { gate.await() }
            ReplySubmitResult.Success(null, null)
        }
        val vm = create()
        runCurrent()
        fill(vm)
        vm.effects.test {
            vm.onIntent(ReportContentIntent.Submit)
            runCurrent()
            assertTrue(vm.state.value.isSending)
            switchAccount("XaTriX")
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            expectNoEvents()
        }
        assertEquals(ReportSession.RECIPIENT, vm.state.value.session)
        assertEquals(ReportDraft(), vm.state.value.draft)
        assertFalse(vm.state.value.formAvailable)
        assertFalse(vm.state.value.isSending)
        assertFalse(vm.state.value.canSubmit)
        assertEquals(null, vm.state.value.error)
        coVerify(exactly = 1) { repository.fetchComposeForm("XaTriX") }
        coVerify(exactly = 1) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
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

    private suspend fun TestScope.assertPurgedAttemptRecovery(pseudo: String, expectedSession: ReportSession) {
        val key = EditorDraftKey.report()
        val draft = ReportDraft(ReportReason.CHILD_SAFETY, "Description privée", "https://forum.hardware.fr/")
        val started = store.beginAttempt("tester", key, ReportDraftCodec.encode(draft), 0)
        val token = (started as EditorDraftStore.BeginAttemptResult.Started).token
        store.finishAttempt(token, EditorDraftStore.AttemptOutcome.UNCERTAIN)
        val before = store.entries.getValue(key)
        val vm = create()
        runCurrent()
        assertEquals(draft, vm.state.value.draft)
        assertEquals(EditorSubmissionState.Uncertain(token), vm.state.value.submission)

        vm.effects.test {
            switchAccount(pseudo)
            runCurrent()
            assertEquals(expectedSession, vm.state.value.session)
            assertEquals(ReportDraft(), vm.state.value.draft)
            assertEquals(null, vm.state.value.error)
            switchAccount("tester")
            runCurrent()
            assertEquals(
                before.copy(draft = before.draft.copy(body = "", subject = null, recipients = null)),
                store.entries.getValue(key),
            )
            assertEquals(ReportDraft(), vm.state.value.draft)
            assertTrue(vm.state.value.draftReady)
            assertTrue(vm.state.value.canEdit)
            assertTrue(vm.state.value.formAvailable)
            assertFalse(vm.state.value.draftWriteFailed)
            assertFalse(vm.state.value.canSubmit)
            assertEquals(ReportError.UNCERTAIN, vm.state.value.error)
            assertEquals(EditorSubmissionState.Uncertain(token), vm.state.value.submission)

            vm.onIntent(ReportContentIntent.Submit)
            vm.onIntent(ReportContentIntent.RetryForm)
            runCurrent()
            expectNoEvents()
            assertEquals(ReportDraft(), vm.state.value.draft)
            assertTrue(vm.state.value.formAvailable)
            assertTrue(vm.state.value.canEdit)
            assertEquals(ReportError.UNCERTAIN, vm.state.value.error)
            vm.onIntent(ReportContentIntent.CheckMessages)
            runCurrent()
            assertEquals(ReportContentEffect.CheckMessages, awaitItem())
            expectNoEvents()
        }
        assertEquals(before.attempt, store.entries.getValue(key).attempt)
        assertEquals(ReportDraftCodec.encode(ReportDraft()), store.entries.getValue(key).draft)
        vm.viewModelScope.cancel()

        val reopened = create()
        runCurrent()
        assertEquals(ReportDraft(), reopened.state.value.draft)
        assertTrue(reopened.state.value.draftReady)
        assertTrue(reopened.state.value.canEdit)
        assertTrue(reopened.state.value.formAvailable)
        assertFalse(reopened.state.value.draftWriteFailed)
        assertFalse(reopened.state.value.canSubmit)
        assertEquals(ReportError.UNCERTAIN, reopened.state.value.error)
        assertEquals(EditorSubmissionState.Uncertain(token), reopened.state.value.submission)
        assertEquals(before.attempt, store.entries.getValue(key).attempt)
        fill(reopened)
        assertTrue(reopened.state.value.canSubmit)
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    private suspend fun TestScope.assertPurgedCertainFailureRecovery(pseudo: String) {
        val vm = returnAfterPurgedCertainFailure(pseudo)
        val key = EditorDraftKey.report()
        val purged = store.entries.getValue(key)
        assertEquals(null, purged.attempt)
        assertEquals(EditorDraftStore.Draft("", isPrivate = true), purged.draft)
        assertEquals(ReportDraft(), vm.state.value.draft)
        assertTrue(vm.state.value.draftReady)
        assertTrue(vm.state.value.canEdit)
        assertTrue(vm.state.value.formAvailable)
        assertFalse(vm.state.value.canSubmit)
        assertFalse(vm.state.value.draftWriteFailed)
        assertEquals(null, vm.state.value.error)
        assertEquals(EditorSubmissionState.Ready, vm.state.value.submission)

        vm.effects.test {
            vm.onIntent(ReportContentIntent.Submit)
            vm.onIntent(ReportContentIntent.RetryForm)
            runCurrent()
            expectNoEvents()
            assertTrue(vm.state.value.canEdit)
            assertEquals(null, vm.state.value.error)
            fill(vm)
            assertTrue(vm.state.value.canSubmit)
            vm.onIntent(ReportContentIntent.Close)
            runCurrent()
            assertEquals(ReportContentEffect.CloseCommitted, awaitItem())
            expectNoEvents()
        }
        val saved = store.entries.getValue(key)
        assertEquals(purged.version + 1, saved.version)
        assertEquals(null, saved.attempt)
        assertEquals(ReportDraftCodec.encode(vm.state.value.draft), saved.draft)
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    private suspend fun TestScope.returnAfterPurgedCertainFailure(pseudo: String): ReportContentViewModel {
        val key = EditorDraftKey.report()
        val draft = ReportDraftCodec.encode(ReportDraft(ReportReason.OTHER, "Private content"))
        store.beginAttempt("tester", key, draft, expectedVersion = 0)
        val vm = create()
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        store.finishGate = gate
        // Retrying first resolves the restored attempt without entering the write repository.
        vm.onIntent(ReportContentIntent.Submit)
        runCurrent()
        try {
            assertTrue(vm.state.value.isSending)
            switchAccount(pseudo)
            runCurrent()
        } finally {
            // Metadata-only CERTAIN_FAILURE completes even after accountChanged cancels execution.
            gate.complete(Unit)
        }
        runCurrent()
        switchAccount("tester")
        runCurrent()
        return vm
    }

    private fun switchAccount(pseudo: String) {
        val owner = pseudo.lowercase()
        store.activeOwner?.takeIf { it != owner }?.let(store::purgePrivateForUser)
        store.activeOwner = owner
        auth.value = AuthState.Authenticated(pseudo)
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
