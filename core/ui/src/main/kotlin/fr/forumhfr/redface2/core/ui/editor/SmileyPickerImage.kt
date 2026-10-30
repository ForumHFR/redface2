package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.IntSize
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.ui.post.LocalIntrinsicMediaSizeCache
import fr.forumhfr.redface2.core.ui.post.LocalMediaAttemptLedger
import fr.forumhfr.redface2.core.ui.post.measureAndCacheIntrinsicMediaSize

/** Grid and detail share the same original URL, intrinsic measurements and Coil caches. */
@Composable
internal fun rememberSmileyIntrinsicSize(smiley: EditorSmiley): IntSize? {
    val cache = LocalIntrinsicMediaSizeCache.current
    val ledger = LocalMediaAttemptLedger.current
    val context = LocalPlatformContext.current
    if (smiley.source == EditorSmileySource.BUILTIN) return null
    LaunchedEffect(smiley.imageUrl) {
        measureAndCacheIntrinsicMediaSize(
            url = smiley.imageUrl,
            cache = cache,
            ledger = ledger,
            context = context,
            imageLoader = SingletonImageLoader.get(context),
            isContentMedia = false,
        )
    }
    return cache.get(smiley.imageUrl)?.size
}
