package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlin.math.abs

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

// Commit thresholds: a swipe changes page only past a clear intent, never on the bare touch slop.
private const val COMMIT_FRACTION = 0.20f
private val MIN_COMMIT_DISTANCE = 72.dp
private val FLING_VELOCITY_THRESHOLD = 900.dp

/**
 * Horizontal swipe → change topic page (#282, Option A). A committed left/right swipe calls
 * [onOpenPage] (the existing route-driven navigation: pop+push of `TopicRoute`), reusing the whole
 * pagination/prefetch machinery — no neighbour page is composed, so the « prefetch non authentifié »
 * invariant is never touched.
 *
 * Coexistence (validated with Codex gpt-5.5):
 * - it engages only on **horizontal** touch slop, so the vertical `LazyColumn` scroll is never stolen;
 * - a child that consumes the horizontal drag first (the page-grid's `horizontalScroll`) cancels our
 *   slop detection, so it keeps its own gesture;
 * - at the edges (target is `null`) the gesture is a no-op (no navigation, no flash);
 * - exactly one [onOpenPage] per gesture.
 *
 * Direction is **geometric**, not layout-direction aware: a physical leftward drag always opens the
 * next page (rightward = previous), regardless of `LayoutDirection`. The forum content is LTR
 * (French) so an RTL-mirrored mapping would only matter for a locale this app does not target;
 * mirroring on `LayoutDirection` is a deliberate non-goal here (revisit if RTL becomes a use case).
 *
 * Primitive choice: low-level `awaitEachGesture` + `awaitHorizontalTouchSlopOrCancellation` rather
 * than `detectHorizontalDragGestures` (too blunt — auto-consumes and locks the axis) or
 * `anchoredDraggable` (offset/snap animation, overkill for a discrete route-driven page change).
 */
internal fun Modifier.topicPageSwipe(
    currentPage: Int,
    totalPages: Int,
    onOpenPage: (Int) -> Unit,
): Modifier = pointerInput(currentPage, totalPages) {
    val commitDistancePx = maxOf(MIN_COMMIT_DISTANCE.toPx(), size.width * COMMIT_FRACTION)
    val flingThresholdPx = FLING_VELOCITY_THRESHOLD.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val velocityTracker = VelocityTracker()
        velocityTracker.addPosition(down.uptimeMillis, down.position)
        var overSlop = 0f
        // Horizontal slop only: a vertical-dominant drag (list scroll) never reaches this branch;
        // a child that already consumed the horizontal move makes this return null (we bail).
        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, slop ->
            change.consume()
            overSlop = slop
        } ?: return@awaitEachGesture
        var totalDx = overSlop
        velocityTracker.addPosition(drag.uptimeMillis, drag.position)
        horizontalDrag(drag.id) { change ->
            totalDx += change.positionChange().x
            velocityTracker.addPosition(change.uptimeMillis, change.position)
            change.consume()
        }
        val velocityX = velocityTracker.calculateVelocity().x
        swipeCommitDirection(totalDx, velocityX, commitDistancePx, flingThresholdPx)?.let { forward ->
            swipeTargetPage(currentPage, totalPages, forward)?.let(onOpenPage)
        }
    }
}
