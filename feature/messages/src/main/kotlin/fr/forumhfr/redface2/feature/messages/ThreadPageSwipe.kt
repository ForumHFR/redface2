package fr.forumhfr.redface2.feature.messages

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
 * Horizontal swipe → change conversation page (#351b): the minimal counterpart of the topic's
 * `topicPageSwipe` (#282 — itself in-place too since #895 étape 4, riding the in-VM page engine).
 * The *geometry* is shared — thresholds, drag-follow shaping and edge hint all come from the pure
 * functions in `:core:ui` (`core.ui.pager.PageSwipe`) — but the machinery differs on purpose
 * (ADR-013), and the topic gesture has since gained hardening this one does not reproduce (the
 * #936 multi-touch cancellation and the #752 system-gesture start dead-zone):
 *
 * - **Commit → [ThreadSwipeHandlers.onSelectPage]**, the same in-place reload the pager buttons
 *   use: the ViewModel keeps the displayed page on screen behind `isRefreshing` (keep-content load,
 *   #351a) and swaps it when the new page lands. The composition SURVIVES the page change, so there
 *   is **no slide-out**: a committed page springs back to rest and stays readable while the network
 *   round-trip runs. The "network feel" is assumed — `cat=prive` is auth-only, so there is no
 *   anonymous prefetch and (per ADR-013) no persisted cache to make the landing instant.
 * - **No per-composition re-entrance latch**: the topic latch is reset by its
 *   `pointerInput(currentPage)` re-key when the engine renders the target page (#895 étape 4 —
 *   historically by the route change tearing the composition down); here the gesture is instead
 *   gated by [ThreadSwipeHandlers.enabled] (wired to `!isRefreshing` at the call site), which re-arms
 *   naturally when the load settles. The gate is read at `down`, so a swipe during an in-flight
 *   load is inert (same UX as the disabled pager buttons would be). A commit that races the
 *   `isRefreshing` state propagation can at worst re-issue `onSelectPage` for the same target
 *   (currentPage has not changed yet); the ViewModel's load() supersedes the in-flight identical
 *   request — benign by construction.
 * - [currentPage] and [totalPages] are lambdas (backed by `rememberUpdatedState` at the call site):
 *   the in-place model changes the page UNDER a live composition, so the gesture — keyed on `Unit`,
 *   never re-keyed — must read both fresh on every frame.
 *
 * Coexistence mirrors the topic gesture: horizontal slop only (the vertical list scroll and the
 * pull-to-refresh nested-scroll are never stolen), a child consuming the horizontal drag first
 * cancels ours, edges are a damped no-op wall.
 */
internal fun Modifier.threadPageSwipe(
    currentPage: () -> Int,
    totalPages: () -> Int,
    dragOffset: MutableFloatState,
    handlers: ThreadSwipeHandlers,
): Modifier = this
    // `pointerInput` BEFORE `graphicsLayer`, same constraint as the topic gesture: deltas must be
    // read in the untranslated coordinate space (inside the translated layer each frame's
    // `positionChange()` would be Δfinger − Δtranslation → halved tracking, doubled commit
    // distance). Keyed on Unit: nothing in here ever needs a restart — pages, counts and the gate
    // are all read through live lambdas.
    .pointerInput(Unit) {
        val commitDistancePx = swipeCommitDistancePx(size.width.toFloat(), MIN_COMMIT_DISTANCE.toPx())
        val flingThresholdPx = FLING_VELOCITY_THRESHOLD.toPx()
        // Reserved for the spring-back release transition; the drag writes `dragOffset`
        // synchronously per frame (no coroutine, no allocation), the Animatable streams back into
        // the same state on release.
        val release = Animatable(0f)
        coroutineScope {
            val animationScope = this
            var releaseJob: Job? = null
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Gate at `down`: while a page load is in flight (isRefreshing) the swipe is inert,
                // and it re-arms when the load settles — the equivalent of the topic's "ignore
                // until the page change re-keys the pointerInput" (#895 étape 4).
                if (!handlers.enabled()) return@awaitEachGesture
                val velocityTracker = VelocityTracker()
                velocityTracker.addPosition(down.uptimeMillis, down.position)
                var overSlop = 0f
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, slop ->
                    change.consume()
                    overSlop = slop
                } ?: return@awaitEachGesture
                // Cancel a running spring-back deterministically at slop crossing (not at `down`,
                // which would let a mere tap interrupt it). Synchronous Job.cancel(): no late
                // fire-and-forget stop() racing a newer animation (same reasoning as #282).
                releaseJob?.cancel()
                var totalDx = overSlop
                var armed = false
                velocityTracker.addPosition(drag.uptimeMillis, drag.position)
                dragOffset.floatValue =
                    threadFollowOffset(totalDx, commitDistancePx, currentPage(), totalPages())
                val completed = horizontalDrag(drag.id) { change ->
                    totalDx += change.positionChange().x
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    val offset =
                        threadFollowOffset(totalDx, commitDistancePx, currentPage(), totalPages())
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
                    val target = forward?.let { swipeTargetPage(currentPage(), totalPages(), it) }
                    if (forward != null && target != null) {
                        handlers.haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        handlers.onSelectPage(target)
                    }
                }
                // ALWAYS spring back — commit included: with no slide-out (the composition is kept),
                // the page returns to rest and stays readable behind the refresh indicator until the
                // new page lands (the screen then scrolls to top and resets the offset).
                releaseJob = animationScope.launch {
                    release.snapTo(dragOffset.floatValue)
                    release.animateTo(0f, THREAD_SPRING_BACK) { dragOffset.floatValue = value }
                }
            }
        }
    }
    // Draw-only translation + armed elevation lift, same draw-phase contract as the topic gesture
    // (reads `dragOffset` without recomposition; the lift sells "release will turn the page").
    .graphicsLayer {
        val offset = dragOffset.floatValue
        translationX = offset
        val commitDistancePx = swipeCommitDistancePx(size.width, MIN_COMMIT_DISTANCE.toPx())
        shadowElevation =
            if (swipeArmed(offset, commitDistancePx)) THREAD_COMMIT_SHADOW_ELEVATION_DP.dp.toPx() else 0f
    }

/**
 * Non-per-frame inputs of [threadPageSwipe], bundled (same shape as `TopicSwipeHandlers`) so the
 * gesture's parameter list stays within the project's limit. [enabled] is read once per gesture at
 * `down` and re-arms when the in-flight load settles; [onSelectPage] performs the in-place page
 * change (keep-content load).
 */
internal class ThreadSwipeHandlers(
    val haptics: HapticFeedback,
    val onSelectPage: (Int) -> Unit,
    val enabled: () -> Boolean,
)

private fun threadFollowOffset(
    totalDx: Float,
    commitDistancePx: Float,
    currentPage: Int,
    totalPages: Int,
): Float {
    val hasTarget = swipeTargetPage(currentPage, totalPages, forward = totalDx < 0f) != null
    return swipeFollowOffset(totalDx, commitDistancePx, hasTarget)
}

private const val THREAD_COMMIT_SHADOW_ELEVATION_DP = 8f

private val THREAD_SPRING_BACK = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)
