package fr.forumhfr.redface2.core.domain.diagnostics

/** Android binds elapsedRealtimeNanos, including deep sleep; JVM tests can supply a fake. */
fun interface DiagnosticsClock {
    fun elapsedRealtimeNanos(): Long
}

data class DiagnosticsTimingSnapshot(val uptimeS: Long, val foregroundS: Long?) {
    fun line(): String = "uptimeS=$uptimeS foregroundS=${foregroundS ?: "?"}"
}
