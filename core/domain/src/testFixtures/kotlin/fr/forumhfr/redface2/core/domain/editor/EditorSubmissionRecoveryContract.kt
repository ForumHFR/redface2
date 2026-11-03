package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Astra gate regressions: real adapters, writes suspended at commit/return and cleanup boundaries. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class EditorSubmissionRecoveryContract {
    protected var submissionScenario = EditorSubmissionScenario()
    protected abstract fun createAdapter(
        scope: TestScope,
        preferences: Flow<Boolean>,
        delayedSharedRestore: Boolean,
    ): EditorSubmissionAdapter

    /** Gate Astra: accepted success stays locked while durable cleanup is suspended. */
    @Test
    fun acceptedSuccessBlocksCloseUntilCleanup() = withReadyAdapter { adapter ->
        val gate = CompletableDeferred<Unit>()
        submissionScenario.store.cleanupGate = gate
        startSending(adapter)
        adapter.completePost()
        runCurrent()
        assertEquals(0, adapter.successEffects)
        assertTrue(adapter.snapshot().blocksClose)
        adapter.close()
        runCurrent()
        assertEquals(0, adapter.closeEffects)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, adapter.successEffects)
    }

    /** Gate Astra: editing and closing must retain both the new text and uncertainty. */
    @Test
    fun editsAfterUncertaintySurviveCloseAndRecreation() {
        val scenario = EditorSubmissionScenario().apply { postFailure = IOException("response lost") }
        withReadyAdapter(scenario = scenario) { adapter ->
            startSending(adapter)
            adapter.completePost()
            runCurrent()
            adapter.editBody("edited after uncertainty")
            assertEquals(SubmissionErrorProbe.UNCERTAIN, adapter.snapshot().error)
            adapter.close()
            runCurrent()
            assertEquals("edited after uncertainty", scenario.store.entries.values.single().draft.body)
            scenario.recreateEditor()
            runCurrent()
            assertEquals("edited after uncertainty", adapter.snapshot().body)
            assertEquals(SubmissionErrorProbe.UNCERTAIN, adapter.snapshot().error)
            assertFalse(adapter.snapshot().restoreOffered)
        }
    }

    /** An unrecognised POST response cannot prove refusal: retain the attempt across reopening. */
    @Test
    fun unknownPostResponsePersistsAndRestoresUncertainty() {
        val scenario = EditorSubmissionScenario().apply { postRejection = ReplyFailureReason.Unknown }
        withReadyAdapter(scenario = scenario) { adapter ->
            startSending(adapter)
            val body = adapter.snapshot().body
            adapter.completePost()
            runCurrent()
            val entry = scenario.store.entries.values.single()
            assertEquals(EditorDraftStore.AttemptStatus.UNCERTAIN, entry.attempt?.status)
            assertEquals(body, entry.draft.body)
            assertEquals(body, adapter.snapshot().body)
            assertEquals(SubmissionErrorProbe.UNCERTAIN, adapter.snapshot().error)
            assertFalse(adapter.snapshot().isSending)
            assertEquals(0, adapter.successEffects)
            advanceTimeBy(751)
            runCurrent()
            assertEquals("aucun renvoi automatique après une réponse inconnue", 1, adapter.postCalls)
            scenario.recreateEditor()
            advanceTimeBy(751)
            runCurrent()
            assertEquals(body, adapter.snapshot().body)
            assertEquals(SubmissionErrorProbe.UNCERTAIN, adapter.snapshot().error)
            assertEquals(entry.attempt, scenario.store.entries.values.single().attempt)
            assertFalse(adapter.snapshot().restoreOffered)
            assertFalse(adapter.snapshot().confirmationVisible)
            assertEquals("aucun renvoi automatique à la réouverture", 1, adapter.postCalls)
            assertEquals(0, adapter.successEffects)
        }
    }

    @Test
    fun recognisedPostRejectionRemainsCertain() {
        val scenario = EditorSubmissionScenario().apply { postRejection = ReplyFailureReason.AntiFlood }
        withReadyAdapter(scenario = scenario) { adapter ->
            startSending(adapter)
            val body = adapter.snapshot().body
            adapter.completePost()
            runCurrent()
            val entry = scenario.store.entries.values.single()
            assertNull("un refus reconnu efface le marqueur de tentative", entry.attempt)
            assertEquals(body, entry.draft.body)
            assertEquals(body, adapter.snapshot().body)
            assertTrue(adapter.snapshot().error != SubmissionErrorProbe.UNCERTAIN)
            assertFalse(adapter.snapshot().isSending)
            assertTrue(adapter.snapshot().canSubmit)
            assertEquals(0, adapter.successEffects)
            assertEquals(1, adapter.postCalls)
        }
    }

    /** Gate Astra: owner validation already passed inside storage when the account changes. */
    @Test
    fun accountChangeAfterAttemptCommitRevokesEmission() = withReadyAdapter { adapter ->
        val gate = CompletableDeferred<Unit>()
        submissionScenario.store.beginReturnGate = gate
        adapter.clickSubmit()
        runCurrent()
        assertTrue(submissionScenario.store.entries.values.single().attempt != null)
        submissionScenario.switchAccount()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, adapter.postCalls)
        assertEquals(0, adapter.successEffects)
    }

    @Test
    fun ownerIsRecheckedEvenBeforeAuthObserverRuns() = withReadyAdapter { adapter ->
        val gate = CompletableDeferred<Unit>()
        submissionScenario.store.beginReturnGate = gate
        adapter.clickSubmit()
        runCurrent()
        // Deliberately leave the VM's auth flow unchanged: the emission boundary must read the owner too.
        submissionScenario.store.activeOwner = "other"
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, adapter.postCalls)
        assertEquals(0, adapter.successEffects)
    }

    @Test
    fun rejectedAccountAtTransportIsCertainAndResolvesTheAttempt() {
        val scenario = EditorSubmissionScenario().apply { postFailure = EditorAccountChangedBeforeSendException() }
        withReadyAdapter(scenario = scenario) { adapter ->
            startSending(adapter)
            adapter.completePost()
            runCurrent()
            assertFalse(adapter.snapshot().isSending)
            assertTrue(adapter.snapshot().canSubmit)
            assertTrue(adapter.snapshot().error != SubmissionErrorProbe.UNCERTAIN)
            assertTrue(scenario.store.entries.values.single().attempt == null)
            assertEquals(0, adapter.successEffects)
        }
    }

    @Test
    fun accountChangeDuringPostSuppressesOldSuccess() {
        val scenario = EditorSubmissionScenario().apply { ignorePostCancellation = true }
        withReadyAdapter(scenario = scenario) { adapter ->
            startSending(adapter)
            scenario.switchAccount()
            runCurrent()
            adapter.completePost()
            runCurrent()
            assertEquals("le faux POST a rendu son succès malgré l'annulation", 1, scenario.returnedPosts)
            assertEquals(0, adapter.successEffects)
        }
    }

    /** Gate Astra: cancellation after commit cannot leave the session's CAS version behind. */
    @Test
    fun cancelledAutosaveAdoptsCommittedVersionBeforeNextSave() = withReadyAdapter { adapter ->
        val gate = CompletableDeferred<Unit>()
        submissionScenario.store.saveReturnGate = gate
        advanceTimeBy(751)
        runCurrent()
        adapter.editBody("next keystroke")
        advanceTimeBy(751)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals("next keystroke", submissionScenario.store.entries.values.single().draft.body)
        startSending(adapter)
    }

    @Test
    fun invalidHashRefreshWaitsForDurableOutcome() {
        val scenario = EditorSubmissionScenario().apply { postRejection = ReplyFailureReason.InvalidHashCheck }
        withReadyAdapter(scenario = scenario) { adapter ->
            val gate = CompletableDeferred<Unit>()
            scenario.store.finishGate = gate
            startSending(adapter)
            val fetches = adapter.formFetchCalls
            adapter.completePost()
            runCurrent()
            assertEquals("aucun nouveau formulaire sous le verrou d'envoi", fetches, adapter.formFetchCalls)
            gate.complete(Unit)
            runCurrent()
            assertTrue("rechargement après l'issue durable", adapter.formFetchCalls > fetches)
            assertEquals("contract body", adapter.snapshot().body)
        }
    }

    /** Gate Astra: process loss restores IN_PROGRESS; arming must keep its identity until confirmation. */
    @Test
    fun restoredAttemptCanBeDeliberatelyRetriedAfterArming() {
        val preference = MutableStateFlow(false)
        withReadyAdapter(preference) { adapter ->
            startSending(adapter)
            val previous = submissionScenario.store.entries.values.single().attempt?.token
            preference.value = true
            submissionScenario.recreateEditor()
            runCurrent()
            assertEquals(SubmissionErrorProbe.UNCERTAIN, adapter.snapshot().error)
            adapter.editBody("edited before deliberate retry")
            advanceTimeBy(751)
            runCurrent()
            assertEquals(previous, submissionScenario.store.entries.values.single().attempt?.token)
            adapter.clickSubmit()
            runCurrent()
            assertTrue(adapter.snapshot().confirmationVisible)
            advanceTimeBy(400)
            adapter.confirm()
            runCurrent()
            assertEquals(2, adapter.postCalls)
            assertTrue(previous != submissionScenario.store.entries.values.single().attempt?.token)
            assertEquals("edited before deliberate retry", submissionScenario.store.entries.values.single().draft.body)
        }
    }

    @Test
    fun restoredAttemptSurvivesArmDismissal() = restoredArmCanBeRetried(expire = false)

    @Test
    fun restoredAttemptSurvivesArmExpiration() = restoredArmCanBeRetried(expire = true)

    private fun restoredArmCanBeRetried(expire: Boolean) {
        val preference = MutableStateFlow(false)
        withReadyAdapter(preference) { adapter ->
            startSending(adapter)
            preference.value = true
            submissionScenario.recreateEditor()
            runCurrent()
            adapter.clickSubmit()
            runCurrent()
            val previousArm = adapter.snapshot().armId
            if (expire) advanceTimeBy(4_000) else adapter.dismissConfirmation()
            runCurrent()
            assertFalse(adapter.snapshot().confirmationVisible)
            adapter.confirmIdentity(previousArm)
            runCurrent()
            assertEquals(1, adapter.postCalls)
            adapter.clickSubmit()
            runCurrent()
            advanceTimeBy(400)
            adapter.confirm()
            runCurrent()
            assertEquals("la tentative restaurée doit être acquittée avant le renvoi", 2, adapter.postCalls)
        }
    }

    protected fun TestScope.startSending(adapter: EditorSubmissionAdapter) {
        adapter.clickSubmit()
        runCurrent()
        assertEquals("le POST doit avoir atteint le repository suspendu", 1, adapter.postCalls)
        assertTrue(adapter.snapshot().isSending)
    }

    protected fun withReadyAdapter(
        preferences: Flow<Boolean> = flowOf(false),
        delayedSharedRestore: Boolean = false,
        scenario: EditorSubmissionScenario = EditorSubmissionScenario(),
        block: suspend TestScope.(EditorSubmissionAdapter) -> Unit,
    ) = runTest {
        submissionScenario = scenario
        val adapter = createAdapter(this, preferences, delayedSharedRestore)
        try {
            runCurrent()
            adapter.editBody("contract body")
            runCurrent()
            assertTrue("formulaire valide avant l'exercice du contrat", adapter.snapshot().canSubmit)
            block(adapter)
        } finally {
            adapter.dispose()
            runCurrent()
        }
    }
}
