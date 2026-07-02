package fr.forumhfr.redface2.core.ui.loading

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
import fr.forumhfr.redface2.core.ui.post.rememberAnimationsEnabled

/**
 * #604 — shared skeleton/shimmer primitive: a tinted box swept by a diagonal highlight band, read as
 * « du contenu se charge ici ». Extracted from the #249 block-image shimmer (which now delegates here)
 * so loading skeletons (topic page, and later other views) reuse the exact same rendering.
 *
 * Allocation profile unchanged from #249: a single [Modifier.drawBehind] reads one animated progress
 * float and rebuilds only a lightweight linear [Brush] per frame — no per-frame composable.
 *
 * Accessibility: when [animated] is false (system animator scale 0, see [rememberAnimationsEnabled],
 * the default) the band is parked mid-box and the tint is STATIC — no infinite animation. Callers
 * shape the box through [modifier] (size + clip); the box itself is purely decorative, so callers
 * owning a semantic loading state should announce it on their container, not per block.
 */
@Composable
fun SkeletonBox(modifier: Modifier = Modifier, animated: Boolean = rememberAnimationsEnabled()) {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val highlight = MaterialTheme.colorScheme.surfaceContainerHigh
    val shimmerColors = remember(base, highlight) { listOf(base, highlight, base) }

    val progress = if (animated) {
        val transition = rememberInfiniteTransition(label = "skeleton_shimmer")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = SHIMMER_PERIOD_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "skeleton_shimmer_progress",
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

/** One full sweep period. ~1.1 s reads as "loading" without being distracting (dogfood-tunable). */
private const val SHIMMER_PERIOD_MS = 1100

private const val STATIC_PROGRESS = 0.5f
