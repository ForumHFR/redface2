package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest

/**
 * #831 — small square thumbnail of a post image, the hero of [PostImageMenuSheet].
 *
 * Coil is already wired in `:core:ui` (`coil-compose` is an `implementation` dependency of this
 * module). The neutral `surfaceContainerHighest` square doubles as the loading and error
 * placeholder — the sheet remains fully usable when the image host is down (the actions operate
 * on the URL, not the bitmap). The bitmap is served from Coil's caches (same URL key as the post
 * render), so no second network fetch in the common public-topic case. A private-message menu
 * passes [PostMediaDiskCachePolicy.DISABLED], matching the request that rendered its source image.
 */
@Composable
fun PostImageThumbnail(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = DEFAULT_THUMBNAIL_SIZE,
    mediaDiskCachePolicy: PostMediaDiskCachePolicy = PostMediaDiskCachePolicy.ENABLED,
) {
    val context = LocalPlatformContext.current
    val request = remember(url, context, mediaDiskCachePolicy) {
        ImageRequest.Builder(context)
            .data(url)
            .diskCachePolicy(mediaDiskCachePolicy.coilPolicy)
            .build()
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Same footprint as the post-menu hero avatar (`RedfaceUserAvatar` at 56.dp in PostMenuSheet). */
private val DEFAULT_THUMBNAIL_SIZE = 56.dp
