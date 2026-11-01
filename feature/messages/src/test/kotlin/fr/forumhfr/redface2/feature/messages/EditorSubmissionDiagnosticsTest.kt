package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.EditorSubmissionTrace
import fr.forumhfr.redface2.core.domain.editor.EditorDelivery
import fr.forumhfr.redface2.core.domain.editor.EditorDraftSession
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionBinding
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionCallbacks
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionEnvironment
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionErrors
import fr.forumhfr.redface2.core.domain.editor.SubmissionRequest
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorSubmissionDiagnosticsTest {
    @Test
    fun `K16 measures the awaited durable write before entry and exports only safe events`() = runTest {
        val log = DiagnosticsLog()
        val exported = mutableListOf<String>()
        log.exportSubmissionTraces { exported += it }
        val store = VersionedEditorDraftStore()
        val gate = CompletableDeferred<Unit>()
        store.beginGate = gate
        val binding = EditorSubmissionBinding(
            EditorSubmissionEnvironment(this, DiagnosticsClock { testScheduler.currentTime * 1_000_000L }, log),
            EditorDraftSession(store, "mp-compose"),
            EditorSubmissionCallbacks<Unit, Issue>(state = {}, error = {}, success = {}),
            EditorSubmissionErrors(Issue.UNCERTAIN, Issue.STORAGE, Issue.AUTH, Issue.UNKNOWN),
        )
        binding.restore("mp-compose")
        binding.setPreference(false)
        val permit = binding.request(4_000, true) as SubmissionRequest.Accepted
        var entered = false
        binding.launch(permit, EditorDraftStore.Draft(
            body = "PrivateBody", subject = "PrivateSubject", recipients = "PrivateRecipient", isPrivate = true,
        )) {
            entered = true
            assertEquals(1, exported.size)
            assertTrue(exported.single().endsWith("attempt_durable writeMs=17"))
            EditorDelivery(effect = Unit)
        }
        runCurrent()
        assertFalse(entered)
        assertTrue(exported.isEmpty())
        advanceTimeBy(17)
        gate.complete(Unit)
        runCurrent()
        assertTrue(entered)
        assertEquals(2, exported.size)
        assertTrue(exported.last().endsWith("result class=SUCCESS"))
        assertEquals(1, exported.map { it.substringBefore(' ') }.distinct().size)
        exported.forEach { line ->
            listOf("PrivateBody", "PrivateSubject", "PrivateRecipient", "mp-compose", "tester", "attempt-")
                .forEach { assertFalse(line, line.contains(it)) }
        }
    }

    @Test
    fun `failed durable write emits a classified safe failure without entering the repository`() = runTest {
        val log = DiagnosticsLog()
        val store = VersionedEditorDraftStore().apply { beginFailure = IOException("SecretCookie SecretBody") }
        val binding = EditorSubmissionBinding(
            EditorSubmissionEnvironment(this, DiagnosticsClock { testScheduler.currentTime * 1_000_000L }, log),
            EditorDraftSession(store, "mp-compose"),
            EditorSubmissionCallbacks<Unit, Issue>(state = {}, error = {}, success = {}),
            EditorSubmissionErrors(Issue.UNCERTAIN, Issue.STORAGE, Issue.AUTH, Issue.UNKNOWN),
        )
        binding.restore("mp-compose")
        binding.setPreference(false)
        val permit = binding.request(4_000, true) as SubmissionRequest.Accepted
        var entered = false
        binding.launch(permit, EditorDraftStore.Draft(body = "SecretBody")) {
            entered = true
            EditorDelivery(effect = Unit)
        }
        runCurrent()
        assertFalse(entered)
        val lines = log.entries.value.filter { it.tag == EditorSubmissionTrace.TAG }.map { it.message }
        assertTrue(lines.any { it.endsWith("result class=DRAFT_WRITE_FAILED") })
        assertTrue(lines.none { it.contains("Secret") })
        assertTrue(lines.none { it.contains("attempt_durable") })
    }

    private enum class Issue { UNCERTAIN, STORAGE, AUTH, UNKNOWN }
}
