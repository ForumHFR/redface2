package fr.forumhfr.redface2.core.ui.post

import fr.forumhfr.redface2.core.ui.viewer.ImageViewerRequest
import java.net.URI

/** Hosts whose image endpoints do not expose a stable file extension in their path. */
private val IMAGE_HOST_ALLOWLIST = setOf(
    "reho.st",
    "rehost.diberie.com",
    "i.imgur.com",
)

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "avif")

/**
 * Returns true when [url] is safe to treat as a direct image target for the fullscreen viewer.
 * Query parameters and fragments never participate in the extension check.
 */
@Suppress("ReturnCount") // Parse guard + host allowlist shortcut, each a cheap early return.
fun isImageLikeUrl(url: String): Boolean {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    val host = uri.host?.lowercase()
    if (host in IMAGE_HOST_ALLOWLIST) return true
    val extension = uri.path
        ?.substringAfterLast('/', missingDelimiterValue = "")
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase()
    return extension in IMAGE_EXTENSIONS
}

/**
 * Applies the #182 image truth table, shared by linked inline taps since v1.5 (#1279), without
 * changing inline/block rendering (§2). A linked image opens the viewer only when its wrapping
 * target is image-like; an unlinked image uses its rendered URL for all three roles. The renderer
 * keeps unlinked inline images long-press-only. The contextual sheet calls this with
 * `target.copy(linkUrl = null)` to open the rendered image, independently of its wrapping link.
 */
@Suppress("ReturnCount") // Eligibility guard + non-image link rejection, both terminal cases.
fun viewerRequestFor(target: PostImageTarget, diskCache: Boolean): ImageViewerRequest? {
    if (!isEligiblePostImageUrl(target.url)) return null
    val linkUrl = target.linkUrl
    val sourceUrl = when {
        linkUrl == null -> target.url
        isEligiblePostImageUrl(linkUrl) && isImageLikeUrl(linkUrl) -> linkUrl
        else -> return null
    }
    return ImageViewerRequest(
        sourceUrl = sourceUrl,
        previewUrl = target.url,
        externalUrl = sourceUrl,
        description = target.description,
        diskCache = diskCache,
    )
}
