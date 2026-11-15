package fr.forumhfr.redface2.core.domain.diagnostics

/** One bounded environment timeline per refresh; subscriptions live exactly as long as the trace. */
internal class FlagRefreshEnvironment(
    foreground: AppForegroundDiagnostics,
    network: NetworkDiagnosticsContextProvider,
    private val clock: DiagnosticsClock,
    private val activity: DiagnosticsSubscription,
) {
    private val started = clock.elapsedRealtimeNanos()
    private var firstVisibility: AppVisibility? = null
    private var visibility = AppVisibility.UNKNOWN
    private val foregroundEvents = mutableListOf<String>()
    private val networkEvents = mutableListOf<String>()
    private var foregroundOmitted = 0
    private var networkOmitted = 0
    private var closed = false
    private val foregroundSubscription = foreground.observe(::visibilityChanged)
    private val networkSubscription = runCatching { network.observe(::networkChanged) }
        .getOrElse { DiagnosticsSubscription { } }

    @Synchronized
    fun beginLine(): String = "foregroundStart=${firstVisibility?.label ?: "unknown"}"

    @Synchronized
    private fun visibilityChanged(value: AppVisibility) {
        if (closed) return
        if (firstVisibility == null) {
            firstVisibility = value
        } else if (foregroundEvents.size < MAX_EVENTS) {
            foregroundEvents += "${relativeMs()}:${value.label}"
        } else {
            foregroundOmitted++
        }
        visibility = value
    }

    @Synchronized
    private fun networkChanged(event: NetworkDiagnosticsEvent) {
        if (closed) return
        if (networkEvents.size < MAX_EVENTS) {
            networkEvents += "${relativeMs()}:${event.line()}"
        } else {
            networkOmitted++
        }
    }

    /** Never hold this monitor while removing a listener from a platform/lifecycle monitor. */
    fun finish(): String {
        try {
            runCatching { networkSubscription.close() }
        } finally {
            try {
                foregroundSubscription.close()
            } finally {
                activity.close()
            }
        }
        return synchronized(this) {
            closed = true
            "${beginLine()} foregroundEnd=${visibility.label} " +
                "foregroundTransitions=[${foregroundEvents.joinToString(";")}] foregroundOmitted=$foregroundOmitted " +
                "networkEvents=[${networkEvents.joinToString(";")}] networkEventsOmitted=$networkOmitted"
        }
    }

    private fun relativeMs(): Long = elapsedDiagnosticMs(started, clock.elapsedRealtimeNanos())

    companion object { const val MAX_EVENTS = 16 }
}
