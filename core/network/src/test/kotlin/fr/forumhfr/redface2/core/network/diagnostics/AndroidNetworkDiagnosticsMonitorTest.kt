package fr.forumhfr.redface2.core.network.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshDiagnostics
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshTrigger
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkKind
import fr.forumhfr.redface2.core.model.FlagType
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AndroidNetworkDiagnosticsMonitorTest {
    private val context = mockk<Context>()
    private val manager = mockk<ConnectivityManager>(relaxed = true)
    private val callback = slot<ConnectivityManager.NetworkCallback>()
    private val provider = AndroidNetworkDiagnosticsContextProvider(context)
    private val network = mockk<Network>()

    init {
        every { context.getSystemService(ConnectivityManager::class.java) } returns manager
        every { manager.activeNetwork } returns network
        every { manager.registerDefaultNetworkCallback(capture(callback)) } just Runs
        every { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just Runs
    }

    @Test
    fun `concurrent refreshes share one callback until the last trace finishes`() {
        val diagnostics = FlagRefreshDiagnostics(DiagnosticsLog(), provider)
        val first = diagnostics.begin(FlagRefreshTrigger.PULL, listOf(FlagType.CYAN))
        val second = diagnostics.begin(FlagRefreshTrigger.RESUME, listOf(FlagType.RED))
        verify(exactly = 1) { manager.registerDefaultNetworkCallback(any()) }
        first.finish()
        first.finish()
        verify(exactly = 0) { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
        second.finish()
        second.finish()
        verify(exactly = 1) { manager.unregisterNetworkCallback(callback.captured) }
    }

    @Test
    fun `statistics seed the cached transport and follow changes using the shared callback`() {
        val wifi = mockk<NetworkCapabilities>(relaxed = true)
        val mobile = mockk<NetworkCapabilities>(relaxed = true)
        val vpn = mockk<NetworkCapabilities>(relaxed = true)
        every { manager.getNetworkCapabilities(network) } returns wifi
        every { wifi.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        every { mobile.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns true
        every { vpn.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } returns true
        every { vpn.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        val replacement = mockk<Network>()

        provider.startNetworkKindTracking()
        assertEquals(NetworkKind.WIFI, provider.currentNetworkKind())
        provider.observe { }.close()
        callback.captured.onAvailable(replacement)
        assertEquals(NetworkKind.UNKNOWN, provider.currentNetworkKind())
        callback.captured.onCapabilitiesChanged(replacement, mobile)
        assertEquals(NetworkKind.CELLULAR, provider.currentNetworkKind())
        callback.captured.onCapabilitiesChanged(replacement, vpn)
        assertEquals(NetworkKind.OTHER, provider.currentNetworkKind())
        callback.captured.onLost(network)
        assertEquals(NetworkKind.OTHER, provider.currentNetworkKind())
        callback.captured.onLost(replacement)
        assertEquals(NetworkKind.UNKNOWN, provider.currentNetworkKind())
        verify(exactly = 1) { manager.activeNetwork }
        verify(exactly = 1) { manager.getNetworkCapabilities(any()) }
        verify(exactly = 1) { manager.registerDefaultNetworkCallback(any()) }
        verify(exactly = 0) { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }

    @Test
    fun `observation returns while statistics startup is waiting for connectivity`() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { manager.activeNetwork } answers {
            reading.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            network
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val startup = executor.submit { provider.startNetworkKindTracking() }
            check(reading.await(2, TimeUnit.SECONDS))
            val subscription = executor.submit { provider.observe { }.close() }
            subscription.get(2, TimeUnit.SECONDS)
            release.countDown()
            startup.get(2, TimeUnit.SECONDS)
            verify(exactly = 1) { manager.registerDefaultNetworkCallback(any()) }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `capability events are deduplicated and a late loss keeps the current capabilities`() {
        val events = mutableListOf<String>()
        val subscription = provider.observe { events += it.line() }
        val capabilities = mockk<NetworkCapabilities>(relaxed = true)
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true
        callback.captured.onAvailable(network)
        callback.captured.onCapabilitiesChanged(network, capabilities)
        callback.captured.onCapabilitiesChanged(network, capabilities)
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns false
        callback.captured.onCapabilitiesChanged(network, capabilities)
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns true
        callback.captured.onCapabilitiesChanged(network, capabilities)
        callback.captured.onLost(mockk())
        callback.captured.onLost(network)
        assertEquals(listOf(
            "available transport=unknown validated=?",
            "validated transport=wifi validated=1",
            "transport transport=wifi validated=1",
            "validated transport=wifi validated=0",
            "transport transport=cellular validated=0",
            "lost transport=unknown validated=?",
            "lost transport=cellular validated=0",
        ), events)
        verify(exactly = 1) { manager.activeNetwork }
        verify(exactly = 0) { manager.getNetworkCapabilities(any()) }
        subscription.close()
    }

    @Test
    fun `initial default is stable and replacement on the same transport is a change`() {
        val changes = mutableListOf<Boolean>()
        val subscription = provider.observe { changes += it.defaultNetworkChanged }
        val replacement = mockk<Network>()
        val wifi = mockk<NetworkCapabilities>(relaxed = true)
        every { wifi.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true

        callback.captured.onAvailable(network)
        callback.captured.onCapabilitiesChanged(network, wifi)
        callback.captured.onAvailable(replacement)
        callback.captured.onCapabilitiesChanged(replacement, wifi)
        callback.captured.onLost(network)
        callback.captured.onLost(replacement)

        assertEquals(listOf(false, false, false, true, false, false, true, true), changes)
        subscription.close()
    }

    @Test
    fun `loss of the seeded default is reported before its initial callback`() {
        val changes = mutableListOf<Boolean>()
        val subscription = provider.observe { changes += it.defaultNetworkChanged }
        callback.captured.onLost(network)
        assertEquals(listOf(true), changes)
        subscription.close()
    }

    @Test
    fun `first callback sets the baseline when the initial default cannot be read`() {
        every { manager.activeNetwork } throws SecurityException()
        val changes = mutableListOf<Boolean>()
        val subscription = provider.observe { changes += it.defaultNetworkChanged }
        callback.captured.onAvailable(network)
        callback.captured.onAvailable(mockk())

        assertEquals(listOf(false, true), changes)
        subscription.close()
    }

    @Test
    fun `first callback is a change after a confirmed absence of default network`() {
        every { manager.activeNetwork } returns null
        val changes = mutableListOf<Boolean>()
        val subscription = provider.observe { changes += it.defaultNetworkChanged }
        every { manager.activeNetwork } returns network
        callback.captured.onAvailable(network)

        assertEquals(listOf(true), changes)
        subscription.close()
    }

    @Test
    fun `late callbacks from an old registration cannot pollute a later refresh`() {
        val oldEvents = mutableListOf<String>()
        val first = provider.observe { oldEvents += it.line() }
        val oldCallback = callback.captured
        first.close()
        val newEvents = mutableListOf<String>()
        val second = provider.observe { newEvents += it.line() }
        oldCallback.onAvailable(network)
        oldCallback.onCapabilitiesChanged(network, mockk(relaxed = true))
        assertEquals(emptyList<String>(), oldEvents)
        assertEquals(emptyList<String>(), newEvents)
        callback.captured.onAvailable(network)
        assertEquals(listOf("available transport=unknown validated=?"), newEvents)
        second.close()
        first.close()
        verify(exactly = 2) { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }

    @Test
    fun `registration and unregistration failures stay local and allow a later subscription`() {
        val events = mutableListOf<String>()
        every { manager.registerDefaultNetworkCallback(any()) } throws SecurityException("SecretSSID")
        val failed = provider.observe { events += it.line() }
        failed.close()
        every { manager.registerDefaultNetworkCallback(capture(callback)) } just Runs
        val working = provider.observe { events += it.line() }
        callback.captured.onAvailable(network)
        every { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } throws
            IllegalArgumentException("SecretNetworkId")
        working.close()
        working.close()
        assertEquals(listOf("available transport=unknown validated=?"), events)
        assertFalse(events.toString().contains("Secret"))
    }
}
