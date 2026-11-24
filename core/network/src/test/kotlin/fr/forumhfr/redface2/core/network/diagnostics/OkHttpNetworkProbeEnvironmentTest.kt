package fr.forumhfr.redface2.core.network.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import fr.forumhfr.redface2.core.domain.diagnostics.AppForegroundDiagnostics
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsSubscription
import fr.forumhfr.redface2.core.domain.diagnostics.HfrProbeTraffic
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsContext
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsContextProvider
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsEvent
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsEventKind
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeDisturbance
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeOutcome
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeWave
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class OkHttpNetworkProbeEnvironmentTest {
    private val foreground = AppForegroundDiagnostics(DiagnosticsLog())
    private val network = RecordingNetwork()
    private val traffic = HfrProbeTraffic()

    @Test
    fun `wave starting in the background stays disturbed after returning to the foreground`() = runBlocking {
        foreground.foregrounded()
        val waves = mutableListOf<NetworkProbeWave>()
        runner().run(onWaveStarted = { size ->
            if (size == 20) foreground.foregrounded()
        }, onWaveFinished = { wave ->
            waves += wave
            if (wave.size == 10) foreground.backgrounded()
        })

        assertEquals(listOf(false, false, false, true), waves.map { it.disturbed })
        assertEquals(setOf(NetworkProbeDisturbance.BACKGROUND), waves.last().disturbedBy)
        assertEquals(20, waves.last().results.count { it.outcome == NetworkProbeOutcome.DNS_ERROR })
    }

    @Test
    fun `brief background during DNS affects one wave and ordinary HFR traffic remains cumulative`() = runBlocking {
        foreground.foregrounded()
        val firstLookup = AtomicBoolean(true)
        val dns = Dns {
            if (firstLookup.getAndSet(false)) {
                traffic.started()
                foreground.backgrounded()
                foreground.foregrounded()
                traffic.finished()
            }
            throw UnknownHostException()
        }
        val waves = mutableListOf<NetworkProbeWave>()
        runner(dns).run({}, waves::add)

        assertEquals(setOf(NetworkProbeDisturbance.HFR_TRAFFIC, NetworkProbeDisturbance.BACKGROUND),
            waves.first().disturbedBy)
        assertEquals(List(3) { setOf(NetworkProbeDisturbance.HFR_TRAFFIC) }, waves.drop(1).map { it.disturbedBy })
    }

    @Test
    fun `only waves spanning a default network change are disturbed`() = runBlocking {
        val waves = mutableListOf<NetworkProbeWave>()
        runner().run(onWaveStarted = { size ->
            when (size) {
                1 -> network.emit(NetworkDiagnosticsEvent(NetworkDiagnosticsEventKind.AVAILABLE))
                5 -> network.emit(NetworkDiagnosticsEvent(
                    NetworkDiagnosticsEventKind.AVAILABLE,
                    defaultNetworkChanged = true,
                ))
                10 -> network.emit(NetworkDiagnosticsEvent(NetworkDiagnosticsEventKind.VALIDATED))
                20 -> network.emit(NetworkDiagnosticsEvent(NetworkDiagnosticsEventKind.LOST))
            }
        }, onWaveFinished = { wave ->
            waves += wave
            if (wave.size == 5) network.emit(NetworkDiagnosticsEvent(NetworkDiagnosticsEventKind.LOST))
        })

        val changed = setOf(NetworkProbeDisturbance.NETWORK_CHANGE)
        assertEquals(listOf(emptySet(), changed, emptySet(), changed), waves.map { it.disturbedBy })
        assertEquals(1, network.observations)
        assertEquals(1, network.closes)
    }

    @Test
    fun `a change during wave 10 is counted there even when the callback arrives in wave 20`() = runBlocking {
        val changed = setOf(NetworkProbeDisturbance.NETWORK_CHANGE)
        assertEquals(listOf(emptySet(), emptySet(), changed, emptySet()), delayedDefaultChange())
    }

    @Test
    fun `a late callback does not mask another brief switch during wave 20`() = runBlocking {
        val changed = setOf(NetworkProbeDisturbance.NETWORK_CHANGE)
        assertEquals(listOf(emptySet(), emptySet(), changed, changed), delayedDefaultChange(switchAgain = true))
    }

    @Test
    fun `a change between waves does not disturb the next wave when its callback arrives`() = runBlocking {
        assertEquals(List(4) { emptySet<NetworkProbeDisturbance>() }, delayedDefaultChange(betweenWaves = true))
    }

    private suspend fun delayedDefaultChange(
        switchAgain: Boolean = false,
        betweenWaves: Boolean = false,
    ): List<Set<NetworkProbeDisturbance>> {
        val initial = mockk<Network>()
        val replacement = mockk<Network>()
        var active = initial
        val context = mockk<Context>()
        val manager = mockk<ConnectivityManager>()
        val callback = slot<ConnectivityManager.NetworkCallback>()
        every { context.getSystemService(ConnectivityManager::class.java) } returns manager
        every { manager.activeNetwork } answers { active }
        every { manager.registerDefaultNetworkCallback(capture(callback)) } just Runs
        every { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just Runs

        val waves = mutableListOf<NetworkProbeWave>()
        runner(network = AndroidNetworkDiagnosticsContextProvider(context)).run(onWaveStarted = { size ->
            when (size) {
                10 -> if (!betweenWaves) active = replacement
                20 -> {
                    callback.captured.onAvailable(initial)
                    callback.captured.onAvailable(replacement)
                    if (switchAgain) {
                        // A brief switch reported by the callbacks leaves the same default at the drain.
                        active = mockk()
                        callback.captured.onAvailable(active)
                        active = replacement
                        callback.captured.onAvailable(active)
                    }
                }
            }
        }, onWaveFinished = { wave ->
            waves += wave
            if (wave.size == 10 && betweenWaves) active = replacement
        })
        return waves.map { it.disturbedBy }
    }

    @Test(timeout = 10_000)
    fun `cancelling a wave records its disturbances and closes both subscriptions`() = runBlocking {
        val lookupStarted = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val dns = Dns {
            lookupStarted.complete(Unit)
            release.await()
            throw UnknownHostException()
        }
        val foreground = mockk<AppForegroundDiagnostics>()
        val subscription = mockk<DiagnosticsSubscription>(relaxed = true)
        every { foreground.observe(any()) } returns subscription
        val waves = mutableListOf<NetworkProbeWave>()
        val campaign = launch { runner(dns, foreground).run({}, waves::add) }
        try {
            withTimeout(5_000) { lookupStarted.await() }
            network.emit(NetworkDiagnosticsEvent(NetworkDiagnosticsEventKind.LOST))
            campaign.cancelAndJoin()

            assertEquals(NetworkProbeOutcome.CANCELLED, waves.single().results.single().outcome)
            assertEquals(setOf(NetworkProbeDisturbance.NETWORK_CHANGE), waves.single().disturbedBy)
            verify(exactly = 1) { foreground.observe(any()) }
            verify(exactly = 1) { subscription.close() }
            assertEquals(1, network.observations)
            assertEquals(1, network.closes)
        } finally {
            release.countDown()
            campaign.cancelAndJoin()
        }
    }

    private fun runner(
        dns: Dns = Dns { throw UnknownHostException() },
        foreground: AppForegroundDiagnostics = this.foreground,
        network: NetworkDiagnosticsContextProvider = this.network,
    ): OkHttpNetworkProbeRunner {
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(dns).build()
        return OkHttpNetworkProbeRunner(client, "https://probe.invalid/".toHttpUrl(), Dispatchers.IO, traffic,
            foreground, network)
    }

    private class RecordingNetwork : NetworkDiagnosticsContextProvider {
        private var listener: ((NetworkDiagnosticsEvent) -> Unit)? = null
        var observations = 0
            private set
        var closes = 0
            private set

        override fun snapshot() = NetworkDiagnosticsContext()

        override fun observe(listener: (NetworkDiagnosticsEvent) -> Unit): DiagnosticsSubscription {
            observations++
            this.listener = listener
            return DiagnosticsSubscription {
                closes++
                this.listener = null
            }
        }

        fun emit(event: NetworkDiagnosticsEvent) = checkNotNull(listener).invoke(event)
    }
}
