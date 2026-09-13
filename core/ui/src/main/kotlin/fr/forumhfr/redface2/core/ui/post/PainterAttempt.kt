package fr.forumhfr.redface2.core.ui.post

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
 *  - terminal success → [renderPainter] from Coil's caches; an observed cache-miss error
 *    fails the PAINTER axis so §6 still exposes a shared error slot and manual retry;
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
    private val isContentMedia: Boolean = true,
) {
    private var granted by mutableStateOf(false)
    private var settled = false
    private var active = true

    /** E3 — includes the pending first frame; terminal painter states remove the announcement. */
    var loading by mutableStateOf(true)
        private set

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
     * #960 P2 (Sol, O1) originally healed FIFO eviction from any successful painter callback;
     * E1 also records errors after success, E3 tracks loading for a11y. v1.6-10 preserves those
     * outcome gates but removes cache-based geometry authority and the §7 G2 re-decode.
     * Current live callbacks alone can settle outcomes or geometry. G2 deposits into the
     * ledger, whose first pair survives memo eviction, retries and divergent later painters.
     * No callback may use a stale result to fix or enrich the current generation.
     */
    fun onState(state: AsyncImagePainter.State) {
        if (!active || ledger.generationOf(url) != generation) return
        loading = state !is AsyncImagePainter.State.Success && state !is AsyncImagePainter.State.Error
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
                } else if (renderPainter) {
                    ledger.failPainterAfterSuccess(url, generation, System.currentTimeMillis())
                }
            }

            else -> Unit
        }
    }

    /**
     * #960 P2 — G2 (§6, probe KO / painter OK) supplies the oriented image dimensions. No usable
     * dimensions means no deposit and the cold box stays. v1.6-10 accepts the first current pair
     * in the ledger, which survives memo eviction and keeps the measurement need satisfied.
     * #973 ([AMENDEMENT-v1.5-2]) — a painter never supplies MIME or reclassifies known MIME;
     * v1.6-10 now permits a later reliable current probe to enrich the missing MIME only.
     */
    private fun settlePainterGeometry(state: AsyncImagePainter.State.Success) {
        val image = state.result.image
        val metadata = IntrinsicMediaMetadata(IntSize(image.width, image.height), null)
        if (isContentMedia) {
            ledger.acceptGeometry(url, generation, metadata, MediaAttemptKind.PAINTER, cache)
        } else if (image.width > 0 && image.height > 0) {
            cache.putSuccessIfAbsent(url, metadata)
            ledger.settleSuccess(url, generation, MediaAttemptKind.PROBE)
        }
    }

    fun rollbackIfUnsettled() {
        active = false
        if (granted && !settled) ledger.rollbackReservation(url, generation, MediaAttemptKind.PAINTER)
    }
}

/**
 * Remembers the [PainterAttempt] of this occurrence of [url]. Keyed on the url's ledger
 * GENERATION (a tracked read): a scoped retry recreates the attempt — and with it the whole
 * painter node, replacing the pre-#960 screen-wide `key(refreshGeneration)` bumps.
 */
@Composable
internal fun rememberPainterAttempt(
    url: String,
    enabled: Boolean = true,
    requestContext: Any? = null,
    isContentMedia: Boolean = false,
): PainterAttempt {
    val ledger = LocalMediaAttemptLedger.current
    val cache = LocalIntrinsicMediaSizeCache.current
    val generation = ledger.generationOf(url)
    val attempt = remember(ledger, cache, url, generation, requestContext, isContentMedia) {
        PainterAttempt(ledger, cache, url, generation, isContentMedia)
    }
    // Keyed on failedFresh: when a recomposition observes the failure EXPIRED (fresh → false)
    // the effect re-runs and the reservation path consults C1 — reopening the axis in a new
    // generation instead of leaving a no-longer-fresh, still-failed axis stuck on the placeholder.
    //
    // #960 N1 — keyed on `untried` too: a loser denied while ANOTHER occurrence of the same url
    // held the reservation must re-run when that winner is disposed mid-flight, since
    // `rollbackReservation` returns the axis to untried WITHOUT bumping the generation (lock #5).
    // Both `attempt` and `failedFresh` are unchanged by that rollback, so this bit is the only
    // key that moves. The body stays gated on `failedFresh` ONLY: an EXPIRED failure is still
    // `Failed`, not untried, and must keep reaching `reserveIfUntried()` to consult C1.
    val failedFresh = attempt.failedFresh
    val untried = ledger.isUntried(url, MediaAttemptKind.PAINTER)
    LaunchedEffect(attempt, failedFresh, untried, enabled) {
        if (enabled && !failedFresh) attempt.reserveIfUntried()
    }
    DisposableEffect(attempt) { onDispose { attempt.rollbackIfUnsettled() } }
    return attempt
}
