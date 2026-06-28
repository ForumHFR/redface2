package fr.forumhfr.redface2.core.ui.loader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.post.rememberAnimationsEnabled

/**
 * #7 — the « redface » pull-to-refresh puck (Concept 1, XaTriX's pick): an elevated round surface that
 * holds the [RedfaceFace], shown while the user is pulling. It EMERGES (rises + scales + fades in) and
 * the face ROLLS on itself as a pure function of [progress] (the pull distance fraction) — no timeline,
 * fully deterministic, so it stops the instant the user stops pulling. The face only rolls when system
 * animations are enabled ([rememberAnimationsEnabled]); reduce-motion shows it upright.
 *
 * « Amorce seule » (XaTriX): this puck is the PULL cue only. The caller hides it the moment the refresh
 * starts, leaving the thin top loading bar as the single refresh indicator (no double indicator). So the
 * face deliberately rolls during the PULL (driven by [progress]), never in an infinite refresh spin.
 *
 * The container is `surfaceContainerHighest`, so the puck adapts to light/dark/AMOLED on its own.
 */
@Composable
fun RedfacePullPuck(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val animationsEnabled = rememberAnimationsEnabled()
    val pull = progress.coerceIn(0f, 1f)
    val puckAlpha = (pull * ALPHA_RAMP).coerceIn(0f, 1f)
    val puckScale = MIN_SCALE + (1f - MIN_SCALE) * pull
    // Over-pull keeps rolling past the threshold (progress can exceed 1) for a livelier feel.
    val rollDegrees = if (animationsEnabled) progress.coerceAtLeast(0f) * MAX_ROLL_DEGREES else 0f

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = PUCK_ELEVATION,
        modifier = modifier
            .size(PUCK_SIZE)
            .graphicsLayer {
                alpha = puckAlpha
                scaleX = puckScale
                scaleY = puckScale
                // Descends into place: starts slightly ABOVE its slot (negative Y) and settles down to
                // rest (0) as the pull completes — the puck drops in from behind the bar.
                translationY = -RISE.toPx() * (1f - pull)
            },
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            RedfaceFace(modifier = Modifier.size(FACE_SIZE), rotationDegrees = rollDegrees)
        }
    }
}

private val PUCK_SIZE = 40.dp
private val FACE_SIZE = 26.dp
private val PUCK_ELEVATION = 6.dp
private val RISE = 16.dp

// The face reaches the threshold having rolled 1.5 turns — clearly « rolling » without looking frantic.
private const val MAX_ROLL_DEGREES = 540f

// Scale at the very start of the pull (grows to 1 at the threshold).
private const val MIN_SCALE = 0.6f

// Fade-in faster than the descent so the puck is opaque before it settles.
private const val ALPHA_RAMP = 1.4f
