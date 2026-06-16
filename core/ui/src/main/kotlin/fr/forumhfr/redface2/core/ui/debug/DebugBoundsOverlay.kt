package fr.forumhfr.redface2.core.ui.debug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsNode

/**
 * #445 — full-screen debug overlay that outlines the bounds of every laid-out component, drawn ON TOP
 * of the app content so a developer can eyeball layout/spacing while dogfooding. Exposed in Settings on
 * the DEV channel only, default OFF.
 *
 * Bounds source: the Compose semantics tree. [LocalView] under an `AndroidComposeView` is a
 * [RootForTest], whose `semanticsOwner.unmergedRootSemanticsNode` roots a walk where each node exposes
 * `boundsInRoot` — already in this overlay's own coordinate space (the overlay fills the same root).
 * The cast is guarded ([rememberDebugBounds] swallows a `ClassCastException` / a `@Preview` host) so a
 * non-`RootForTest` view degrades to drawing nothing instead of crashing.
 *
 * The walk is re-sampled on a throttled ~8 Hz tick (not every frame) into a snapshot `State`, so the
 * overlay does not re-walk the (potentially large) tree on each of the 60–120 fps content frames.
 *
 * Perf when disabled: this composable is gated by the caller (`if (enabled) DebugBoundsOverlay()`),
 * so when the preference is off it is never composed and the tick loop never starts.
 */
@Composable
fun DebugBoundsOverlay(modifier: Modifier = Modifier) {
    val bounds = rememberDebugBounds()
    // Fills the same root the bounds are measured against, so `boundsInRoot` maps 1:1 to draw
    // coordinates. `Canvas` is a `Spacer`-backed drawing node with NO pointer handling, so it paints
    // over the content without intercepting taps.
    Canvas(modifier = modifier.fillMaxSize()) {
        bounds.forEach { box ->
            drawRect(
                color = depthColor(box.depth),
                topLeft = Offset(box.rect.left, box.rect.top),
                size = Size(box.rect.width, box.rect.height),
                style = STROKE,
            )
        }
    }
}

/** A single outlined node: its [rect] (in root coordinates) and tree [depth] (drives the colour). */
internal data class DebugBoundsBox(val rect: Rect, val depth: Int)

/**
 * Re-samples the semantics tree into a bounds snapshot on a throttled ~8 Hz tick. Held in a plain
 * `State` so the [Canvas] repaints only when the snapshot is replaced (≈ every [TICK_INTERVAL_NANOS]),
 * not on every content frame. The root view is resolved once; the cast is guarded so a host that is
 * not a [RootForTest] (e.g. a `@Preview`) yields an empty list rather than throwing.
 */
@OptIn(InternalComposeUiApi::class)
@Composable
private fun rememberDebugBounds(): List<DebugBoundsBox> {
    val view = LocalView.current
    var snapshot by remember { mutableStateOf(emptyList<DebugBoundsBox>()) }
    LaunchedEffect(view) {
        var lastTickNanos = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (now - lastTickNanos >= TICK_INTERVAL_NANOS) {
                lastTickNanos = now
                snapshot = sampleBounds(view as? RootForTest)
            }
        }
    }
    return snapshot
}

/**
 * Walks the semantics tree from the root (depth-first, iterative to avoid recursion-depth limits) and
 * collects each node's `boundsInRoot`. Returns an empty list when [root] is null (non-`RootForTest`
 * host) or when accessing the tree throws (defensive: the semantics owner is an internal surface).
 */
@OptIn(InternalComposeUiApi::class)
private fun sampleBounds(root: RootForTest?): List<DebugBoundsBox> {
    // Unmerged root (#445 design): the unmerged tree keeps the individual layout/text/interactive
    // nodes that a merged subtree would collapse into one accessibility node — that finer granularity
    // is the whole point of a "outline every component" debug overlay.
    val rootNode = root?.semanticsOwner?.unmergedRootSemanticsNode ?: return emptyList()
    return runCatching { walkBounds(rootNode) }.getOrDefault(emptyList())
}

/** Iterative depth-first collection of every node's finite, non-empty `boundsInRoot`. */
private fun walkBounds(rootNode: SemanticsNode): List<DebugBoundsBox> {
    val out = ArrayList<DebugBoundsBox>()
    val stack = ArrayDeque<Pair<SemanticsNode, Int>>()
    stack.addLast(rootNode to 0)
    while (stack.isNotEmpty()) {
        val (node, depth) = stack.removeLast()
        val rect = node.boundsInRoot
        if (rect.width > 0f && rect.height > 0f && rect.isFinite) {
            out.add(DebugBoundsBox(rect, depth))
        }
        node.children.forEach { child -> stack.addLast(child to depth + 1) }
    }
    return out
}

/** Cycles a small palette by tree depth so nested components stay visually distinguishable. */
private fun depthColor(depth: Int): Color = DEPTH_PALETTE[depth % DEPTH_PALETTE.size]

private val STROKE = Stroke(width = 2f)

// Semi-transparent so the underlying content stays readable through the outlines.
private val DEPTH_PALETTE = listOf(
    Color(0x88E53935), // red
    Color(0x881E88E5), // blue
    Color(0x8843A047), // green
    Color(0x88FB8C00), // orange
    Color(0x888E24AA), // purple
    Color(0x8800ACC1), // cyan
)

// ~8 Hz refresh: 125 ms between re-walks of the tree (1_000 ms / 8).
private const val TICK_INTERVAL_NANOS = 125_000_000L
