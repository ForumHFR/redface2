package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size

/**
 * #175 — measure a media's **intrinsic** (native) pixel dimensions via Coil.
 *
 * Requests `Size.ORIGINAL` so Coil decodes to the SOURCE dimensions (not the display target), then
 * reads `coil3.Image.width/height` (raw bitmap px, never screen-density scaled). `execute()` is a
 * main-safe suspend call (Coil dispatches its own I/O), so the caller invokes it directly from a
 * `LaunchedEffect` and caches the result by URL. Returns `null` on error / non-positive dimensions so
 * the caller can fall back to a provisional size.
 *
 * Caveat: `Size.ORIGINAL` sets the request *target*, not Coil's `maxBitmapSize` (default 4096) — so
 * the reported size equals the source only while the source is ≤ 4096 on each axis; a larger source
 * is reported at the clamped decode size. Irrelevant for smileys (all well under 4096); it would only
 * matter if this were reused to size arbitrary large inline images.
 *
 * NB (#175 conversion): the returned px are CSS/logical-pixel equivalents — fed to the placeholder as
 * `.sp` directly (`70px → 70.sp`), NOT divided by screen density (which would render smileys
 * ~`1/density` too small — the bug the design doc calls out).
 */
internal suspend fun measureIntrinsicMediaSize(
    url: String,
    context: PlatformContext,
    imageLoader: ImageLoader,
): IntSize? {
    val result = imageLoader.execute(
        ImageRequest.Builder(context)
            .data(url)
            .size(Size.ORIGINAL)
            .build(),
    )
    val image = (result as? SuccessResult)?.image ?: return null
    return if (image.width > 0 && image.height > 0) IntSize(image.width, image.height) else null
}
