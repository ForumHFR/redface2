package fr.forumhfr.redface2.core.network.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshDiagnostics
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshTrigger
import fr.forumhfr.redface2.core.model.FlagType
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
