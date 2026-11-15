package fr.forumhfr.redface2.core.domain.diagnostics

fun interface DiagnosticsSubscription {
    fun close()
}

enum class NetworkDiagnosticsEventKind(val label: String) {
    AVAILABLE("available"), LOST("lost"), VALIDATED("validated"), TRANSPORT("transport"),
}

/** Closed capability values only. Platform network identities never cross this boundary. */
class NetworkDiagnosticsEvent(
    private val kind: NetworkDiagnosticsEventKind,
    transports: Set<NetworkTransport>? = null,
    private val validated: Boolean? = null,
) {
    private val transport = diagnosticTransportLabel(transports)

    fun line(): String = "${kind.label} transport=$transport validated=${validated.diagnosticBit()}"
}

enum class DiagnosticsDataSaver(val label: String) {
    DISABLED("disabled"), WHITELISTED("whitelisted"), ENABLED("enabled"), UNKNOWN("unknown"),
}

data class NetworkDiagnosticsRestrictions(
    val dataSaver: DiagnosticsDataSaver = DiagnosticsDataSaver.UNKNOWN,
    val batterySaver: Boolean? = null,
) {
    fun line(): String = "dataSaver=${dataSaver.label} batterySaver=${batterySaver.diagnosticBit()}"
}

internal fun diagnosticTransportLabel(transports: Set<NetworkTransport>?): String = when {
    transports == null -> "unknown"
    transports.isEmpty() -> "none"
    else -> NetworkTransport.entries.filter { it in transports }.joinToString("+") { it.label }
}

internal fun Boolean?.diagnosticBit(): String = when (this) { true -> "1"; false -> "0"; null -> "?" }
