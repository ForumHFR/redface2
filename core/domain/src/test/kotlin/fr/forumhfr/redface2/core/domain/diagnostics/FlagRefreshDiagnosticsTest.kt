package fr.forumhfr.redface2.core.domain.diagnostics

import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshCategory
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlagRefreshDiagnosticsTest {
    @Test
    fun `begin captures immutable network and device context once for both refresh lines`() {
        var now = 100_900_000_000L
        val log = DiagnosticsLog()
        val foreground = AppForegroundDiagnostics(log, DiagnosticsClock { now })
        foreground.foregrounded()
        now = 106_200_000_000L
        val transports = mutableSetOf(NetworkTransport.VPN, NetworkTransport.WIFI)
        var context = NetworkDiagnosticsContext(transports, metered = false, validated = true, privateDns = true)
        var captures = 0
        val provider = NetworkDiagnosticsContextProvider { captures++; context }
        val diagnostics = FlagRefreshDiagnostics(log, provider, foreground)
        val trace = diagnostics.begin(FlagRefreshTrigger.PULL, listOf(FlagType.CYAN))
        // Neither a mutable source, a network switch, nor a later resume can rewrite this refresh.
        transports.clear()
        context = NetworkDiagnosticsContext(emptySet())
        now = 150_000_000_000L
        foreground.backgrounded()
        foreground.foregrounded()
        trace.finish()
        val suffix = "transport=vpn+wifi metered=0 validated=1 privateDns=1 uptimeS=106 foregroundS=5"
        log.entries.value.filter { it.tag == "FlagsRefresh" }.forEach {
            assertTrue(it.message, it.message.endsWith(suffix))
        }
        assertEquals(1, captures)
        diagnostics.begin(FlagRefreshTrigger.RESUME, listOf(FlagType.RED))
        assertTrue(log.entries.value.last().message.endsWith(
            "transport=none metered=? validated=? privateDns=? uptimeS=150 foregroundS=0",
        ))
    }

    @Test
    fun `context provider failures are unknown and never copy exception messages`() {
        val log = DiagnosticsLog()
        val provider = NetworkDiagnosticsContextProvider { error("SecretSsid 192.0.2.1 SecretDns") }
        val foreground = AppForegroundDiagnostics(log, DiagnosticsClock { 15_000_000_000L })
        val trace = FlagRefreshDiagnostics(log, provider, foreground)
            .begin(FlagRefreshTrigger.LAUNCH, listOf(FlagType.CYAN))
        trace.finish()
        log.entries.value.forEach {
            assertTrue(it.message.endsWith(
                "transport=unknown metered=? validated=? privateDns=? uptimeS=15 foregroundS=?",
            ))
            assertFalse(it.message.contains("Secret"))
            assertFalse(it.message.contains("192.0.2.1"))
        }
    }

    @Test
    fun `list issue uses the shared sweep gaps and excludes uncertain count or storage gaps - 984`() {
        val categories = listOf(Category(13, "Programmation", forceSubcat = false, subcategoryCount = 0))
        val fetch = FlagFetchTrace(1, FlagType.CYAN)
        assertNull(fetch.listIssue(categories))
        fetch.gap(FlagRefreshGap.EMPTY_BUCKET_PAGE)
        fetch.gap(FlagRefreshGap.ROOM_WRITE)
        assertNull(fetch.listIssue(categories))

        val sweep = FlagFetchTrace(1, FlagType.CYAN)
        sweep.gap(FlagRefreshGap.STICKY_REQUEST, stickyCategory = 13)
        fetch.absorbSticky(sweep)
        assertEquals(listOf(FlagRefreshCategory(13, "Programmation")), fetch.listIssue(categories)?.stickyCategories)
        fetch.gap(FlagRefreshGap.STICKY_DECODE, stickyCategory = 13)
        fetch.gap(FlagRefreshGap.PAGE_CAP)
        assertEquals(1, fetch.listIssue(categories)?.stickyCategories?.size)
        assertEquals(true, fetch.listIssue(categories)?.paginationTruncated)
        assertTrue(fetch.gapSnapshot().containsAll(sweep.gapSnapshot()))
    }

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
        listOf("outcome=success", "fetches=2 newFetches=1 joined=1", "calls=4", "maxMs=80", "medianMs=60", "reused=4",
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
    fun `both generations are new but only the returned result determines completeness`() {
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
        assertTrue(line, line.contains("fetches=2 newFetches=2 joined=0"))
        assertFalse(line, line.contains("cache_generation") || line.contains("sticky_request"))
    }

    @Test
    fun `returned cached replacement retains its own gaps instead of abandoned attempt gaps`() {
        val log = DiagnosticsLog()
        val diagnostics = FlagRefreshDiagnostics(log)
        val trace = diagnostics.begin(FlagRefreshTrigger.PULL, listOf(FlagType.CYAN))
        val abandoned = FlagFetchTrace(trace.id, FlagType.CYAN)
        abandoned.gap(FlagRefreshGap.CACHE_GENERATION)
        trace.attach(abandoned, false)
        val cached = diagnostics.fetch(FlagType.CYAN, refreshId = null)
        cached.gap(FlagRefreshGap.ROOM_WRITE)
        cached.markFinished()
        trace.result(FlagType.CYAN, cached)
        trace.finish()

        val line = log.entries.value.last().message
        // A detached source consumes an identity, but creates neither a begin nor a summary.
        assertEquals(2, log.entries.value.size)
        assertTrue(line, line.contains("outcome=success-incomplete"))
        assertTrue(line, line.contains("reasons=room_write"))
        assertTrue(line, line.contains("fetches=2 newFetches=1 joined=1"))
        assertTrue(line, line.contains("fetchRefreshIds=[1, 2]"))
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
