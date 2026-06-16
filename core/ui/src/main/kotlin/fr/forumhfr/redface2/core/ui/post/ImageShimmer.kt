package fr.forumhfr.redface2.core.ui.post

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext

/**
 * #249 — animated shimmer painted INSIDE the already-reserved block-image box (see
 * [PostMediaDisplayPolicy.reservedBlockImageHeight]) while the bitmap loads. A diagonal highlight band
 * sweeps left→right over a tinted base, so the user reads "image en cours de chargement ici" without a
 * spinner — and because the box is pre-sized to the final image, the crossfade reveal causes no bump.
 *
 * Allocation profile mirrors [fr.forumhfr.redface2.core.ui.pager.PageSwipe]: a single
 * [Modifier.drawBehind] reads one animated progress float and rebuilds only a lightweight linear
 * [Brush] per frame over the box width — no per-frame composable, no full-screen overdraw.
 *
 * Accessibility (#249 §4 "réduire les animations") : when [animated] is false (system animator scale
 * 0, see [rememberAnimationsEnabled]) the band is parked at rest and the box shows a STATIC tint — no
 * infinite animation, no crossfade upstream. The reveal is then instant.
 */
@Composable
internal fun ImageShimmer(animated: Boolean, modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val highlight = MaterialTheme.colorScheme.surfaceContainerHigh
    val shimmerColors = remember(base, highlight) { listOf(base, highlight, base) }

    val progress = if (animated) {
        val transition = rememberInfiniteTransition(label = "post_image_shimmer")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = SHIMMER_PERIOD_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "post_image_shimmer_progress",
        ).value
    } else {
        // Reduce-motion: park the band mid-box so the static tint blends base↔highlight, no harsh edge.
        STATIC_PROGRESS
    }

    Box(
        modifier = modifier
            .drawBehind {
                // Travel the band from off-left to off-right so the highlight enters and exits the box
                // cleanly each period (width × 2 of travel keeps the diagonal visible throughout).
                val travel = size.width * 2f
                val start = -size.width + travel * progress
                drawRect(
                    brush = Brush.linearGradient(
                        colors = shimmerColors,
                        start = Offset(start, 0f),
                        end = Offset(start + size.width, size.height),
                    ),
                )
            },
    )
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

/** One full sweep period. ~1.1 s reads as "loading" without being distracting (dogfood-tunable). */
private const val SHIMMER_PERIOD_MS = 1100

private const val STATIC_PROGRESS = 0.5f
