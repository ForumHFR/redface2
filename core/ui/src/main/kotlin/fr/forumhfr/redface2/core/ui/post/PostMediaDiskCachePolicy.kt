package fr.forumhfr.redface2.core.ui.post

import androidx.compose.runtime.staticCompositionLocalOf
import coil3.request.CachePolicy

/**
 * Disk-persistence policy for media rendered from a [fr.forumhfr.redface2.core.model.PostContent].
 *
 * The singleton Coil loader cannot infer whether a request comes from a public topic or a private
 * message. The reading host therefore supplies this policy at the shared-card boundary, and every
 * renderer request (intrinsic probe and painter alike) applies it explicitly. Memory caching stays
 * enabled on painter requests in both modes; [PostMediaDiskCachePolicy.DISABLED] only prevents
 * Coil from reading or writing its disk cache.
 */
enum class PostMediaDiskCachePolicy {
    ENABLED,
    DISABLED,
}

internal val PostMediaDiskCachePolicy.coilPolicy: CachePolicy
    get() = when (this) {
        PostMediaDiskCachePolicy.ENABLED -> CachePolicy.ENABLED
        PostMediaDiskCachePolicy.DISABLED -> CachePolicy.DISABLED
    }

/** Defaults every non-private renderer host (topics and editor previews) to the Coil disk cache. */
internal val LocalPostMediaDiskCachePolicy = staticCompositionLocalOf {
    PostMediaDiskCachePolicy.ENABLED
}
