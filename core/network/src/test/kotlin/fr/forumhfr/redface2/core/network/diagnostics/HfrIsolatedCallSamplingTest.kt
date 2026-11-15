package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshDiagnostics
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshTrigger
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCallOutcome
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCallSummary
import fr.forumhfr.redface2.core.domain.diagnostics.HfrClientRole
import fr.forumhfr.redface2.core.domain.diagnostics.HfrEndpoint
import fr.forumhfr.redface2.core.domain.diagnostics.HfrRefreshActivity
import fr.forumhfr.redface2.core.model.FlagType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HfrIsolatedCallSamplingTest {
    private var now = 0L
    private val log = DiagnosticsLog()
    private val activity = HfrRefreshActivity()
    private val collector = HfrNetworkDiagnostics(log, activity) { now }
    private val refreshes = FlagRefreshDiagnostics(log, activity = activity)

    @Test
    fun `only two isolated successes are sampled in any rolling minute`() {
        complete(start())
        now = 30_000_000_000L
        complete(start())
        now = 59_999_999_999L
        complete(start())
        assertEquals(2, samples().size)
        now = 60_000_000_000L
        complete(start())
        complete(start())
        assertEquals(3, samples().size)
        now = 90_000_000_000L
        complete(start())
        assertEquals(4, samples().size)
        assertTrue(samples().all { it.contains("outcome=ok") && it.endsWith("sample=isolated") })
    }

    @Test
    fun `a later overlapping call disqualifies both calls even if the first started alone`() {
        val first = start()
        val second = start()
        assertEquals(1, first.inFlightAtStart)
        complete(second)
        complete(first)
        assertTrue(samples().isEmpty())
        complete(start())
        assertEquals(1, samples().size)
    }

    @Test
    fun `overlap is remembered when the older call finishes first too`() {
        val first = start()
        val second = start()
        complete(first)
        complete(second)
        assertTrue(samples().isEmpty())
    }

    @Test
    fun `a refresh entirely inside an uncorrelated call disqualifies its sample`() {
        val call = start()
        refreshes.begin(FlagRefreshTrigger.PULL, listOf(FlagType.CYAN)).finish()
        complete(call)
        assertTrue(samples().isEmpty())
        complete(start())
        assertEquals(1, samples().size)
    }

    @Test
    fun `an uncorrelated call started during a refresh is not sampled after that refresh closes`() {
        val refresh = refreshes.begin(FlagRefreshTrigger.PULL, listOf(FlagType.CYAN))
        val call = start()
        refresh.finish()
        complete(call)
        assertTrue(samples().isEmpty())
    }

    @Test
    fun `failures keep the existing detail quota without spending the success sample quota`() {
        collector.completed(start().copy(outcome = HfrCallOutcome.FAILURE), null)
        collector.completed(start().copy(outcome = HfrCallOutcome.CANCELLED), null)
        complete(start())
        complete(start())
        assertEquals(2, samples().size)
        assertEquals(4, log.entries.value.size)
    }

    private fun start(): HfrCallSummary {
        val id = collector.nextId()
        return HfrCallSummary(
            id = id, endpoint = HfrEndpoint(HfrEndpoint.Family.MP_LIST, page = 1), role = HfrClientRole.AUTH,
            refreshId = null, phase = null, waitMs = 0, dnsMs = 1, connectMs = 2, tlsMs = 1,
            headersSentToReceivedMs = 3, bodyMs = 4, totalMs = 10, status = 200, protocol = "h2",
            reusedConnection = false, inFlightAtStart = collector.started(id), outcome = HfrCallOutcome.OK,
        )
    }

    private fun complete(call: HfrCallSummary) { collector.completed(call, null) }
    private fun samples(): List<String> = log.entries.value.map { it.message }.filter { it.endsWith("sample=isolated") }
}
