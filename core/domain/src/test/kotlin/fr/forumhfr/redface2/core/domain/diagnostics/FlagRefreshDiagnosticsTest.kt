package fr.forumhfr.redface2.core.domain.diagnostics

import fr.forumhfr.redface2.core.model.FlagType
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlagRefreshDiagnosticsTest {
    @Test
    fun `a multi-type fast refresh uses two entries and deduplicates shared transport`() {
        val log = DiagnosticsLog()
        val diagnostics = FlagRefreshDiagnostics(log)
        val trace = diagnostics.begin(FlagRefreshTrigger.PULL, FlagType.entries)
        val first = FlagFetchTrace(trace.id, FlagType.CYAN)
        val second = FlagFetchTrace(trace.id, FlagType.RED)
        first.categoryCount = 2
        second.categoryCount = 2
        first.page(sticky = false)
        second.page(sticky = false)
        val calls = listOf(summary(1, 20), summary(2, 40), summary(3, 60), summary(4, 80))
        calls.forEach(first::call)
        second.call(calls.last())
        first.markFinished()
        second.markFinished()
        trace.attach(first, wasJoined = false)
        trace.attach(second, wasJoined = true)
        trace.published()
        trace.finish()
        assertEquals(2, log.entries.value.size)
        val line = log.entries.value.last().message
        listOf("outcome=success", "joined=1", "calls=4", "maxMs=80", "medianMs=60", "reused=4",
            "categories=4", "bucketPages=2", "callFailures=0", "firstFreshPublicationMs=").forEach {
            assertTrue(line, line.contains(it))
        }
        assertFalse(line.contains("firstFreshPublicationMs=-"))
        assertEquals(3, line.substringAfter("slowest=[").split(";").size)
    }

    @Test
    fun `incomplete success and folded failure are distinguished without changing a business result`() {
        val log = DiagnosticsLog()
        val diagnostics = FlagRefreshDiagnostics(log)
        val incomplete = diagnostics.begin(FlagRefreshTrigger.INITIAL_OBSERVE, listOf(FlagType.CYAN))
        val fetch = FlagFetchTrace(incomplete.id, FlagType.CYAN)
        fetch.gap(FlagRefreshGap.STICKY_REQUEST)
        fetch.failed(FlagRefreshPhase.STICKIES, IOException("secret cookie"), fatal = false)
        incomplete.attach(fetch, false)
        incomplete.finish()
        assertTrue(log.entries.value.last().message.contains("outcome=success-incomplete"))
        assertTrue(log.entries.value.last().message.contains("stickiesFailures=1"))
        val failed = diagnostics.begin(FlagRefreshTrigger.RESUME, listOf(FlagType.RED))
        failed.failure(IOException("https://secret.proxy/?hash_check=SecretCookie"))
        failed.finish()
        assertTrue(log.entries.value.last().message.contains("outcome=failure exception=IOException"))
        assertFalse(log.entries.value.any { it.message.contains("SecretCookie") || it.message.contains("secret.proxy") })
    }

    private fun summary(id: Long, duration: Long): HfrCallSummary = HfrCallSummary(
        id = id, endpoint = HfrEndpoint(HfrEndpoint.Family.PARTICIPATED, 23, 1), role = HfrClientRole.AUTH,
        refreshId = 1, phase = FlagRefreshPhase.BUCKETS, waitMs = 0, dnsMs = 0, connectMs = 0, tlsMs = 0,
        headersSentToReceivedMs = duration, bodyMs = 0, totalMs = duration, status = 200, protocol = "h2",
        reusedConnection = true, inFlightAtStart = 1, outcome = HfrCallOutcome.OK,
    )
}
