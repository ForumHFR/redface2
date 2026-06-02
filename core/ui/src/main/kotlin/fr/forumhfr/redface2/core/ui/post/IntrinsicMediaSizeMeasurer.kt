package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale

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
