package fr.forumhfr.redface2.feature.flags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.HfrRefreshActivity
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeReservationStore
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeRunner
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeService
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeStatus
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeWave
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    private val owners = mutableListOf<ViewModelStore>()
    private val log = DiagnosticsLog()
    private val reservations = MemoryReservations()
    private val activity = HfrRefreshActivity()
    private var calls = 0
    private var cancellations = 0
    private val runner = object : NetworkProbeRunner {
        override suspend fun run(onWaveStarted: (Int) -> Unit, onWaveFinished: (NetworkProbeWave) -> Unit) {
            calls++
            onWaveStarted(5)
            try {
                awaitCancellation()
            } finally {
                cancellations++
            }
        }
    }

    @After
    fun cleanup() {
        owners.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    @Test
    fun `double click starts once and close cancels while keeping persisted cooldown on recreation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val service = newService()
        val first = owned(service)
        runCurrent()
        assertEquals(0L, first.cooldownSeconds.value)
        first.startProbe()
        first.startProbe()
        runCurrent()
        assertEquals(1, calls)
        assertEquals(5, first.probeState.value.waveSize)
        first.cancelProbe()
        runCurrent()
        assertEquals(1, cancellations)
        assertEquals(NetworkProbeStatus.CANCELLED, first.probeState.value.status)
        owners.first().clear()
        val recreated = owned(newService())
        runCurrent()
        assertEquals(60L, recreated.cooldownSeconds.value)
        recreated.startProbe()
        runCurrent()
        assertEquals(1, calls)
        assertEquals(NetworkProbeStatus.COOLDOWN, recreated.probeState.value.status)
        owners.forEach { it.clear() }
        runCurrent()
    }

    @Test
    fun `ViewModel cleared during a wave cancels its transport and releases exclusive admission`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = owned(newService())
        vm.startProbe()
        runCurrent()
        assertTrue(vm.probeState.value.running)
        owners.single().clear()
        runCurrent()
        assertEquals(1, cancellations)
        val lease = activity.tryBeginProbe()
        assertTrue(lease != null)
        lease?.close()
    }

    private fun newService() = NetworkProbeService(runner, reservations, activity, log)

    private fun owned(service: NetworkProbeService): DiagnosticsViewModel {
        val owner = ViewModelStore().also { owners += it }
        return ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DiagnosticsViewModel(log, service) as T
        })[DiagnosticsViewModel::class.java]
    }

    private class MemoryReservations : NetworkProbeReservationStore {
        private val nowMillis = 100_000L
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
