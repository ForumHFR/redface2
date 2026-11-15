package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.FlagFetchTrace
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCallDetailPolicy
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCallOutcome
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCallSummary
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCatalogueTrace
import fr.forumhfr.redface2.core.domain.diagnostics.HfrRefreshActivity
import fr.forumhfr.redface2.core.domain.diagnostics.HfrProbeTraffic
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Shared by all HFR client factories, while image calls bypass it entirely. */
@Singleton
class HfrNetworkDiagnostics internal constructor(
    private val log: DiagnosticsLog,
    private val refreshActivity: HfrRefreshActivity = HfrRefreshActivity(),
    private val probeTraffic: HfrProbeTraffic = HfrProbeTraffic(),
    private val nanoTime: () -> Long,
) {
    @Inject constructor(
        log: DiagnosticsLog,
        refreshActivity: HfrRefreshActivity = HfrRefreshActivity(),
        probeTraffic: HfrProbeTraffic = HfrProbeTraffic(),
    ) : this(log, refreshActivity, probeTraffic, System::nanoTime)
    private val sequence = AtomicLong()
    private val inFlight = linkedMapOf<Long, CallIsolation>()
    private val isolatedSamples = ArrayDeque<Long>()
    private var detailWindowStarted = nanoTime()
    private var windowDetails = 0

    fun nextId(): Long = sequence.incrementAndGet()

    @Synchronized
    fun started(id: Long): Int {
        probeTraffic.started()
        val overlapped = inFlight.isNotEmpty()
        inFlight.values.forEach { it.overlapped = true }
        inFlight[id] = CallIsolation(refreshActivity.idleGeneration(), overlapped)
        return inFlight.size
    }

    fun completed(summary: HfrCallSummary, fetch: FlagFetchTrace?, catalogue: HfrCatalogueTrace? = null) {
        val isolated = finishIsolation(summary)
        val fetchWillPublish = fetch?.call(summary) == true
        val catalogueWillPublish = catalogue?.call(summary) == true
        val uncorrelated = !fetchWillPublish && !catalogueWillPublish
        // Correlated details are emitted with a shared quota immediately before the final summary.
        if (uncorrelated && HfrCallDetailPolicy.includes(summary)) {
            // Keep the fetch provenance in its snapshot, but do not label immediate details as
            // belonging to a closed (or absent) refresh and exceed that refresh's twelve lines.
            recordUncorrelated(summary.copy(refreshId = null))
        } else if (uncorrelated && summary.outcome == HfrCallOutcome.OK && isolated) {
            recordUncorrelated(summary.copy(refreshId = null), sample = true)
        }
    }

    @Synchronized
    private fun finishIsolation(summary: HfrCallSummary): Boolean {
        val call = inFlight.remove(summary.id)
        if (call != null) probeTraffic.finished()
        return call != null && !call.overlapped && call.idleGeneration != null &&
            call.idleGeneration == refreshActivity.idleGeneration()
    }

    @Synchronized
    private fun recordUncorrelated(summary: HfrCallSummary, sample: Boolean = false) {
        val now = nanoTime()
        while (isolatedSamples.isNotEmpty() && now - isolatedSamples.first() >= DETAIL_WINDOW_NANOS) {
            isolatedSamples.removeFirst()
        }
        if (now - detailWindowStarted >= DETAIL_WINDOW_NANOS) {
            detailWindowStarted = now
            windowDetails = 0
        }
        val sampleAllowed = !sample || isolatedSamples.size < MAX_ISOLATED_SAMPLES
        if (sampleAllowed && windowDetails < MAX_WINDOW_DETAILS && summary.claimDetail()) {
            windowDetails++
            if (sample) isolatedSamples.addLast(now)
            log.record(DiagnosticsLog.Level.INFO, "HfrCall", summary.line() + if (sample) " sample=isolated" else "")
        }
    }

    private class CallIsolation(val idleGeneration: Long?, var overlapped: Boolean)

    companion object {
        const val SLOW_CALL_MS = HfrCallDetailPolicy.SLOW_CALL_MS
        const val MAX_WINDOW_DETAILS = 24
        private const val MAX_ISOLATED_SAMPLES = 2
        private const val DETAIL_WINDOW_NANOS = 60_000_000_000L
    }
}
