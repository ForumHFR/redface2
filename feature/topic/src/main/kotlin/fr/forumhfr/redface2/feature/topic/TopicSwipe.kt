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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.tanh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * ([flingThresholdPx]) threshold. Direction priority is **distance first, then velocity**: once the
 * drag has travelled past [commitDistancePx] it is "armed" and the page-follow, edge glow and arming
 * haptic have already committed the user to the direction of [totalDx] — so the commit MUST follow
 * that direction even if the finger flicked back the other way at lift-off (a fast reverse on release
 * would otherwise open the opposite page and contradict every bit of feedback the user just saw).
 * Velocity decides ONLY for a short gesture that never crossed the distance threshold (a quick flick
 * that lifts on the wrong side of its start still goes the way it was thrown). A near-zero orientation
 * never picks an unstable sign. Negative = leftward drag = next page (geometric, see [topicPageSwipe]).
 */
internal fun swipeCommitDirection(
    totalDx: Float,
    velocityX: Float,
    commitDistancePx: Float,
    flingThresholdPx: Float,
): Boolean? {
    val committedByDistance = abs(totalDx) >= commitDistancePx
    val committedByFling = abs(velocityX) >= flingThresholdPx
    val orientation = when {
        committedByDistance -> totalDx
        committedByFling -> velocityX
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
 * - [hasTarget] = true (a page exists that way): track the finger 1:1 up to [commitDistancePx]
 *   (`commitDistancePx` is the visual "armed" point the edge hint mirrors), then a **bounded
 *   overpull** — a `tanh` asymptote that lets the page keep giving a little while it can never drift
 *   past `commitDistancePx + OVERPULL_MAX_FRACTION × commitDistancePx`. The old linear-with-resistance
 *   ramp grew without bound on a long drag, pulling the page arbitrarily far off-screen; the asymptote
 *   keeps the gesture readable as "armed, will commit" instead of "I am tearing the page off".
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
            val overpull = commitDistancePx * OVERPULL_MAX_FRACTION
            val excess = magnitude - commitDistancePx
            commitDistancePx + overpull * tanh(excess / overpull)
        }
    } else {
        minOf(magnitude * EDGE_RESISTANCE, commitDistancePx * EDGE_MAX_FRACTION)
    }
    return sign * travel
}

/**
 * Whether the swipe has crossed the commit distance, i.e. releasing now would change page. Pure →
 * unit-tested. Drives the haptic "armed" tick (fired once on the rising edge, see [topicPageSwipe]);
 * the edge-hint mirrors the same threshold continuously via [swipeEdgeHintAlpha]. Mirrors the
 * distance arm of [swipeCommitDirection]; the fling arm can still commit a shorter drag (no static
 * "armed" state for a fling, which is decided only at release).
 */
internal fun swipeArmed(offsetPx: Float, commitDistancePx: Float): Boolean =
    commitDistancePx > 0f && abs(offsetPx) >= commitDistancePx

/**
 * Edge-glow opacity for a swipe [progress] = `|offset| / commitDistance` (`0f` at rest, `1f` at the
 * commit/armed point, up to `1f + OVERPULL_MAX_FRACTION` — currently `1.5f` — in the bounded overpull
 * region). Pure → unit-tested.
 *
 * **Late-start** so the glow is a "you're about to commit" confirmation, not a decoration that
 * occupies the screen from the first pixel: it stays fully invisible below [EDGE_HINT_START_PROGRESS]
 * (the page already follows the finger, which conveys direction early). Three segments, continuous at
 * `progress = 1f` so the brighten is finger-driven (no separate animation primitive in the draw phase,
 * which reads state synchronously without recomposition):
 * - `0f..EDGE_HINT_START_PROGRESS`: nothing (`0f`).
 * - `EDGE_HINT_START_PROGRESS..1f`: ramp to [EDGE_HINT_MAX_ALPHA] as the swipe nears arming.
 * - past `1f`: a quick further brighten to [EDGE_HINT_ARMED_ALPHA] over the [EDGE_HINT_ARMED_RAMP]
 *   window, so crossing the threshold visibly intensifies the glow — the user sees that releasing will
 *   validate, in lock-step with the arming haptic tick.
 */
internal fun swipeEdgeHintAlpha(progress: Float): Float =
    when {
        progress <= EDGE_HINT_START_PROGRESS -> 0f
        progress <= 1f -> {
            val ramp = (progress - EDGE_HINT_START_PROGRESS) / (1f - EDGE_HINT_START_PROGRESS)
            ramp * EDGE_HINT_MAX_ALPHA
        }
        else -> {
            val armedProgress = ((progress - 1f) / EDGE_HINT_ARMED_RAMP).coerceAtMost(1f)
            EDGE_HINT_MAX_ALPHA + (EDGE_HINT_ARMED_ALPHA - EDGE_HINT_MAX_ALPHA) * armedProgress
        }
    }

// Commit thresholds: a swipe changes page only past a clear intent, never on the bare touch slop.
private const val COMMIT_FRACTION = 0.20f
internal val MIN_COMMIT_DISTANCE = 72.dp
private val FLING_VELOCITY_THRESHOLD = 900.dp

// Drag-follow feel (#282 (b)+(c)). All purely visual — they never affect the commit decision.
// Past the commit point the follow saturates: `tanh` asymptote bounded to this × commit distance so
// the page can never drift arbitrarily far on a long drag.
private const val OVERPULL_MAX_FRACTION = 0.5f
private const val EDGE_RESISTANCE = 0.30f // damping into a blocked edge (first/last page)
private const val EDGE_MAX_FRACTION = 0.4f // a blocked edge can travel at most this × commit distance
private const val EDGE_HINT_WIDTH_FRACTION = 0.10f // edge-glow band width, as a fraction of the page…
private val EDGE_HINT_MAX_WIDTH = 40.dp // …but never wider than this, so it reads as an edge accent
private const val EDGE_HINT_START_PROGRESS = 0.65f // glow stays invisible below this fraction of commit
private const val EDGE_HINT_MAX_ALPHA = 0.2f // edge-glow opacity right at the arming point
private const val EDGE_HINT_ARMED_ALPHA = 0.3f // brighter edge-glow once the swipe is fully armed
private const val EDGE_HINT_ARMED_RAMP = 0.15f // overpull-progress window over which the armed glow fills
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
 * navigates, softening the **departure** of the old instant cut (the incoming page still hard-appears
 * via the route change until the NavDisplay slide-in follow-up (a) lands — out of scope here, it would
 * touch `:app`/navigation). Otherwise (no-commit, edge, or a child taking the drag) it springs back to
 * rest. Haptics: a tick when the swipe arms (crosses the commit distance, once per rising edge) and a
 * confirm on commit.
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

/**
 * Edge hint for the topic page swipe (#282 (c)). Draws a glow on the edge the neighbour page is being
 * pulled in from — right edge for a leftward drag (next page), left edge for a rightward drag — whose
 * opacity ramps with the swipe's progress toward the commit distance and brightens to an armed accent
 * ([swipeEdgeHintAlpha]) once the swipe is armed, so the user sees that releasing will validate. It
 * reads [dragOffset] at draw time only, so following the finger never recomposes.
 *
 * The glow is the "release brings the neighbour page" affordance, so it is **suppressed at a blocked
 * edge**: when the dragged direction has no target page ([currentPage]/[totalPages] = first/last), the
 * damped wall already communicates the boundary and lighting a glow there would contradict it. Only a
 * direction that can actually commit gets the hint.
 *
 * Per-frame work is **allocation-free**: the two edge brushes (left / right, baked at full opacity,
 * Transparent → [accent]) and the band geometry are cached in [drawWithCache], keyed on size; the
 * per-frame draw block only reads the offset, computes the opacity and calls `drawRect(brush, …,
 * alpha = …)` on the matching sub-rect. Only the edge band (a [EDGE_HINT_WIDTH_FRACTION] sub-rect of
 * the page) is painted — never a full-screen brush, never a brush re-allocated per frame.
 *
 * Must sit **before** [topicPageSwipe] in the modifier chain: it draws in the element's own
 * (untranslated) space, so the glow stays pinned to the screen edge while the page itself is
 * translated by the `graphicsLayer` that [topicPageSwipe] adds further down the chain. [accent] is
 * read from the theme by the (composable) caller and passed in, since a draw scope cannot.
 */
internal fun Modifier.topicPageSwipeEdge(
    currentPage: Int,
    totalPages: () -> Int,
    dragOffset: MutableFloatState,
    accent: Color,
    enabled: () -> Boolean,
): Modifier = drawWithCache {
    // Cache (per size) what does not vary per frame: the band geometry and the two full-opacity edge
    // brushes. The per-frame opacity is applied via `drawRect`'s `alpha`, so neither brush nor the
    // colors `List` is re-allocated while the finger moves.
    val commitDistancePx = swipeCommitDistancePx(size.width, MIN_COMMIT_DISTANCE.toPx())
    val band = minOf(size.width * EDGE_HINT_WIDTH_FRACTION, EDGE_HINT_MAX_WIDTH.toPx())
    val rightTopLeft = Offset(size.width - band, 0f)
    val bandSize = Size(band, size.height)
    val rightBrush = Brush.horizontalGradient(
        colors = listOf(Color.Transparent, accent),
        startX = size.width - band,
        endX = size.width,
    )
    val leftBrush = Brush.horizontalGradient(
        colors = listOf(accent, Color.Transparent),
        startX = 0f,
        endX = band,
    )
    onDrawWithContent {
        drawContent()
        // No glow while this nav entry is mid-transition (lifecycle < RESUMED): the gesture is gated
        // off then (see topicPageSwipe), and an exiting page parked off-screen must not keep its glow.
        if (!enabled()) return@onDrawWithContent
        val offset = dragOffset.floatValue
        if (offset == 0f || commitDistancePx <= 0f) return@onDrawWithContent
        val leftward = offset < 0f
        // Suppress the glow when this direction is a blocked edge (no neighbour page to bring in).
        if (swipeTargetPage(currentPage, totalPages(), forward = leftward) == null) return@onDrawWithContent
        val alpha = swipeEdgeHintAlpha(abs(offset) / commitDistancePx)
        if (leftward) {
            drawRect(brush = rightBrush, topLeft = rightTopLeft, size = bandSize, alpha = alpha)
        } else {
            drawRect(brush = leftBrush, topLeft = Offset.Zero, size = bandSize, alpha = alpha)
        }
    }
}
