package fr.forumhfr.redface2.core.ui.loader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.post.rememberAnimationsEnabled

/**
 * #7/#728 — the « redface » pull-to-refresh puck: an elevated round M3-style CONTAINED loading
 * indicator holding the real [RedfaceFace], sitting just under the top bar (the caller anchors it).
 *
 * - **pull (amorce)** — [refreshing] == `false`: the puck EMERGES (rises + scales + fades in) and the
 *   redface ROLLS as a pure function of [progress] (the pull distance fraction). No timeline, fully
 *   deterministic, so it stops the instant the user stops pulling.
 * - **refresh (hero)** — [refreshing] == `true`: the puck is fully present, the redface is held still,
 *   and an indeterminate [CircularProgressIndicator] ring spins around it to signal the refresh
 *   activity. It stays in view for the whole manual refresh (M3 « keep the indicator in view until the
 *   activity completes »); the thin top loading bar is then reserved for auto / cold loads (no double
 *   indicator).
 *
 * The face only rolls when system animations are enabled ([rememberAnimationsEnabled]); reduce-motion
 * shows it upright. The container is `surfaceContainerHighest`, so it adapts to light/dark/AMOLED.
 */
@Composable
fun RedfacePullPuck(
    progress: Float,
    modifier: Modifier = Modifier,
    refreshing: Boolean = false,
) {
    val animationsEnabled = rememberAnimationsEnabled()
    val pull = progress.coerceIn(0f, 1f)
    // While refreshing the puck is the fully-present hero; during the pull it emerges with the gesture.
    val puckAlpha = if (refreshing) 1f else (pull * ALPHA_RAMP).coerceIn(0f, 1f)
    val puckScale = if (refreshing) 1f else MIN_SCALE + (1f - MIN_SCALE) * pull
    // Held still during the refresh; rolls with the pull distance otherwise (over-pull keeps rolling).
    val rollDegrees =
        if (refreshing || !animationsEnabled) 0f else progress.coerceAtLeast(0f) * MAX_ROLL_DEGREES

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
                // Drops in from behind the bar during the pull; settled (0) once refreshing.
                translationY = if (refreshing) 0f else -RISE.toPx() * (1f - pull)
                // #603 (thibw dogfood) — without this, `alpha < 1f` (the pull fade-in) forces an
                // OFFSCREEN RECTANGULAR layer at the 48 dp bounds; the Surface's unclipped elevation
                // shadow (`clip = false`) then fills that rect and, scaled down at small `puckScale`,
                // reads as a SQUIRCLE around the round redface (« le cercle rose tronqué dans un carré »).
                // ModulateAlpha multiplies the alpha per draw op instead of compositing through that
                // offscreen buffer, so the round shape + soft shadow are preserved (a hard circular clip
                // would instead cut the shadow). The puck is opaque past pull≈0.71 (ALPHA_RAMP), so the
                // per-op modulation only differs from group-alpha during the faint early pull.
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize().padding(RING_INSET),
                    strokeWidth = RING_STROKE,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            RedfaceFace(modifier = Modifier.size(FACE_SIZE), rotationDegrees = rollDegrees)
        }
    }
}

// #728 — M3 contained loading indicator default (~48 dp container, XaTriX dogfood); face ~30 dp.
private val PUCK_SIZE = 48.dp
private val FACE_SIZE = 30.dp
private val PUCK_ELEVATION = 6.dp
private val RISE = 16.dp

// The refresh ring hugs the container edge, just inside it, around the held redface.
private val RING_INSET = 1.dp
private val RING_STROKE = 3.dp

// The face reaches the threshold having rolled 1.5 turns — clearly « rolling » without looking frantic.
private const val MAX_ROLL_DEGREES = 540f

// Scale at the very start of the pull (grows to 1 at the threshold).
private const val MIN_SCALE = 0.6f

// Fade-in faster than the descent so the puck is opaque before it settles.
private const val ALPHA_RAMP = 1.4f
