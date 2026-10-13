package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.FlagFetchTrace
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCallOutcome
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCallSummary
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Shared by all HFR client factories, while image calls bypass it entirely. */
@Singleton
class HfrNetworkDiagnostics @Inject constructor(private val log: DiagnosticsLog) {
    private val sequence = AtomicLong()
    private val inFlight = AtomicInteger()

    fun nextId(): Long = sequence.incrementAndGet()
    fun started(): Int = inFlight.incrementAndGet()

    fun completed(summary: HfrCallSummary, fetch: FlagFetchTrace?) {
        inFlight.decrementAndGet()
        fetch?.call(summary)
        if (summary.totalMs >= SLOW_CALL_MS || summary.outcome != HfrCallOutcome.OK) {
            log.record(DiagnosticsLog.Level.INFO, "HfrCall", summary.line())
        }
    }

    companion object { const val SLOW_CALL_MS = 1_000L }
}
