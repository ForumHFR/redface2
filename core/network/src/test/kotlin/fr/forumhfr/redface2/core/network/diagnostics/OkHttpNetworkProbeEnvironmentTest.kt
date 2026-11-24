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
    fun `wave of 20 in the background is disturbed even when every DNS lookup fails`() = runBlocking {
        foreground.foregrounded()
        val waves = mutableListOf<NetworkProbeWave>()
        runner().run(onWaveStarted = {}, onWaveFinished = { wave ->
            waves += wave
            if (wave.size == 10) foreground.backgrounded()
        })

        assertEquals(20, waves.last().results.count { it.outcome == NetworkProbeOutcome.DNS_ERROR })
        assertEquals(listOf(false, false, false, true), waves.map { it.disturbed })
        assertEquals(setOf(NetworkProbeDisturbance.BACKGROUND), waves.last().disturbedBy)
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
    fun `initial availability leaves its wave clean and loss and return mark their waves`() = runBlocking {
        val android = AndroidDefaultNetwork()
        val waves = mutableListOf<NetworkProbeWave>()
        runner(network = android.provider).run(onWaveStarted = { size ->
            when (size) {
                1 -> android.callback.captured.onAvailable(android.initial)
                5 -> android.callback.captured.onLost(android.initial)
                10 -> android.callback.captured.onAvailable(android.initial)
            }
        }, onWaveFinished = waves::add)

        val changed = setOf(NetworkProbeDisturbance.NETWORK_CHANGE)
        assertEquals(listOf(emptySet(), changed, changed, emptySet()), waves.map { it.disturbedBy })
    }

    @Test
    fun `a different Android default at the drain marks the wave without a callback`() = runBlocking {
        val android = AndroidDefaultNetwork()
        val waves = mutableListOf<NetworkProbeWave>()
        runner(network = android.provider).run(onWaveStarted = { size ->
            if (size == 10) android.active = android.replacement
        }, onWaveFinished = waves::add)

        val changed = setOf(NetworkProbeDisturbance.NETWORK_CHANGE)
        assertEquals(listOf(emptySet(), emptySet(), changed, emptySet()), waves.map { it.disturbedBy })
    }

    @Test
    fun `delayed switch and return between waves may mark the next wave`() = runBlocking {
        val android = AndroidDefaultNetwork()
        val waves = mutableListOf<NetworkProbeWave>()
        runner(network = android.provider).run(onWaveStarted = { size ->
            if (size == 20) {
                android.callback.captured.onAvailable(android.initial)
                android.callback.captured.onAvailable(android.replacement)
                android.callback.captured.onAvailable(android.initial)
            }
        }, onWaveFinished = { wave ->
            waves += wave
            if (wave.size == 10) {
                android.active = android.replacement
                android.active = android.initial
            }
        })

        // The default stayed the same throughout wave 20. The delayed callbacks still mark it.
        val changed = setOf(NetworkProbeDisturbance.NETWORK_CHANGE)
        assertEquals(listOf(emptySet(), emptySet(), emptySet(), changed), waves.map { it.disturbedBy })
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

    private class AndroidDefaultNetwork {
        private val context = mockk<Context>()
        private val manager = mockk<ConnectivityManager>()
        val initial = mockk<Network>()
        val replacement = mockk<Network>()
        var active = initial
        val callback = slot<ConnectivityManager.NetworkCallback>()
        val provider = AndroidNetworkDiagnosticsContextProvider(context)

        init {
            every { context.getSystemService(ConnectivityManager::class.java) } returns manager
            every { manager.activeNetwork } answers { active }
            every { manager.registerDefaultNetworkCallback(capture(callback)) } just Runs
            every { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just Runs
        }
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
