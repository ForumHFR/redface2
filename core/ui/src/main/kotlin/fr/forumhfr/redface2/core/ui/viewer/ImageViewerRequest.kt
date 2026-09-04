package fr.forumhfr.redface2.core.ui.viewer

/**
 * Navigation-neutral contract for the fullscreen image viewer (#182).
 *
 * [sourceUrl] is fetched at its original resolution, [previewUrl] identifies the already-rendered
 * post image used as an immediate memory-cache placeholder, and [externalUrl] is the explicit
 * browser destination. Private-message callers set [diskCache] to false so neither the preview nor
 * the full source creates a persistent Coil entry.
 */
data class ImageViewerRequest(
    val sourceUrl: String,
    val previewUrl: String,
    val externalUrl: String,
    val description: String?,
    val diskCache: Boolean,
)
