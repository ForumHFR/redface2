package fr.forumhfr.redface2.core.domain.diagnostics

import javax.inject.Inject
import javax.inject.Singleton

enum class AppVisibility(val label: String) {
    FOREGROUND("foreground"), BACKGROUND("background"), UNKNOWN("unknown"),
}

/** MainActivity's existing lifecycle is enough; no process-lifecycle dependency is needed. */
@Singleton
class AppForegroundDiagnostics @Inject constructor(
    private val log: DiagnosticsLog,
    private val clock: DiagnosticsClock = DiagnosticsClock(System::nanoTime),
) {
    private var backgroundNanos: Long? = null
    private var foregroundNanos: Long? = null
    @Volatile private var visibility = AppVisibility.UNKNOWN
    private val listeners = linkedSetOf<(AppVisibility) -> Unit>()

    @Synchronized
    fun backgrounded(nowNanos: Long = clock.elapsedRealtimeNanos()) {
        if (visibility != AppVisibility.BACKGROUND) backgroundNanos = nowNanos
        changeVisibility(AppVisibility.BACKGROUND)
    }

    @Synchronized
    fun foregrounded(nowNanos: Long = clock.elapsedRealtimeNanos()) {
        val start = backgroundNanos
        if (foregroundNanos == null || start != null) foregroundNanos = nowNanos
        backgroundNanos = null
        changeVisibility(AppVisibility.FOREGROUND)
        if (start != null) {
            log.record(DiagnosticsLog.Level.INFO, "AppForeground",
                "backgroundMs=${elapsedDiagnosticMs(start, nowNanos)}")
        }
    }

    fun currentVisibility(): AppVisibility = visibility

    /** The initial state and subscription are atomic with lifecycle transitions. */
    @Synchronized
    fun observe(listener: (AppVisibility) -> Unit): DiagnosticsSubscription {
        listeners += listener
        listener(visibility)
        return DiagnosticsSubscription { synchronized(this) { listeners -= listener } }
    }

    private fun changeVisibility(value: AppVisibility) {
        if (visibility != value) {
            visibility = value
            listeners.forEach { it(value) }
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
