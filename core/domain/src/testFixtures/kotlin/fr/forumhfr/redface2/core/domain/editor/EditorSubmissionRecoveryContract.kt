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
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Astra gate regressions: real adapters, writes suspended at commit/return and cleanup boundaries. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class EditorSubmissionRecoveryContract {
    protected var submissionScenario = EditorSubmissionScenario()
    private var independentOffers = emptyMap<String, List<EditorDraftStore.DraftOffer>>()
    protected abstract fun createAdapter(
        scope: TestScope,
        preferences: Flow<Boolean>,
        delayedSharedRestore: Boolean,
    ): EditorSubmissionAdapter

    /** All five surfaces: an autosave failure cannot turn an unknown POST into non-emission. */
    @Test
    fun autosaveFailurePreservesUncertaintyAfterTyping() {
        val scenario = EditorSubmissionScenario().apply { postFailure = IOException("response lost") }
        withReadyAdapter(scenario = scenario) { adapter ->
            startSending(adapter)
            adapter.completePost()
            runCurrent()
            val attempt = scenario.store.entries.values.single().attempt
            scenario.store.saveFailure = IOException("disk full")
            adapter.editBody("edited after uncertainty")
            advanceTimeBy(751)
            runCurrent()
            assertEquals(SubmissionErrorProbe.UNCERTAIN, adapter.snapshot().error)
            assertEquals("edited after uncertainty", adapter.snapshot().body)
            assertEquals(attempt, scenario.store.entries.values.single().attempt)
            assertEquals(1, adapter.postCalls)
        }
    }

    /** The pre-POST attempt failure keeps its certain diagnostic despite later draft failures. */
    @Test
    fun autosaveFailureDoesNotReplaceAttemptWriteFailure() {
        val scenario = EditorSubmissionScenario().apply { store.beginFailure = IOException("disk full") }
        withReadyAdapter(scenario = scenario) { adapter ->
            adapter.clickSubmit()
            runCurrent()
            assertEquals(SubmissionErrorProbe.DRAFT_WRITE_FAILED, adapter.snapshot().error)
            scenario.store.saveFailure = IOException("still full")
            adapter.editBody("keep this text")
            advanceTimeBy(751)
            runCurrent()
            assertEquals(SubmissionErrorProbe.DRAFT_WRITE_FAILED, adapter.snapshot().error)
            assertEquals("keep this text", adapter.snapshot().body)
            assertEquals(0, adapter.postCalls)
        }
    }

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
            assertOnlyIndependentOffers(adapter)
        }
    }

    /** An unrecognised POST response cannot prove refusal: retain the attempt across reopening. */
    @Test
    fun unknownPostResponsePersistsAndRestoresUncertainty() = unknownResponse(preloadIndependentDraft = false)

    @Test
    fun unknownPostResponseKeepsPreloadedIndependentDraft() = unknownResponse(preloadIndependentDraft = true)

    private fun unknownResponse(preloadIndependentDraft: Boolean) {
        val scenario = EditorSubmissionScenario().apply { postRejection = ReplyFailureReason.Unknown }
        withReadyAdapter(scenario = scenario, preloadIndependentDraft = preloadIndependentDraft) { adapter ->
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
            assertOnlyIndependentOffers(adapter)
            assertFalse(adapter.snapshot().confirmationVisible)
            assertEquals("aucun renvoi automatique à la réouverture", 1, adapter.postCalls)
            assertEquals(0, adapter.successEffects)
        }
    }

    @Test
    fun restoreIndependentDraftAfterPublishedSend() = resolveIndependentDraft(uncertain = false, restoring = true)

    @Test
    fun ignoreIndependentDraftAfterPublishedSend() = resolveIndependentDraft(uncertain = false, restoring = false)

    @Test
    fun restoreIndependentDraftAfterUncertainSend() = resolveIndependentDraft(uncertain = true, restoring = true)

    @Test
    fun ignoreIndependentDraftAfterUncertainSend() = resolveIndependentDraft(uncertain = true, restoring = false)

    @Test
    fun failedRestoreDoesNotBlockTypingOrDuplicateTheMerge() =
        resolveIndependentDraft(uncertain = false, restoring = true, failResolution = true)

    @Test
    fun failedRestoreAfterUncertainSendDoesNotSealAutosave() =
        resolveIndependentDraft(uncertain = true, restoring = true, failResolution = true)

    @Test
    fun typingDuringOfferResolutionIsSavedAfterItsCallback() = withReadyAdapter(
        preloadIndependentDraft = true,
    ) { adapter ->
        val key = submissionScenario.store.entries.keys.single()
        assertTrue("ED-121 keeps offered drafts on this surface", keepsOfferedDrafts(key))
        startSending(adapter)
        adapter.completePost()
        runCurrent()
        submissionScenario.recreateEditor()
        runCurrent()
        assertOnlyIndependentOffers(adapter)
        val gate = CompletableDeferred<Unit>()
        submissionScenario.store.saveReturnGate = gate
        val offered = checkNotNull(adapter.snapshot().offeredBody)
        adapter.resolveOfferedDraft(restoring = true)
        runCurrent()
        val continued = adapter.snapshot().body + " typed while resolving"
        adapter.editBody(continued)
        runCurrent()
        gate.complete(Unit)
        advanceTimeBy(751)
        runCurrent()
        assertEquals(continued, submissionScenario.store.entries.getValue(key).draft.body)
        assertOfferDecided(adapter, key, offered)
        assertEquals(1, adapter.postCalls)
    }

    /** ED-121 applies to replies, the sheet, new topics and new MPs; edits and reports keep their contract. */
    private fun resolveIndependentDraft(uncertain: Boolean, restoring: Boolean, failResolution: Boolean = false) {
        val scenario = EditorSubmissionScenario().apply {
            if (uncertain) postFailure = IOException("response lost")
        }
        withReadyAdapter(scenario = scenario, preloadIndependentDraft = true) { adapter ->
            val key = scenario.store.entries.keys.single()
            assertTrue("ED-121 keeps offered drafts on this surface", keepsOfferedDrafts(key))
            startSending(adapter)
            adapter.completePost()
            runCurrent()
            val attempt = scenario.store.entries.getValue(key).attempt
            assertEquals(if (uncertain) EditorDraftStore.AttemptStatus.UNCERTAIN
                else EditorDraftStore.AttemptStatus.PUBLISHED, attempt?.status)
            scenario.recreateEditor()
            runCurrent()
            assertOnlyIndependentOffers(adapter)
            val before = adapter.snapshot().body
            val offered = checkNotNull(adapter.snapshot().offeredBody)
            if (failResolution) scenario.store.saveFailure = IOException("disk full")
            adapter.resolveOfferedDraft(restoring)
            runCurrent()
            val resolvedBody = adapter.snapshot().body
            if (failResolution) {
                assertEquals(offered, adapter.snapshot().offeredBody)
                scenario.store.saveFailure = null
                adapter.editBody(resolvedBody + " more typing")
                advanceTimeBy(751)
                runCurrent()
                assertEquals(adapter.snapshot().body, scenario.store.entries.getValue(key).draft.body)
                // ED-121: the autosave of the merged field may already have consumed D. A retry exists
                // only while that same draft is still offered, and it must not merge it twice.
                if (adapter.snapshot().offeredBody == offered) {
                    adapter.resolveOfferedDraft(restoring)
                    runCurrent()
                }
                assertEquals(resolvedBody + " more typing", adapter.snapshot().body)
            }
            assertOfferDecided(adapter, key, offered)
            if (restoring) assertTrue(adapter.snapshot().body.contains(offered))
            else assertEquals(before, adapter.snapshot().body)
            assertEquals(adapter.snapshot().body, scenario.store.entries.getValue(key).draft.body)
            val expected = independentOffers[key].orEmpty().filterNot { it.draft.body == offered }
            assertEquals(expected, scenario.store.loadOffers(scenario.store.currentOwner(), key))
            val resolvedAttempt = scenario.store.entries.getValue(key).attempt
            if (uncertain) assertEquals(attempt, resolvedAttempt) else assertNull(resolvedAttempt)
            adapter.editBody("typing after the decision")
            advanceTimeBy(751)
            runCurrent()
            assertEquals("typing after the decision", scenario.store.entries.getValue(key).draft.body)
            assertEquals("no automatic resend", 1, adapter.postCalls)
            if (!uncertain) {
                adapter.clickSubmit()
                runCurrent()
                assertEquals("the next send is not blocked by offer resolution", 2, adapter.postCalls)
            }
        }
    }

    @Test
    fun recognisedPostRejectionRemainsCertain() = recognisedRejection(ReplyFailureReason.AntiFlood)

    @Test
    fun unknownRecipientRejectionRemainsCertain() = recognisedRejection(ReplyFailureReason.UnknownRecipient)

    private fun recognisedRejection(reason: ReplyFailureReason) {
        val scenario = EditorSubmissionScenario().apply { postRejection = reason }
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

    @Test
    fun cancelledPreparationResolvesBeforeReopeningWithoutAPost() {
        val scenario = EditorSubmissionScenario().apply {
            preparationFailure = CancellationException("local preparation cancelled")
        }
        withReadyAdapter(scenario = scenario) { adapter ->
            adapter.clickSubmit()
            runCurrent()
            assertEquals(0, adapter.postCalls)
            assertEquals(0, scenario.emittedPosts)
            assertNull(scenario.store.entries.values.single().attempt)
            assertFalse(adapter.snapshot().isSending)
            scenario.recreateEditor()
            runCurrent()
            assertTrue(adapter.snapshot().error != SubmissionErrorProbe.UNCERTAIN)
            assertEquals(0, adapter.successEffects)
        }
    }

    @Test
    fun failedCancellationResolutionKeepsConservativeRecovery() {
        val scenario = EditorSubmissionScenario().apply {
            preparationFailure = CancellationException("local preparation cancelled")
            store.finishFailure = IOException("outcome unavailable")
        }
        withReadyAdapter(scenario = scenario) { adapter ->
            adapter.clickSubmit()
            runCurrent()
            assertEquals(0, adapter.postCalls)
            assertEquals(SubmissionErrorProbe.UNCERTAIN, adapter.snapshot().error)
            assertEquals(
                EditorDraftStore.AttemptStatus.IN_PROGRESS, scenario.store.entries.values.single().attempt?.status,
            )
            scenario.recreateEditor()
            runCurrent()
            assertEquals(SubmissionErrorProbe.UNCERTAIN, adapter.snapshot().error)
            assertEquals(0, adapter.successEffects)
        }
    }

    /**
     * ED-170: teardown is not a local send cancellation, even before the emission boundary.
     * Restored attempts remain unresolved; autosave can normalize their marker to UNCERTAIN.
     */
    @Test
    fun recreationDuringPreparationRestoresUnresolvedAttemptWithoutResending() {
        val gate = CompletableDeferred<Unit>()
        val scenario = EditorSubmissionScenario().apply { preparationGate = gate }
        withReadyAdapter(scenario = scenario) { adapter ->
            adapter.clickSubmit()
            runCurrent()
            assertEquals(1, scenario.preparationCalls)
            assertEquals(0, adapter.postCalls)
            val entry = scenario.store.entries.values.single()
            assertEquals(EditorDraftStore.AttemptStatus.IN_PROGRESS, entry.attempt?.status)
            scenario.recreateEditor()
            runCurrent()
            assertEquals(entry.attempt, scenario.store.entries.values.single().attempt)
            assertEquals(entry.draft.body, adapter.snapshot().body)
            assertEquals(SubmissionErrorProbe.UNCERTAIN, adapter.snapshot().error)
            assertFalse(adapter.snapshot().isSending)
            assertOnlyIndependentOffers(adapter)
            assertFalse(adapter.snapshot().confirmationVisible)
            gate.complete(Unit)
            runCurrent()
            assertEquals("teardown must not finish the old attempt", 0, scenario.store.finishCalls)
            assertEquals(entry, scenario.store.entries.values.single())
            advanceTimeBy(751)
            runCurrent()
            assertEquals(1, scenario.preparationCalls)
            assertEquals(0, adapter.postCalls)
            assertEquals(0, scenario.emittedPosts)
            assertEquals(0, adapter.successEffects)
            val recovered = scenario.store.entries.values.single()
            assertEquals(entry.draft, recovered.draft)
            assertEquals(entry.attempt?.token, recovered.attempt?.token)
            // Opening autosaves may normalize IN_PROGRESS to UNCERTAIN via saveUnresolved.
            // Neither marker acknowledges the attempt; no outcome may come from the destroyed scope.
            assertTrue(
                recovered.attempt?.status in setOf(
                    EditorDraftStore.AttemptStatus.IN_PROGRESS, EditorDraftStore.AttemptStatus.UNCERTAIN,
                ),
            )
            assertEquals(0, scenario.store.finishCalls)
            assertEquals(SubmissionErrorProbe.UNCERTAIN, adapter.snapshot().error)
            adapter.editBody("edited after preparation teardown")
            advanceTimeBy(751)
            runCurrent()
            val edited = scenario.store.entries.values.single()
            assertEquals("edited after preparation teardown", edited.draft.body)
            assertEquals(entry.attempt?.copy(status = EditorDraftStore.AttemptStatus.UNCERTAIN), edited.attempt)
            assertEquals(0, scenario.store.finishCalls)
            assertEquals(1, scenario.preparationCalls)
            assertEquals(0, adapter.postCalls)
            assertEquals(0, scenario.emittedPosts)
            assertEquals(0, adapter.successEffects)
            assertEquals(SubmissionErrorProbe.UNCERTAIN, adapter.snapshot().error)
        }
    }

    @Test
    fun accountChangeCancelsPreparationWithoutRevivingTheOldUi() {
        val scenario = EditorSubmissionScenario().apply { preparationGate = CompletableDeferred() }
        withReadyAdapter(scenario = scenario) { adapter ->
            adapter.clickSubmit()
            runCurrent()
            assertEquals(1, scenario.preparationCalls)
            assertEquals(0, adapter.postCalls)
            val owner = scenario.store.currentOwner()
            scenario.switchAccount()
            runCurrent()
            val invalidated = adapter.snapshot()
            scenario.preparationGate?.complete(Unit)
            runCurrent()
            assertEquals(invalidated.body, adapter.snapshot().body)
            assertEquals(invalidated.error, adapter.snapshot().error)
            assertEquals(0, adapter.postCalls)
            assertEquals(0, adapter.successEffects)
            scenario.store.activeOwner = owner
            assertNull(scenario.store.entries.values.single().attempt)
        }
    }

    @Test
    fun cancelledPostRetainsUnresolvedAttempt() {
        val scenario = EditorSubmissionScenario().apply { cancelPost = true }
        withReadyAdapter(scenario = scenario) { adapter ->
            startSending(adapter)
            adapter.completePost()
            runCurrent()
            assertEquals(1, scenario.emittedPosts)
            assertEquals(SubmissionErrorProbe.UNCERTAIN, adapter.snapshot().error)
            assertEquals(
                EditorDraftStore.AttemptStatus.IN_PROGRESS, scenario.store.entries.values.single().attempt?.status,
            )
            scenario.recreateEditor()
            runCurrent()
            assertEquals(SubmissionErrorProbe.UNCERTAIN, adapter.snapshot().error)
            assertEquals(0, adapter.successEffects)
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

    /**
     * ED-121/161/170 v0.7.8 replaces "no offer at all" with "no attempted C as an offer".
     * Compare the complete set with the exact pre-send D (identity, revision and fields), and
     * the real visible offer with that set: neither publication nor uncertainty may invent D.
     * All attempt-status, body, acknowledgement and POST-count assertions remain mandatory.
     */
    /**
     * v0.7.8, "Plusieurs brouillons offerts" (ED-41, ED-121): the decided draft leaves the offer, and
     * the editor keeps offering exactly while another durable draft still awaits a choice.
     */
    private suspend fun assertOfferDecided(adapter: EditorSubmissionAdapter, key: String, decided: String) {
        val store = submissionScenario.store
        val remaining = store.loadOffers(store.currentOwner(), key)
        assertTrue(remaining.none { it.draft.body == decided })
        assertFalse(decided == adapter.snapshot().offeredBody)
        assertEquals(remaining.isNotEmpty(), adapter.snapshot().restoreOffered)
    }

    protected suspend fun assertOnlyIndependentOffers(adapter: EditorSubmissionAdapter) {
        val (key, entry) = submissionScenario.store.entries.entries.single()
        val expected = independentOffers[key].orEmpty()
        assertEquals(expected, submissionScenario.store.loadOffers(submissionScenario.store.currentOwner(), key))
        assertTrue(expected.none { it.draft.body == entry.draft.body })
        val probe = adapter.snapshot()
        assertEquals(expected.firstOrNull()?.draft?.body, probe.offeredBody)
        assertEquals(expected.isNotEmpty(), probe.restoreOffered)
    }

    protected fun withReadyAdapter(
        preferences: Flow<Boolean> = flowOf(false),
        delayedSharedRestore: Boolean = false,
        scenario: EditorSubmissionScenario = EditorSubmissionScenario(),
        preloadIndependentDraft: Boolean = false,
        block: suspend TestScope.(EditorSubmissionAdapter) -> Unit,
    ) = runTest {
        submissionScenario = scenario
        val adapter = createAdapter(this, preferences, delayedSharedRestore)
        try {
            if (preloadIndependentDraft) {
                assumeTrue("ED-121 requires independent draft offers", adapter.supportsIndependentDraftOffers)
            }
            runCurrent()
            if (preloadIndependentDraft) {
                scenario.store.entries.keys.filter(::keepsOfferedDrafts).forEach { key ->
                    val private = key.startsWith("mpreply:") || key == EditorDraftKey.mpCompose()
                    val draft = EditorDraftStore.Draft("independent D", isPrivate = private, updatedAt = 1234)
                    scenario.store.preloadOffer(key,
                        EditorDraftStore.DraftOffer.from(EditorDraftStore.DraftEntry(draft, 0)))
                }
            }
            // Only an actually offered, ordinary draft of a reply, a new topic or a new MP is D.
            // The sheet's auto-resumed body is C; edits and reports keep their non-ED-121 contract.
            independentOffers = scenario.store.entries.mapValues { (key, entry) ->
                val offered = adapter.snapshot().restoreOffered && entry.attempt == null
                val source = if (offered && keepsOfferedDrafts(key)) {
                    listOf(EditorDraftStore.DraftOffer.from(entry))
                } else emptyList()
                (scenario.store.loadOffers(scenario.store.currentOwner(), key) + source)
                    .distinctBy { it.id }.sortedWith(compareByDescending<EditorDraftStore.DraftOffer> {
                        it.draft.updatedAt
                    }.thenByDescending { it.sourceVersion })
            }
            adapter.editBody("contract body")
            runCurrent()
            assertTrue("formulaire valide avant l'exercice du contrat", adapter.snapshot().canSubmit)
            block(adapter)
        } finally {
            adapter.dispose()
            runCurrent()
        }
    }

    private fun keepsOfferedDrafts(key: String): Boolean = EditorDraftKey.keepsOfferedDrafts(key)
}
