package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.FlagFetchTrace
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCallDetailPolicy
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCallSummary
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCatalogueTrace
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Shared by all HFR client factories, while image calls bypass it entirely. */
@Singleton
class HfrNetworkDiagnostics internal constructor(
    private val log: DiagnosticsLog,
    private val nanoTime: () -> Long,
) {
    @Inject constructor(log: DiagnosticsLog) : this(log, System::nanoTime)
    private val sequence = AtomicLong()
    private val inFlight = AtomicInteger()
    private var detailWindowStarted = nanoTime()
    private var windowDetails = 0

    fun nextId(): Long = sequence.incrementAndGet()
    fun started(): Int = inFlight.incrementAndGet()

    fun completed(summary: HfrCallSummary, fetch: FlagFetchTrace?, catalogue: HfrCatalogueTrace? = null) {
        inFlight.decrementAndGet()
        val fetchWillPublish = fetch?.call(summary) == true
        val catalogueWillPublish = catalogue?.call(summary) == true
        // Correlated details are emitted with a shared quota immediately before the final summary.
        if (!fetchWillPublish && !catalogueWillPublish && HfrCallDetailPolicy.includes(summary)) {
            // Keep the fetch provenance in its snapshot, but do not label immediate details as
            // belonging to a closed (or absent) refresh and exceed that refresh's twelve lines.
            recordUncorrelated(summary.copy(refreshId = null))
        }
    }

    @Synchronized
    private fun recordUncorrelated(summary: HfrCallSummary) {
        val now = nanoTime()
        if (now - detailWindowStarted >= DETAIL_WINDOW_NANOS) {
            detailWindowStarted = now
            windowDetails = 0
        }
        if (windowDetails < MAX_WINDOW_DETAILS) {
            windowDetails++
            log.record(DiagnosticsLog.Level.INFO, "HfrCall", summary.line())
        }
    }

    companion object {
        const val SLOW_CALL_MS = HfrCallDetailPolicy.SLOW_CALL_MS
        const val MAX_WINDOW_DETAILS = 24
        private const val DETAIL_WINDOW_NANOS = 60_000_000_000L
    }
}
