package fr.forumhfr.redface2.core.ui.post

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import fr.forumhfr.redface2.core.ui.loading.SkeletonBox

/**
 * #249 — animated shimmer painted INSIDE the already-reserved block-image box (see
 * [PostMediaDisplayPolicy.reservedBlockImageHeight]) while the bitmap loads, so the user reads
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

/**
 * #249 §4 — `true` unless the user disabled animations system-wide (Developer options / "Remove
 * animations", or an accessibility profile setting `ANIMATOR_DURATION_SCALE` to 0). Reading the system
 * setting (rather than the app's unwired `future_a11y_reduce_motion` row) honours the OS-level
 * preference the same way Compose's own animations do. Defaults to enabled if the setting is absent.
 */
@Composable
internal fun rememberAnimationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        val scale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        scale != 0f
    }
}
