package fr.forumhfr.redface2.core.ui.post

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf

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

    /** True when [kind] settled as a success — terminal, drives the "render from cache" branch. */
    fun hasSucceeded(url: String, kind: MediaAttemptKind): Boolean =
        entries[url]?.axis(kind) == AxisState.Succeeded

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

    /**
     * Settles a success — terminal for the axis. A stale-generation settlement is discarded; a
     * settlement for an unknown URL creates its entry (render-time writers never reserve — their
     * attempt is always current, e.g. the smiley error slot).
     */
    fun settleSuccess(url: String, generation: Int, kind: MediaAttemptKind) {
        synchronized(lock) {
            val entry = entries[url] ?: UrlEntry()
            if (entry.generation != generation) return
            entries[url] = entry.withAxis(kind, AxisState.Succeeded)
        }
    }

    /**
     * Settles a failure (starts its TTL). A stale-generation settlement is discarded; an unknown
     * URL creates its entry (render-time writers never reserve). MONOTONIC (Sol P1): a settled
     * success is terminal — a late concurrent failure (render-time writers can race) never
     * demotes it (lock #4).
     */
    fun settleFailure(url: String, generation: Int, kind: MediaAttemptKind, nowMillis: Long) {
        synchronized(lock) {
            val entry = entries[url] ?: UrlEntry()
            if (entry.generation != generation) return
            if (entry.axis(kind) == AxisState.Succeeded) return
            entries[url] = entry.withAxis(kind, AxisState.Failed(nowMillis))
        }
    }

    /**
     * C1 — returns the current generation of [url], atomically opening a NEW one (once) when at
     * least one failed axis has expired: the EXPIRED failures reset to untried, and so does any
     * IN-FLIGHT reservation (Sol P1 blocker 1: it belonged to the dying generation — its
     * settlement will be discarded by lock #5, so leaving it in place would freeze the axis
     * forever, nobody able to reserve again). A fresh failure on the other axis keeps blocking
     * until its own expiry (it rides the new generation as-is, still denied by [tryReserve] and
     * still fresh for [isFailedFresh]). Succeeded axes are never touched — a G2 "probe KO,
     * painter OK" URL is stable forever.
     */
    fun consultGeneration(url: String, nowMillis: Long): Int = synchronized(lock) {
        val entry = entries[url] ?: return 0
        val expired = { state: AxisState ->
            state is AxisState.Failed && nowMillis - state.atMillis >= failureTtlMillis
        }
        if (!expired(entry.probe) && !expired(entry.painter)) return entry.generation
        val released = { state: AxisState ->
            if (expired(state) || state is AxisState.InFlight) AxisState.Untried else state
        }
        val advanced = entry.copy(
            generation = entry.generation + 1,
            probe = released(entry.probe),
            painter = released(entry.painter),
        )
        entries[url] = advanced
        advanced.generation
    }

    /**
     * Rolls back a reservation whose attempt was CANCELLED before settling (effect disposed,
     * screen left) — a cancelled try is not a try, the axis returns to untried so a later
     * occurrence may attempt again. Only the exact in-flight state of the SAME generation is
     * rolled back: a fresh generation's state (reopened, re-attempted, settled) is never
     * clobbered by a late rollback (lock #5).
     */
    fun rollbackReservation(url: String, generation: Int, kind: MediaAttemptKind) {
        synchronized(lock) {
            val entry = entries[url] ?: return
            val heldReservation = entry.generation == generation &&
                entry.axis(kind) == AxisState.InFlight(generation)
            if (heldReservation) entries[url] = entry.withAxis(kind, AxisState.Untried)
        }
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

/**
 * Process-wide default ledger. Like [ProcessIntrinsicMediaSizeCache] it lives above the
 * composition (attempt memory survives recomposition, LazyColumn recycling and navigation) and
 * does NOT survive process death — acceptable, a fresh process retries everything once anyway.
 */
internal object ProcessMediaAttemptLedger {
    val instance = MediaAttemptLedger()
}

/**
 * #960 (§6) — exposes the [MediaAttemptLedger] to the post renderer. Defaults to the process-wide
 * singleton so no wiring is required at the app entry point; tests inject a fresh instance via
 * `CompositionLocalProvider` for deterministic attempt counting.
 */
internal val LocalMediaAttemptLedger = staticCompositionLocalOf { ProcessMediaAttemptLedger.instance }
