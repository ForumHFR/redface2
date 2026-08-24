package fr.forumhfr.redface2.feature.messages

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.runtime.MutableFloatState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.pager.FLING_VELOCITY_THRESHOLD
import fr.forumhfr.redface2.core.ui.pager.MIN_COMMIT_DISTANCE
import fr.forumhfr.redface2.core.ui.pager.inStartGestureDeadZone
import fr.forumhfr.redface2.core.ui.pager.swipeArmed
import fr.forumhfr.redface2.core.ui.pager.swipeCommitDirection
import fr.forumhfr.redface2.core.ui.pager.swipeCommitDistancePx
import fr.forumhfr.redface2.core.ui.pager.swipeFollowOffset
import fr.forumhfr.redface2.core.ui.pager.swipeTargetPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Horizontal swipe → change conversation page (#351b/#1040). Geometry, thresholds, edge hint and
 * the #752 start dead-zone predicate come from `core.ui.pager.PageSwipe`; the pointer machine stays
 * feature-owned because its cache/keep-content coupling differs from the topic (ADR-013).
 *
 * A commit has two release paths, selected from a generation/account-sealed cache probe at lift-off:
 *
 * - **warm target** — latch, slide the outgoing page fully off-screen, then call
 *   [ThreadSwipeHandlers.onSelectPage]. The cache emission renders the target in-place and the
 *   release owner immediately parks the retained offset back at zero after handing off selection;
 *   the call-site repeats that fail-safe when the rendered page changes;
 * - **cold target** — latch, spring fully back to offset zero, then select. The outgoing page stays
 *   readable under the refresh indicator for the network round-trip; failure therefore also leaves
 *   readable content at rest instead of a blank/off-screen page.
 *
 * The latch is local to `pointerInput(currentPage, isRefreshing)`. A committed selection first
 * changes `isRefreshing`, then either a target emission changes [currentPage] or a failure returns
 * `isRefreshing` to false: each transition re-keys the pointer block and creates a fresh latch. This
 * is the explicit in-place rearm that prevents both duplicate commits during release and the
 * historical frozen-after-first-swipe failure.
 *
 * #936 multi-touch cancellation is native to this machine: a secondary pointer after horizontal
 * slop cancels an uncommitted drag into spring-back, even after it armed. [ThreadSwipeHandlers.enabled]
 * is sampled at DOWN and rechecked before commit; it folds the screen's competing producers
 * (refresh, zoom gesture/fling/settle, scrollbar drag and page landing/alignment). A child consuming
 * the horizontal primary drag still cancels ours; edges remain a damped no-op wall.
 */
internal fun Modifier.threadPageSwipe(
    currentPage: Int,
    totalPages: () -> Int,
    isRefreshing: Boolean,
    dragOffset: MutableFloatState,
    handlers: ThreadSwipeHandlers,
): Modifier = this
    // Re-key on the rendered page AND the load gate. A cold failure keeps the page number unchanged,
    // so isRefreshing=true→false is the event that discards its committed latch and re-arms.
    .pointerInput(currentPage, isRefreshing) {
        val commitDistancePx = swipeCommitDistancePx(size.width.toFloat(), MIN_COMMIT_DISTANCE.toPx())
        val flingThresholdPx = FLING_VELOCITY_THRESHOLD.toPx()
        val widthPx = size.width.toFloat()
        val release = Animatable(0f)
        coroutineScope {
            val animationScope = this
            var committed = false
            var releaseJob: Job? = null
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (committed || !handlers.enabled()) return@awaitEachGesture
                // Insets are deliberately resolved once for this gesture, at DOWN. Rotation or a
                // posture change reaches the next gesture through the call-site's live lambdas.
                if (inStartGestureDeadZone(
                        x = down.position.x,
                        widthPx = size.width,
                        leftInsetPx = handlers.leftGestureInsetPx(),
                        rightInsetPx = handlers.rightGestureInsetPx(),
                    )
                ) {
                    return@awaitEachGesture
                }
                val velocityTracker = VelocityTracker()
                velocityTracker.addPosition(down.uptimeMillis, down.position)
                var overSlop = 0f
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, slop ->
                    change.consume()
                    overSlop = slop
                } ?: return@awaitEachGesture
                // Cancel only at horizontal slop: a tap/vertical scroll must not interrupt a return.
                // A committed transition never reaches this point because the latch wins above.
                releaseJob?.cancel()
                var totalDx = overSlop
                var armed = false
                velocityTracker.addPosition(drag.uptimeMillis, drag.position)
                dragOffset.floatValue =
                    threadFollowOffset(totalDx, commitDistancePx, currentPage, totalPages())
                val outcome = trackThreadHorizontalDrag(drag.id) { change ->
                    totalDx += change.positionChange().x
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    val offset =
                        threadFollowOffset(totalDx, commitDistancePx, currentPage, totalPages())
                    dragOffset.floatValue = offset
                    val nowArmed = swipeArmed(offset, commitDistancePx)
                    if (nowArmed && !armed) {
                        handlers.haptics.performHapticFeedback(ARMED_HAPTIC)
                    }
                    armed = nowArmed
                    change.consume()
                }
                if (threadSwipeMustCancel(outcome, handlers.enabled())) {
                    releaseJob = springBackToRest(animationScope, release, dragOffset)
                    return@awaitEachGesture
                }
                val velocityX = velocityTracker.calculateVelocity().x
                val forward =
                    swipeCommitDirection(totalDx, velocityX, commitDistancePx, flingThresholdPx)
                val target = forward?.let { swipeTargetPage(currentPage, totalPages(), it) }
                if (forward != null && target != null) {
                    committed = true
                    handlers.haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    releaseJob = releaseCommittedThreadSwipe(
                        scope = animationScope,
                        release = release,
                        dragOffset = dragOffset,
                        targetX = if (forward) -widthPx else widthPx,
                        targetPage = target,
                        handlers = handlers,
                    )
                } else {
                    releaseJob = springBackToRest(animationScope, release, dragOffset)
                }
            }
        }
    }
    // Draw-only translation/elevation: no list recomposition per drag or release-animation frame.
    .graphicsLayer {
        val offset = dragOffset.floatValue
        translationX = offset
        val commitDistancePx = swipeCommitDistancePx(size.width, MIN_COMMIT_DISTANCE.toPx())
        shadowElevation =
            if (swipeArmed(offset, commitDistancePx)) THREAD_COMMIT_SHADOW_ELEVATION_DP.dp.toPx() else 0f
    }

/** Non-per-frame gesture inputs, bundled to keep the modifier signature below detekt's limit. */
internal class ThreadSwipeHandlers(
    val haptics: HapticFeedback,
    val onSelectPage: (Int) -> Unit,
    val enabled: () -> Boolean,
    val isTargetPageWarm: (Int) -> Boolean,
    val leftGestureInsetPx: () -> Int,
    val rightGestureInsetPx: () -> Int,
)

/** Any outcome except [ThreadDragOutcome.COMPLETED] cancels into spring-back. */
private enum class ThreadDragOutcome { MULTI_TOUCH, TAKEN_OVER, COMPLETED }

/**
 * #936 — track the primary horizontal drag while observing raw pointer topology. A secondary DOWN
 * wins before primary-up/consumption checks, so an armed drag never commits through a two-finger
 * race and never transfers ownership to the second pointer.
 */
private suspend fun AwaitPointerEventScope.trackThreadHorizontalDrag(
    dragId: PointerId,
    onMove: (PointerInputChange) -> Unit,
): ThreadDragOutcome {
    var outcome: ThreadDragOutcome? = null
    while (outcome == null) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == dragId }
        when {
            event.changes.any { it.id != dragId && it.changedToDownIgnoreConsumed() } ->
                outcome = ThreadDragOutcome.MULTI_TOUCH
            change == null -> outcome = ThreadDragOutcome.TAKEN_OVER
            change.changedToUpIgnoreConsumed() -> outcome = ThreadDragOutcome.COMPLETED
            change.isConsumed -> outcome = ThreadDragOutcome.TAKEN_OVER
            change.positionChange().x != 0f -> onMove(change)
        }
    }
    return outcome
}

/** A producer that starts after DOWN still wins before selection is committed. */
private fun threadSwipeMustCancel(outcome: ThreadDragOutcome, enabledAtRelease: Boolean): Boolean =
    outcome != ThreadDragOutcome.COMPLETED || !enabledAtRelease

/** Return to readable rest; [onRest] may start a cold page selection only after offset reaches zero. */
private fun springBackToRest(
    scope: CoroutineScope,
    release: Animatable<Float, *>,
    dragOffset: MutableFloatState,
    onRest: () -> Unit = {},
): Job = scope.launch {
    release.snapTo(dragOffset.floatValue)
    release.animateTo(0f, THREAD_SPRING_BACK) { dragOffset.floatValue = value }
    onRest()
}

/** Select the release path without adding cache coupling to the pointer-event loop. */
private fun releaseCommittedThreadSwipe(
    scope: CoroutineScope,
    release: Animatable<Float, *>,
    dragOffset: MutableFloatState,
    targetX: Float,
    targetPage: Int,
    handlers: ThreadSwipeHandlers,
): Job = if (handlers.isTargetPageWarm(targetPage)) {
    slideOutThenSelect(scope, release, dragOffset, targetX) {
        handlers.onSelectPage(targetPage)
    }
} else {
    springBackToRest(scope, release, dragOffset) {
        handlers.onSelectPage(targetPage)
    }
}

/**
 * Slide a warm outgoing page fully away, then select exactly once behind the committed latch. The
 * immediate zero after the non-suspending handoff is a fail-safe for a warmth race (LRU eviction or
 * generation invalidation during the slide): latency/failure can reveal the old page, never leave it
 * parked off-screen. A real cache hit can then swap content without waiting for the network.
 */
private fun slideOutThenSelect(
    scope: CoroutineScope,
    release: Animatable<Float, *>,
    dragOffset: MutableFloatState,
    targetX: Float,
    onSelected: () -> Unit,
): Job = scope.launch {
    try {
        release.snapTo(dragOffset.floatValue)
        release.animateTo(
            targetValue = targetX,
            animationSpec = tween(
                durationMillis = THREAD_COMMIT_SLIDE_OUT_MILLIS,
                easing = LinearOutSlowInEasing,
            ),
        ) { dragOffset.floatValue = value }
        onSelected()
    } finally {
        // The committed latch means this job is never cancelled by a newer swipe. Reset is therefore
        // safe even if the pointer block itself is superseded by an external refresh/state change.
        dragOffset.floatValue = 0f
    }
}

private fun threadFollowOffset(
    totalDx: Float,
    commitDistancePx: Float,
    currentPage: Int,
    totalPages: Int,
): Float {
    val hasTarget = swipeTargetPage(currentPage, totalPages, forward = totalDx < 0f) != null
    return swipeFollowOffset(totalDx, commitDistancePx, hasTarget)
}

private const val THREAD_COMMIT_SLIDE_OUT_MILLIS = 200
private const val THREAD_COMMIT_SHADOW_ELEVATION_DP = 8f
private val ARMED_HAPTIC = HapticFeedbackType.GestureThresholdActivate

private val THREAD_SPRING_BACK = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)
