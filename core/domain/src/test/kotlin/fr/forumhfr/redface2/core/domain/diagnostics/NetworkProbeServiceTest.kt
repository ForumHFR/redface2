package fr.forumhfr.redface2.core.domain.diagnostics

import fr.forumhfr.redface2.core.domain.error.HfrRestAvailability
import fr.forumhfr.redface2.core.domain.error.HfrRestBlockedException
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkProbeServiceTest {
    private val log = DiagnosticsLog()
    private val activity = HfrRefreshActivity()
    private val store = MemoryReservations()

    @Test
    fun `REST pause refuses Diagnostics before it reserves a probe`() = runTest {
        val blocked = object : HfrRestAvailability {
            override val blockedUntil = flowOf(Instant.parse("2026-11-27T14:00:00Z"))
            override val probeBlocked = flowOf(true)
            override suspend fun checkProbeAllowed() { throw HfrRestBlockedException() }
        }
        val service = NetworkProbeService(
            runner { _, _ -> error("Probe must not start") }, store, activity, log, blocked,
        )
        service.run()
        assertEquals(NetworkProbeStatus.BLOCKED, service.state.value.status)
        assertEquals(0L, service.remainingSeconds())
        assertTrue(service.state.value.waves.isEmpty())
    }

    @Test
    fun `double launch is ignored and cancellation retains reservation and summary`() = runTest {
        var starts = 0
        val runner = runner { _, _ -> starts++; awaitCancellation() }
        val service = service(runner)
        val job = launch { service.run() }
        runCurrent()
        service.run()
        assertEquals(1, starts)
        assertTrue(service.state.value.running)
        job.cancelAndJoin()
        assertEquals(NetworkProbeStatus.CANCELLED, service.state.value.status)
        assertEquals(60L, service.remainingSeconds())
        assertTrue(log.entries.value.last().message.contains("summary outcome=cancelled"))
        service.run()
        assertEquals(NetworkProbeStatus.COOLDOWN, service.state.value.status)
        assertEquals(1, starts)
    }

    @Test
    fun `new service after process recreation observes persisted cooldown`() = runTest {
        var starts = 0
        val runner = runner { _, _ -> starts++ }
        service(runner).run()
        service(runner).run()
        assertEquals(1, starts)
        store.nowMillis += 60_000
        service(runner).run()
        assertEquals(2, starts)
        assertEquals(1L, NetworkProbeReservationStore.remainingSeconds(100_000, 159_999))
        assertEquals(60L, NetworkProbeReservationStore.remainingSeconds(100_000, 99_000))
    }

    @Test
    fun `refresh rejects probe without reserving and probe delays new refresh until drained`() = runTest {
        var starts = 0
        val runner = runner { _, _ -> starts++; awaitCancellation() }
        val service = service(runner)
        val refresh = activity.begin()
        service.run()
        assertEquals(NetworkProbeStatus.REFRESH_ACTIVE, service.state.value.status)
        assertEquals(0L, service.remainingSeconds())
        refresh.close()
        val job = launch { service.run() }
        runCurrent()
        val refreshed = CompletableDeferred<Unit>()
        val waiting = launch { activity.withRefresh { refreshed.complete(Unit) } }
        runCurrent()
        assertFalse(refreshed.isCompleted)
        assertEquals(1, starts)
        job.cancelAndJoin()
        waiting.join()
        assertTrue(refreshed.isCompleted)
        assertTrue(activity.idleGeneration() != null)
    }

    @Test
    fun `failed persistent reservation sends no requests and releases admission`() = runTest {
        var starts = 0
        val failing = object : NetworkProbeReservationStore {
            override suspend fun reserve(): Long = throw IOException("Secret path")
            override suspend fun remainingSeconds(): Long = 0
        }
        val service = NetworkProbeService(runner { _, _ -> starts++ }, failing, activity, log)
        service.run()
        assertEquals(0, starts)
        assertEquals(NetworkProbeStatus.STORAGE_ERROR, service.state.value.status)
        val lease = activity.tryBeginProbe()
        assertTrue(lease != null)
        lease?.close()
        assertTrue(log.entries.value.isEmpty())
    }

    /** Healthy connection test: 36 → 16 anonymous GETs. */
    @Test
    fun `wave export is bounded and connection median combines separate TCP and TLS samples`() = runTest {
        val service = service(runner { started, finished ->
            NetworkProbeRunner.WAVE_SIZES.forEach { size ->
                started(size)
                val results = List(size) { index ->
                    NetworkProbeRequestResult(tcpMs = 10L + index, tlsMs = 20, outcome = NetworkProbeOutcome.OK)
                }
                val disturbances = if (size >= 5) NetworkProbeDisturbance.entries.toSet() else emptySet()
                finished(NetworkProbeWave(size, results, disturbances))
            }
        })
        service.run()
        assertEquals(listOf(1, 5, 10), service.state.value.waves.map { it.size })
        assertEquals(16, service.state.value.waves.sumOf { it.successes })
        assertEquals(34L, service.state.value.waves.last().medianConnectionMs)
        assertEquals(4, log.entries.value.size)
        assertTrue(log.entries.value.all { it.tag == NetworkProbeService.TAG })
        assertTrue(log.entries.value.last().message.contains("requests=16 disturbed=true"))
        val waves = log.entries.value.filter { it.message.startsWith("wave") }.map { it.message }
        assertTrue(waves.first().contains("disturbed=false results=["))
        assertTrue(waves.first().endsWith("disturbedBy=[]"))
        assertTrue(waves.drop(1).all { it.contains("disturbed=true results=[") })
        assertTrue(waves.drop(1).all { it.endsWith("disturbedBy=[hfr_traffic,background,network_change]") })
    }

    private fun service(runner: NetworkProbeRunner) = NetworkProbeService(runner, store, activity, log)

    private fun runner(block: suspend ((Int) -> Unit, (NetworkProbeWave) -> Unit) -> Unit) =
        object : NetworkProbeRunner {
            override suspend fun run(onWaveStarted: (Int) -> Unit, onWaveFinished: (NetworkProbeWave) -> Unit) =
                block(onWaveStarted, onWaveFinished)
        }

    private class MemoryReservations : NetworkProbeReservationStore {
        var nowMillis = 100_000L
        private var last: Long? = null
        override suspend fun remainingSeconds(): Long =
            NetworkProbeReservationStore.remainingSeconds(last, nowMillis)
        override suspend fun reserve(): Long {
            val remaining = remainingSeconds()
            if (remaining == 0L) last = nowMillis
            return remaining
        }
    }
}
