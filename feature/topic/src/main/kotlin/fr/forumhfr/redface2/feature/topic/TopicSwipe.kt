package fr.forumhfr.redface2.feature.topic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// The swipe *geometry* (thresholds, drag-follow shaping, edge-hint alpha) lives in
// `:core:ui` (`core.ui.pager.PageSwipe`), shared with the private-message thread (#351,
// ADR-013). This file keeps only the topic-specific gesture MACHINERY, which is
// intrinsically coupled to the route-driven pagination model: the re-entrance latch and
// the slide-out below are reset by the route change destroying this composition — an
// in-place pager (the MP thread) must re-arm its own latch instead.

private const val COMMIT_SLIDE_OUT_MILLIS = 200 // page slide-out before navigation on commit (decelerated)

// A committed page slides fully off-screen before navigating; the elevation lift sells "the page
// leaves". Both are read at draw time from the same offset state the drag writes.
private const val COMMIT_SHADOW_ELEVATION_DP = 8f

private val SPRING_BACK = spring<Float>(
    // Crisp, non-bouncy return when a drag does NOT commit (cancel / fling-less release): the page
    // snaps back to rest without the soft overshoot used for a blocked edge, so a cancel reads as a
    // deliberate "no" rather than a wobble.
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

/**
 * Horizontal swipe → change topic page (#282, Option A) with drag-follow feedback (b). A committed
 * left/right swipe slides the current page off-screen then calls [onOpenPage] (the existing
 * route-driven navigation: pop+push of `TopicRoute`), reusing the whole pagination/prefetch
 * machinery — no neighbour page is composed, so the « prefetch non authentifié » invariant is never
 * touched.
 *
 * Per-frame follow is **synchronous and allocation-free**: the gesture writes the shaped offset into
 * [dragOffset] (a [MutableFloatState]) directly in the `horizontalDrag` callback, and the draw phase
 * reads it via `graphicsLayer { translationX = … }`. An [Animatable] is used ONLY for the two release
 * transitions (spring-back on cancel/no-commit, slide-out on commit); it streams its value back into
 * the same [dragOffset] so the draw phase never has to know which drives it. No `snapTo`/coroutine is
 * launched per drag event.
 *
 * On commit the page first animates off-screen (translationX → ±width, a short [tween]) and only then
 * navigates, softening the **departure**. The incoming page then appears via an instant
 * `TopicRoute → TopicRoute` NavDisplay transition wired in `:app` navigation (see #282), which also
 * collapses the swipe dead-zone — not a generic slide-in. Otherwise (no-commit, edge, or a child taking
 * the drag) it springs back to rest. Haptics: a tick when the swipe arms (crosses the commit distance,
 * once per rising edge) and a confirm on commit.
 *
 * Coexistence (unchanged from the discrete version, validated with Codex gpt-5.5):
 * - it engages only on **horizontal** touch slop, so the vertical `LazyColumn` scroll is never stolen;
 * - a child that consumes the horizontal drag first (the page-grid's `horizontalScroll`) cancels our
 *   slop detection / `horizontalDrag`, so it keeps its own gesture and the page springs back;
 * - at the edges (target is `null`) the gesture is a damped no-op (no navigation, no flash);
 * - exactly one [onOpenPage] per committing gesture: once a commit starts its slide-out, a
 *   re-entrance latch ignores any further gesture until the route change tears this modifier down,
 *   so a second swipe landing inside the slide-out window can never fire a duplicate navigation.
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
    totalPages: () -> Int,
    dragOffset: MutableFloatState,
    handlers: TopicSwipeHandlers,
): Modifier = this
    // `pointerInput` sits BEFORE `graphicsLayer` on purpose: the gesture must read finger deltas in
    // the untranslated coordinate space. If it were inside the translated layer, each frame's
    // `positionChange()` would be `Δfinger − Δtranslation`, halving the tracking and doubling the
    // effective commit distance. `graphicsLayer` (draw-only) leaves the hit-test bounds put, so the
    // finger stays over the page while the page visibly follows.
    //
    // Keyed on `currentPage` ONLY — never `totalPages`. A commit defers `onOpenPage` by
    // COMMIT_SLIDE_OUT_MILLIS inside this block's `coroutineScope`; if `totalPages` were part of the
    // key, a background refresh changing the page count (cache→network, or a new post) during that
    // slide-out window would restart `pointerInput`, cancel the scope and drop `onOpenPage` — a silent
    // nav failure with a snap-back. `totalPages` is instead read fresh through the `totalPages()`
    // lambda (backed by `rememberUpdatedState` at the call site), so the gesture always sees the live
    // count without ever re-keying.
    .pointerInput(currentPage) {
        val commitDistancePx = swipeCommitDistancePx(size.width.toFloat(), MIN_COMMIT_DISTANCE.toPx())
        val flingThresholdPx = FLING_VELOCITY_THRESHOLD.toPx()
        val widthPx = size.width.toFloat()
        // Reserved for release transitions ONLY (spring-back / slide-out). The drag itself writes
        // `dragOffset` synchronously; this `Animatable` streams its value back into the same state.
        val release = Animatable(0f)
        coroutineScope {
            val animationScope = this
            // Re-entrance latch: a committed swipe defers `onOpenPage` by COMMIT_SLIDE_OUT_MILLIS (the
            // slide-out), during which `awaitEachGesture` has already rebooted and is armed for a new
            // `down` while the outgoing composition is NOT yet replaced. Without this guard, a second
            // commit landing in that window could fire a second `onOpenPage` on the stale composition
            // (currentPage unchanged) → a duplicate pop+push / phantom back-stack entry, or silently
            // drop the first navigation. Once any commit starts the slide-out we ignore further
            // gestures until this pointerInput is torn down by the route change — so `onOpenPage` fires
            // exactly once.
            var committed = false
            // Tracks the in-flight release transition (spring-back / slide-out) so a new drag can
            // cancel it DETERMINISTICALLY and SYNCHRONOUSLY (see the slop branch below). Replaces the
            // former fire-and-forget `launch { release.stop() }`, whose dispatch order was not
            // guaranteed: a late `stop()` could cancel the WRONG (newer) animation — including a commit
            // slide-out before its `onOpenPage` fired, leaving `committed` latched true forever and the
            // page frozen off-screen. Cancelling this exact job — and only behind the `committed` guard
            // — means a committing slide-out is never interrupted, so navigation always completes.
            var releaseJob: Job? = null
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (committed) return@awaitEachGesture
                // Ignore the gesture while this nav entry is NOT settled (lifecycle < RESUMED), i.e.
                // mid NavDisplay transition. A freshly-Loaded incoming page (served from cache) would
                // otherwise accept a swipe DURING the transition and commit a SECOND `onOpenPage`
                // mid-flight, interrupting the transition and freezing the screen — the per-composition
                // `committed` latch cannot span the inter-page transition (the incoming page is a fresh
                // composition with its own latch). Gating at `down`, before the gesture arms/follows, is
                // required: merely dropping `onOpenPage` after the slide-out would still park the page
                // off-screen (the very freeze). See #282. (A `down` that lands while still RESUMED but
                // whose slop is crossed mid-transition is safe: the `committed` latch blocks a 2nd fire.)
                if (!handlers.enabled()) return@awaitEachGesture
                val velocityTracker = VelocityTracker()
                velocityTracker.addPosition(down.uptimeMillis, down.position)
                var overSlop = 0f
                // Horizontal slop only: a vertical-dominant drag (list scroll) never reaches this
                // branch; a child that already consumed the horizontal move makes this return null.
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, slop ->
                    change.consume()
                    overSlop = slop
                } ?: return@awaitEachGesture
                // Cancel the previous release transition deterministically — AT slop crossing, not at
                // `down` (a tap / vertical scroll that never crosses horizontal slop returned above and
                // must NOT interrupt a running spring-back). `Job.cancel()` is not `suspend`, so it runs
                // in-order right here, before this drag writes `dragOffset` below — there is no late,
                // out-of-order `stop()` that could kill a newer animation. The drag then owns
                // `dragOffset` synchronously each frame; the next release transition (set at gesture
                // end) streams into it again. A committed slide-out never reaches this line (the
                // `committed` latch returned at the top of the gesture), so a commit's `onOpenPage`
                // always fires.
                releaseJob?.cancel()
                var totalDx = overSlop
                var armed = false
                velocityTracker.addPosition(drag.uptimeMillis, drag.position)
                dragOffset.floatValue = followOffsetFor(totalDx, commitDistancePx, currentPage, totalPages())
                // `horizontalDrag` returns false when the drag is CANCELLED — e.g. a descendant
                // horizontal scroller (a wide `[fixed]` code block, the page grid) takes the pointer
                // over after we crossed slop. Honour it: a taken-over gesture must NOT navigate.
                val completed = horizontalDrag(drag.id) { change ->
                    totalDx += change.positionChange().x
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    val offset = followOffsetFor(totalDx, commitDistancePx, currentPage, totalPages())
                    dragOffset.floatValue = offset // synchronous, no coroutine, no allocation
                    val nowArmed = swipeArmed(offset, commitDistancePx)
                    if (nowArmed && !armed) handlers.haptics.performHapticFeedback(ARMED_HAPTIC)
                    armed = nowArmed
                    change.consume()
                }
                if (!completed) {
                    releaseJob = springBackTo(animationScope, release, dragOffset)
                    return@awaitEachGesture
                }
                val velocityX = velocityTracker.calculateVelocity().x
                val forward = swipeCommitDirection(totalDx, velocityX, commitDistancePx, flingThresholdPx)
                val target = forward?.let { swipeTargetPage(currentPage, totalPages(), it) }
                if (forward != null && target != null) {
                    // Latch BEFORE the deferred slide-out so any gesture starting in the slide-out
                    // window is ignored (see `committed` declaration) — no second `onOpenPage`.
                    committed = true
                    handlers.haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    // Slide in the commit direction (forward = next = leftward = negative), not the
                    // residual offset sign: a fast fling can commit with a near-zero/opposite offset.
                    val targetX = if (forward) -widthPx else widthPx
                    releaseJob = commitSlideOut(animationScope, release, dragOffset, targetX) {
                        handlers.onOpenPage(target)
                    }
                } else {
                    releaseJob = springBackTo(animationScope, release, dragOffset)
                }
            }
        }
    }
    // Draw-only translation of the page content + a slight elevation lift so a committing page reads
    // as leaving. After `pointerInput` (see above) so the gesture is read in untranslated space;
    // reading `dragOffset.floatValue` here keeps the follow on the draw phase (no recomposition per
    // frame). The lift is gated on the ARMED state (offset past the commit distance), not on any
    // offset: an exploratory nudge or the damped wall at a blocked edge must NOT float, so the shadow
    // sells the commit ("this page will leave") rather than mere exploration.
    .graphicsLayer {
        val offset = dragOffset.floatValue
        translationX = offset
        val commitDistancePx = swipeCommitDistancePx(size.width, MIN_COMMIT_DISTANCE.toPx())
        shadowElevation = if (swipeArmed(offset, commitDistancePx)) COMMIT_SHADOW_ELEVATION_DP.dp.toPx() else 0f
    }

/**
 * The non-per-frame inputs of [topicPageSwipe], bundled so the gesture's parameter list stays within
 * the project's limit. [enabled] is read once per gesture (at `down`) and gates the whole gesture off
 * while this nav entry is mid-transition (lifecycle < RESUMED, see #282); [haptics] fires on arm and
 * commit; [onOpenPage] performs the route-driven page change exactly once per committed swipe.
 */
internal class TopicSwipeHandlers(
    val haptics: HapticFeedback,
    val onOpenPage: (Int) -> Unit,
    val enabled: () -> Boolean,
)

/** Spring the page back to rest (cancel / no-commit), streaming the animation into [dragOffset]. */
private fun springBackTo(
    scope: CoroutineScope,
    release: Animatable<Float, *>,
    dragOffset: MutableFloatState,
): Job = scope.launch {
    release.snapTo(dragOffset.floatValue)
    release.animateTo(0f, SPRING_BACK) { dragOffset.floatValue = value }
}

/**
 * Slide the committed page fully off-screen to [targetX] (±width, chosen by the commit direction) over
 * [COMMIT_SLIDE_OUT_MILLIS], then navigate exactly once via [onCommitted]. The navigation replaces the
 * screen (the offset state is discarded with it), so there is nothing to reset afterwards. Streaming
 * into [dragOffset] keeps the draw phase agnostic of what drives the offset.
 */
private fun commitSlideOut(
    scope: CoroutineScope,
    release: Animatable<Float, *>,
    dragOffset: MutableFloatState,
    targetX: Float,
    onCommitted: () -> Unit,
): Job = scope.launch {
    release.snapTo(dragOffset.floatValue)
    release.animateTo(targetX, tween(durationMillis = COMMIT_SLIDE_OUT_MILLIS, easing = LinearOutSlowInEasing)) {
        dragOffset.floatValue = value
    }
    onCommitted()
}

private val ARMED_HAPTIC = HapticFeedbackType.GestureThresholdActivate

private fun followOffsetFor(totalDx: Float, commitDistancePx: Float, currentPage: Int, totalPages: Int): Float {
    val hasTarget = swipeTargetPage(currentPage, totalPages, forward = totalDx < 0f) != null
    return swipeFollowOffset(totalDx, commitDistancePx, hasTarget)
}
