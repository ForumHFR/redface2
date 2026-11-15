package fr.forumhfr.redface2.core.domain.diagnostics

/** No platform objects or identifying network data cross this boundary. */
fun interface NetworkDiagnosticsContextProvider {
    fun snapshot(): NetworkDiagnosticsContext

    fun restrictions(): NetworkDiagnosticsRestrictions = NetworkDiagnosticsRestrictions()

    fun observe(listener: (NetworkDiagnosticsEvent) -> Unit): DiagnosticsSubscription = DiagnosticsSubscription { }
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

    fun line(): String = "transport=$transport metered=${metered.diagnosticBit()} " +
        "validated=${validated.diagnosticBit()} privateDns=${privateDns.diagnosticBit()}"
}
