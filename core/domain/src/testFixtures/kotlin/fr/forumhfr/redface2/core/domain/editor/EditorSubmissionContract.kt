package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.error.HfrServerException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * #1301-ST — contrat comportemental commun, contre les entrées existantes à e5226a65.
 * Les prédictions ci-dessous ne sont pas des résultats d'exécution.
 * Les adaptateurs traduisent les intents et observent les vrais ViewModels ; aucun automate simulé ici.
 * Les POST et uploads restent suspendus jusqu'à leur libération explicite, sans temps réel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class EditorSubmissionContract : EditorSubmissionRecoveryContract() {

    @Before
    fun setSubmissionMainDispatcher() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun resetSubmissionMainDispatcher() {
        Dispatchers.resetMain()
    }

    /** ED-160 — vert : les cinq ViewModels gardent déjà le POST suspendu contre les doubles appuis. */
    @Test
    fun doubleTapWhilePostIsSuspendedSubmitsOnce() = withReadyAdapter { adapter ->
        startSending(adapter)
        adapter.clickSubmit()
        adapter.confirm()
        runCurrent()
        assertEquals(1, adapter.postCalls)
        adapter.completePost()
        runCurrent()
        assertEquals(1, adapter.successEffects)
    }

    /** ED-160/161 — rouge à e5226a65, y compris la feuille via sa confirmation directe. */
    @Test
    fun successRemainsTerminal() = withReadyAdapter { adapter ->
        succeed(adapter)
        adapter.clickSubmit()
        adapter.confirm()
        runCurrent()
        assertEquals("aucun second POST après succès dans la même session", 1, adapter.postCalls)
        assertEquals("un seul effet de succès", 1, adapter.successEffects)
    }

    /** ED-161 — rouge à e5226a65 sur les pleins écrans ; vert sur la feuille qui vide son champ. */
    @Test
    fun successDisablesSubmit() = withReadyAdapter { adapter ->
        succeed(adapter)
        assertFalse(adapter.snapshot().canSubmit)
    }

    /**
     * ED-161 — rouge à e5226a65 : sauvegarde au retour plein écran, nouveau delete sur la feuille.
     * Compter aussi les suppressions permet de vérifier que la fermeture est effectivement inerte.
     */
    @Test
    fun closeAfterSuccessNeverWritesDraft() = withReadyAdapter { adapter ->
        succeed(adapter)
        val saves = adapter.draftSaves
        val writes = adapter.draftWrites
        val closes = adapter.closeEffects
        adapter.close()
        advanceTimeBy(751)
        runCurrent()
        assertEquals("aucune sauvegarde après succès", saves, adapter.draftSaves)
        assertEquals("fermeture inerte, même pour un champ vidé", writes, adapter.draftWrites)
        assertEquals("aucun second effet de fermeture", closes, adapter.closeEffects)
    }

    /** ED-164 — rouge à e5226a65 : SubmitConfirmed contourne l'absence d'armement. */
    @Test
    fun confirmationWithoutArmingCannotSubmit() = withReadyAdapter(flowOf(true)) { adapter ->
        adapter.confirm()
        runCurrent()
        assertEquals(0, adapter.postCalls)
    }

    /** ED-164 — rouge à e5226a65 : désarmer ne révoque pas SubmitConfirmed. */
    @Test
    fun dismissedConfirmationCannotSubmit() = withReadyAdapter(flowOf(true)) { adapter ->
        adapter.clickSubmit()
        runCurrent()
        assertTrue(adapter.snapshot().confirmationVisible)
        adapter.dismissConfirmation()
        runCurrent()
        assertFalse(adapter.snapshot().confirmationVisible)
        advanceTimeBy(400)
        adapter.confirm()
        runCurrent()
        assertEquals(0, adapter.postCalls)
    }

    /**
     * ED-166 — rouge à e5226a65. Les constructeurs actuels n'injectent aucune horloge métier :
     * les deux intents sont livrés au même instant virtuel, avant toute avance du scheduler.
     */
    @Test
    fun immediateConfirmationIsIgnored() = withReadyAdapter(flowOf(true)) { adapter ->
        adapter.clickSubmit()
        runCurrent()
        assertTrue(adapter.snapshot().confirmationVisible)
        adapter.confirm()
        runCurrent()
        assertEquals(0, adapter.postCalls)
    }

    /** ED-166 — rouge à e5226a65 ; borne virtuelle à raccorder à l'horloge injectée à l'étape 2. */
    @Test
    fun confirmationBefore400msIsIgnored() = withReadyAdapter(flowOf(true)) { adapter ->
        adapter.clickSubmit()
        runCurrent()
        advanceTimeBy(399)
        adapter.confirm()
        runCurrent()
        assertEquals(0, adapter.postCalls)
    }

    /** ED-164/166 — vert : une confirmation après 400 ms autorise exactement un POST. */
    @Test
    fun confirmationAt400msSubmitsOnce() = withReadyAdapter(flowOf(true)) { adapter ->
        adapter.clickSubmit()
        runCurrent()
        assertTrue(adapter.snapshot().confirmationVisible)
        assertEquals(0, adapter.postCalls)
        advanceTimeBy(400)
        adapter.confirm()
        runCurrent()
        assertEquals(1, adapter.postCalls)
    }

    /** ED-169/172 — rouge à e5226a65 sur les cinq surfaces. */
    @Test
    fun typingCannotMutateSendingBody() = mutationIsBlocked(SubmissionBodyMutation.TYPING)

    /** ED-169/172 — rouge à e5226a65 ; sans objet sur la feuille (raison dans l'adaptateur). */
    @Test
    fun smileyCannotMutateSendingBody() = mutationIsBlocked(SubmissionBodyMutation.SMILEY)

    /** ED-169/172 — rouge à e5226a65 ; sans objet sur la feuille. */
    @Test
    fun formattingCannotMutateSendingBody() = mutationIsBlocked(SubmissionBodyMutation.FORMAT)

    /** ED-169/172 — rouge à e5226a65 sur post/sujet ; pas d'entrée URL sur les MP ni la feuille. */
    @Test
    fun imageUrlCannotMutateSendingBody() = mutationIsBlocked(SubmissionBodyMutation.IMAGE_URL)

    /** ED-169/172 — rouge à e5226a65 : offre #1415 ou rechargement de la ligne pour la feuille. */
    @Test
    fun restorationCannotMutateSendingBody() = mutationIsBlocked(SubmissionBodyMutation.RESTORE)

    /** ED-169/172 — rouge à e5226a65 : retour tardif de resumeSharedDraft, réponse/citation. */
    @Test
    fun lateSharedDraftCannotMutateSendingBody() = runTest {
        val adapter = createAdapter(this, flowOf(false), delayedSharedRestore = true)
        try {
            runCurrent()
            requireMutation(adapter, SubmissionBodyMutation.SHARED_RESTORE)
            adapter.editBody("live body")
            adapter.clickSubmit()
            runCurrent()
            assertEquals("ED-84/170: entry read precedes any write path", 0, adapter.postCalls)
            adapter.mutateBody(SubmissionBodyMutation.SHARED_RESTORE)
            runCurrent()
            startSending(adapter)
            val body = adapter.snapshot().body
            adapter.mutateBody(SubmissionBodyMutation.SHARED_RESTORE)
            runCurrent()
            assertEquals(body, adapter.snapshot().body)
        } finally {
            adapter.dispose()
            runCurrent()
        }
    }

    /** ED-172 — rouge à e5226a65 sur MP ; vert sur post/sujet ; sans objet sur la feuille. */
    @Test
    fun sendingDuringUploadIsRefused() = withReadyAdapter { adapter ->
        requireUpload(adapter)
        adapter.startUpload()
        runCurrent()
        assertTrue("l'upload est réellement suspendu", adapter.snapshot().isUploading)
        assertEquals(1, adapter.uploadCalls)
        adapter.clickSubmit()
        adapter.confirm()
        runCurrent()
        assertEquals(0, adapter.postCalls)
    }

    /** ED-172 — rouge à e5226a65 sur les quatre surfaces avec upload. */
    @Test
    fun uploadDuringSendingIsRefused() = withReadyAdapter { adapter ->
        requireUpload(adapter)
        startSending(adapter)
        adapter.startUpload()
        runCurrent()
        assertEquals(0, adapter.uploadCalls)
        assertFalse(adapter.snapshot().isUploading)
    }

    /**
     * ED-169/172 — rouge à e5226a65 : l'upload lancé pendant le POST insère au retour du faux serveur.
     * Après correction, son lancement peut être refusé : ce test vérifie alors l'absence de mutation.
     * Une preuve isolée de la garde du callback malgré le refus au lancement attend l'étape 2.
     */
    @Test
    fun lateUploadCallbackCannotMutateSendingBody() = withReadyAdapter { adapter ->
        requireUpload(adapter)
        startSending(adapter)
        val body = adapter.snapshot().body
        adapter.startUpload()
        runCurrent()
        adapter.completeUpload()
        runCurrent()
        assertTrue(adapter.snapshot().isSending)
        assertEquals(body, adapter.snapshot().body)
    }

    /** ED-161/172 — rouge à e5226a65 : un callback après le succès ne doit pas recréer le brouillon. */
    @Test
    fun lateUploadCallbackAfterSuccessNeverWritesDraft() = withReadyAdapter { adapter ->
        requireUpload(adapter)
        startSending(adapter)
        adapter.startUpload()
        runCurrent()
        adapter.completePost()
        runCurrent()
        assertEquals(1, adapter.successEffects)
        val body = adapter.snapshot().body
        val saves = adapter.draftSaves
        adapter.completeUpload()
        advanceTimeBy(751)
        runCurrent()
        assertEquals("aucune insertion après succès", body, adapter.snapshot().body)
        assertEquals("aucun brouillon ressuscité par le callback", saves, adapter.draftSaves)
        assertEquals(1, adapter.successEffects)
    }

    /** ED-171 — rouge à e5226a65 : aucune désactivation n'a été lue, aucun POST direct n'est permis. */
    @Test
    fun missingPreferenceEmissionRequiresConfirmation() = withReadyAdapter(flow { awaitCancellation() }) { adapter ->
        adapter.clickSubmit()
        runCurrent()
        assertEquals(0, adapter.postCalls)
        assertTrue(adapter.snapshot().confirmationVisible)
    }

    /**
     * ED-171 — rouge à e5226a65 : flux sans émission puis IOException, sans faux fallback dans le test.
     * Le ViewModel doit aussi absorber cette erreur ; une exception non traitée fait échouer runTest.
     */
    @Test
    fun failedPreferenceFlowRequiresConfirmation() {
        val failure = CompletableDeferred<Unit>()
        val preferences = flow<Boolean> {
            failure.await()
            throw IOException("preference read failed")
        }
        withReadyAdapter(preferences) { adapter ->
            failure.complete(Unit)
            runCurrent()
            adapter.clickSubmit()
            runCurrent()
            assertEquals(0, adapter.postCalls)
            assertTrue(adapter.snapshot().confirmationVisible)
        }
    }

    @Test
    fun attemptIsAwaitedBeforeWriteRepository() {
        val scenario = EditorSubmissionScenario()
        val durableWrite = CompletableDeferred<Unit>()
        scenario.store.beginGate = durableWrite
        withReadyAdapter(scenario = scenario) { adapter ->
            adapter.clickSubmit()
            runCurrent()
            assertEquals(1, scenario.store.beginCalls)
            assertEquals("POST waits for durable IN_PROGRESS", 0, adapter.postCalls)
            assertTrue(adapter.snapshot().isSending)
            durableWrite.complete(Unit)
            runCurrent()
            assertEquals(1, adapter.postCalls)
            assertEquals(
                EditorDraftStore.AttemptStatus.IN_PROGRESS, scenario.store.entries.values.single().attempt?.status,
            )
        }
    }

    @Test
    fun failedAttemptWriteNeverEntersWriteRepository() {
        val scenario = EditorSubmissionScenario()
        scenario.store.beginFailure = IOException("disk full")
        withReadyAdapter(scenario = scenario) { adapter ->
            adapter.clickSubmit()
            runCurrent()
            assertEquals(0, adapter.postCalls)
            assertEquals(SubmissionErrorProbe.DRAFT_WRITE_FAILED, adapter.snapshot().error)
            assertEquals(0, adapter.successEffects)
        }
    }

    @Test
    fun recreatingInProgressAttemptRestoresUncertain() = withReadyAdapter { adapter ->
        startSending(adapter)
        val body = adapter.snapshot().body
        val attempt = submissionScenario.store.entries.values.single().attempt
        submissionScenario.recreateEditor()
        runCurrent()
        assertEquals(body, adapter.snapshot().body)
        assertEquals(attempt, submissionScenario.store.entries.values.single().attempt)
        assertEquals(SubmissionErrorProbe.UNCERTAIN, adapter.snapshot().error)
        assertOnlyIndependentOffers(adapter)
        assertFalse(adapter.snapshot().confirmationVisible)
        assertEquals(1, adapter.postCalls)
    }

    @Test
    fun publishedMarkerNeverRestoresEvenIfCleanupFailed() {
        val scenario = EditorSubmissionScenario()
        scenario.store.cleanupFailure = IOException("cleanup failed")
        withReadyAdapter(scenario = scenario) { adapter ->
            succeed(adapter)
            assertEquals("An accepted send must retain exactly one durable PUBLISHED row",
                1, scenario.store.entries.size)
            assertEquals(
                EditorDraftStore.AttemptStatus.PUBLISHED, scenario.store.entries.values.single().attempt?.status,
            )
            assertTrue(scenario.store.entries.values.single().draft.body.isNotBlank())
            val published = scenario.store.entries.values.single()
            scenario.recreateEditor()
            runCurrent()
            assertOnlyIndependentOffers(adapter)
            assertEquals(published, scenario.store.entries.values.single())
            assertFalse(adapter.snapshot().body.contains("contract body"))
            assertEquals(1, adapter.successEffects)
        }
    }

    @Test
    fun staleArmIdentityCannotConfirmNewArm() = withReadyAdapter(flowOf(true)) { adapter ->
        adapter.clickSubmit()
        runCurrent()
        val previous = adapter.snapshot().armId
        adapter.dismissConfirmation()
        adapter.clickSubmit()
        advanceTimeBy(400)
        adapter.confirmIdentity(previous)
        runCurrent()
        assertEquals(0, adapter.postCalls)
        assertTrue(adapter.snapshot().confirmationVisible)
    }

    @Test
    fun expiredArmCannotConfirm() = withReadyAdapter(flowOf(true)) { adapter ->
        adapter.clickSubmit()
        runCurrent()
        val id = adapter.snapshot().armId
        advanceTimeBy(4_000)
        runCurrent()
        adapter.confirmIdentity(id)
        runCurrent()
        assertEquals(0, adapter.postCalls)
        assertFalse(adapter.snapshot().confirmationVisible)
    }

    @Test
    fun rotationNeverExtendsArmDeadline() = withReadyAdapter(flowOf(true)) { adapter ->
        adapter.clickSubmit()
        runCurrent()
        val id = adapter.snapshot().armId
        advanceTimeBy(3_500)
        submissionScenario.rotateEditor()
        runCurrent()
        assertEquals(id, adapter.snapshot().armId)
        advanceTimeBy(500)
        runCurrent()
        adapter.confirmIdentity(id)
        runCurrent()
        assertEquals(0, adapter.postCalls)
        assertFalse(adapter.snapshot().confirmationVisible)
    }

    /** ED-169/172: bypass only the launch gate, delivering directly to the real final insertion path. */
    @Test
    fun staleUploadCompletionIsRejectedAtFinalGuard() = withReadyAdapter { adapter ->
        requireUpload(adapter)
        startSending(adapter)
        val body = adapter.snapshot().body
        adapter.deliverLateUploadCallback()
        runCurrent()
        assertTrue(adapter.snapshot().isSending)
        assertEquals(body, adapter.snapshot().body)
        adapter.completePost()
        runCurrent()
        val writes = adapter.draftWrites
        adapter.deliverLateUploadCallback()
        advanceTimeBy(751)
        runCurrent()
        assertEquals(body, adapter.snapshot().body)
        assertEquals(writes, adapter.draftWrites)
        assertEquals(1, adapter.successEffects)
    }

    @Test
    fun redirectAfterWriteEntryIsUncertain() = writeFailureIsUncertain(
        HfrServerException(307, "https://example.test/write"),
    )

    @Test
    fun ioExceptionAfterWriteEntryIsUncertain() = writeFailureIsUncertain(IOException("response lost"))

    @Test
    fun localNetworkFailureIsCertainWithoutAPost() {
        val scenario = EditorSubmissionScenario().apply { preparationFailure = IOException("offline before POST") }
        withReadyAdapter(scenario = scenario) { adapter ->
            val body = adapter.snapshot().body
            adapter.clickSubmit()
            runCurrent()
            assertEquals(0, adapter.postCalls)
            assertEquals(0, scenario.emittedPosts)
            assertEquals(SubmissionErrorProbe.NETWORK, adapter.snapshot().error)
            assertEquals(null, scenario.store.entries.values.single().attempt)
            assertEquals(body, adapter.snapshot().body)
            assertEquals(0, adapter.successEffects)
        }
    }

    @Test
    fun emptyPreparationIsCertainWithoutAPost() {
        val scenario = EditorSubmissionScenario().apply {
            preparationFailure = EditorPreparationException(EditorPreparationException.Reason.INVALID_FORM)
        }
        withReadyAdapter(scenario = scenario) { adapter ->
            adapter.clickSubmit()
            runCurrent()
            assertEquals(0, adapter.postCalls)
            assertEquals(0, scenario.emittedPosts)
            assertEquals(null, scenario.store.entries.values.single().attempt)
            assertFalse(adapter.snapshot().isSending)
            assertEquals(SubmissionErrorProbe.PREPARATION_FAILED, adapter.snapshot().error)
            assertEquals(0, adapter.successEffects)
        }
    }

    @Test
    fun sessionExpiryDuringLocalPreparationIsCertain() {
        val scenario = EditorSubmissionScenario().apply {
            preparationFailure = fr.forumhfr.redface2.core.domain.auth.SessionExpiredException("local preparation")
        }
        withReadyAdapter(scenario = scenario) { adapter ->
            adapter.clickSubmit()
            runCurrent()
            assertEquals(0, adapter.postCalls)
            assertEquals(SubmissionErrorProbe.SESSION_EXPIRED, adapter.snapshot().error)
            assertEquals(null, scenario.store.entries.values.single().attempt)
        }
    }

    @Test
    fun loginRecognitionAfterEmissionCannotProveRefusal() = writeFailureIsUncertain(
        fr.forumhfr.redface2.core.domain.auth.SessionExpiredException("https://example.test/login.php"),
    )

    private fun writeFailureIsUncertain(error: IOException) {
        val scenario = EditorSubmissionScenario().apply { postFailure = error }
        withReadyAdapter(scenario = scenario) { adapter ->
            startSending(adapter)
            adapter.completePost()
            runCurrent()
            assertEquals(SubmissionErrorProbe.UNCERTAIN, adapter.snapshot().error)
            assertEquals(0, adapter.successEffects)
            assertEquals(
                EditorDraftStore.AttemptStatus.UNCERTAIN, scenario.store.entries.values.single().attempt?.status,
            )
            val body = adapter.snapshot().body
            val attempt = scenario.store.entries.values.single().attempt
            scenario.recreateEditor()
            runCurrent()
            assertEquals(body, adapter.snapshot().body)
            assertEquals(attempt, scenario.store.entries.values.single().attempt)
            assertEquals(SubmissionErrorProbe.UNCERTAIN, adapter.snapshot().error)
            assertOnlyIndependentOffers(adapter)
        }
    }

    private fun mutationIsBlocked(mutation: SubmissionBodyMutation) = withReadyAdapter { adapter ->
        requireMutation(adapter, mutation)
        startSending(adapter)
        val body = adapter.snapshot().body
        adapter.mutateBody(mutation)
        runCurrent()
        assertEquals("$mutation doit conserver le snapshot d'envoi", body, adapter.snapshot().body)
    }

    private fun requireMutation(adapter: EditorSubmissionAdapter, mutation: SubmissionBodyMutation) {
        val reason = adapter.unsupportedMutations[mutation]
        assumeTrue("$mutation sans objet : $reason", reason == null)
    }

    private fun requireUpload(adapter: EditorSubmissionAdapter) {
        assumeTrue("upload sans objet : ${adapter.uploadUnsupportedReason}", adapter.uploadUnsupportedReason == null)
    }

    private fun TestScope.succeed(adapter: EditorSubmissionAdapter) {
        startSending(adapter)
        adapter.completePost()
        runCurrent()
        assertEquals("succès réellement consommé avant le geste suivant", 1, adapter.successEffects)
    }
}

/** Adaptateur pur : aucune dépendance Compose, Android, feature ou implémentation de repository. */
interface EditorSubmissionAdapter {
    val unsupportedMutations: Map<SubmissionBodyMutation, String>
    val uploadUnsupportedReason: String?
    /** ED-121 independent draft offers apply to topic replies, MP replies and quick reply. */
    val supportsIndependentDraftOffers: Boolean
    val postCalls: Int
    val formFetchCalls: Int
    val uploadCalls: Int
    val draftSaves: Int
    val draftWrites: Int
    val successEffects: Int
    val closeEffects: Int

    fun editBody(body: String)
    fun clickSubmit()
    fun confirm()
    fun confirmIdentity(id: Long?) = confirm()
    fun dismissConfirmation()
    fun close()
    fun snapshot(): SubmissionProbe
    fun mutateBody(mutation: SubmissionBodyMutation)
    fun startUpload()
    fun completeUpload()
    fun deliverLateUploadCallback()
    fun completePost()
    fun resolveOfferedDraft(restoring: Boolean): Unit = error("This surface has no independent reply offers")
    fun dispose()
}

data class SubmissionProbe(
    val body: String,
    val canSubmit: Boolean,
    val isSending: Boolean,
    val isUploading: Boolean,
    val confirmationVisible: Boolean,
    val armId: Long? = null,
    val error: SubmissionErrorProbe? = null,
    val restoreOffered: Boolean = false,
    val offeredBody: String? = null,
    val blocksClose: Boolean = isSending,
)

enum class SubmissionErrorProbe { UNCERTAIN, DRAFT_WRITE_FAILED, NETWORK, SESSION_EXPIRED, PREPARATION_FAILED }

enum class SubmissionBodyMutation { TYPING, SMILEY, FORMAT, IMAGE_URL, RESTORE, SHARED_RESTORE }
