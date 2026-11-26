package fr.forumhfr.redface2.core.data.stats

import fr.forumhfr.redface2.core.domain.diagnostics.AppVisibility
import fr.forumhfr.redface2.core.domain.stats.NetworkPeakGroup
import fr.forumhfr.redface2.core.domain.stats.NetworkPeaks
import fr.forumhfr.redface2.core.domain.stats.NetworkRequestCounts
import fr.forumhfr.redface2.core.domain.stats.NetworkRole
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsBuffer
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsProcess
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsReport
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsStart
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsTotals
import fr.forumhfr.redface2.core.domain.stats.NetworkStatus
import fr.forumhfr.redface2.core.domain.stats.NetworkTarget
import fr.forumhfr.redface2.core.domain.stats.NetworkTrigger
import fr.forumhfr.redface2.core.domain.stats.NoRequestReason
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkStatsWriterTest {
    private val now = Instant.ofEpochSecond(3000L * 3600)
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val buffer = NetworkStatsBuffer(NetworkStatsProcess(72, isMain = true))
    private val store = MemoryStore()

    @Test
    fun `startup purges and both the 60 second timer and app stop flush`() = runTest {
        val writer = NetworkStatsWriter(buffer, store, clock, backgroundScope, StandardTestDispatcher(testScheduler))
        writer.start()
        runCurrent()
        assertEquals(840L, store.purgeBefore)

        record(3000)
        advanceTimeBy(59_999)
        assertTrue(store.rows.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1L, store.rows.getValue(3000).noRequests.values.single())

        record(3000)
        writer.requestFlush()
        runCurrent()
        assertEquals(2L, store.rows.getValue(3000).noRequests.values.single())
        assertEquals(2, store.merges)
    }

    @Test
    fun `256 keys wake the writer without advancing virtual time`() = runTest {
        val writer = NetworkStatsWriter(buffer, store, clock, backgroundScope, StandardTestDispatcher(testScheduler))
        writer.start()
        runCurrent()
        repeat(256) { record(3000L + it) }
        runCurrent()
        assertEquals(256, store.rows.size)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `peaks merge by max between flushes and windows restart at the next UTC hour`() = runTest {
        val writer = NetworkStatsWriter(buffer, store, clock, backgroundScope, StandardTestDispatcher(testScheduler))
        writer.start()
        val second = now.epochSecond + 3599
        send(second)
        writer.requestFlush()
        runCurrent()
        send(second)
        send(second + 1)
        writer.requestFlush()
        runCurrent()

        assertEquals(NetworkPeaks(2, 2, 1), store.rows.getValue(3000).peaks.values.single())
        assertEquals(NetworkPeaks(1, 1, 1), store.rows.getValue(3001).peaks.values.single())
    }

    @Test
    fun `reset waits for the writer and removes old in flight measurements`() = runTest {
        val writer = NetworkStatsWriter(buffer, store, clock, backgroundScope, StandardTestDispatcher(testScheduler))
        val old = checkNotNull(buffer.started(start(now.epochSecond)))
            .sent(NetworkTarget.HTML_FORUM2, NetworkPeakGroup.FORUM, NetworkTrigger.NONE)
        val releaseMerge = CompletableDeferred<Unit>()
        store.mergeGate = releaseMerge
        writer.start()
        writer.requestFlush()
        runCurrent()
        assertEquals(1, store.merges)

        val reset = async { writer.reset() }
        runCurrent()
        assertFalse(reset.isCompleted)
        releaseMerge.complete(Unit)
        runCurrent()
        assertTrue(reset.await())
        old.finish(NetworkStatus.SUCCESS, NetworkRequestCounts(requests = 1))
        writer.requestFlush()
        runCurrent()
        assertTrue(store.rows.isEmpty())
        assertEquals(1, store.merges)
    }

    @Test
    fun `a failed flush keeps additions for the next successful write`() = runTest {
        val writer = NetworkStatsWriter(buffer, store, clock, backgroundScope, StandardTestDispatcher(testScheduler))
        writer.start()
        store.failNextMerge = true
        record(3000)
        writer.requestFlush()
        runCurrent()
        assertTrue(store.rows.isEmpty())

        record(3000)
        writer.requestFlush()
        runCurrent()
        assertEquals(2L, store.rows.getValue(3000).noRequests.values.single())
        assertEquals(2, store.merges)
    }

    @Test
    fun `read persists completed exchanges and rounds outwards to UTC hours`() = runTest {
        val writer = NetworkStatsWriter(buffer, store, clock, backgroundScope, StandardTestDispatcher(testScheduler))
        send(now.epochSecond)
        writer.read(now.plusSeconds(1), now.plusSeconds(3600).plusNanos(1))

        assertEquals(3000L to 3002L, store.lastRange)
        assertEquals(1L, store.rows.getValue(3000).requests.values.single().requests)
    }

    @Test
    fun `secondary process does not purge flush read or reset the store`() = runTest {
        val app = "fr.forumhfr.redface2.dev"
        val other = NetworkStatsBuffer(NetworkStatsProcess.forApp(72, "$app:launcherIconRestart", app))
        val writer = NetworkStatsWriter(other, store, clock, backgroundScope, StandardTestDispatcher(testScheduler))
        writer.start()
        writer.requestFlush()
        advanceTimeBy(60_001)

        assertEquals(NetworkStatsReport(), writer.read(now, now.plusSeconds(3600)))
        assertFalse(writer.reset())
        assertEquals(null, store.purgeBefore)
        assertEquals(0, store.merges + store.reads + store.resets)
    }

    private fun record(hour: Long) =
        checkNotNull(buffer.started(start(hour * 3600)))
            .noRequest(NetworkTarget.REST_CATALOGUE, NoRequestReason.DNS, NetworkTrigger.NONE)

    private fun send(second: Long) = checkNotNull(buffer.started(start(second)))
        .sent(NetworkTarget.HTML_FORUM2, NetworkPeakGroup.FORUM, NetworkTrigger.NONE)
        .finish(NetworkStatus.SUCCESS, NetworkRequestCounts(requests = 1))

    private fun start(second: Long) =
        NetworkStatsStart(second, second * 1_000_000_000, AppVisibility.UNKNOWN, NetworkRole.AUTH)

    private class MemoryStore : NetworkStatsStore {
        var purgeBefore: Long? = null
        var mergeGate: CompletableDeferred<Unit>? = null
        var failNextMerge = false
        var lastRange: Pair<Long, Long>? = null
        var merges = 0
        var reads = 0
        var resets = 0
        val rows = mutableMapOf<Long, NetworkStatsTotals>()

        override suspend fun merge(hours: Map<Long, NetworkStatsTotals>) {
            merges++
            if (failNextMerge) {
                failNextMerge = false
                error("storage failure")
            }
            mergeGate?.await()
            hours.forEach { (hour, totals) -> rows.merge(hour, totals, NetworkStatsTotals::plus) }
        }

        override suspend fun purge(beforeHour: Long) {
            purgeBefore = beforeHour
        }

        override suspend fun read(fromHour: Long, untilHour: Long): NetworkStatsReport {
            reads++
            lastRange = fromHour to untilHour
            return NetworkStatsReport()
        }

        override suspend fun reset() {
            resets++
            rows.clear()
        }
    }
}
