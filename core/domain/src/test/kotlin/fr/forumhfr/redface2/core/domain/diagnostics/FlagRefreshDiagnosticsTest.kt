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
        trace.result(FlagType.CYAN, first)
        trace.result(FlagType.RED, second)
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
        incomplete.result(FlagType.CYAN, fetch)
        incomplete.finish()
        assertTrue(log.entries.value.last().message.contains("outcome=success-incomplete"))
        assertTrue(log.entries.value.last().message.contains("stickiesFailures=1"))
        val failed = diagnostics.begin(FlagRefreshTrigger.RESUME, listOf(FlagType.RED))
        failed.failure(IOException("https://secret.proxy/?hash_check=SecretCookie"))
        failed.finish()
        assertTrue(log.entries.value.last().message.contains("outcome=failure exception=IOException"))
        assertFalse(log.entries.value.any {
            it.message.contains("SecretCookie") || it.message.contains("secret.proxy")
        })
    }

    @Test
    fun `a replaced generation keeps attempt counters but only the returned result determines completeness`() {
        val log = DiagnosticsLog()
        val trace = FlagRefreshDiagnostics(log).begin(FlagRefreshTrigger.PULL, listOf(FlagType.CYAN))
        val superseded = FlagFetchTrace(trace.id, FlagType.CYAN)
        superseded.gap(FlagRefreshGap.CACHE_GENERATION)
        superseded.gap(FlagRefreshGap.STICKY_REQUEST)
        superseded.markFinished()
        val replacement = FlagFetchTrace(trace.id, FlagType.CYAN)
        replacement.markFinished()
        trace.attach(superseded, false)
        trace.attach(replacement, false)
        trace.result(FlagType.CYAN, replacement)
        trace.published()
        trace.finish()

        val line = log.entries.value.last().message
        assertTrue(line, line.contains("outcome=success "))
        assertTrue(line, line.contains("fetches=2 newFetches=2"))
        assertFalse(line, line.contains("cache_generation") || line.contains("sticky_request"))
    }

    @Test
    fun `returned cached replacement retains its own gaps instead of abandoned attempt gaps`() {
        val log = DiagnosticsLog()
        val trace = FlagRefreshDiagnostics(log).begin(FlagRefreshTrigger.PULL, listOf(FlagType.CYAN))
        val abandoned = FlagFetchTrace(trace.id, FlagType.CYAN)
        abandoned.gap(FlagRefreshGap.CACHE_GENERATION)
        trace.attach(abandoned, false)
        val cached = FlagFetchTrace(99, FlagType.CYAN)
        cached.gap(FlagRefreshGap.ROOM_WRITE)
        trace.result(FlagType.CYAN, cached)
        trace.finish()

        val line = log.entries.value.last().message
        assertTrue(line, line.contains("outcome=success-incomplete"))
        assertTrue(line, line.contains("reasons=room_write"))
        assertTrue(line, line.contains("fetches=2 newFetches=1 joined=1"))
        assertFalse(line, line.contains("cache_generation"))
    }

    @Test
    fun `two saturated Super pulls share one twelve detail quota each and retain both triggers and summaries`() {
        val log = DiagnosticsLog()
        val diagnostics = FlagRefreshDiagnostics(log)
        repeat(2) { refresh ->
            val trace = diagnostics.begin(FlagRefreshTrigger.PULL, FlagType.entries)
            val fetches = FlagType.entries.map { FlagFetchTrace(trace.id, it) }
            repeat(114) { index ->
                val fetch = fetches[index % fetches.size]
                fetch.call(summary((refresh * 114 + index).toLong(), 1_500).copy(refreshId = trace.id))
            }
            // A shared call does not consume quota twice, even across types.
            fetches.last().call(fetches.first().callSnapshot().first())
            fetches.forEach { trace.attach(it, false); trace.result(it.type, it); it.markFinished() }
            trace.published()
            trace.finish()
        }

        val entries = log.entries.value
        assertEquals(28, entries.size)
        assertEquals(24, entries.count { it.tag == "HfrCall" })
        assertEquals(2, entries.count { it.message.contains("trigger=pull") })
        val summaries = entries.filter { it.tag == "FlagsRefresh" && it.message.contains("outcome=") }
        assertEquals(2, summaries.size)
        summaries.forEach {
            assertTrue(it.message, it.message.contains("calls=114 "))
            assertTrue(it.message, it.message.contains("details-omitted=102 "))
        }
        assertEquals(summaries.last(), entries.last())
    }

    private fun summary(id: Long, duration: Long): HfrCallSummary = HfrCallSummary(
        id = id, endpoint = HfrEndpoint(HfrEndpoint.Family.PARTICIPATED, 23, 1), role = HfrClientRole.AUTH,
        refreshId = 1, phase = FlagRefreshPhase.BUCKETS, waitMs = 0, dnsMs = 0, connectMs = 0, tlsMs = 0,
        headersSentToReceivedMs = duration, bodyMs = 0, totalMs = duration, status = 200, protocol = "h2",
        reusedConnection = true, inFlightAtStart = 1, outcome = HfrCallOutcome.OK,
    )
}
