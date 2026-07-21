package fr.forumhfr.redface2.core.ui.post

import android.util.Log
import androidx.compose.ui.unit.IntSize
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * #175/#257/#959 — probe a media's NATIVE ORIENTED dimensions via a **header-only** decode
 * ([ProbeMetadataDecoder], attached per request — cadrage Sol Lot 3, Q1 option b).
 *
 * The pre-#959 probe requested a 1024-bounded FIT decode and read `image.width/height` from the
 * decoded bitmap — which CLIPPED the reported dimensions of any source past the bound (measured:
 * 4000×3000 → 1024×768, EXIF 900×1200 → 768×1024), violating §3 "the probe must never clip the
 * reported native dimensions". The header-only decoder reads the bounds (and EXIF orientation)
 * without ever allocating the bitmap — cheaper than the old bounded decode AND exact at any size.
 *
 * The memory cache is disabled BOTH ways on this request: the metadata pseudo-image must never be
 * served to a render request (and a cached render bitmap must not short-circuit the probe with
 * its possibly-resized dimensions — the §3 "first valid pair" authority stays with the probe).
 * The disk cache stays active: the downloaded bytes serve the subsequent render decode.
 * `execute()` is main-safe (Coil dispatches its own I/O); the caller invokes it from a
 * `LaunchedEffect` and caches the result by URL. Returns `null` on error / non-positive
 * dimensions. The returned pair is in SOURCE PIXELS — the §3 equation consumes it as physical px.
 */
internal suspend fun measureIntrinsicMediaSize(
    url: String,
    context: PlatformContext,
    imageLoader: ImageLoader,
): IntSize? {
    val result = imageLoader.execute(
        ImageRequest.Builder(context)
            .data(url)
            .decoderFactory(ProbeMetadataDecoder.Factory)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build(),
    )
    val image = (result as? SuccessResult)?.image ?: return null
    return if (image.width > 0 && image.height > 0) IntSize(image.width, image.height) else null
}

/**
 * #249 follow-up / #960 (§6) — measure [url], store a success in [cache] and settle the outcome on
 * the PROBE axis of [ledger]. Single shared seam for every caller that feeds the intrinsic-size
 * cache: the paragraph measure effect (#175/#224 — smileys + inline images) AND the standalone
 * `PostBlock.Image` effect. Keeping one implementation prevents the two paths from drifting.
 *
 * The ledger is the single source of truth for failures and generations (#960, Sol r3):
 *  - a cached success or a FRESH probe failure short-circuits without a probe;
 *  - consulting the generation applies C1 (an EXPIRED failure atomically opens a new generation);
 *  - the probe runs only under a granted reservation — ONE attempt per (URL, generation), the
 *    settlement carries the reserved generation so a stale result (the user retried mid-probe)
 *    is discarded by the ledger instead of the legacy failure-epoch guard;
 *  - a CANCELLED probe rolls its reservation back (a cancelled try is not a try) — nothing keeps
 *    the axis in-flight forever.
 *
 * The cache guard is a non-atomic check-then-act, so two callers racing on the SAME cold URL (the
 * BlockImage effect vs the paragraph effect, or two on-screen copies) could each consult before
 * the first result lands; the reservation makes the race harmless, and [inFlightMeasurements]
 * keeps the LOSER AWAITING the winner's ticket instead of returning: when a generation bump
 * cancels the winning effect mid-probe, a plain "loser returns" would leave the URL cold with
 * nobody left to probe it (#813) — the loser waking up on the ticket re-runs the guards and
 * becomes the new winner (the rollback reopened the axis). Process-wide; keyed by URL.
 */
private val inFlightMeasurements = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

// LongParameterList: the trailing `probe` is a test-only seam (cancellation-race pins); the five
// real parameters are the url + its pipeline collaborators — grouping them would be a one-call
// holder class with no other purpose.
@Suppress("LongParameterList")
internal suspend fun measureAndCacheIntrinsicMediaSize(
    url: String,
    cache: IntrinsicMediaSizeCache,
    ledger: MediaAttemptLedger,
    context: PlatformContext,
    imageLoader: ImageLoader,
    // Injectable for the cancellation-race tests only — production callers keep the default.
    probe: suspend (String, PlatformContext, ImageLoader) -> IntSize? = ::measureIntrinsicMediaSize,
) {
    while (true) {
        val now = System.currentTimeMillis()
        if (cache.get(url) != null || ledger.isFailedFresh(url, MediaAttemptKind.PROBE, now)) return
        val ticket = CompletableDeferred<Unit>()
        val winner = inFlightMeasurements.putIfAbsent(url, ticket)
        if (winner != null) {
            // Lost the race — wait for the in-flight probe to settle (result OR cancellation),
            // then loop: a landed result short-circuits on the guards, a cancelled probe rolled
            // its reservation back and this caller takes over.
            winner.await()
            continue
        }
        try {
            probeUnderReservation(url, cache, ledger, context, imageLoader, probe, now)
        } finally {
            inFlightMeasurements.remove(url, ticket)
            ticket.complete(Unit)
        }
        return
    }
}

// LongParameterList: private tail of the seam above — same collaborators, same rationale.
@Suppress("LongParameterList")
private suspend fun probeUnderReservation(
    url: String,
    cache: IntrinsicMediaSizeCache,
    ledger: MediaAttemptLedger,
    context: PlatformContext,
    imageLoader: ImageLoader,
    probe: suspend (String, PlatformContext, ImageLoader) -> IntSize?,
    nowMillis: Long,
) {
    // C1 — consulting may open a new generation when the recorded failure has expired; the
    // reservation is then taken against the CURRENT generation. A denied reservation means the
    // axis already settled this generation (e.g. a terminal success whose cache entry was
    // evicted) — nothing to do.
    val generation = ledger.consultGeneration(url, nowMillis)
    if (!ledger.tryReserve(url, generation, MediaAttemptKind.PROBE)) return
    var settled = false
    try {
        val size = probe(url, context, imageLoader)
        // Belt for a probe that swallowed cancellation: never publish a result on behalf of a
        // dead effect.
        currentCoroutineContext().ensureActive()
        if (size != null) {
            // §3/§6 — first-pair authority: a concurrent G2 painter deposit may have fixed the
            // box already; the probe's disagreeing pair is then logged, never applied.
            val deposited = cache.putSuccessIfAbsent(url, size)
            if (!deposited && cache.get(url) != size) {
                Log.d(
                    MEDIA_GEOMETRY_LOG_TAG,
                    "geometry disagreement for $url: kept=${cache.get(url)} probe=$size (first valid pair wins, §3)",
                )
            }
            ledger.settleSuccess(url, generation, MediaAttemptKind.PROBE)
        } else {
            ledger.settleFailure(url, generation, MediaAttemptKind.PROBE, System.currentTimeMillis())
        }
        settled = true
    } finally {
        // A cancelled try is not a try: reopen the axis so the awaiting loser (or the next
        // occurrence) may attempt again.
        if (!settled) ledger.rollbackReservation(url, generation, MediaAttemptKind.PROBE)
    }
}
