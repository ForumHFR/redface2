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
class HfrNetworkDiagnostics @Inject constructor(private val log: DiagnosticsLog) {
    private val sequence = AtomicLong()
    private val inFlight = AtomicInteger()
    private var detailWindowStarted = System.nanoTime()
    private var windowDetails = 0

    fun nextId(): Long = sequence.incrementAndGet()
    fun started(): Int = inFlight.incrementAndGet()

    fun completed(summary: HfrCallSummary, fetch: FlagFetchTrace?, catalogue: HfrCatalogueTrace? = null) {
        inFlight.decrementAndGet()
        fetch?.call(summary)
        catalogue?.call(summary)
        // Correlated details are emitted with a shared quota immediately before the final summary.
        if (fetch == null && catalogue?.hasRefresh() != true && HfrCallDetailPolicy.includes(summary)) {
            recordUncorrelated(summary)
        }
    }

    @Synchronized
    private fun recordUncorrelated(summary: HfrCallSummary) {
        val now = System.nanoTime()
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
