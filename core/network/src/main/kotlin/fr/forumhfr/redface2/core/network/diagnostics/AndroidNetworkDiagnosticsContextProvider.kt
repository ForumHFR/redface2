package fr.forumhfr.redface2.core.network.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsContext
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsContextProvider
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkTransport
import javax.inject.Inject

/** Synchronous local system reads only; never inspect addresses, DNS names, SSIDs or carriers. */
class AndroidNetworkDiagnosticsContextProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkDiagnosticsContextProvider {
    override fun snapshot(): NetworkDiagnosticsContext = runCatching {
        context.getSystemService(ConnectivityManager::class.java)?.let(::readContext) ?: NetworkDiagnosticsContext()
    }.getOrElse { NetworkDiagnosticsContext() }

    private fun readContext(manager: ConnectivityManager): NetworkDiagnosticsContext {
        // Pin the identity once: a network switch must not mix two networks' properties.
        val network = manager.activeNetwork ?: return NetworkDiagnosticsContext(emptySet())
        val capabilities = manager.getNetworkCapabilities(network)
        val properties = manager.getLinkProperties(network)
        return NetworkDiagnosticsContext(
            transports = capabilities?.transports(),
            metered = capabilities?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) },
            validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            privateDns = properties?.isPrivateDnsActive,
        )
    }

    private fun NetworkCapabilities.transports(): Set<NetworkTransport>? = buildSet {
        if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(NetworkTransport.VPN)
        if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(NetworkTransport.WIFI)
        if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(NetworkTransport.CELLULAR)
        if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(NetworkTransport.ETHERNET)
    }.takeIf { it.isNotEmpty() }
}
