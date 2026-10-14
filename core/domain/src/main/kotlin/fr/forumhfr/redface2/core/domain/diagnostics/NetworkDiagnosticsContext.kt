package fr.forumhfr.redface2.core.domain.diagnostics

/** No platform objects or identifying network data cross this boundary. */
fun interface NetworkDiagnosticsContextProvider {
    fun snapshot(): NetworkDiagnosticsContext
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
    private val transport = when {
        transports == null -> "unknown"
        transports.isEmpty() -> "none"
        else -> NetworkTransport.entries.filter { it in transports }.joinToString("+") { it.label }
    }

    fun line(): String = "transport=$transport metered=${metered.bit()} " +
        "validated=${validated.bit()} privateDns=${privateDns.bit()}"

    private fun Boolean?.bit(): String = when (this) { true -> "1"; false -> "0"; null -> "?" }
}
