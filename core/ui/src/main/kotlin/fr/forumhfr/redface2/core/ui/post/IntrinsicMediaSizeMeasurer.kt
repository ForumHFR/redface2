package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size

/**
 * #175 — measure a media's **intrinsic** (native) pixel dimensions via Coil, off the main thread.
 *
 * Requests `Size.ORIGINAL` so Coil reports the SOURCE dimensions (not the display target), then
 * reads `coil3.Image.width/height` (raw bitmap px, never screen-density scaled). The caller wraps
 * this in `withContext(ioDispatcher)` and caches the result by URL.
 *
 * Returns `null` on error / non-positive dimensions so the caller falls back to a provisional size.
 *
 * NB (#175 conversion): the returned px are CSS/logical-pixel equivalents — they are fed to the
 * placeholder as `.sp` directly (`70px → 70.sp`), NOT divided by screen density. See the design
 * doc; dividing by density would render smileys ~`1/density` too small.
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
