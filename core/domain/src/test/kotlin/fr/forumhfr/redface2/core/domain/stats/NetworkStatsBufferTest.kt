package fr.forumhfr.redface2.core.domain.stats

import fr.forumhfr.redface2.core.domain.diagnostics.AppVisibility
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkStatsBufferTest {
    private val buffer = NetworkStatsBuffer(NetworkStatsProcess(72, isMain = true))

    @Test
    fun `one minute and second survive a drain and the hour boundary starts new windows`() = runTest {
        val first = send(3599)
        val before = buffer.drain()
        val second = send(3599)
        first.finish(NetworkStatus.SUCCESS, NetworkRequestCounts(requests = 1))
        val nextHour = send(3600)
        second.finish(NetworkStatus.FORBIDDEN, NetworkRequestCounts(1, 0, 0, 1))
        nextHour.finish(NetworkStatus.SUCCESS, NetworkRequestCounts(1, 10, 0, 0))
        val after = buffer.drain()

        assertEquals(NetworkPeaks(1, 1, 1), before.getValue(0).peaks.values.single())
        assertEquals(NetworkPeaks(2, 2, 2), after.getValue(0).peaks.values.single())
        assertEquals(NetworkPeaks(1, 1, 2), after.getValue(1).peaks.values.single())
        assertEquals(setOf(NetworkStatus.SUCCESS, NetworkStatus.FORBIDDEN),
            after.getValue(0).requests.keys.map { it.status }.toSet())
        assertEquals(10L, after.getValue(1).requests.values.single().bodyBytesIn)
    }

    @Test
    fun `only distinct keys count toward the flush signal`() = runTest {
        val wake = async { buffer.awaitFlushSignal() }
        repeat(1000) { noRequest(0) }
        repeat(254) { noRequest(it + 1L) }
        runCurrent()
        assertFalse(wake.isCompleted)

        noRequest(255)
        runCurrent()
        assertTrue(wake.isCompleted)
        val rows = buffer.drain()
        assertEquals(256, rows.size)
        assertEquals(1000L, rows.getValue(0).noRequests.values.single())
    }

    @Test
    fun `draining concurrently does not double count or lose requests`() = runTest {
        val submissions = List(8) {
            async(Dispatchers.Default) {
                repeat(1000) { noRequest(0) }
            }
        }
        val partial = buffer.drain()
        submissions.awaitAll()
        val final = buffer.drain()
        val count = (partial.values + final.values).sumOf { it.noRequests.values.sum() }
        assertEquals(8000L, count)
    }

    @Test
    fun `reset discards late completion and abandons old peak windows`() = runTest {
        val old = send(1)
        buffer.reset()
        old.finish(NetworkStatus.SUCCESS, NetworkRequestCounts(requests = 1))
        send(1).finish(NetworkStatus.SUCCESS, NetworkRequestCounts(requests = 1))

        val hour = buffer.drain().getValue(0)
        assertEquals(1L, hour.requests.values.single().requests)
        assertEquals(NetworkPeaks(1, 1, 1), hour.peaks.values.single())
    }

    @Test
    fun `launcher icon restart process cannot record`() = runTest {
        val mainPackage = "fr.forumhfr.redface2.dev"
        val process = NetworkStatsProcess.forApp(72, "$mainPackage:launcherIconRestart", mainPackage)
        val secondary = NetworkStatsBuffer(process)
        assertFalse(secondary.isEnabled)
        assertNull(secondary.started(start(0)))
        assertTrue(secondary.drain().isEmpty())
        assertTrue(NetworkStatsProcess.forApp(72, mainPackage, mainPackage).isMain)
    }

    private fun send(second: Long) = checkNotNull(buffer.started(start(second)))
        .sent(NetworkTarget.HTML_FORUM2, NetworkPeakGroup.FORUM, NetworkTrigger.NONE)

    private fun noRequest(hour: Long) = checkNotNull(buffer.started(start(hour * 3600)))
        .noRequest(NetworkTarget.REST_CATALOGUE, NoRequestReason.DNS, NetworkTrigger.NONE)

    private fun start(second: Long) =
        NetworkStatsStart(second, second * 1_000_000_000, AppVisibility.UNKNOWN, NetworkRole.AUTH)
}
