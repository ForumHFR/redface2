package fr.forumhfr.redface2.core.domain.diagnostics

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
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

    @Test
    fun `wave export is bounded and connection median combines separate TCP and TLS samples`() = runTest {
        val service = service(runner { started, finished ->
            NetworkProbeRunner.WAVE_SIZES.forEach { size ->
                started(size)
                finished(NetworkProbeWave(size, List(size) { index ->
                    NetworkProbeRequestResult(tcpMs = 10L + index, tlsMs = 20, outcome = NetworkProbeOutcome.OK)
                }, disturbed = size >= 5))
            }
        })
        service.run()
        assertEquals(listOf(1, 5, 10, 20), service.state.value.waves.map { it.size })
        assertEquals(36, service.state.value.waves.sumOf { it.successes })
        assertEquals(39L, service.state.value.waves.last().medianConnectionMs)
        assertEquals(5, log.entries.value.size)
        assertTrue(log.entries.value.all { it.tag == NetworkProbeService.TAG })
        assertTrue(log.entries.value.last().message.contains("requests=36 disturbed=true"))
        assertTrue(log.entries.value.filter { it.message.startsWith("wave") }.all { it.message.contains("results=[") })
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
