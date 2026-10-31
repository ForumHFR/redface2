package fr.forumhfr.redface2.core.domain.diagnostics

import fr.forumhfr.redface2.core.domain.flags.FlagRefreshCategory
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshIssue
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.FlagType
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class FlagRefreshTrigger(val label: String) {
    LAUNCH("launch"), RETURN_FROM_TOPIC("return-from-topic"), RESUME("resume"),
    TAB("tab"), PULL("pull"), INITIAL_OBSERVE("initial-observe"),
}

enum class FlagRefreshGap {
    CATALOGUE_FALLBACK, EMPTY_BUCKET_PAGE, PAGE_CAP, STICKY_GENERATION, STICKY_CANCELLED, STICKY_REQUEST,
    STICKY_DECODE, ROOM_WRITE, CACHE_GENERATION,
}

/** Injected process-local identity owner; no UUID, account or global mutable singleton. */
@Singleton
class FlagRefreshDiagnostics @Inject constructor(
    private val log: DiagnosticsLog,
    private val networkContext: NetworkDiagnosticsContextProvider =
        NetworkDiagnosticsContextProvider { NetworkDiagnosticsContext() },
    private val foreground: AppForegroundDiagnostics = AppForegroundDiagnostics(log),
) {
    private val sequence = AtomicLong()

    /** An unowned fetch gets a source identity from the same sequence, without beginning a refresh. */
    fun fetch(type: FlagType, refreshId: Long?): FlagFetchTrace =
        FlagFetchTrace(refreshId ?: sequence.incrementAndGet(), type)

    fun begin(
        trigger: FlagRefreshTrigger,
        types: List<FlagType>,
        startedNanos: Long = System.nanoTime(),
    ): FlagRefreshTrace {
        val timing = foreground.snapshot()
        val network = runCatching { networkContext.snapshot() }.getOrElse { NetworkDiagnosticsContext() }
        val contextLine = "${network.line()} ${timing.line()}"
        val trace = FlagRefreshTrace(sequence.incrementAndGet(), types, log, startedNanos, contextLine)
        log.record(DiagnosticsLog.Level.INFO, TAG,
            "refresh=${trace.id} trigger=${trigger.label} types=${types.joinToString { it.name }} begin $contextLine")
        return trace
    }

    fun ignored(
        trigger: FlagRefreshTrigger,
        types: List<FlagType>,
        throttled: Boolean,
        deferredTypes: List<FlagType> = emptyList(),
    ) {
        log.record(DiagnosticsLog.Level.INFO, TAG,
            "trigger=${trigger.label} types=${types.joinToString { it.name }} " +
                "ignored=${if (throttled) "throttle" else "reentrance"} " +
                "deferredTypes=${deferredTypes.joinToString { it.name }}")
    }

    companion object { const val TAG = "FlagsRefresh" }
}

/** A refresh owns its lifetime; detached fetches retain their own measurements for late joiners. */
class FlagRefreshTrace internal constructor(
    val id: Long,
    private val types: List<FlagType>,
    private val log: DiagnosticsLog,
    private val started: Long,
    private val contextLine: String,
) : AbstractCoroutineContextElement(Key) {
    private val triggerToBeginMs = elapsedDiagnosticMs(started)
    private val fetches = linkedSetOf<FlagFetchTrace>()
    private val publication = HfrCallPublication()
    private val resultFetches = mutableMapOf<FlagType, FlagFetchTrace?>()
    private var joined = 0
    private var publicationMs: Long? = null
    private var failureClass: String? = null

    /** New/joined counts are relative to this refresh: reuse includes a completed cached replacement. */
    @Synchronized
    fun attach(fetch: FlagFetchTrace, wasJoined: Boolean) {
        if (!publication.isActive()) return
        if (fetches.add(fetch)) {
            fetch.publishTo(publication)
            if (wasJoined) joined++
        }
    }

    /**
     * Select the returned result's source; abandoned attempts remain in the transport totals.
     * A replacement created by another caller is joined, even if it belongs to a newer generation.
     */
    @Synchronized
    fun result(type: FlagType, fetch: FlagFetchTrace?) {
        if (fetch != null && fetch !in fetches) attach(fetch, wasJoined = true)
        resultFetches[type] = fetch
    }

    @Synchronized
    fun published() {
        if (publicationMs == null) publicationMs = elapsedDiagnosticMs(started)
    }

    @Synchronized
    fun failure(error: Throwable) { failureClass = error.diagnosticClassName() }

    @Synchronized
    fun finish() {
        // Closing and taking the accepted calls is atomic with network completion. A late call
        // either belongs to this snapshot or uses the immediate quota, never a stale owner.
        val calls = publication.finish() ?: return
        val gaps = resultFetches.values.filterNotNull().flatMap { it.gapSnapshot() }.distinct()
        val omitted = publishDetails(calls)
        // Write the summary last; details of this refresh cannot evict its trigger or summary.
        log.record(DiagnosticsLog.Level.INFO, FlagRefreshDiagnostics.TAG,
            "refresh=$id types=${types.joinToString { it.name }} ${resultLine(gaps)} " +
                "${fetchLine()} ${callLine(calls, omitted)} ${transportLine(calls)} ${phaseLine(calls)} " +
                "triggerToBeginMs=$triggerToBeginMs firstFreshPublicationMs=${publicationMs ?: "-"} " +
                "totalMs=${elapsedDiagnosticMs(started)} slowest=[${slowestLine(calls)}] $contextLine")
    }

    private fun publishDetails(calls: List<HfrCallSummary>): Int {
        val details = calls.filter(HfrCallDetailPolicy::includes)
        var emitted = 0
        for (summary in details) {
            if (emitted == HfrCallDetailPolicy.MAX_REFRESH_DETAILS) break
            if (summary.claimDetail()) {
                log.record(DiagnosticsLog.Level.INFO, "HfrCall", summary.line())
                emitted++
            }
        }
        return details.count { !it.isDetailPublished() }
    }

    private fun resultLine(gaps: List<FlagRefreshGap>): String {
        // Outcome follows the result actually published, even if a superseded attempt failed.
        val outcome = when {
            failureClass != null -> "failure"
            gaps.isNotEmpty() -> "success-incomplete"
            else -> "success"
        }
        return "outcome=$outcome exception=${failureClass ?: "-"} " +
            "reasons=${gaps.joinToString { it.name.lowercase() }}"
    }

    private fun fetchLine(): String =
        "fetches=${fetches.size} newFetches=${fetches.size - joined} joined=$joined " +
            "pendingFetches=${fetches.count { !it.isFinished() }} " +
            "fetchRefreshIds=${fetches.map { it.refreshId }.distinct()} " +
            "fetchFailureClasses=${fetches.mapNotNull { it.failureClass }.distinct()} " +
            "categories=${fetches.sumOf { it.categoryCount }} bucketPages=${fetches.sumOf { it.bucketPages }} " +
            "stickyPages=${fetches.sumOf { it.stickyPages }} " +
            "catalogue=${if (fetches.any { it.joinedCatalogue() }) "joined" else "local"} " +
            "stickyGenerationRefused=${fetches.sumOf { it.stickyGenerationRefused }} " +
            "stickySweepsJoined=${fetches.sumOf { it.stickySweepsJoined }}"

    private fun callLine(calls: List<HfrCallSummary>, omitted: Int): String {
        val durations = calls.map { it.totalMs }.sorted()
        return "calls=${calls.size} callRefreshIds=${calls.mapNotNull { it.refreshId }.distinct()} " +
            "details-omitted=$omitted connectAttempts=${calls.sumOf { it.connectAttempts }} " +
            "connectFailures=${calls.sumOf { it.connectFailures }} " +
            "connectAbandoned=${calls.sumOf { it.connectAbandoned }} " +
            "callFailures=${calls.count { it.outcome != HfrCallOutcome.OK }} " +
            "reused=${calls.count { it.reusedConnection }} maxMs=${durations.lastOrNull() ?: 0} " +
            "medianMs=${durations.getOrNull(durations.size / 2) ?: 0}"
    }

    private fun phaseLine(calls: List<HfrCallSummary>): String =
        FlagRefreshPhase.entries.joinToString { phase ->
            val wall = fetches.sumOf { it.phaseMs(phase) }
            val transport = calls.filter { it.phase == phase }
            "${phase.name.lowercase()}Ms=$wall " +
                "${phase.name.lowercase()}CallMs=${transport.sumOf { it.totalMs }} " +
                "${phase.name.lowercase()}Failures=${fetches.sumOf { it.failures(phase) }}"
        }

    private fun slowestLine(calls: List<HfrCallSummary>): String =
        calls.sortedByDescending { it.totalMs }.take(3).joinToString(";") {
            "${it.endpoint.label()} totalMs=${it.totalMs}"
        }

    private fun transportLine(calls: List<HfrCallSummary>): String =
        "waitMs=${calls.sumOf { it.waitMs ?: 0L }} " +
            "waitMeasuredCalls=${calls.count { it.waitMs != null }} dnsMs=${calls.sumOf { it.dnsMs }} " +
            "connectMs=${calls.sumOf { it.connectMs }} tlsMs=${calls.sumOf { it.tlsMs }} " +
            "failedConnectMs=${calls.sumOf { it.failedConnectMs }} " +
            "maxFailedConnectMs=${calls.maxOfOrNull { it.maxFailedConnectMs } ?: 0} " +
            "failedTlsMs=${calls.sumOf { it.failedTlsMs }} " +
            "maxFailedTlsMs=${calls.maxOfOrNull { it.maxFailedTlsMs } ?: 0} " +
            "headersSentToReceivedMs=${calls.sumOf { it.headersSentToReceivedMs ?: 0L }} " +
            "bodyMs=${calls.sumOf { it.bodyMs }} totalCallMs=${calls.sumOf { it.totalMs }} " +
            "peakInFlight=${calls.maxOfOrNull { it.inFlightAtStart } ?: 0}"

    companion object Key : CoroutineContext.Key<FlagRefreshTrace>
}

/** Only safe transport summaries and numeric counters are retained, for the fetch's lifetime. */
class FlagFetchTrace(val refreshId: Long, val type: FlagType) : AbstractCoroutineContextElement(Key) {
    private val calls = mutableListOf<HfrCallSummary>()
    private val catalogues = linkedSetOf<HfrCatalogueTrace>()
    private val stickySweeps = linkedSetOf<FlagFetchTrace>()
    private val publications = linkedSetOf<HfrCallPublication>()
    private var catalogueJoined = false
    private val gaps = mutableSetOf<FlagRefreshGap>()
    private val missingStickyCategories = linkedSetOf<Int>()
    private val phases = mutableMapOf<FlagRefreshPhase, Long>()
    private val phaseFailures = mutableMapOf<FlagRefreshPhase, Int>()
    private var finished = false
    var categoryCount: Int = 0
    var bucketPages: Int = 0
        private set
    var stickyPages: Int = 0
        private set
    var stickyGenerationRefused: Int = 0
        private set
    var stickySweepsJoined: Int = 0
        private set
    var failureClass: String? = null
        private set

    @Synchronized fun call(summary: HfrCallSummary): Boolean {
        calls += summary
        publications.removeAll { !it.isActive() }
        var deferred = false
        publications.forEach { deferred = it.call(summary) || deferred }
        return deferred
    }
    @Synchronized internal fun publishTo(publication: HfrCallPublication) {
        publications.removeAll { !it.isActive() }
        if (!publication.isActive() || !publications.add(publication)) return
        calls.forEach { publication.call(it) }
        catalogues.forEach { it.publishTo(publication, refreshId, type) }
        stickySweeps.forEach { it.publishTo(publication) }
    }
    @Synchronized fun markFinished() { finished = true }
    @Synchronized fun isFinished(): Boolean = finished
    @Synchronized fun joinedStickySweep() { stickySweepsJoined++ }
    @Synchronized fun sticky(sweep: FlagFetchTrace) {
        if (stickySweeps.add(sweep)) publications.forEach { sweep.publishTo(it) }
    }
    @Synchronized fun absorbSticky(sweep: FlagFetchTrace) {
        sticky(sweep)
        calls += sweep.callSnapshot()
        stickyPages += sweep.stickyPages
        gaps += sweep.gapSnapshot()
        missingStickyCategories += sweep.missingStickyCategorySnapshot()
        phaseFailures[FlagRefreshPhase.STICKIES] = failures(FlagRefreshPhase.STICKIES) +
            sweep.failures(FlagRefreshPhase.STICKIES)
    }
    @Synchronized fun catalogue(trace: HfrCatalogueTrace, wasJoined: Boolean) {
        catalogues += trace
        catalogueJoined = catalogueJoined || wasJoined
        publications.forEach { trace.publishTo(it, refreshId, type) }
    }
    @Synchronized fun joinedCatalogue(): Boolean = catalogueJoined
    @Synchronized fun callSnapshot(): List<HfrCallSummary> =
        (calls + catalogues.flatMap { it.callSnapshot() }.map {
            it.copy(refreshId = refreshId, flagType = type, phase = FlagRefreshPhase.CATALOGUE)
        }).distinctBy { it.id }
    @Synchronized fun gapSnapshot(): Set<FlagRefreshGap> = gaps.toSet()
    @Synchronized fun gap(gap: FlagRefreshGap, stickyCategory: Int? = null) {
        gaps += gap
        if (stickyCategory != null) missingStickyCategories += stickyCategory
        if (gap == FlagRefreshGap.STICKY_GENERATION) stickyGenerationRefused++
    }
    @Synchronized private fun missingStickyCategorySnapshot(): Set<Int> = missingStickyCategories.toSet()

    /** Reuse the diagnostic gaps, but warn only about certain missing list data. An empty page
     * can reflect a divergent server count; Room failures and catalogue fallback are diagnostics
     * too, without proving that this published list is missing flags. */
    @Synchronized fun listIssue(categories: List<Category>): FlagRefreshIssue? {
        val missing = categories.filter { it.id in missingStickyCategories }
            .map { FlagRefreshCategory(it.id, it.name) }
        val truncated = FlagRefreshGap.PAGE_CAP in gaps
        return if (missing.isEmpty() && !truncated) null else FlagRefreshIssue(missing, truncated)
    }
    @Synchronized fun page(sticky: Boolean) { if (sticky) stickyPages++ else bucketPages++ }
    @Synchronized fun phase(phase: FlagRefreshPhase, nanos: Long) {
        phases[phase] = (phases[phase] ?: 0L) + elapsedDiagnosticMs(nanos)
    }
    @Synchronized fun phaseMs(phase: FlagRefreshPhase): Long = phases[phase] ?: 0L
    @Synchronized fun failures(phase: FlagRefreshPhase): Int = phaseFailures[phase] ?: 0
    @Synchronized fun fatal(error: Throwable) { failureClass = error.diagnosticClassName() }
    @Synchronized fun failed(phase: FlagRefreshPhase, error: Throwable, fatal: Boolean) {
        phaseFailures[phase] = (phaseFailures[phase] ?: 0) + 1
        if (fatal) failureClass = error.diagnosticClassName()
    }

    companion object Key : CoroutineContext.Key<FlagFetchTrace>
}

/** Created BEFORE dispatching each request-producing child, to measure its queue wait explicitly. */
class HfrRequestQueued(val nanos: Long = System.nanoTime()) : AbstractCoroutineContextElement(Key) {
    private val consumed = AtomicBoolean()
    fun consume(): Long = if (consumed.compareAndSet(false, true)) nanos else System.nanoTime()
    companion object Key : CoroutineContext.Key<HfrRequestQueued>
}
