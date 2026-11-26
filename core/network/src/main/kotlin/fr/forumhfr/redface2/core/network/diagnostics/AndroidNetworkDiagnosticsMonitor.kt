package fr.forumhfr.redface2.core.network.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsSubscription
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsEvent
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsEventKind
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkKind
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkTransport

/** Shares one default-network callback between diagnostics and persistent statistics. */
internal class AndroidNetworkDiagnosticsMonitor(private val context: Context) {
    private val listeners = linkedMapOf<Any, (NetworkDiagnosticsEvent) -> Unit>()
    private var registration: Registration? = null
    private var trackingNetworkKind = false
    @Volatile var networkKind = NetworkKind.UNKNOWN
        private set

    @Synchronized
    fun trackNetworkKind() {
        trackingNetworkKind = true
        registerIfNeeded()
    }

    @Synchronized
    fun observe(listener: (NetworkDiagnosticsEvent) -> Unit): DiagnosticsSubscription {
        val token = Any()
        listeners[token] = listener
        registerIfNeeded()
        return DiagnosticsSubscription { unsubscribe(token) }
    }

    private fun registerIfNeeded() {
        if (registration == null) {
            val manager = runCatching { context.getSystemService(ConnectivityManager::class.java) }.getOrNull()
            if (manager != null) {
                val candidate = Registration(manager)
                registration = candidate
                if (trackingNetworkKind) networkKind = candidate.initialKind()
                runCatching { manager.registerDefaultNetworkCallback(candidate) }.onFailure {
                    registration = null
                    networkKind = NetworkKind.UNKNOWN
                    runCatching { manager.unregisterNetworkCallback(candidate) }
                }
            }
        }
    }

    @Synchronized
    private fun unsubscribe(token: Any) {
        listeners.remove(token)
        if (listeners.isEmpty() && !trackingNetworkKind) {
            val previous = registration
            registration = null
            networkKind = NetworkKind.UNKNOWN
            if (previous != null) runCatching { previous.manager.unregisterNetworkCallback(previous) }
        }
    }

    // A failed read is unknown. A successful read can confirm that there is no default.
    private data class DefaultNetwork(val network: Network?)

    private inner class Registration(val manager: ConnectivityManager) : ConnectivityManager.NetworkCallback() {
        private var callbackDefault = runCatching { DefaultNetwork(manager.activeNetwork) }.getOrNull()
        private var awaitingInitialCallback = true
        private var transports: Set<NetworkTransport>? = null
        private var validated: Boolean? = null

        fun initialKind(): NetworkKind = runCatching {
            callbackDefault?.network?.let(manager::getNetworkCapabilities)?.statsNetworkKind()
        }.getOrNull() ?: NetworkKind.UNKNOWN

        override fun onAvailable(network: Network) = synchronized(this@AndroidNetworkDiagnosticsMonitor) {
            if (registration !== this) return@synchronized
            val changed = !awaitingInitialCallback || callbackDefault?.let { it.network != network } == true
            awaitingInitialCallback = false
            if (changed) networkKind = NetworkKind.UNKNOWN
            callbackDefault = DefaultNetwork(network)
            transports = null
            validated = null
            emit(NetworkDiagnosticsEventKind.AVAILABLE, changed)
        }

        override fun onLost(network: Network) = synchronized(this@AndroidNetworkDiagnosticsMonitor) {
            if (registration !== this) return@synchronized
            awaitingInitialCallback = false
            if (callbackDefault?.let { it.network != network } == true) {
                emit(NetworkDiagnosticsEvent(NetworkDiagnosticsEventKind.LOST))
            } else {
                networkKind = NetworkKind.UNKNOWN
                emit(NetworkDiagnosticsEventKind.LOST, defaultNetworkChanged = true)
                callbackDefault = DefaultNetwork(null)
                transports = null
                validated = null
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            synchronized(this@AndroidNetworkDiagnosticsMonitor) {
                if (registration !== this || callbackDefault?.network != network) return@synchronized
                // Use the callback's capabilities, never a racy read of activeNetwork here.
                networkKind = capabilities.statsNetworkKind()
                val nextTransports = capabilities.diagnosticTransports()
                val nextValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val transportChanged = nextTransports != transports
                val validationChanged = nextValidated != validated
                transports = nextTransports
                validated = nextValidated
                if (validationChanged) emit(NetworkDiagnosticsEventKind.VALIDATED)
                if (transportChanged) emit(NetworkDiagnosticsEventKind.TRANSPORT)
            }

        private fun emit(kind: NetworkDiagnosticsEventKind, defaultNetworkChanged: Boolean = false) {
            emit(NetworkDiagnosticsEvent(kind, transports, validated, defaultNetworkChanged))
        }

        private fun emit(event: NetworkDiagnosticsEvent) {
            listeners.values.forEach { it(event) }
        }
    }
}

private fun NetworkCapabilities.statsNetworkKind(): NetworkKind {
    val wifi = hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    val cellular = hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    return when {
        hasTransport(NetworkCapabilities.TRANSPORT_VPN) || (wifi && cellular) -> NetworkKind.OTHER
        wifi -> NetworkKind.WIFI
        cellular -> NetworkKind.CELLULAR
        else -> NetworkKind.OTHER
    }
}

internal fun NetworkCapabilities.diagnosticTransports(): Set<NetworkTransport>? = buildSet {
    if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(NetworkTransport.VPN)
    if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(NetworkTransport.WIFI)
    if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(NetworkTransport.CELLULAR)
    if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(NetworkTransport.ETHERNET)
}.takeIf { it.isNotEmpty() }
