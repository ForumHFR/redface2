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
import fr.forumhfr.redface2.core.domain.editor.EditorDelivery
import fr.forumhfr.redface2.core.domain.editor.EditorSendOutcome
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
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
    fun `timeout is certain only while the witness proves non emission`() = runTest {
        assertClassification(InterruptedIOException("timeout"), rejected = false, expected = "network")
        assertClassification(InterruptedIOException("timeout"), rejected = false, expected = "uncertain", emitted = true)
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

    @Test
    fun `exception type never replaces the emission witness`() = runTest {
        val failures = listOf(
            UnknownHostException("DNS"), ConnectException("refused"),
            SocketTimeoutException("timeout"), SSLHandshakeException("TLS"),
        )
        for (failure in failures) {
            assertClassification(failure, false, "network")
            assertClassification(failure, false, "uncertain", emitted = true)
            assertClassification(failure, false, "uncertain", observed = false)
        }
    }

    @Test
    fun `absence of transport observation remains ambiguous`() = runTest {
        assertClassification(IOException("unobserved transport"), false, "uncertain", observed = false)
    }

    @Test
    fun `every non successful HTTP response stays ambiguous even without an emission callback`() = runTest {
        for (code in listOf(301, 302, 303, 307, 308, 421, 503)) {
            assertClassification(HfrServerException(code, "https://example.test/write"), false, "uncertain")
        }
    }

    @Test
    fun `login sniff is certain during preparation and ambiguous after emission`() = runTest {
        val expired = SessionExpiredException("https://example.test/login.php")
        assertClassification(expired, false, "session")
        assertClassification(expired, false, "uncertain", emitted = true)
    }

    @Test
    fun `delivery outcome is independent of equal presentation values`() = runTest {
        for (outcome in listOf(EditorSendOutcome.CERTAIN_FAILURE, EditorSendOutcome.UNCERTAIN)) {
            val store = VersionedEditorDraftStore()
            val settled = CompletableDeferred<String>()
            val binding = EditorSubmissionBinding<Unit, String>(
                EditorSubmissionEnvironment(this, DiagnosticsClock { 0L }, DiagnosticsLog()),
                EditorDraftSession(store, "key"),
                EditorSubmissionCallbacks(state = {}, error = { it?.let(settled::complete) }, success = {}),
                EditorSubmissionErrors("same", "same", "same", "same"),
            )
            binding.restore("key")
            binding.setPreference(false)
            binding.launch(binding.request(4_000, true) as SubmissionRequest.Accepted, Draft("body")) {
                EditorDelivery(error = "same", outcome = outcome)
            }
            assertEquals("same", settled.await())
            assertEquals(outcome == EditorSendOutcome.UNCERTAIN, binding.state is EditorSubmissionState.Uncertain)
            assertEquals(outcome == EditorSendOutcome.UNCERTAIN, store.entries["key"]?.attempt != null)
        }
    }

    private suspend fun TestScope.assertClassification(
        error: Exception,
        rejected: Boolean,
        expected: String,
        emitted: Boolean = false,
        observed: Boolean = true,
    ) {
        val store = VersionedEditorDraftStore()
        val settled = CompletableDeferred<String>()
        val binding = EditorSubmissionBinding<Unit, String>(
            EditorSubmissionEnvironment(this, DiagnosticsClock { 0L }, DiagnosticsLog()),
            EditorDraftSession(store, "key"),
            EditorSubmissionCallbacks(state = {}, error = { it?.let(settled::complete) }, success = {}),
            EditorSubmissionErrors("uncertain", "draft", "session", "unknown", "network"),
        )
        binding.restore("key")
        binding.setPreference(false)
        binding.launch(binding.request(4_000, true) as SubmissionRequest.Accepted, Draft("body")) {
            val witness = checkNotNull(currentCoroutineContext()[EditorWriteOwner])
            if (!observed) witness.emissionUnobserved()
            if (emitted) witness.markEmissionPossible()
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
