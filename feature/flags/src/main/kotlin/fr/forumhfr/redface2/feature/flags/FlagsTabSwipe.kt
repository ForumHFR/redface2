package fr.forumhfr.redface2.feature.flags

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.MutableFloatState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.pager.FLING_VELOCITY_THRESHOLD
import fr.forumhfr.redface2.core.ui.pager.MIN_COMMIT_DISTANCE
import fr.forumhfr.redface2.core.ui.pager.swipeArmed
import fr.forumhfr.redface2.core.ui.pager.swipeCommitDirection
import fr.forumhfr.redface2.core.ui.pager.swipeCommitDistancePx
import fr.forumhfr.redface2.core.ui.pager.swipeFollowOffset
import fr.forumhfr.redface2.core.ui.pager.swipeTargetPage
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Horizontal swipe → change flag tab (#457): the tab-index counterpart of the MP conversation's
 * in-place `threadPageSwipe` (#351b). The *feel* is identical by construction — thresholds,
 * drag-follow shaping and edge hint all come from the shared pure geometry in `:core:ui`
 * (`core.ui.pager.PageSwipe`) — with "page" mapped to the **tab index** (0-based, left-to-right):
 * swiping the content left commits to the tab on the right, exactly like turning to the next
 * page. The tab row itself is NOT part of the gesture surface — it already has taps.
 *
 * In-place mechanics mirror the thread gesture (the composition survives the tab change, so a
 * committed swipe springs back to rest while the new tab's content swaps in — instantly on a
 * cache hit, behind the existing loading state otherwise):
 *
 * - **Commit → [FlagsTabSwipeHandlers.onSelectTab]** with the target index; the caller maps it
 *   back to a `FlagTab` (the DT tab is conditional, so the index↔tab mapping lives where the tab
 *   list is built). The ViewModel's re-tap toggle (Cyan « +lus ») is unreachable by construction:
 *   [swipeTargetPage] never returns the current index.
 * - [currentIndex] and [tabCount] are lambdas: the selected tab changes UNDER this live
 *   composition (gesture keyed on `Unit`, never re-keyed), both must be read fresh per frame.
 * - No re-entrance latch and no enabled-gate: a tab switch is local state + cached list, there
 *   is no in-flight window to guard (the thread gesture's gate exists for its network reload).
 *
 * Coexistence contract: horizontal slop only — the vertical list scroll and the pull-to-refresh
 * nested-scroll are never stolen. This gesture only became possible once the rows' horizontal
 * `SwipeToDismissBox` (#99) was retired in this same change: a child consuming the horizontal
 * drag first cancels ours. Edges (first/last tab) are a damped no-op wall.
 */
internal fun Modifier.flagsTabSwipe(
    currentIndex: () -> Int,
    tabCount: () -> Int,
    dragOffset: MutableFloatState,
    handlers: FlagsTabSwipeHandlers,
): Modifier = this
    // `pointerInput` BEFORE `graphicsLayer`, same constraint as the topic/thread gestures:
    // deltas must be read in the untranslated coordinate space (inside the translated layer each
    // frame's `positionChange()` would be Δfinger − Δtranslation → halved tracking).
    .pointerInput(Unit) {
        val commitDistancePx = swipeCommitDistancePx(size.width.toFloat(), MIN_COMMIT_DISTANCE.toPx())
        val flingThresholdPx = FLING_VELOCITY_THRESHOLD.toPx()
        val release = Animatable(0f)
        coroutineScope {
            val animationScope = this
            var releaseJob: Job? = null
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val velocityTracker = VelocityTracker()
                velocityTracker.addPosition(down.uptimeMillis, down.position)
                var overSlop = 0f
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, slop ->
                    change.consume()
                    overSlop = slop
                } ?: return@awaitEachGesture
                // Cancel a running spring-back deterministically at slop crossing (not at `down`,
                // which would let a mere tap interrupt it) — same reasoning as #282/#351b.
                releaseJob?.cancel()
                var totalDx = overSlop
                var armed = false
                velocityTracker.addPosition(drag.uptimeMillis, drag.position)
                dragOffset.floatValue =
                    tabFollowOffset(totalDx, commitDistancePx, currentIndex(), tabCount())
                val completed = horizontalDrag(drag.id) { change ->
                    totalDx += change.positionChange().x
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    val offset =
                        tabFollowOffset(totalDx, commitDistancePx, currentIndex(), tabCount())
                    dragOffset.floatValue = offset
                    val nowArmed = swipeArmed(offset, commitDistancePx)
                    if (nowArmed && !armed) {
                        handlers.haptics.performHapticFeedback(
                            HapticFeedbackType.GestureThresholdActivate,
                        )
                    }
                    armed = nowArmed
                    change.consume()
                }
                if (completed) {
                    val velocityX = velocityTracker.calculateVelocity().x
                    val forward =
                        swipeCommitDirection(totalDx, velocityX, commitDistancePx, flingThresholdPx)
                    val target = forward?.let { swipeTargetIndex(currentIndex(), tabCount(), it) }
                    if (forward != null && target != null) {
                        handlers.haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        handlers.onSelectTab(target)
                    }
                }
                // ALWAYS spring back — commit included: the composition is kept, the new tab's
                // content swaps in at rest (cache hit = instant, otherwise the loading state).
                releaseJob = animationScope.launch {
                    release.snapTo(dragOffset.floatValue)
                    release.animateTo(0f, TAB_SPRING_BACK) { dragOffset.floatValue = value }
                }
            }
        }
    }
    // Draw-only translation + armed elevation lift, same draw-phase contract as the other swipe
    // gestures (reads `dragOffset` without recomposition).
    .graphicsLayer {
        val offset = dragOffset.floatValue
        translationX = offset
        val commitDistancePx = swipeCommitDistancePx(size.width, MIN_COMMIT_DISTANCE.toPx())
        shadowElevation =
            if (swipeArmed(offset, commitDistancePx)) TAB_COMMIT_SHADOW_ELEVATION_DP.dp.toPx() else 0f
    }

/**
 * Non-per-frame inputs of [flagsTabSwipe], bundled (same shape as `ThreadSwipeHandlers`) so the
 * gesture's parameter list stays within the project's limit. [onSelectTab] receives the target
 * **tab index** in the currently displayed tab list.
 */
internal class FlagsTabSwipeHandlers(
    val haptics: HapticFeedback,
    val onSelectTab: (Int) -> Unit,
)

/**
 * The shared pager geometry speaks 1-based pages; tabs are 0-based indices. Shift by one for
 * [swipeTargetPage] so its `1..totalPages` bounds check becomes a `0..tabCount-1` check.
 */
private fun swipeTargetIndex(currentIndex: Int, tabCount: Int, forward: Boolean): Int? =
    swipeTargetPage(currentIndex + 1, tabCount, forward)?.minus(1)

private fun tabFollowOffset(
    totalDx: Float,
    commitDistancePx: Float,
    currentIndex: Int,
    tabCount: Int,
): Float {
    val hasTarget = swipeTargetIndex(currentIndex, tabCount, forward = totalDx < 0f) != null
    return swipeFollowOffset(totalDx, commitDistancePx, hasTarget)
}

private const val TAB_COMMIT_SHADOW_ELEVATION_DP = 8f

private val TAB_SPRING_BACK = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)
