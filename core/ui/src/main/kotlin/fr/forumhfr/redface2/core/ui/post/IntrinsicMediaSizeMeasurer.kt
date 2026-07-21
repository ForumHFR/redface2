package fr.forumhfr.redface2.core.ui.post

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
 * #249 follow-up — measure [url] and store the outcome in [cache], but ONLY when it is neither already
 * known nor a fresh failure. Single shared seam for every caller that feeds the intrinsic-size cache:
 * the paragraph measure effect (#175/#224 — smileys + inline images) AND the standalone `PostBlock.Image`
 * effect. Keeping one implementation prevents the two paths from drifting (e.g. one forgetting the
 * [IntrinsicMediaSizeCache.isFailureFresh] guard and re-probing a dead host every recomposition).
 *
 * Idempotent: a cached success / fresh failure short-circuits without a probe, so once the first result
 * lands the `SnapshotStateMap` write recomposes readers and subsequent calls no-op.
 *
 * The cache guard is a non-atomic check-then-act, so two callers racing on the SAME cold URL (the
 * BlockImage effect vs the paragraph effect, or two on-screen copies) could each start a probe before
 * the first result lands. [inFlightMeasurements] de-dupes that window: the first caller wins the URL,
 * the others AWAIT its ticket then re-run the guard. The await (not a fire-and-forget no-op) matters
 * for #813: when a refresh generation bump cancels the winning effect mid-probe, a plain "loser
 * returns" would leave the URL cold with nobody left to probe it until the next refresh — the loser
 * waking up on the ticket re-checks the cache and becomes the new winner. A CANCELLED winner records
 * nothing (a cancelled probe is not a dead host), so its late unwind can never overwrite a fresh
 * success either. Process-wide like [ProcessIntrinsicMediaSizeCache]; keyed by URL.
 */
private val inFlightMeasurements = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

internal suspend fun measureAndCacheIntrinsicMediaSize(
    url: String,
    cache: IntrinsicMediaSizeCache,
    context: PlatformContext,
    imageLoader: ImageLoader,
    // Injectable for the cancellation-race tests only — production callers keep the default.
    probe: suspend (String, PlatformContext, ImageLoader) -> IntSize? = ::measureIntrinsicMediaSize,
) {
    while (true) {
        val now = System.currentTimeMillis()
        if (cache.get(url) != null || cache.isFailureFresh(url, now)) return
        // #813 — capture the failure epoch BEFORE probing: if the user refreshes (clearFailures)
        // while this probe is in flight, its failure result is STALE — exactly the outage the user
        // is retrying — and must not be re-deposited on top of the clear. Compose only cancels the
        // old measure effect at the next recomposition, so this window is real, not theoretical.
        val epoch = cache.failureEpoch()
        val ticket = CompletableDeferred<Unit>()
        val winner = inFlightMeasurements.putIfAbsent(url, ticket)
        if (winner != null) {
            // Lost the race — wait for the in-flight probe to settle (result OR cancellation),
            // then loop: a landed result short-circuits on the guard, a cancelled probe left the
            // URL cold and this caller takes over.
            winner.await()
            continue
        }
        try {
            val size = probe(url, context, imageLoader)
            // Belt for a probe that swallowed cancellation: never publish a result on behalf of a
            // dead effect (the epoch guard below covers failures; a success is always welcome, but
            // only from a live coroutine).
            currentCoroutineContext().ensureActive()
            if (size != null) cache.putSuccess(url, size) else cache.putFailureIfEpoch(url, now, epoch)
            return
        } finally {
            inFlightMeasurements.remove(url, ticket)
            ticket.complete(Unit)
        }
    }
}
