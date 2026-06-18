package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import java.util.concurrent.ConcurrentHashMap

/**
 * #175/#257 — probe a media's dimensions via a **bounded** Coil decode (aspect ratio + size class).
 *
 * Requests a [INTRINSIC_PROBE_SIZE_PX]-bounded `FIT` decode (NOT `Size.ORIGINAL`), then reads
 * `coil3.Image.width/height`. `Size.ORIGINAL` fully decoded a large photo at source resolution **just
 * to read its dimensions** — slow and memory-heavy on every measurable image, on top of the render
 * decode (#257). A 1024-bounded decode is far cheaper and still answers everything the callers need:
 *  - **aspect ratio** — preserved by Coil's uniform downsample, used by `imageDisplayBox`;
 *  - **size class** ("larger than the inline caps?") — all inline caps (≤ 240×200 sp) are well below
 *    1024, so a source exceeding them still reports a width/height past the cap after probing.
 * A source ≤ 1024 px (every smiley, most inline images) decodes at native size, unchanged from before.
 * `execute()` is main-safe (Coil dispatches its own I/O); the caller invokes it from a `LaunchedEffect`
 * and caches the result by URL. Returns `null` on error / non-positive dimensions.
 *
 * NB (#175 conversion): the returned px are CSS/logical-pixel equivalents — fed to the placeholder as
 * `.sp` directly (`70px → 70.sp`), NOT divided by screen density.
 */
internal const val INTRINSIC_PROBE_SIZE_PX = 1024

internal suspend fun measureIntrinsicMediaSize(
    url: String,
    context: PlatformContext,
    imageLoader: ImageLoader,
): IntSize? {
    val result = imageLoader.execute(
        ImageRequest.Builder(context)
            .data(url)
            .size(INTRINSIC_PROBE_SIZE_PX)
            .scale(Scale.FIT)
            // INEXACT is REQUIRED here (Codex review): Coil's default EXACT precision would UPSCALE a
            // source smaller than the probe (a 16×16 emoji, a 70×50 smiley) up to 1024 before reporting
            // image.width/height — measuring small media as huge and breaking imageDisplayBox sizing +
            // the promotion threshold. INEXACT lets Coil report the native size for sources ≤ probe.
            .precision(Precision.INEXACT)
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
 * the first result lands. [inFlightMeasurements] de-dupes that window: the first caller wins the URL and
 * the others no-op until it clears the entry (in a `finally`, so a cancelled probe also releases it).
 * Process-wide like [ProcessIntrinsicMediaSizeCache]; keyed by URL (native sizes are URL-immutable).
 */
private val inFlightMeasurements: MutableSet<String> = ConcurrentHashMap.newKeySet()

internal suspend fun measureAndCacheIntrinsicMediaSize(
    url: String,
    cache: IntrinsicMediaSizeCache,
    context: PlatformContext,
    imageLoader: ImageLoader,
) {
    val now = System.currentTimeMillis()
    if (cache.get(url) != null || cache.isFailureFresh(url, now)) return
    // Lost the race to another in-flight probe for this URL — its putSuccess/putFailure will recompose us.
    if (!inFlightMeasurements.add(url)) return
    try {
        val size = measureIntrinsicMediaSize(url, context, imageLoader)
        if (size != null) cache.putSuccess(url, size) else cache.putFailure(url, now)
    } finally {
        inFlightMeasurements.remove(url)
    }
}
