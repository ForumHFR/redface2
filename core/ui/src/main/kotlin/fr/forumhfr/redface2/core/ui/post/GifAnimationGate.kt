package fr.forumhfr.redface2.core.ui.post

import android.graphics.drawable.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.DrawableImage
import coil3.compose.AsyncImagePainter

/**
 * #959 (Lot 3, contrat v1.5 §3 GIF, cadrage Sol r1 blocker #3) — drives the animation of a
 * CONTENT image's [Animatable] result: it runs ONLY while
 *
 *  1. the §3 box is FINAL ([boxReady] — the first valid native pair landed; a cold GIF must not
 *     start before its box and ratio are definitive),
 *  2. the node's REAL bounds intersect the window ([GifAnimationGate.modifier] watches
 *     `boundsInWindow`, which is clipped by every parent — a prefetched item composed off-viewport
 *     reports empty bounds and stays still), and
 *  3. the lifecycle is at least RESUMED (backgrounded app ⇒ stopped).
 *
 * The Coil painter auto-starts Animatable drawables when remembered; the gate's effect runs right
 * after (keyed on the captured drawable) and stops it before any visible progress when the gate is
 * closed. `AnimatedImageDrawable` has no frame-exact pause — stop() rewinds to the first frame, so
 * a GIF scrolled out and back restarts from the top (documented degradation, no platform pause
 * API). Non-animatable images never register an [Animatable]: the gate is a strict no-op for
 * static content, and smileys/cc never go through it (#175/#256 unchanged — content images only).
 */
@Stable
internal class GifAnimationGate {
    internal var inWindow by mutableStateOf(false)
    internal var animatable by mutableStateOf<Animatable?>(null)

    /** Watches the node's clipped window bounds — empty means out of the visible viewport. */
    val modifier: Modifier = Modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        inWindow = bounds.width > 0f && bounds.height > 0f
    }

    /** Captures the Animatable behind a successful load (null for static results). */
    val onState: (AsyncImagePainter.State) -> Unit = { state ->
        animatable = ((state as? AsyncImagePainter.State.Success)?.result?.image as? DrawableImage)
            ?.drawable as? Animatable
    }
}

@Composable
internal fun rememberGifAnimationGate(boxReady: Boolean): GifAnimationGate {
    val gate = remember { GifAnimationGate() }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = boxReady && gate.inWindow && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val animatable = gate.animatable
    LaunchedEffect(active, animatable) {
        if (animatable == null) return@LaunchedEffect
        if (active) {
            if (!animatable.isRunning) animatable.start()
        } else if (animatable.isRunning) {
            animatable.stop()
        }
    }
    // Leaving the composition (disposal, LazyColumn recycling) must never leave a detached
    // drawable animating on the Choreographer.
    DisposableEffect(animatable) {
        onDispose { animatable?.stop() }
    }
    return gate
}
