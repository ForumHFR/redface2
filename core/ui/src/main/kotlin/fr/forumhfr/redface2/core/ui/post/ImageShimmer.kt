package fr.forumhfr.redface2.core.ui.post

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import fr.forumhfr.redface2.core.ui.loading.SkeletonBox

/**
 * #249 — animated shimmer painted INSIDE the already-reserved block-image box (see
 * [PostMediaDisplayPolicy.blockImageDisplaySize]) while the bitmap loads, so the user reads
 * "image en cours de chargement ici" without a spinner — and because the box is pre-sized to the
 * final image, the crossfade reveal causes no bump.
 *
 * Rendering is the shared [SkeletonBox] primitive (#604 extracted it from here so topic-page
 * skeletons reuse the exact same band); this wrapper only keeps the call sites' explicit
 * [animated] gate (#249 §4 "réduire les animations" — static tint, instant reveal upstream).
 */
@Composable
internal fun ImageShimmer(animated: Boolean, modifier: Modifier = Modifier) {
    SkeletonBox(modifier = modifier, animated = animated)
}

