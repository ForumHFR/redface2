package fr.forumhfr.redface2.feature.topic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Target page of a horizontal swipe while reading a topic (#282), or `null` when the gesture is
 * blocked by an edge. [forward] = true → next page, false → previous page. Pure → unit-tested
 * without Compose.
 *
 * Bounds the result to `1..totalPages` — the same range as `TopicUiState.canGoNext` /
 * `canGoPrevious`. The page source is the rendered `Topic.page` (what the user is looking at), which
 * matches the value the Previous/Next buttons pass to `onOpenPage`.
 */
internal fun swipeTargetPage(currentPage: Int, totalPages: Int, forward: Boolean): Int? =
    if (forward) {
        (currentPage + 1).takeIf { it <= totalPages }
    } else {
        (currentPage - 1).takeIf { it >= 1 }
    }

/**
 * Direction a finished horizontal drag committed to, or `null` for a no-op (drag too small AND too
 * slow). `true` = next page (leftward drag), `false` = previous page (rightward). Pure → unit-tested
 * without a gesture clock. Edge clamping is the caller's job (via [swipeTargetPage]).
 *
 * Commit when the drag crossed EITHER the distance ([commitDistancePx]) or the fling velocity
 * ([flingThresholdPx]) threshold. Direction follows the **velocity** when a fling carried the commit,
 * otherwise the **distance** — so a fast flick that happens to lift on the wrong side of its start
 * (finger reversed) still goes the way it was thrown, and a near-zero displacement never picks an
 * unstable sign. Negative = leftward drag = next page (geometric, see [topicPageSwipe]).
 */
internal fun swipeCommitDirection(
    totalDx: Float,
    velocityX: Float,
    commitDistancePx: Float,
    flingThresholdPx: Float,
): Boolean? {
    val committedByFling = abs(velocityX) >= flingThresholdPx
    val orientation = when {
        committedByFling -> velocityX
        abs(totalDx) >= commitDistancePx -> totalDx
        else -> return null
    }
    return if (orientation == 0f) null else orientation < 0f
}

/**
 * Distance (px) past which a slow drag commits to a page change. The same value drives the visual
 * drag-follow cap and the edge hint (so the page stops growing exactly when the hint is fully lit),
 * so it is computed once per layout and shared by [topicPageSwipe] and [topicPageSwipeEdge].
 * Pure (takes already-resolved pixels) → unit-tested without a `Density`.
 */
internal fun swipeCommitDistancePx(widthPx: Float, minCommitPx: Float): Float =
    maxOf(minCommitPx, widthPx * COMMIT_FRACTION)

/**
 * Visual translation (px) of the current page for a raw horizontal displacement [rawDx], giving the
 * drag immediate feedback (#282 follow). The sign matches [rawDx] (leftward drag → negative offset).
 * Pure → unit-tested.
 *
 * - [hasTarget] = true (a page exists that way): track the finger 1:1 up to [commitDistancePx], then
 *   apply [OVERPULL_RESISTANCE] so the page keeps giving a little without sliding the whole way —
 *   reaching `commitDistancePx` is the visual "armed" point the edge hint mirrors.
 * - [hasTarget] = false (first/last page): a damped "wall", capped at [EDGE_MAX_FRACTION] of the
 *   commit distance, so a swipe into the void barely moves and reads as a boundary.
 */
internal fun swipeFollowOffset(rawDx: Float, commitDistancePx: Float, hasTarget: Boolean): Float {
    if (commitDistancePx <= 0f) return 0f
    val magnitude = abs(rawDx)
    val sign = if (rawDx < 0f) -1f else 1f
    val travel = if (hasTarget) {
        if (magnitude <= commitDistancePx) {
            magnitude
        } else {
            commitDistancePx + (magnitude - commitDistancePx) * OVERPULL_RESISTANCE
        }
    } else {
        minOf(magnitude * EDGE_RESISTANCE, commitDistancePx * EDGE_MAX_FRACTION)
    }
    return sign * travel
}

// Commit thresholds: a swipe changes page only past a clear intent, never on the bare touch slop.
private const val COMMIT_FRACTION = 0.20f
internal val MIN_COMMIT_DISTANCE = 72.dp
private val FLING_VELOCITY_THRESHOLD = 900.dp

// Drag-follow feel (#282 (b)+(c)). All purely visual — they never affect the commit decision.
private const val OVERPULL_RESISTANCE = 0.35f // diminishing follow past the commit point
private const val EDGE_RESISTANCE = 0.30f // damping into a blocked edge (first/last page)
private const val EDGE_MAX_FRACTION = 0.4f // a blocked edge can travel at most this × commit distance
private const val EDGE_HINT_WIDTH_FRACTION = 0.18f // edge-glow band width, as a fraction of the page
private const val EDGE_HINT_MAX_ALPHA = 0.5f // edge-glow opacity once the swipe is fully armed

private val SPRING_BACK = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * Horizontal swipe → change topic page (#282, Option A) with drag-follow feedback (b). A committed
 * left/right swipe calls [onOpenPage] (the existing route-driven navigation: pop+push of
 * `TopicRoute`), reusing the whole pagination/prefetch machinery — no neighbour page is composed, so
 * the « prefetch non authentifié » invariant is never touched.
 *
 * While dragging, the current page follows the finger ([dragOffset] drives a `translationX`, shaped
 * by [swipeFollowOffset]); on commit it navigates, otherwise (no-commit, edge, or a child taking the
 * drag) it springs back to rest. The cross-page cut after a commit is unchanged (no shared-element
 * transition) — that is the optional follow-up (a) at the `NavDisplay` level.
 *
 * Coexistence (unchanged from the discrete version, validated with Codex gpt-5.5):
 * - it engages only on **horizontal** touch slop, so the vertical `LazyColumn` scroll is never stolen;
 * - a child that consumes the horizontal drag first (the page-grid's `horizontalScroll`) cancels our
 *   slop detection / `horizontalDrag`, so it keeps its own gesture and the page springs back;
 * - at the edges (target is `null`) the gesture is a damped no-op (no navigation, no flash);
 * - exactly one [onOpenPage] per gesture.
 *
 * Direction is **geometric**, not layout-direction aware: a physical leftward drag always opens the
 * next page (rightward = previous), regardless of `LayoutDirection`. The forum content is LTR
 * (French) so an RTL-mirrored mapping would only matter for a locale this app does not target;
 * mirroring on `LayoutDirection` is a deliberate non-goal here (revisit if RTL becomes a use case).
 *
 * Primitive choice: low-level `awaitEachGesture` + `awaitHorizontalTouchSlopOrCancellation` rather
 * than `detectHorizontalDragGestures` (too blunt — auto-consumes and locks the axis) or
 * `anchoredDraggable` (anchored snap states, the wrong model for a threshold/velocity commit that has
 * to coexist with the list scroll, the page grid and `[fixed]` blocks).
 */
internal fun Modifier.topicPageSwipe(
    currentPage: Int,
    totalPages: Int,
    dragOffset: Animatable<Float, AnimationVector1D>,
    onOpenPage: (Int) -> Unit,
): Modifier = this
    // `pointerInput` sits BEFORE `graphicsLayer` on purpose: the gesture must read finger deltas in
    // the untranslated coordinate space. If it were inside the translated layer, each frame's
    // `positionChange()` would be `Δfinger − Δtranslation`, halving the tracking and doubling the
    // effective commit distance. `graphicsLayer` (draw-only) leaves the hit-test bounds put, so the
    // finger stays over the page while the page visibly follows.
    .pointerInput(currentPage, totalPages) {
        val commitDistancePx = swipeCommitDistancePx(size.width.toFloat(), MIN_COMMIT_DISTANCE.toPx())
        val flingThresholdPx = FLING_VELOCITY_THRESHOLD.toPx()
        coroutineScope {
            val animationScope = this
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val velocityTracker = VelocityTracker()
                velocityTracker.addPosition(down.uptimeMillis, down.position)
                var overSlop = 0f
                // Horizontal slop only: a vertical-dominant drag (list scroll) never reaches this
                // branch; a child that already consumed the horizontal move makes this return null.
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, slop ->
                    change.consume()
                    overSlop = slop
                } ?: return@awaitEachGesture
                var totalDx = overSlop
                velocityTracker.addPosition(drag.uptimeMillis, drag.position)
                // The first snapTo also cancels any spring-back still running from a previous swipe.
                animationScope.launch {
                    dragOffset.snapTo(followOffsetFor(totalDx, commitDistancePx, currentPage, totalPages))
                }
                // `horizontalDrag` returns false when the drag is CANCELLED — e.g. a descendant
                // horizontal scroller (a wide `[fixed]` code block, the page grid) takes the pointer
                // over after we crossed slop. Honour it: a taken-over gesture must NOT navigate.
                val completed = horizontalDrag(drag.id) { change ->
                    totalDx += change.positionChange().x
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    animationScope.launch {
                        dragOffset.snapTo(followOffsetFor(totalDx, commitDistancePx, currentPage, totalPages))
                    }
                    change.consume()
                }
                if (!completed) {
                    animationScope.launch { dragOffset.animateTo(0f, SPRING_BACK) }
                    return@awaitEachGesture
                }
                val velocityX = velocityTracker.calculateVelocity().x
                val target = swipeCommitDirection(totalDx, velocityX, commitDistancePx, flingThresholdPx)
                    ?.let { forward -> swipeTargetPage(currentPage, totalPages, forward) }
                if (target != null) {
                    // Navigation replaces the screen; the offset state is discarded with it.
                    onOpenPage(target)
                } else {
                    animationScope.launch { dragOffset.animateTo(0f, SPRING_BACK) }
                }
            }
        }
    }
    // Draw-only translation of the page content. After `pointerInput` (see above) so the gesture is
    // read in untranslated space; reading `dragOffset.value` here keeps the follow on the draw phase
    // (no recomposition per frame).
    .graphicsLayer { translationX = dragOffset.value }

private fun followOffsetFor(totalDx: Float, commitDistancePx: Float, currentPage: Int, totalPages: Int): Float {
    val hasTarget = swipeTargetPage(currentPage, totalPages, forward = totalDx < 0f) != null
    return swipeFollowOffset(totalDx, commitDistancePx, hasTarget)
}

/**
 * Edge hint for the topic page swipe (#282 (c)). Draws a glow on the edge the neighbour page is being
 * pulled in from — right edge for a leftward drag (next page), left edge for a rightward drag — whose
 * opacity ramps with the swipe's progress toward the commit distance and reaches [EDGE_HINT_MAX_ALPHA]
 * once the swipe is armed. It reads [dragOffset] at draw time only, so following the finger never
 * recomposes.
 *
 * Must sit **before** [topicPageSwipe] in the modifier chain: it draws via `drawWithContent` in the
 * element's own (untranslated) space, so the glow stays pinned to the screen edge while the page
 * itself is translated by the `graphicsLayer` that [topicPageSwipe] adds further down the chain.
 * [accent] is read from the theme by the (composable) caller and passed in, since a draw scope cannot.
 */
internal fun Modifier.topicPageSwipeEdge(
    dragOffset: Animatable<Float, AnimationVector1D>,
    accent: Color,
): Modifier = drawWithContent {
    drawContent()
    val offset = dragOffset.value
    if (offset == 0f) return@drawWithContent
    val commitDistancePx = swipeCommitDistancePx(size.width, MIN_COMMIT_DISTANCE.toPx())
    if (commitDistancePx <= 0f) return@drawWithContent
    val progress = (abs(offset) / commitDistancePx).coerceIn(0f, 1f)
    val alpha = progress * EDGE_HINT_MAX_ALPHA
    val band = size.width * EDGE_HINT_WIDTH_FRACTION
    val brush = if (offset < 0f) {
        Brush.horizontalGradient(
            colors = listOf(Color.Transparent, accent.copy(alpha = alpha)),
            startX = size.width - band,
            endX = size.width,
        )
    } else {
        Brush.horizontalGradient(
            colors = listOf(accent.copy(alpha = alpha), Color.Transparent),
            startX = 0f,
            endX = band,
        )
    }
    drawRect(brush = brush)
}
