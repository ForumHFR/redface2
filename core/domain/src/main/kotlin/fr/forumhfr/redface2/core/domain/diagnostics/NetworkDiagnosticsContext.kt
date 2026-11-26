package fr.forumhfr.redface2.core.domain.diagnostics

/** No platform objects or identifying network data cross this boundary. */
fun interface NetworkDiagnosticsContextProvider {
    fun snapshot(): NetworkDiagnosticsContext

    fun currentNetworkKind(): NetworkKind = snapshot().networkKind

    fun restrictions(): NetworkDiagnosticsRestrictions = NetworkDiagnosticsRestrictions()

    fun observe(listener: (NetworkDiagnosticsEvent) -> Unit): DiagnosticsSubscription = DiagnosticsSubscription { }

    /** Captures the default network and returns a check for a later change, if the initial read succeeds. */
    fun defaultNetworkChangeCheck(): (() -> Boolean)? = null
}

enum class NetworkKind { WIFI, CELLULAR, OTHER, UNKNOWN }

internal fun networkKind(transports: Set<NetworkTransport>?): NetworkKind = when {
    transports.isNullOrEmpty() -> NetworkKind.UNKNOWN
    NetworkTransport.VPN in transports -> NetworkKind.OTHER
    NetworkTransport.WIFI in transports && NetworkTransport.CELLULAR in transports -> NetworkKind.OTHER
    NetworkTransport.WIFI in transports -> NetworkKind.WIFI
    NetworkTransport.CELLULAR in transports -> NetworkKind.CELLULAR
    else -> NetworkKind.OTHER
}

/** Stable ordering keeps VPN visible even when capabilities also expose another transport. */
enum class NetworkTransport(val label: String) {
    VPN("vpn"), WIFI("wifi"), CELLULAR("cellular"), ETHERNET("ethernet"),
}

/**
 * Null transports means unknown; an empty set means no active network. The set is consumed
 * immediately into a closed label, so callers cannot mutate a captured context afterwards.
 */
class NetworkDiagnosticsContext(
    transports: Set<NetworkTransport>? = null,
    private val metered: Boolean? = null,
    private val validated: Boolean? = null,
    private val privateDns: Boolean? = null,
) {
    private val transport = diagnosticTransportLabel(transports)
    val networkKind = networkKind(transports)

    fun line(): String = "transport=$transport metered=${metered.diagnosticBit()} " +
        "validated=${validated.diagnosticBit()} privateDns=${privateDns.diagnosticBit()}"
}
