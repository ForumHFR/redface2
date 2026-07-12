package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
