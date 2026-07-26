package fr.forumhfr.redface2.core.ui.post

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImagePainter

/** Log tag of the §3 geometry-disagreement diagnostics (first valid pair keeps the authority). */
internal const val MEDIA_GEOMETRY_LOG_TAG = "PostMediaGeometry"

/**
 * #960 (§6) — the PAINTER-axis gate of ONE on-screen occurrence of a media url. Reads the
 * [MediaAttemptLedger] through tracked snapshot reads and takes AT MOST one reservation per
 * (url, generation):
 *
 *  - [failedFresh] → the occurrence composes the §6 error state and NEVER a painter node, so no
 *    network attempt can fire (the pre-#960 pipeline re-attempted per occurrence and per key()
 *    bump);
 *  - terminal success → [renderPainter] without any settlement (Coil serves its caches);
 *  - untried → the FIRST occurrence wins the reservation and reports the painter outcome through
 *    [onState]; concurrent occurrences hold the placeholder until the winner settles (the ledger's
 *    snapshot write recomposes them onto the settled branch).
 *
 * The reservation is taken in a [LaunchedEffect] — never during composition (an abandoned
 * composition would leak an in-flight axis) — and rolls back on dispose while unsettled: the
 * AsyncImage's request is cancelled with its composition, so nobody else would ever settle the
 * axis (a cancelled try is not a try).
 */
@Stable
internal class PainterAttempt(
    private val ledger: MediaAttemptLedger,
    private val cache: IntrinsicMediaSizeCache,
    private val url: String,
    private val generation: Int,
) {
    private var granted by mutableStateOf(false)
    private var settled = false

    /** Fresh painter failure on record — compose the error state, never a painter node. */
    val failedFresh: Boolean
        get() = ledger.isFailedFresh(url, MediaAttemptKind.PAINTER, System.currentTimeMillis())

    /** True when THIS occurrence composes the painter node (own grant, or a terminal success). */
    val renderPainter: Boolean
        get() = granted || ledger.hasSucceeded(url, MediaAttemptKind.PAINTER)

    fun reserveIfUntried() {
        // C1 — consulting may reopen EXPIRED failed axes into a NEW generation (never the current
        // one). When it does, this attempt is stale: the ledger's snapshot write recomposes the
        // occurrence, which remembers a fresh attempt for the new generation and reserves there —
        // without this consultation an expired painter failure would hold the placeholder forever
        // (failure no longer fresh, axis still failed, nobody allowed to re-attempt).
        if (ledger.consultGeneration(url, System.currentTimeMillis()) != generation) return
        if (ledger.tryReserve(url, generation, MediaAttemptKind.PAINTER)) granted = true
    }

    /**
     * Settles the granted attempt on the painter's terminal states; loading/empty are ignored.
     * The GEOMETRY deposit (G2) runs on EVERY success — granted, settled or terminal — because
     * the pair is immutable-true and the deposit is idempotent first-pair: this is what heals a
     * FIFO-evicted cache entry when a terminal painter re-renders (Sol P2, O1 — the §6 locked
     * slot survives eviction), and the §7 re-decode's callback can never apply a second
     * correction through it. Guard asymmetry (deliberate, Sol P2): the PAINTER settlement is
     * grant- AND generation-guarded; the G2-derived PROBE settlement is generation-guarded ONLY
     * (any truthful painter success may satisfy the measurement need, grant or not).
     */
    fun onState(state: AsyncImagePainter.State) {
        when (state) {
            is AsyncImagePainter.State.Success -> {
                settlePainterGeometry(state)
                if (granted && !settled) {
                    settled = true
                    ledger.settleSuccess(url, generation, MediaAttemptKind.PAINTER)
                }
            }

            is AsyncImagePainter.State.Error -> {
                if (granted && !settled) {
                    settled = true
                    ledger.settleFailure(url, generation, MediaAttemptKind.PAINTER, System.currentTimeMillis())
                }
            }

            else -> Unit
        }
    }

    /**
     * #960 P2 — G2 (contrat v1.5 §6, « probe KO, painter OK »): the painter's ORIENTED image
     * dimensions (`coil3.Image.width/height`, the §3 normative source) produce THE unique box
     * correction when they are the FIRST valid pair (`putSuccessIfAbsent` — a later disagreeing
     * pair is logged, never applied), and settle the PROBE axis too: the measurement need is
     * met, the url becomes stable forever (no TTL advancement, no replayed probe). A success
     * WITHOUT usable geometry deposits nothing and leaves the probe axis retryable (C1) — §6
     * « aucune dimension exploitable → boîte cold CONSERVÉE ».
     *
     * #973 — the painter deposit carries NO MIME (only the probe's header decode identifies the
     * container), and `putSuccessIfAbsent` guarantees it can never RECLASSIFY an entry the probe
     * already fixed — in either direction (« AUCUN reclassement tardif »).
     */
    private fun settlePainterGeometry(state: AsyncImagePainter.State.Success) {
        val image = state.result.image
        if (image.width <= 0 || image.height <= 0) return
        val painterSize = IntSize(image.width, image.height)
        val deposited = cache.putSuccessIfAbsent(url, IntrinsicMediaMetadata(painterSize, mimeType = null))
        if (!deposited && cache.get(url)?.size != painterSize) {
            Log.d(
                MEDIA_GEOMETRY_LOG_TAG,
                "geometry disagreement for $url: kept=${cache.get(url)?.size} painter=$painterSize " +
                    "(first valid pair wins, §3)",
            )
        }
        ledger.settleSuccess(url, generation, MediaAttemptKind.PROBE)
    }

    fun rollbackIfUnsettled() {
        if (granted && !settled) ledger.rollbackReservation(url, generation, MediaAttemptKind.PAINTER)
    }
}

/**
 * Remembers the [PainterAttempt] of this occurrence of [url]. Keyed on the url's ledger
 * GENERATION (a tracked read): a scoped retry recreates the attempt — and with it the whole
 * painter node, replacing the pre-#960 screen-wide `key(refreshGeneration)` bumps.
 */
@Composable
internal fun rememberPainterAttempt(url: String): PainterAttempt {
    val ledger = LocalMediaAttemptLedger.current
    val cache = LocalIntrinsicMediaSizeCache.current
    val generation = ledger.generationOf(url)
    val attempt = remember(ledger, cache, url, generation) { PainterAttempt(ledger, cache, url, generation) }
    // Keyed on failedFresh too: when a recomposition observes the failure EXPIRED (fresh → false)
    // the effect re-runs and the reservation path consults C1 — reopening the axis in a new
    // generation instead of leaving a no-longer-fresh, still-failed axis stuck on the placeholder.
    val failedFresh = attempt.failedFresh
    LaunchedEffect(attempt, failedFresh) {
        if (!failedFresh) attempt.reserveIfUntried()
    }
    DisposableEffect(attempt) { onDispose { attempt.rollbackIfUnsettled() } }
    return attempt
}
