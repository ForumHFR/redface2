package fr.forumhfr.redface2.feature.flags

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.unit.IntOffset
import fr.forumhfr.redface2.core.ui.pager.FLING_VELOCITY_THRESHOLD
import fr.forumhfr.redface2.core.ui.pager.MIN_COMMIT_DISTANCE
import fr.forumhfr.redface2.core.ui.pager.swipeArmed
import fr.forumhfr.redface2.core.ui.pager.swipeCommitDirection
import fr.forumhfr.redface2.core.ui.pager.swipeCommitDistancePx
import fr.forumhfr.redface2.core.ui.pager.swipeFollowOffset
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
 * In-place mechanics (the composition survives the tab change). On commit the new tab's content is
 * brought in by the body's Shared Axis X [androidx.compose.animation.AnimatedContent] (see
 * [flagsTabSlide]) — it enters from the direction of travel, the old one leaves the opposite way —
 * while the residual drag offset always springs back to rest. #660 history: the first version sprang
 * the offset back WITHOUT that transition, so the incoming tab rode the released offset and slid in
 * from the wrong side; the interim fix snapped straight to rest (an abrupt « pop », styx42). With the
 * transition owning the entry side, springing the offset back is both correct and smooth.
 *
 * - **Commit → [FlagsTabSwipeHandlers.onSelectTab]** with the target index AND the swipe direction;
 *   the caller maps the index back to a `FlagTab` (the DT tab is conditional, so the index↔tab
 *   mapping lives where the tab list is built) and feeds the direction to the transition via
 *   [flagsTabSlideForward]. The ViewModel's re-tap toggle (Cyan « +lus ») is unreachable by
 *   construction: [swipeTargetIndex] never returns the current index (≥2 tabs).
 * - [currentIndex] and [tabCount] are lambdas: the selected tab changes UNDER this live
 *   composition (gesture keyed on `Unit`, never re-keyed), both must be read fresh per frame.
 * - No re-entrance latch and no enabled-gate: a tab switch is local state + cached list, there
 *   is no in-flight window to guard (the thread gesture's gate exists for its network reload).
 *
 * Coexistence contract: horizontal slop only — the vertical list scroll and the pull-to-refresh
 * nested-scroll are never stolen. This gesture only became possible once the rows' horizontal
 * `SwipeToDismissBox` (#99) was retired in this same change: a child consuming the horizontal
 * drag first cancels ours. Tabs are cyclic (#663): swiping past the last tab wraps to the first and
 * vice-versa, so there is no edge wall (every direction has a target).
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
        val release = Animatable(0f)
        coroutineScope {
            val animationScope = this
            var releaseJob: Job? = null
            awaitEachGesture {
                // Codex audit: recompute the thresholds PER GESTURE, not once at `pointerInput(Unit)`
                // start — the block is keyed on Unit and never restarts, so a one-shot `size.width`
                // read goes stale on rotation / split-screen / density change. `size` and `.toPx()`
                // are available on this gesture scope, so each gesture sizes to the current viewport.
                val commitDistancePx =
                    swipeCommitDistancePx(size.width.toFloat(), MIN_COMMIT_DISTANCE.toPx())
                val flingThresholdPx = FLING_VELOCITY_THRESHOLD.toPx()
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
                        handlers.onSelectTab(target, forward)
                    }
                }
                // #660 — always settle the drag-follow back to rest with a spring, committed or not.
                // The committed tab's entrance is owned by the Shared Axis X AnimatedContent transition
                // (it brings the new tab in from the direction of travel), so this residual offset only
                // damps out — it no longer dictates the entry side. A sub-threshold drag settles the
                // same way (the damped over-pull returns to rest).
                releaseJob = animationScope.launch {
                    release.snapTo(dragOffset.floatValue)
                    release.animateTo(0f, TAB_SPRING_BACK) { dragOffset.floatValue = value }
                }
            }
        }
    }
    // Draw-only translation (reads `dragOffset` without recomposition), same draw-phase contract as
    // the other swipe gestures. The armed `shadowElevation` lift was REMOVED (XaTriX dogfood): the
    // swipe surface is a full-bleed, background-less viewport — not a card — so an elevation cast no
    // real surface, only an ugly grey frame around the viewport bounds that travelled with the pane
    // (« surlignage extérieur mal placé »). The arm threshold is still cued by the haptic above; the
    // translationX follow + the Shared Axis X commit transition are the visual cues.
    .graphicsLayer {
        translationX = dragOffset.floatValue
    }

/**
 * Non-per-frame inputs of [flagsTabSwipe], bundled (same shape as `ThreadSwipeHandlers`) so the
 * gesture's parameter list stays within the project's limit. [onSelectTab] receives the target
 * **tab index** in the currently displayed tab list AND the swipe direction (`forward = true` when
 * swiping the content left, toward the next tab) — the latter drives the Shared Axis slide side via
 * [flagsTabSlideForward], so a cyclic wrap (#663) still enters from the correct edge.
 */
internal class FlagsTabSwipeHandlers(
    val haptics: HapticFeedback,
    val onSelectTab: (index: Int, forward: Boolean) -> Unit,
)

/**
 * Cyclic tab target (#663): flag tabs form a ring, so a forward swipe past the last tab lands on the
 * first and a backward swipe before the first lands on the last. This deliberately does NOT reuse the
 * shared `core.ui.pager.swipeTargetPage` geometry: the topic/MP pagers are hard-bounded (a topic must
 * never wrap page N → page 1), tabs wrap. Returns `null` only when there is nowhere to go (0 or 1
 * tab); with ≥2 tabs the result is never [currentIndex] (so the re-tap «+lus» toggle stays unreachable
 * by swipe, by construction).
 */
internal fun swipeTargetIndex(currentIndex: Int, tabCount: Int, forward: Boolean): Int? {
    if (tabCount <= 1) return null
    return if (forward) {
        (currentIndex + 1) % tabCount
    } else {
        (currentIndex - 1 + tabCount) % tabCount
    }
}

private fun tabFollowOffset(
    totalDx: Float,
    commitDistancePx: Float,
    currentIndex: Int,
    tabCount: Int,
): Float {
    val hasTarget = swipeTargetIndex(currentIndex, tabCount, forward = totalDx < 0f) != null
    return swipeFollowOffset(totalDx, commitDistancePx, hasTarget)
}

/**
 * #660 — direction of the Shared Axis X tab transition (`true` = the new tab enters from the right,
 * the « forward » side). A swipe carries an authoritative [swipeForward] (it knows the gesture
 * direction, so the cyclic wrap last↔first lands on the correct side — the very edge case the
 * original bug got wrong). A tab tap has no swipe direction (`swipeForward = null`); it falls back to
 * the tabs' visual order, sliding forward when moving to a later tab.
 */
internal fun flagsTabSlideForward(fromIndex: Int, toIndex: Int, swipeForward: Boolean?): Boolean =
    swipeForward ?: (toIndex >= fromIndex)

/**
 * #660 — the committed tab transition: Material « Shared Axis X » (the prototype's chosen variant,
 * styx42). The incoming tab slides in from the [forward] side while the outgoing one slides out the
 * opposite way, no fade (the panes never overlap), with the prototype's emphasized-decelerate easing.
 * Pure builder so the body's `AnimatedContent` stays declarative; the parent must `clipToBounds()` so
 * the panes don't draw past the viewport mid-slide.
 */
internal fun flagsTabSlide(forward: Boolean): ContentTransform {
    val spec = tween<IntOffset>(
        durationMillis = TAB_SLIDE_DURATION_MS,
        easing = TAB_SLIDE_EASING,
    )
    val direction = if (forward) 1 else -1
    return slideInHorizontally(spec) { fullWidth -> direction * fullWidth } togetherWith
        slideOutHorizontally(spec) { fullWidth -> -direction * fullWidth }
}

private val TAB_SPRING_BACK = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

// #660 — the prototype's chosen « slide au commit » timing: emphasized-decelerate
// cubic-bezier(.2, .8, .2, 1) over 280 ms (matches `flags-603-swipe-anim`).
private const val TAB_SLIDE_DURATION_MS = 280
private val TAB_SLIDE_EASING = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
