package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.EditorAccountChangedBeforeSendException
import fr.forumhfr.redface2.core.domain.editor.EditorDraftSession
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorPreparationException
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionBinding
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionCallbacks
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionEnvironment
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionErrors
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.EditorWriteOwner
import fr.forumhfr.redface2.core.domain.editor.SubmissionRequest
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic equivalent of RealCall.timeoutExit wrapping a guard refusal during callTimeout. */
class EditorWriteRejectionClassificationTest {
    @Test
    fun `refusal witness takes precedence over timeout with or without the original cause`() = runTest {
        for (withCause in listOf(false, true)) {
            val timeout = InterruptedIOException("timeout")
            if (withCause) timeout.initCause(IOException(EditorAccountChangedBeforeSendException()))
            assertClassification(timeout, rejected = true, expected = "session")
        }
    }

    @Test
    fun `a chained guard refusal is certain even without its context witness`() = runTest {
        val timeout = InterruptedIOException("timeout")
        timeout.initCause(IOException(EditorAccountChangedBeforeSendException()))
        assertClassification(timeout, rejected = false, expected = "session")
    }

    @Test
    fun `ordinary timeout without refusal remains uncertain`() = runTest {
        assertClassification(InterruptedIOException("timeout"), rejected = false, expected = "uncertain")
    }

    @Test
    fun `refusal witness also takes precedence over other classified exceptions`() = runTest {
        val errors = listOf(
            IllegalStateException("transport stopped"),
            EditorPreparationException(EditorPreparationException.Reason.NETWORK),
            SessionExpiredException("https://example.invalid/"),
        )
        for (error in errors) assertClassification(error, rejected = true, expected = "session")
    }

    private suspend fun TestScope.assertClassification(error: Exception, rejected: Boolean, expected: String) {
        val store = VersionedEditorDraftStore()
        val settled = CompletableDeferred<String>()
        val binding = EditorSubmissionBinding<Unit, String>(
            EditorSubmissionEnvironment(this, DiagnosticsClock { 0L }, DiagnosticsLog()),
            EditorDraftSession(store, "key"),
            EditorSubmissionCallbacks(state = {}, error = { it?.let(settled::complete) }, success = {}),
            EditorSubmissionErrors("uncertain", "draft", "session", "unknown", "network", "after-entry"),
        )
        binding.restore("key")
        binding.setPreference(false)
        binding.launch(binding.request(4_000, true) as SubmissionRequest.Accepted, Draft("body")) {
            if (rejected) {
                val owner = checkNotNull(currentCoroutineContext()[EditorWriteOwner])
                runCatching { owner.rejectBeforeEmission() }
            }
            throw error
        }

        assertEquals(expected, settled.await())
        if (expected == "uncertain") {
            assertTrue(binding.state is EditorSubmissionState.Uncertain)
        } else {
            assertEquals(EditorSubmissionState.Ready, binding.state)
            assertNull(store.entries["key"]?.attempt)
        }
    }
}
