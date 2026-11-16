package fr.forumhfr.redface2.core.domain.diagnostics

enum class HfrConnectErrno(val label: String) {
    REFUSED("refused"), UNREACHABLE("unreachable"), NETWORK_DOWN("network-down"), TIMEOUT("timeout"), OTHER("other"),
}

internal fun Map<HfrConnectErrno, Int>.diagnosticLine(): String = HfrConnectErrno.entries
    .filter { (this[it] ?: 0) > 0 }
    .joinToString(";") { "${it.label}:${this[it]}" }
