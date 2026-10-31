package fr.forumhfr.redface2.core.network.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidNetworkDiagnosticsContextProviderTest {
    private val context = mockk<Context>()
    private val manager = mockk<ConnectivityManager>()
    private val network = mockk<Network>()
    private val capabilities = mockk<NetworkCapabilities>(relaxed = true)
    private val properties = mockk<LinkProperties>()
    private val provider = AndroidNetworkDiagnosticsContextProvider(context)

    init {
        every { context.getSystemService(ConnectivityManager::class.java) } returns manager
        every { manager.activeNetwork } returns network
        every { manager.getNetworkCapabilities(network) } returns capabilities
        every { manager.getLinkProperties(network) } returns properties
        every { properties.isPrivateDnsActive } returns false
    }

    @Test
    fun `wifi validated and cellular metered use only closed capability values`() {
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } returns true
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true
        every { properties.isPrivateDnsActive } returns true
        assertEquals("transport=wifi metered=0 validated=1 privateDns=1", provider.snapshot().line())

        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns true
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } returns false
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns false
        every { properties.isPrivateDnsActive } returns false
        assertEquals("transport=cellular metered=1 validated=0 privateDns=0", provider.snapshot().line())
    }

    @Test
    fun `VPN is distinct and only combines transports explicitly exposed by active capabilities`() {
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } returns true
        assertEquals("transport=vpn metered=1 validated=0 privateDns=0", provider.snapshot().line())
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        assertEquals("transport=vpn+wifi metered=1 validated=0 privateDns=0", provider.snapshot().line())
    }

    @Test
    fun `one active Network is used for both reads and returned snapshot cannot change`() {
        val replacement = mockk<Network>()
        every { manager.activeNetwork } returnsMany listOf(network, replacement)
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } returns true
        val captured = provider.snapshot()
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } returns false
        every { properties.isPrivateDnsActive } returns true
        assertEquals("transport=ethernet metered=1 validated=0 privateDns=0", captured.line())
        verify(exactly = 1) { manager.activeNetwork }
        verify(exactly = 1) { manager.getNetworkCapabilities(network) }
        verify(exactly = 1) { manager.getLinkProperties(network) }
        verify(exactly = 0) { manager.getNetworkCapabilities(replacement) }
        verify(exactly = 0) { manager.getLinkProperties(replacement) }
    }

    @Test
    fun `no active network is none and missing information stays unknown`() {
        every { manager.activeNetwork } returns null
        assertEquals("transport=none metered=? validated=? privateDns=?", provider.snapshot().line())
        verify(exactly = 0) { manager.getNetworkCapabilities(any()) }
        verify(exactly = 0) { manager.getLinkProperties(any()) }

        every { manager.activeNetwork } returns network
        every { manager.getNetworkCapabilities(network) } returns null
        every { manager.getLinkProperties(network) } returns null
        assertEquals("transport=unknown metered=? validated=? privateDns=?", provider.snapshot().line())
    }

    @Test
    fun `missing link properties does not erase available capabilities`() {
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        every { manager.getLinkProperties(network) } returns null
        assertEquals("transport=wifi metered=1 validated=0 privateDns=?", provider.snapshot().line())
    }

    @Test
    fun `missing capabilities does not erase available private DNS state`() {
        every { manager.getNetworkCapabilities(network) } returns null
        every { properties.isPrivateDnsActive } returns true
        assertEquals("transport=unknown metered=? validated=? privateDns=1", provider.snapshot().line())
    }

    @Test
    fun `unrecognized active transport stays unknown instead of reporting no network`() {
        assertEquals("transport=unknown metered=1 validated=0 privateDns=0", provider.snapshot().line())
    }

    @Test
    fun `permission and platform exceptions return unknown without leaking their messages`() {
        every { manager.activeNetwork } throws SecurityException("SecretSsid SecretOperator")
        assertUnknown()
        every { manager.activeNetwork } returns network
        every { manager.getNetworkCapabilities(network) } throws IllegalStateException("192.0.2.1")
        assertUnknown()
        every { manager.getNetworkCapabilities(network) } returns capabilities
        every { manager.getLinkProperties(network) } throws IllegalStateException("SecretDns")
        assertUnknown()
        every { context.getSystemService(ConnectivityManager::class.java) } returns null
        assertUnknown()
    }

    private fun assertUnknown() {
        assertEquals("transport=unknown metered=? validated=? privateDns=?", provider.snapshot().line())
    }
}
