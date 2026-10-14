package fr.forumhfr.redface2.core.domain.diagnostics

import javax.inject.Inject
import javax.inject.Singleton

/** MainActivity's existing lifecycle is enough; no process-lifecycle dependency is needed. */
@Singleton
class AppForegroundDiagnostics @Inject constructor(
    private val log: DiagnosticsLog,
    private val clock: DiagnosticsClock = DiagnosticsClock(System::nanoTime),
) {
    private var backgroundNanos: Long? = null
    private var foregroundNanos: Long? = null

    @Synchronized
    fun backgrounded(nowNanos: Long = clock.elapsedRealtimeNanos()) { backgroundNanos = nowNanos }

    @Synchronized
    fun foregrounded(nowNanos: Long = clock.elapsedRealtimeNanos()) {
        val start = backgroundNanos
        if (foregroundNanos == null || start != null) foregroundNanos = nowNanos
        backgroundNanos = null
        if (start != null) {
            log.record(DiagnosticsLog.Level.INFO, "AppForeground",
                "backgroundMs=${elapsedDiagnosticMs(start, nowNanos)}")
        }
    }

    @Synchronized
    fun snapshot(): DiagnosticsTimingSnapshot {
        val now = clock.elapsedRealtimeNanos()
        return DiagnosticsTimingSnapshot(
            uptimeS = now.coerceAtLeast(0L) / 1_000_000_000L,
            foregroundS = foregroundNanos?.let { (now - it).coerceAtLeast(0L) / 1_000_000_000L },
        )
    }
}
