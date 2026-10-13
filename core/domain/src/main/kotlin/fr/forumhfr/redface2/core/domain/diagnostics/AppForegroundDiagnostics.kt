package fr.forumhfr.redface2.core.domain.diagnostics

import javax.inject.Inject
import javax.inject.Singleton

/** MainActivity's existing lifecycle is enough; no process-lifecycle dependency is needed. */
@Singleton
class AppForegroundDiagnostics @Inject constructor(private val log: DiagnosticsLog) {
    private var backgroundNanos: Long? = null

    fun backgrounded(nowNanos: Long = System.nanoTime()) { backgroundNanos = nowNanos }

    fun foregrounded(nowNanos: Long = System.nanoTime()) {
        val start = backgroundNanos ?: return
        backgroundNanos = null
        log.record(DiagnosticsLog.Level.INFO, "AppForeground",
            "backgroundMs=${elapsedDiagnosticMs(start, nowNanos)}")
    }
}
