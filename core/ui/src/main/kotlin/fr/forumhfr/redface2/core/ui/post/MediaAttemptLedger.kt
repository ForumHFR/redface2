package fr.forumhfr.redface2.core.ui.post

import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntSize

/** #960 (§6) — the two independent attempt axes of a media URL. */
internal enum class MediaAttemptKind { PROBE, PAINTER }

/** v1.6-10: the first accepted current-generation pair and its immutable provenance. */
internal data class MediaGeometry(
    val size: IntSize,
    val provenance: MediaAttemptKind,
    val mimeType: String?,
)

/**
 * #960 (Lot 4, contrat v1.5 §6, cadrage Sol r3) — the per-URL ATTEMPT LEDGER, single source of
 * truth for media-load failures, retry generations and process-lifetime content geometry.
 * Geometry is unbounded O(URL), independent of the bounded intrinsic memo. Locks:
 *
 *  1. every mutation is scoped to explicit URLs — no process-wide sweep API exists;
 *  2. ONE attempt per (URL, generation, axis): [tryReserve] grants atomically, concurrent
 *     occurrences are denied and observe the settled state through snapshot reads instead;
 *  3. the state lives in a [SnapshotStateMap], so composables keyed on [generationOf]
 *     re-evaluate mechanically on retry/advancement — no side notification channel;
 *  4. a settled SUCCESS is never re-attempted, TTL-advanced or reopened by retry alone.
 *     An observed painter cache miss may fail that axis through [failPainterAfterSuccess];
 *     the successful PROBE and the §6 geometry lock are preserved;
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
        val geometry: MediaGeometry? = null,
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

    /** Snapshot-observable authority. Retries, TTL and memo eviction never clear it. */
    fun geometryOf(url: String): MediaGeometry? = entries[url]?.geometry

    /** Terminal outcome. Without geometry, PROBE settles only after execution/timeout cleanup. */
    fun isSettled(url: String, kind: MediaAttemptKind): Boolean = when (entries[url]?.axis(kind)) {
        is AxisState.Failed, AxisState.Succeeded -> true
        else -> false
    }

    /**
     * v1.6-10 — atomically accept a current, valid pair. First dimensions and provenance win;
     * only a reliable probe may fill a missing MIME. Neither a divergent nor a null MIME can
     * replace a known one. The optional memo is written under the same generation guard and
     * lock, so concurrent writers cannot publish an older merge over a newer enrichment.
     * A usable pair satisfies the measurement need (including G2), keeping PROBE terminal.
     * Returns false for stale/invalid results, which must have no observable effect.
     */
    fun acceptGeometry(
        url: String,
        generation: Int,
        metadata: IntrinsicMediaMetadata,
        provenance: MediaAttemptKind,
        cache: IntrinsicMediaSizeCache? = null,
    ): Boolean = synchronized(lock) {
        val entry = entries[url] ?: UrlEntry()
        if (entry.generation != generation || metadata.size.width <= 0 || metadata.size.height <= 0) return false
        val known = entry.geometry
        val reliableMime = metadata.mimeType.takeIf { provenance == MediaAttemptKind.PROBE }
        if (known != null) logDisagreements(known, metadata.size, reliableMime, provenance)
        val geometry = if (known == null) {
            MediaGeometry(metadata.size, provenance, reliableMime)
        } else {
            known.copy(mimeType = known.mimeType ?: reliableMime)
        }
        entries[url] = entry.copy(geometry = geometry, probe = AxisState.Succeeded)
        cache?.putSuccess(url, IntrinsicMediaMetadata(geometry.size, geometry.mimeType))
        true
    }

    private fun logDisagreements(
        known: MediaGeometry,
        size: IntSize,
        mimeType: String?,
        provenance: MediaAttemptKind,
    ) {
        if (known.size != size) {
            Log.d(MEDIA_GEOMETRY_LOG_TAG, "geometry disagreement: kept=${known.size} $provenance=$size (§3)")
        }
        if (known.mimeType != null && mimeType != null && known.mimeType != mimeType) {
            Log.d(MEDIA_GEOMETRY_LOG_TAG, "mime disagreement: kept=${known.mimeType} probe=$mimeType (§8)")
        }
    }

    /** Snapshot-observable generation of [url] (0 until first mutation). */
    fun generationOf(url: String): Int = entries[url]?.generation ?: 0

    /**
     * #960 N1 — snapshot-observable re-arm bit: true when [kind] is untried in the CURRENT
     * generation, an unknown [url] included. A loser denied while another occurrence held the
     * reservation observes this flip when [rollbackReservation] returns the axis to untried
     * WITHOUT bumping the generation (lock #5), and re-runs its reservation effect. Without it
     * the loser's keys never move — the axis stays untried forever and the occurrence is stuck
     * on its placeholder, out of reach of the refresh gesture (which only bumps FAILED axes).
     */
    fun isUntried(url: String, kind: MediaAttemptKind): Boolean =
        (entries[url]?.axis(kind) ?: AxisState.Untried) == AxisState.Untried

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
     * settlement for an unknown URL creates its entry rather than dropping it (belt: every
     * production writer reserves first since the P1 smiley gate, but a truthful outcome must
     * never be lost to a missing entry — e.g. the G2 probe settlement derived from a painter).
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
     * URL creates its entry rather than dropping it (same belt as [settleSuccess]). MONOTONIC
     * (Sol P1): a settled success is terminal — a late concurrent failure never demotes it
     * (lock #4).
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
     * E1 (§6) — a composed painter can fail after an earlier success when Coil's bytes were
     * evicted. Share that observed failure by URL so every occurrence exposes the error/retry
     * slot. This is not a new reservation: only manual retry or TTL can open the next generation.
     * Stale callbacks and repeated errors cannot overwrite a newer attempt or extend its TTL.
     * Generic concurrent failures still use the monotone [settleFailure].
     */
    fun failPainterAfterSuccess(url: String, generation: Int, nowMillis: Long) {
        synchronized(lock) {
            val entry = entries[url] ?: return
            if (entry.generation != generation || entry.painter != AxisState.Succeeded) return
            entries[url] = entry.copy(painter = AxisState.Failed(nowMillis))
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
     * #960 P2 (Sol, O1) — FIFO eviction repair of a succeeded probe, same generation, painter
     * untouched. v1.6-10 restricts this to the smiley memo: content authority survives eviction
     * in this ledger, so content callers never reopen a succeeded probe.
     */
    fun reopenSmileyProbeForLostMemo(url: String) {
        synchronized(lock) {
            val entry = entries[url] ?: return
            if (entry.probe != AxisState.Succeeded) return
            entries[url] = entry.copy(probe = AxisState.Untried)
        }
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
