package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.EditorSubmissionTrace
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSubmissionTraceTest {
    @Test
    fun `case 7 events are correlated and contain no body credentials url or submission token`() {
        val log = DiagnosticsLog()
        val exported = mutableListOf<String>()
        log.exportSubmissionTraces { exported += it }
        val trace = EditorSubmissionTrace(log)
        val secrets = listOf("PrivateBody", "PrivateSubject", "PrivateRecipient", "SecretHash", "SecretCookie")
        val body = FormBody.Builder().add("content", secrets[0]).add("sujet", secrets[1])
            .add("dest", secrets[2]).add("hash_check", secrets[3]).build()
        val request = Request.Builder()
            .url("https://forum.hardware.fr/bddpost.php?private=PrivateRecipient")
            .header("Cookie", secrets[4]).post(body).build().withHfrDiagnostics(trace)
        val call = OkHttpClient().newCall(request) // Never executed: no network in this test.
        val listener = HfrEventListenerFactory(HfrNetworkDiagnostics(log)).create(call)

        trace.durable(17)
        listener.requestBodyEnd(call, 987)
        trace.result(EditorSubmissionTrace.Result.UNCERTAIN)

        val lines = log.entries.value.filter { it.tag == EditorSubmissionTrace.TAG }.map { it.message }
        assertEquals(3, lines.size)
        assertEquals(lines, exported)
        assertEquals(1, lines.map { it.substringBefore(' ') }.distinct().size)
        assertTrue(lines[0].endsWith("attempt_durable writeMs=17"))
        assertTrue(lines[1].endsWith("request_body_end"))
        assertTrue(lines[2].endsWith("result class=UNCERTAIN"))
        val grammar = Regex(
            "trace=[0-9a-f-]{36} (attempt_durable writeMs=[0-9]+|request_body_end|result class=[A-Z_]+)",
        )
        lines.forEach { line ->
            assertTrue(line, grammar.matches(line))
            (secrets + request.url.toString() + listOf("hash_check", "Cookie", "987")).forEach {
                assertFalse(line, line.substringAfter(' ').contains(it))
            }
        }
    }

    @Test
    fun `unrelated requests and preparatory GETs emit no body completion trace`() {
        val log = DiagnosticsLog()
        val factory = HfrEventListenerFactory(HfrNetworkDiagnostics(log))
        val client = OkHttpClient()
        val plain = Request.Builder().url("https://forum.hardware.fr/bddpost.php").build()
        listOf(plain, plain.withHfrDiagnostics(EditorSubmissionTrace(log))).forEach { request ->
            val call = client.newCall(request)
            factory.create(call).requestBodyEnd(call, 42)
        }
        assertTrue(log.entries.value.isEmpty())
    }
}
