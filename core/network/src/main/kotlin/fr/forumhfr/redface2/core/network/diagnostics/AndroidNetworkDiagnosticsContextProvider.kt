package fr.forumhfr.redface2.core.network.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsDataSaver
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsSubscription
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsContext
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsContextProvider
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsEvent
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsRestrictions
import javax.inject.Inject
import javax.inject.Singleton

/** Synchronous local system reads only; never inspect addresses, DNS names, SSIDs or carriers. */
@Singleton
class AndroidNetworkDiagnosticsContextProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkDiagnosticsContextProvider {
    private val monitor = AndroidNetworkDiagnosticsMonitor(context)

    override fun observe(listener: (NetworkDiagnosticsEvent) -> Unit): DiagnosticsSubscription =
        monitor.observe(listener)

    override fun restrictions(): NetworkDiagnosticsRestrictions {
        val dataSaver = runCatching {
            when (context.getSystemService(ConnectivityManager::class.java)?.restrictBackgroundStatus) {
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> DiagnosticsDataSaver.DISABLED
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> DiagnosticsDataSaver.WHITELISTED
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> DiagnosticsDataSaver.ENABLED
                else -> DiagnosticsDataSaver.UNKNOWN
            }
        }.getOrDefault(DiagnosticsDataSaver.UNKNOWN)
        val batterySaver = runCatching {
            context.getSystemService(PowerManager::class.java)?.isPowerSaveMode
        }.getOrNull()
        return NetworkDiagnosticsRestrictions(dataSaver, batterySaver)
    }

    override fun snapshot(): NetworkDiagnosticsContext = runCatching {
        context.getSystemService(ConnectivityManager::class.java)?.let(::readContext) ?: NetworkDiagnosticsContext()
    }.getOrElse { NetworkDiagnosticsContext() }

    private fun readContext(manager: ConnectivityManager): NetworkDiagnosticsContext {
        // Pin the identity once: a network switch must not mix two networks' properties.
        val network = manager.activeNetwork ?: return NetworkDiagnosticsContext(emptySet())
        val capabilities = manager.getNetworkCapabilities(network)
        val properties = manager.getLinkProperties(network)
        return NetworkDiagnosticsContext(
            transports = capabilities?.diagnosticTransports(),
            metered = capabilities?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) },
            validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            privateDns = properties?.isPrivateDnsActive,
        )
    }
}
