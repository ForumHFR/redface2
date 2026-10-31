package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.FlagFetchTrace
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshDiagnostics
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshPhase
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshTrace
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshTrigger
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCallOutcome
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCallSummary
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCatalogueTrace
import fr.forumhfr.redface2.core.domain.diagnostics.HfrClientRole
import fr.forumhfr.redface2.core.domain.diagnostics.HfrEndpoint
import fr.forumhfr.redface2.core.model.FlagType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HfrNetworkDiagnosticsTest {
    private val log = DiagnosticsLog()
    private var now = 0L
    private val collector = HfrNetworkDiagnostics(log) { now }
    private val diagnostics = FlagRefreshDiagnostics(log)

    @Test
    fun `fetch without a refresh immediately publishes slow failed and cancelled calls`() {
        // findFlag and detached mutation refreshes own a fetch, without a UI refresh trace.
        val fetch = diagnostics.fetch(FlagType.CYAN, refreshId = null)
        complete(fetch, duration = 10)
        assertTrue(log.entries.value.isEmpty())
        val slow = complete(fetch)
        val failed = complete(fetch, duration = 10, outcome = HfrCallOutcome.FAILURE)
        val cancelled = complete(fetch, duration = 10, outcome = HfrCallOutcome.CANCELLED)

        assertEquals(listOf(slow, failed, cancelled).map { it.copy(refreshId = null).line() }, details())
        assertEquals(4, fetch.callSnapshot().size)
    }

    @Test
    fun `call completing after its cancelled refresh immediately publishes its detail`() {
        val refresh = diagnostics.begin(FlagRefreshTrigger.PULL, listOf(FlagType.CYAN))
        val fetch = diagnostics.fetch(FlagType.CYAN, refresh.id)
        refresh.attach(fetch, false)
        refresh.failure(CancellationException("Secret cancellation"))
        refresh.finish()
        val late = complete(fetch)
        refresh.finish()

        assertEquals(listOf(late.copy(refreshId = null).line()), details())
        assertEquals(1, fetch.callSnapshot().size)
        assertEquals(2, log.entries.value.count { it.tag == "FlagsRefresh" })
        assertTrue(log.entries.value.last().message.contains("totalMs=1500"))
    }

    @Test
    fun `a remaining active refresh still publishes a shared fetch after another refresh closes`() {
        val first = diagnostics.begin(FlagRefreshTrigger.PULL, listOf(FlagType.CYAN))
        val second = diagnostics.begin(FlagRefreshTrigger.RESUME, listOf(FlagType.CYAN))
        val fetch = diagnostics.fetch(FlagType.CYAN, first.id)
        first.attach(fetch, false)
        second.attach(fetch, true)
        first.finish()
        val call = complete(fetch)
        assertTrue(details().isEmpty())
        second.finish()

        assertEquals(listOf(call.line()), details())
        assertTrue(log.entries.value.last().message.contains("calls=1 "))
        val late = complete(fetch, outcome = HfrCallOutcome.FAILURE)
        assertEquals(listOf(call.line(), late.copy(refreshId = null).line()), details())
    }

    @Test
    fun `catalogue attached to an unowned fetch still uses the immediate quota`() {
        val catalogue = HfrCatalogueTrace()
        val fetch = diagnostics.fetch(FlagType.CYAN, refreshId = null)
        fetch.catalogue(catalogue, wasJoined = true)
        val call = complete(catalogue = catalogue)

        assertEquals(listOf(call.line()), details())
        assertEquals(call.id, fetch.callSnapshot().single().id)
    }

    @Test
    fun `catalogue defers only while a refresh can still publish its calls`() {
        val catalogue = HfrCatalogueTrace()
        val refresh = diagnostics.begin(FlagRefreshTrigger.PULL, listOf(FlagType.CYAN))
        val fetch = diagnostics.fetch(FlagType.CYAN, refresh.id)
        refresh.attach(fetch, false)
        fetch.catalogue(catalogue, wasJoined = true)
        val call = complete(catalogue = catalogue)
        assertTrue(details().isEmpty())
        refresh.finish()

        assertEquals(listOf(call.copy(
            refreshId = fetch.refreshId, flagType = fetch.type, phase = FlagRefreshPhase.CATALOGUE,
        ).line()), details())
        val late = complete(catalogue = catalogue)
        assertEquals(late.copy(refreshId = null).line(), details().last())
        assertEquals(2, details().size)
    }

    @Test
    fun `detached slow call published immediately is aggregated without replay when its refresh joins late`() {
        val refresh = diagnostics.begin(FlagRefreshTrigger.PULL, listOf(FlagType.CYAN))
        val original = diagnostics.fetch(FlagType.CYAN, refresh.id)
        refresh.attach(original, false)
        val replacement = diagnostics.fetch(FlagType.CYAN, refreshId = null)
        val call = HfrCallSummary(
            id = collector.nextId(), endpoint = HfrEndpoint(HfrEndpoint.Family.PARTICIPATED, 23, 1),
            role = HfrClientRole.AUTH, refreshId = replacement.refreshId, flagType = replacement.type,
            phase = FlagRefreshPhase.BUCKETS, waitMs = 50, dnsMs = 10, connectMs = 100, tlsMs = 70,
            headersSentToReceivedMs = 1_000, bodyMs = 20, totalMs = 1_500, status = 200, protocol = "h2",
            reusedConnection = false, inFlightAtStart = collector.started(), outcome = HfrCallOutcome.OK,
        )
        collector.completed(call, replacement)
        // The network already published N1 before the original refresh receives addFlag's result.
        assertEquals(listOf(call.copy(refreshId = null).line()), details())
        replacement.markFinished()
        refresh.result(FlagType.CYAN, replacement)
        refresh.published()
        refresh.finish()
        refresh.finish()

        assertEquals(1, details().size)
        assertEquals(1, replacement.callSnapshot().size)
        val line = log.entries.value.last().message
        listOf("calls=1 ", "totalCallMs=1500 ", "maxMs=1500 ", "medianMs=1500 ",
            "fetches=2 newFetches=1 joined=1", "details-omitted=0 ", "bucketsCallMs=1500 ",
            "waitMs=50 ", "dnsMs=10 ", "connectMs=100 ", "tlsMs=70 ", "bodyMs=20 ",
            "headersSentToReceivedMs=1000 ").forEach {
            assertTrue(line, line.contains(it))
        }
    }

    @Test
    fun `catalogue published immediately retains all timings for a late owner without replay`() {
        val catalogue = HfrCatalogueTrace()
        val call = HfrCallSummary(
            id = collector.nextId(), endpoint = HfrEndpoint(HfrEndpoint.Family.CATEGORIES),
            role = HfrClientRole.ANON, refreshId = null, phase = FlagRefreshPhase.CATALOGUE,
            waitMs = 11, dnsMs = 23, connectMs = 37, tlsMs = 17,
            headersSentToReceivedMs = 1_401, bodyMs = 29, totalMs = 1_501,
            status = 200, protocol = "h2", reusedConnection = false,
            inFlightAtStart = collector.started(), outcome = HfrCallOutcome.OK,
        )
        collector.completed(call, fetch = null, catalogue = catalogue)
        val refresh = diagnostics.begin(FlagRefreshTrigger.PULL, listOf(FlagType.RED))
        val fetch = diagnostics.fetch(FlagType.RED, refresh.id)
        fetch.catalogue(catalogue, wasJoined = true)
        refresh.attach(fetch, wasJoined = false)
        refresh.result(FlagType.RED, fetch)
        refresh.finish()

        assertEquals(listOf(call.line()), details())
        val retained = fetch.callSnapshot().single()
        assertEquals(call.id, retained.id)
        assertEquals(call.totalMs, retained.totalMs)
        assertEquals(call.waitMs, retained.waitMs)
        assertEquals(call.dnsMs, retained.dnsMs)
        assertEquals(call.connectMs, retained.connectMs)
        assertEquals(call.tlsMs, retained.tlsMs)
        assertEquals(call.headersSentToReceivedMs, retained.headersSentToReceivedMs)
        assertEquals(call.bodyMs, retained.bodyMs)
        assertEquals(refresh.id, retained.refreshId)
        assertEquals(FlagType.RED, retained.flagType)
        assertEquals(FlagRefreshPhase.CATALOGUE, retained.phase)
        val line = log.entries.value.last().message
        listOf("calls=1 ", "catalogue=joined ", "catalogueCallMs=1501 ", "totalCallMs=1501 ",
            "waitMs=11 ", "dnsMs=23 ", "connectMs=37 ", "tlsMs=17 ",
            "headersSentToReceivedMs=1401 ", "bodyMs=29 ",
            "details-omitted=0 ").forEach { assertTrue(line, line.contains(it)) }
    }

    @Test
    fun `an already published call does not consume a late refresh detail quota`() {
        val fetch = diagnostics.fetch(FlagType.CYAN, refreshId = null)
        val immediate = complete(fetch)
        val refresh = diagnostics.begin(FlagRefreshTrigger.PULL, listOf(FlagType.CYAN))
        refresh.attach(fetch, wasJoined = true)
        repeat(13) { complete(fetch) }
        refresh.finish()

        assertEquals(13, details().size)
        assertEquals(immediate.copy(refreshId = null).line(), details().first())
        assertTrue(log.entries.value.last().message.contains("calls=14 "))
        assertTrue(log.entries.value.last().message.contains("totalCallMs=21000 "))
        assertTrue(log.entries.value.last().message.contains("details-omitted=1 "))
    }

    @Test
    fun `two refreshes finishing a shared catalogue concurrently emit its detail exactly once`() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(20) {
                log.clear()
                val catalogue = HfrCatalogueTrace()
                val refreshes = listOf(FlagType.CYAN, FlagType.RED).map { type ->
                    val refresh = diagnostics.begin(FlagRefreshTrigger.PULL, listOf(type))
                    val fetch = diagnostics.fetch(type, refresh.id)
                    fetch.catalogue(catalogue, wasJoined = true)
                    refresh.attach(fetch, false)
                    refresh.result(type, fetch)
                    refresh
                }
                complete(catalogue = catalogue)
                assertTrue(details().isEmpty())
                finishTogether(refreshes, workers)

                assertEquals(1, details().size)
                val summaries = log.entries.value.filter { it.message.contains("outcome=success ") }
                assertEquals(2, summaries.size)
                summaries.forEach {
                    assertTrue(it.message, it.message.contains("calls=1 "))
                    assertTrue(it.message, it.message.contains("catalogueCallMs=1500 "))
                    assertTrue(it.message, it.message.contains("totalCallMs=1500 "))
                    assertTrue(it.message, it.message.contains("details-omitted=0 "))
                }
            }
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `shared sticky sweep calls retain the twelve detail refresh quota`() {
        val refresh = diagnostics.begin(FlagRefreshTrigger.PULL, listOf(FlagType.CYAN, FlagType.RED))
        val first = diagnostics.fetch(FlagType.CYAN, refresh.id)
        val second = diagnostics.fetch(FlagType.RED, refresh.id)
        val sweep = FlagFetchTrace(refresh.id, FlagType.CYAN)
        first.sticky(sweep)
        refresh.attach(first, false)
        refresh.attach(second, false)
        second.sticky(sweep)
        repeat(114) { complete(sweep) }
        assertTrue(details().isEmpty())
        first.absorbSticky(sweep)
        second.absorbSticky(sweep)
        refresh.finish()

        assertEquals(12, details().size)
        assertTrue(log.entries.value.last().message.contains("calls=114 "))
        assertTrue(log.entries.value.last().message.contains("details-omitted=102 "))
        val late = complete(sweep)
        assertEquals(late.copy(refreshId = null).line(), details().last())
        assertEquals(13, details().size)
        assertEquals(12, details().count { it.startsWith("refresh=${refresh.id} ") })
        assertTrue(details().last().startsWith("refresh=- "))
    }

    @Test
    fun `immediate detail quota renews at exactly sixty seconds with an injected clock`() {
        val unowned = diagnostics.fetch(FlagType.CYAN, refreshId = null)
        repeat(30) { complete(if (it % 2 == 0) unowned else null) }
        assertEquals(24, details().size)
        now = 59_999_999_999L
        complete(unowned)
        assertEquals(24, details().size)
        now = 60_000_000_000L
        complete(unowned)
        assertEquals(25, details().size)
        repeat(30) { complete() }
        assertEquals(48, details().size)
    }

    @Test
    fun `refresh closure racing with completion neither loses nor duplicates the detail`() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(20) {
                log.clear()
                val refresh = diagnostics.begin(FlagRefreshTrigger.PULL, listOf(FlagType.CYAN))
                val fetch = diagnostics.fetch(FlagType.CYAN, refresh.id)
                refresh.attach(fetch, false)
                val ready = CountDownLatch(2)
                val start = CountDownLatch(1)
                val closing = workers.submit {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    refresh.finish()
                }
                val completing = workers.submit {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    complete(fetch)
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                closing.get(5, TimeUnit.SECONDS)
                completing.get(5, TimeUnit.SECONDS)
                assertEquals(1, details().size)
                assertEquals(1, fetch.callSnapshot().size)
            }
        } finally {
            workers.shutdownNow()
        }
    }

    private fun finishTogether(refreshes: List<FlagRefreshTrace>, workers: ExecutorService) {
        val ready = CountDownLatch(refreshes.size)
        val start = CountDownLatch(1)
        val finishing = refreshes.map { refresh ->
            workers.submit {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                refresh.finish()
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        finishing.forEach { it.get(5, TimeUnit.SECONDS) }
    }

    private fun complete(
        fetch: FlagFetchTrace? = null,
        catalogue: HfrCatalogueTrace? = null,
        duration: Long = 1_500,
        outcome: HfrCallOutcome = HfrCallOutcome.OK,
    ): HfrCallSummary {
        val summary = HfrCallSummary(
            id = collector.nextId(), endpoint = HfrEndpoint(HfrEndpoint.Family.PARTICIPATED, 23, 1),
            role = HfrClientRole.AUTH, refreshId = fetch?.refreshId, flagType = fetch?.type,
            phase = FlagRefreshPhase.BUCKETS, waitMs = 0, dnsMs = 0, connectMs = 0, tlsMs = 0,
            headersSentToReceivedMs = duration, bodyMs = 0, totalMs = duration, status = 200, protocol = "h2",
            reusedConnection = true, inFlightAtStart = collector.started(), outcome = outcome,
        )
        collector.completed(summary, fetch, catalogue)
        return summary
    }

    private fun details(): List<String> = log.entries.value.filter { it.tag == "HfrCall" }.map { it.message }
}
