package fr.forumhfr.redface2.core.ui.post

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap

/** #960 (§6) — the two independent attempt axes of a media URL. */
internal enum class MediaAttemptKind { PROBE, PAINTER }

/**
 * #960 (Lot 4, contrat v1.5 §6, cadrage Sol r3) — the per-URL ATTEMPT LEDGER, single source of
 * truth for media-load failures and retry generations. Locks (r3, tested):
 *
 *  1. every mutation is scoped to explicit URLs — no process-wide sweep API exists;
 *  2. ONE attempt per (URL, generation, axis): [tryReserve] grants atomically, concurrent
 *     occurrences are denied and observe the settled state through snapshot reads instead;
 *  3. the state lives in a [SnapshotStateMap], so composables keyed on [generationOf]
 *     re-evaluate mechanically on retry/advancement — no side notification channel;
 *  4. a settled SUCCESS is terminal for its axis: never re-attempted, never TTL-advanced,
 *     never reopened by a retry (the §6 geometry/painter lock);
 *  5. settlements carry the generation captured at reservation and are DISCARDED when stale.
 *
 * C1 (TTL): the 60 s negative TTL never reopens the CURRENT generation — consulting an URL
 * whose failure has expired atomically opens a NEW generation (once), resetting only the
 * EXPIRED failed axes (a fresh failure on the other axis keeps blocking until its own expiry).
 *
 * Thread-safety: callers are main-confined in production (LaunchedEffect + onState), but every
 * transition is still serialized under a lock so the atomicity contract never depends on that.
 */
internal class MediaAttemptLedger(
    private val failureTtlMillis: Long = FAILURE_TTL_MILLIS,
) {

    private sealed interface AxisState {
        data object Untried : AxisState
        data class InFlight(val generation: Int) : AxisState
        data class Failed(val atMillis: Long) : AxisState
        data object Succeeded : AxisState
    }

    private data class UrlEntry(
        val generation: Int = 0,
        val probe: AxisState = AxisState.Untried,
        val painter: AxisState = AxisState.Untried,
    ) {
        fun axis(kind: MediaAttemptKind): AxisState = when (kind) {
            MediaAttemptKind.PROBE -> probe
            MediaAttemptKind.PAINTER -> painter
        }

        fun withAxis(kind: MediaAttemptKind, state: AxisState): UrlEntry = when (kind) {
            MediaAttemptKind.PROBE -> copy(probe = state)
            MediaAttemptKind.PAINTER -> copy(painter = state)
        }
    }

    private val lock = Any()
    private val entries: SnapshotStateMap<String, UrlEntry> = mutableStateMapOf()

    /** Snapshot-observable generation of [url] (0 until first mutation). */
    fun generationOf(url: String): Int = entries[url]?.generation ?: 0

    /** True while [kind] holds a failure younger than the TTL (drives the error slot). */
    fun isFailedFresh(url: String, kind: MediaAttemptKind, nowMillis: Long): Boolean {
        val failed = entries[url]?.axis(kind) as? AxisState.Failed ?: return false
        return nowMillis - failed.atMillis < failureTtlMillis
    }

    /**
     * Atomically grants THE single attempt of ([url], [generation], [kind]). Denied when the
     * generation is stale or the axis already carries any state (in-flight, failed, succeeded).
     */
    fun tryReserve(url: String, generation: Int, kind: MediaAttemptKind): Boolean =
        synchronized(lock) {
            val entry = entries[url] ?: UrlEntry()
            if (entry.generation != generation) return false
            if (entry.axis(kind) != AxisState.Untried) return false
            entries[url] = entry.withAxis(kind, AxisState.InFlight(generation))
            true
        }

    /** Settles a success — terminal for the axis. A stale-generation settlement is discarded. */
    fun settleSuccess(url: String, generation: Int, kind: MediaAttemptKind) {
        synchronized(lock) {
            val entry = entries[url] ?: return
            if (entry.generation != generation) return
            entries[url] = entry.withAxis(kind, AxisState.Succeeded)
        }
    }

    /** Settles a failure (starts its TTL). A stale-generation settlement is discarded. */
    fun settleFailure(url: String, generation: Int, kind: MediaAttemptKind, nowMillis: Long) {
        synchronized(lock) {
            val entry = entries[url] ?: return
            if (entry.generation != generation) return
            entries[url] = entry.withAxis(kind, AxisState.Failed(nowMillis))
        }
    }

    /**
     * C1 — returns the current generation of [url], atomically opening a NEW one (once) when at
     * least one failed axis has expired: only the EXPIRED failures reset to untried; a fresh
     * failure on the other axis keeps blocking until its own expiry (it rides the new
     * generation as-is, still denied by [tryReserve] and still fresh for [isFailedFresh]).
     * Succeeded axes are never touched — a G2 "probe KO, painter OK" URL is stable forever.
     */
    fun consultGeneration(url: String, nowMillis: Long): Int = synchronized(lock) {
        val entry = entries[url] ?: return 0
        val expired = { state: AxisState ->
            state is AxisState.Failed && nowMillis - state.atMillis >= failureTtlMillis
        }
        if (!expired(entry.probe) && !expired(entry.painter)) return entry.generation
        val advanced = entry.copy(
            generation = entry.generation + 1,
            probe = if (expired(entry.probe)) AxisState.Untried else entry.probe,
            painter = if (expired(entry.painter)) AxisState.Untried else entry.painter,
        )
        entries[url] = advanced
        advanced.generation
    }

    /**
     * Manual retry of ONE url (the error-slot tap, §6): atomically clears both axes' negatives
     * (and invalidates any in-flight attempt — its stale settlement will be discarded) and
     * bumps the generation. Succeeded axes stay terminal (the geometry lock).
     */
    fun retryUrl(url: String) {
        synchronized(lock) {
            val entry = entries[url] ?: UrlEntry()
            entries[url] = reopened(entry)
        }
    }

    /**
     * Screen refresh — bumps ONLY the provided urls that actually carry a failure (lock #1:
     * the caller passes exactly the URLs its gesture refreshes; a healthy or in-flight-only
     * URL is untouched, and nothing outside the scope is ever visited).
     */
    fun retryFailedUrls(urls: Set<String>) {
        synchronized(lock) {
            urls.forEach { url ->
                val entry = entries[url] ?: return@forEach
                val failed = entry.probe is AxisState.Failed || entry.painter is AxisState.Failed
                if (failed) entries[url] = reopened(entry)
            }
        }
    }

    private fun reopened(entry: UrlEntry): UrlEntry = entry.copy(
        generation = entry.generation + 1,
        probe = if (entry.probe == AxisState.Succeeded) entry.probe else AxisState.Untried,
        painter = if (entry.painter == AxisState.Succeeded) entry.painter else AxisState.Untried,
    )

    companion object {
        /** §6 — negative TTL, aligned with the measurement cache's DEFAULT_FAILURE_TTL_MILLIS. */
        const val FAILURE_TTL_MILLIS = 60_000L
    }
}
