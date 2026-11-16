package fr.forumhfr.redface2.core.network.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsSubscription
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsEvent
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsEventKind
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkTransport

/** One default-network callback shared by concurrent refreshes, removed with the last subscriber. */
internal class AndroidNetworkDiagnosticsMonitor(private val context: Context) {
    private val listeners = linkedMapOf<Any, (NetworkDiagnosticsEvent) -> Unit>()
    private var registration: Registration? = null

    @Synchronized
    fun observe(listener: (NetworkDiagnosticsEvent) -> Unit): DiagnosticsSubscription {
        val token = Any()
        listeners[token] = listener
        if (registration == null) {
            val manager = runCatching { context.getSystemService(ConnectivityManager::class.java) }.getOrNull()
            if (manager != null) {
                val candidate = Registration(manager)
                registration = candidate
                runCatching { manager.registerDefaultNetworkCallback(candidate) }.onFailure {
                    registration = null
                    runCatching { manager.unregisterNetworkCallback(candidate) }
                }
            }
        }
        return DiagnosticsSubscription { unsubscribe(token) }
    }

    @Synchronized
    private fun unsubscribe(token: Any) {
        listeners.remove(token)
        if (listeners.isEmpty()) {
            val previous = registration
            registration = null
            if (previous != null) runCatching { previous.manager.unregisterNetworkCallback(previous) }
        }
    }

    private inner class Registration(val manager: ConnectivityManager) : ConnectivityManager.NetworkCallback() {
        private var current: Network? = null
        private var transports: Set<NetworkTransport>? = null
        private var validated: Boolean? = null

        override fun onAvailable(network: Network) = synchronized(this@AndroidNetworkDiagnosticsMonitor) {
            if (registration !== this) return@synchronized
            current = network
            transports = null
            validated = null
            emit(NetworkDiagnosticsEventKind.AVAILABLE)
        }

        override fun onLost(network: Network) = synchronized(this@AndroidNetworkDiagnosticsMonitor) {
            if (registration !== this || current != network) return@synchronized
            emit(NetworkDiagnosticsEventKind.LOST)
            current = null
            transports = null
            validated = null
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            synchronized(this@AndroidNetworkDiagnosticsMonitor) {
                if (registration !== this || current != network) return@synchronized
                // Use the callback's capabilities, never a racy read of activeNetwork here.
                val nextTransports = capabilities.diagnosticTransports()
                val nextValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val transportChanged = nextTransports != transports
                val validationChanged = nextValidated != validated
                transports = nextTransports
                validated = nextValidated
                if (validationChanged) emit(NetworkDiagnosticsEventKind.VALIDATED)
                if (transportChanged) emit(NetworkDiagnosticsEventKind.TRANSPORT)
            }

        private fun emit(kind: NetworkDiagnosticsEventKind) {
            val event = NetworkDiagnosticsEvent(kind, transports, validated)
            listeners.values.forEach { it(event) }
        }
    }
}

internal fun NetworkCapabilities.diagnosticTransports(): Set<NetworkTransport>? = buildSet {
    if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(NetworkTransport.VPN)
    if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(NetworkTransport.WIFI)
    if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(NetworkTransport.CELLULAR)
    if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(NetworkTransport.ETHERNET)
}.takeIf { it.isNotEmpty() }
