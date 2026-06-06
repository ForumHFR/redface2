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
 * blocked by an edge. [forward] = true → next page (drag left), false → previous page (drag right).
 * Pure → unit-tested without Compose.
 *
 * Mirrors the exact bounds of `TopicUiState.canGoNext` / `canGoPrevious` (page in `1..totalPages`)
 * so the swipe and the Previous/Next buttons agree.
 */
internal fun swipeTargetPage(currentPage: Int, totalPages: Int, forward: Boolean): Int? =
    if (forward) {
        (currentPage + 1).takeIf { it <= totalPages }
    } else {
        (currentPage - 1).takeIf { it >= 1 }
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
 * - at the edges ([swipeTargetPage] returns `null`) the gesture is a no-op (no navigation, no flash);
 * - exactly one [onOpenPage] per gesture.
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
        val committed = abs(totalDx) >= commitDistancePx || abs(velocityX) >= flingThresholdPx
        if (committed) {
            // Drag left (negative dx) reveals the next page; drag right the previous one.
            swipeTargetPage(currentPage, totalPages, forward = totalDx < 0f)?.let(onOpenPage)
        }
    }
}
