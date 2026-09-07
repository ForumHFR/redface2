package fr.forumhfr.redface2.core.ui.zoom

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// #182/#1040 — shared global ephemeral magnifier, initially proven by the topic POC #935 and its
// production tranche #937, then extracted without behavioural changes for the MP reader. Contract:
// 1× = every existing gesture intact ; PINCHING = a second pointer cancels any uncommitted swipe,
// scale anchors the content under the centroid ; ZOOMED = one-finger pan past touch slop
// (X = bounded layer translation, Y = real list scrolling + bounded panY complement at the
// bottom edge) while swipe/PTR/double-tap/native list scrolling are suspended; taps and
// long-presses stay owned by transformed children ; release ≤ 1.03 snaps back to rest ; reset on
// page AND reader change (the feature owns and supplies the full route identity key).

private const val ZOOM_SNAP_MILLIS = 200

// v1.1 damped Y fling (community feedback on 0.30.0 : the zoomed pan felt « collant »). STRONG
// friction — the sold contract is « a quick, bounded glide », deliberately shorter than a list
// fling ; XaTriX pinned the axis (Y only : the X range is ~1.5 screens at 3×, a glide there
// would just slam the bound). Values from the Sol framing, dp-based (density-independent).
private const val FLING_FRICTION_MULTIPLIER = 3f
private val FLING_MIN_VELOCITY = 500.dp
private val FLING_MAX_VELOCITY = 4000.dp
private val FLING_STOP_VELOCITY = 10.dp

/** Frames of ~zero EFFECTIVE displacement (list + panY both saturated) before the decay stops. */
private const val FLING_BLOCKED_FRAMES = 2

/**
 * #182/#1040 — shared magnifier state. Scale and panX are [mutableFloatStateOf] so the draw phase reads
 * them per frame without recomposition (same pattern as the swipe's dragOffset). The state lives in
 * the loaded host composition keyed on its route identity: a page or reader change resets to 1× by
 * construction (ephemeral contract — no rememberSaveable, process restore lands at 1×). The key,
 * refresh wiring, page swipe and reset chrome deliberately remain feature-owned.
 */
class PinchZoomState(private val animationScope: CoroutineScope) {
    val scale = mutableFloatStateOf(1f)
    val panX = mutableFloatStateOf(0f)

    /**
     * CONTRACT AMENDMENT (POC iter 1, S25 field report) — bounded vertical layer translation,
     * complement of the real list scroll. The pure「Y = real scrolling」model cannot show the
     * BOTTOM of the page zoomed: at max scroll the anchoring delta clamps and the last post
     * escapes below the visible `H/scale` window. panY ∈ [H×(1−scale), 0] pans across the SCALED
     * RENDER of the already-composed viewport only — the §2.1 virtualisation objection (never
     * reveal uncomposed items) stays honoured. Non-zero ONLY once the list scroll is exhausted;
     * unwound FIRST when panning back. To fold into §2.1 + PinchZoomMath tests at step 3 (#937).
     */
    val panY = mutableFloatStateOf(0f)

    /** True past the snap threshold — gates swipe/PTR/double-tap/native list scroll at call sites. */
    val zoomed: Boolean
        get() = scale.floatValue > 1f

    /** Tracks the in-flight release/reset animation so a new gesture can cancel it deterministically. */
    var releaseJob: Job? = null

    /**
     * #937 framing (Sol) — a live LazyList fling is a SECOND producer racing our controlled
     * scroll mutations. `stopScroll` is suspend and [AwaitPointerEventScope] is a RESTRICTED
     * suspension scope (the compiler forbids calling it inline in the gesture), so the stop runs
     * on [animationScope] and [moveContentUp] BUFFERS list deltas until it has completed —
     * sequenced, no delta lost, never two producers.
     */
    private var stopFlingJob: Job? = null
    private var pendingDeltaScreenPx = 0f

    /**
     * True while a magnifier gesture owns the transform (review finding) : the reset chip sits
     * OUTSIDE the gesture area, so a third finger can tap it mid-pinch — without this guard its
     * settle and the live gesture would both write scale/pan and dispatch scroll deltas.
     */
    var gestureEngaged = false
        private set

    /**
     * True while the magnifier can mutate [LazyListState]: live pinch/pan, engage-time fling stop,
     * release glide or reset settle. Feature-owned anchor reporters sample this only when a native
     * scroll stops, excluding zoom-produced coordinates without observing per-frame index/offset.
     */
    val isListPositionMutationInProgress: Boolean
        get() = gestureEngaged || stopFlingJob?.isActive == true || releaseJob?.isActive == true

    /** Called once per gesture, when the magnifier takes ownership (2nd pointer or zoomed pan). */
    fun engage(listState: LazyListState) {
        releaseJob?.cancel()
        pendingDeltaScreenPx = 0f
        gestureEngaged = true
        stopFlingJob = animationScope.launch { listState.stopScroll() }
    }

    /** Called when the gesture releases its last pointer — the settle may then take over. */
    fun releaseGesture() {
        gestureEngaged = false
    }

    /**
     * #937 framing (Sol) — the previous page's state must not keep mutating the SHARED LazyList
     * after `remember(pageKey)` replaced it : a 200 ms settle outliving its composition would
     * scroll the NEW page. Called from a DisposableEffect in [rememberPinchZoomState]. The floats
     * are also parked at rest (validation 5.5) : a cancelled mid-settle state must never be left
     * partially animated — every other cancellation path hands control to a new pilot (engage),
     * this one has none.
     */
    fun cancelJobs() {
        releaseJob?.cancel()
        stopFlingJob?.cancel()
        scale.floatValue = 1f
        panX.floatValue = 0f
        panY.floatValue = 0f
    }

    /**
     * Last gesture anchor (local, untransformed coords) + viewport size, recorded by the gesture so
     * the settle animation and the reset chip can anchor their scale change. Plain vars: they are
     * never read in composition.
     */
    var lastAnchorX = 0f
    var lastAnchorY = 0f
    var viewportWidthPx = 0f
    var viewportHeightPx = 0f

    fun currentTransform() = ZoomTransform(scale.floatValue, panX.floatValue)

    fun apply(step: PinchStep) {
        scale.floatValue = step.scaleNew
        panX.floatValue = step.panXNew
        reclampPanY()
    }

    /** The panY bound depends on the scale — re-clamp after every scale change. */
    fun reclampPanY() {
        panY.floatValue = panY.floatValue.coerceIn(viewportHeightPx * (1f - scale.floatValue), 0f)
    }

    /**
     * Moves the CONTENT up by [deltaScreenPx] screen pixels (negative = down), distributing
     * between the real list scroll (preferred — unscaled viewport pixels) and the bounded [panY]
     * complement. Upward: whatever the clamped scroll does not consume spills into panY (bottom
     * edge). Downward: panY unwinds back to 0 FIRST, then the list scrolls — panY is never left
     * engaged while scroll headroom exists in the visible direction.
     */
    /** Returns the EFFECTIVE screen displacement (list + panY) — the fling's blocked detector. */
    fun moveContentUp(deltaScreenPx: Float, listState: LazyListState): Float {
        // Fling stop still in flight : buffer instead of racing it (see [engage]).
        val stop = stopFlingJob
        if (stop != null && !stop.isCompleted) {
            pendingDeltaScreenPx += deltaScreenPx
            return 0f
        }
        val delta = deltaScreenPx + pendingDeltaScreenPx
        pendingDeltaScreenPx = 0f
        return applyContentDelta(delta, listState)
    }

    private fun applyContentDelta(deltaScreenPx: Float, listState: LazyListState): Float {
        // Delegates to the PURE PinchZoomMath vertical distribution (review finding : the tested
        // functions must be the ones the gesture executes — no hand-inlined twin).
        val s = scale.floatValue
        val panYBefore = panY.floatValue
        return if (deltaScreenPx >= 0f) {
            val consumed = listState.dispatchRawDelta(upwardListRequestViewportPx(deltaScreenPx, s))
            panY.floatValue =
                upwardPanYAfterScroll(deltaScreenPx, consumed, panY.floatValue, s, viewportHeightPx)
            consumed * s + (panYBefore - panY.floatValue)
        } else {
            val distribution =
                downwardDistribution(deltaScreenPx, panY.floatValue, s, viewportHeightPx)
            panY.floatValue = distribution.panYNew
            var consumedViewport = 0f
            if (distribution.listRequestViewportPx != 0f) {
                consumedViewport = listState.dispatchRawDelta(distribution.listRequestViewportPx)
            }
            consumedViewport * s + (panYBefore - panY.floatValue)
        }
    }

    /**
     * v1.1 — damped vertical glide after a PAN-ONLY gesture (never after a pinch : hadPinch is
     * latched in the gesture, Sol framing). Sequenced AFTER the engage-time stopScroll (join) so
     * the decay is the only scroll producer ; stops early after [FLING_BLOCKED_FRAMES] frames of
     * ~zero effective displacement (both bounds saturated). Interruption : engage() and the reset
     * chip both cancel [releaseJob] — one cosmetic decay frame may land before the cancellation
     * takes effect (main-thread sequencing), harmless because every mutation stays bounded.
     */
    fun startFling(
        velocityYPx: Float,
        stopVelocityPx: Float,
        listState: LazyListState,
    ) {
        releaseJob?.cancel()
        releaseJob = animationScope.launch {
            stopFlingJob?.join()
            var previous = 0f
            var blockedFrames = 0
            AnimationState(initialValue = 0f, initialVelocity = velocityYPx).animateDecay(
                exponentialDecay(
                    frictionMultiplier = FLING_FRICTION_MULTIPLIER,
                    absVelocityThreshold = stopVelocityPx,
                ),
            ) {
                val dy = value - previous
                previous = value
                // Finger semantics : the glide continues the finger, moveContentUp(-dy) like the
                // pan frames (moveContentUp already distributes and divides by the scale).
                val effective = moveContentUp(-dy, listState)
                if (kotlin.math.abs(effective) < 0.5f && kotlin.math.abs(dy) >= 0.5f) {
                    if (++blockedFrames >= FLING_BLOCKED_FRAMES) cancelAnimation()
                } else {
                    blockedFrames = 0
                }
            }
        }
    }

    /**
     * Animated, ANCHORED return to a settled scale: snap to 1× or back to [MAX_ZOOM_SCALE] from
     * the rubber band over [ZOOM_SNAP_MILLIS], keeping the content point under (anchorX, anchorY)
     * stationary — the naive unanchored settle shrank the content toward the top-left origin with
     * no scroll compensation, so a release from deep zoom visibly jumped (S25 field report, POC
     * iter 1). Each frame re-anchors pan X and dispatches the unscaled scroll delta, exactly the
     * per-event pinch correction. Interruptible: a new pinch cancels [releaseJob].
     */
    fun settleAnchoredTo(targetScale: Float, anchorX: Float, anchorY: Float, listState: LazyListState) {
        // The live gesture wins (review finding) : a chip tap landing mid-pinch must not start a
        // second producer — the gesture's own release will settle.
        if (gestureEngaged) return
        if (targetScale == scale.floatValue) return
        releaseJob?.cancel()
        val fromScale = scale.floatValue
        val widthPx = viewportWidthPx
        releaseJob = animationScope.launch {
            // Gate Sol : the settle dispatches scroll deltas too — never with a live fling.
            listState.stopScroll()
            val progress = Animatable(0f)
            var prevScale = fromScale
            progress.animateTo(1f, tween(ZOOM_SNAP_MILLIS, easing = LinearOutSlowInEasing)) {
                val frameScale = fromScale + (targetScale - fromScale) * value
                val viewportX = (anchorX - panX.floatValue) / prevScale
                panX.floatValue = (anchorX - viewportX * frameScale)
                    .coerceIn(panXRange(frameScale, widthPx))
                val panYOld = panY.floatValue
                scale.floatValue = frameScale
                reclampPanY()
                // Pure drift (review finding — same function the math tests pin).
                val drift =
                    anchoredVerticalDrift(anchorY, panYOld, prevScale, frameScale, panY.floatValue)
                moveContentUp(drift, listState)
                prevScale = frameScale
            }
        }
    }
}

@Composable
fun rememberPinchZoomState(pageKey: Any, animationScope: CoroutineScope): PinchZoomState {
    // The feature supplies its FULL route identity, never the page alone. DisposableEffect cancels
    // the replaced state's jobs: animationScope survives a key change, so an orphaned 200 ms settle
    // would otherwise keep scrolling the newly rendered page (#937 framing, Sol).
    val state = remember(pageKey) { PinchZoomState(animationScope) }
    DisposableEffect(state) {
        onDispose { state.cancelJobs() }
    }
    return state
}

/**
 * #182 — the magnifier gesture. Listens on the INITIAL pass so that, once a second pointer is
 * down, consuming the position changes starves every child/sibling detector in their Main pass:
 * the in-flight page swipe cancels into its spring-back (its own native multi-touch defense is
 * #936 — this consumption is defense in depth), the list stops scrolling, PTR never engages.
 * While ZOOMED, one-finger input stays transparent until movement crosses touch slop: down/up
 * pairs and long-presses keep reaching transformed child hit targets. Pan engagement is decided
 * on Main, after children: a consumed event reserves the remaining one-finger gesture for them.
 * An unclaimed pan consumes its first frame on Main and subsequent movement on Initial.
 *
 * Sits BEFORE the zoom graphicsLayer in the modifier chain (same coordinate rule as
 * the feature page swipe): centroids are read in the untransformed local space that `PinchZoomMath`
 * models. One state mutation set per pointer event.
 */
fun Modifier.pinchZoom(
    state: PinchZoomState,
    listState: LazyListState,
): Modifier = pointerInput(state) {
    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        state.viewportWidthPx = size.width.toFloat()
        state.viewportHeightPx = size.height.toFloat()
        val tracking = MagnifierGestureTracking(firstDown.position)
        val engaged = trackMagnifierGesture(state, listState, tracking)
        state.releaseGesture()
        if (!engaged) return@awaitEachGesture
        val settledScale = resolveScaleOnRelease(state.scale.floatValue)
        if (settledScale != state.scale.floatValue) {
            // Snap near-rest scales back to 1×, cap rubber-banded ones — ANCHORED on the last
            // gesture position so the settle never jumps (panX converges into [0,0] at 1×).
            state.settleAnchoredTo(
                targetScale = settledScale,
                anchorX = state.lastAnchorX,
                anchorY = state.lastAnchorY,
                listState = listState,
            )
        } else if (state.zoomed && !tracking.hadPinch) {
            // v1.1 — damped glide, PAN-ONLY gestures (Sol framing : a mixed pinch→pan gesture is
            // latched out — a fling right after a scale change is the RF1 « sucette » feel).
            val velocityY = tracking.velocityTracker.calculateVelocity().y
            val minPx = FLING_MIN_VELOCITY.toPx()
            val maxPx = FLING_MAX_VELOCITY.toPx()
            if (kotlin.math.abs(velocityY) >= minPx) {
                state.startFling(
                    velocityYPx = velocityY.coerceIn(-maxPx, maxPx),
                    stopVelocityPx = FLING_STOP_VELOCITY.toPx(),
                    listState = listState,
                )
            }
        }
    }
}

/**
 * Transform layer of the shared magnifier. Kept separate from [pinchZoom] so a feature-owned
 * page-swipe modifier can remain between the Initial-pass pointer observer and the scaled layer.
 * Reads only snapshot floats in the layer block: pinch frames do not recompose the post list.
 */
fun Modifier.pinchZoomTransform(state: PinchZoomState): Modifier = graphicsLayer {
    transformOrigin = TransformOrigin(0f, 0f)
    val zoomScale = state.scale.floatValue
    scaleX = zoomScale
    scaleY = zoomScale
    translationX = state.panX.floatValue
    translationY = state.panY.floatValue
}

/** Per-gesture tracking for slop gating and release arbitration (settle vs damped glide). */
internal class MagnifierGestureTracking(private val firstDownPosition: Offset) {
    val velocityTracker = VelocityTracker()

    /** Latched at the first two-pointer frame — a gesture that ever pinched never flings (Sol). */
    var hadPinch = false

    private var panSlopCrossed = false
    private var panBlockedByChild = false

    fun zoomedPanReady(event: PointerEvent, touchSlopPx: Float): Boolean {
        // Main-pass consumption includes selection drags, even BEFORE the pan's touch slop.
        // Latch until all pointers lift; a later unconsumed frame cannot steal the same gesture.
        panBlockedByChild = panBlockedByChild || event.changes.any { it.isConsumed }
        if (panBlockedByChild) return false
        val shouldLatchPan = !panSlopCrossed && event.changes.any { change ->
            change.pressed && (change.position - firstDownPosition).getDistance() > touchSlopPx
        }
        if (shouldLatchPan) {
            panSlopCrossed = true
            velocityTracker.resetTracking()
        }
        return panSlopCrossed
    }
}

/**
 * The magnifier event loop — Initial for pinch/engaged gestures, Main for pan arbitration after
 * children, still one mutation per pointer event. Returns whether the
 * gesture ever ENGAGED (2nd pointer, or slop-crossing one-finger pan while zoomed). Once engaged,
 * capture is held until the last `up` even if the scale came back to exactly 1× mid-gesture (gate
 * Sol r1) — and the final up frame is consumed too (bench matrix finding : both fingers lifting in
 * the SAME frame otherwise let an armed sibling swipe complete and COMMIT).
 */
private suspend fun AwaitPointerEventScope.trackMagnifierGesture(
    state: PinchZoomState,
    listState: LazyListState,
    tracking: MagnifierGestureTracking,
): Boolean {
    var engaged = false
    while (true) {
        var event = awaitPointerEvent(PointerEventPass.Initial)
        val released = event.changes.none { it.pressed }
        if (released && engaged) event.changes.forEach { it.consume() }
        if (released) return engaged
        if (!engaged && state.zoomed && event.changes.count { it.pressed } == 1) {
            event = awaitPointerEvent(PointerEventPass.Main)
        }
        engaged = handleMagnifierFrame(state, listState, event, engaged, tracking)
    }
}

/**
 * One pointer frame of the magnifier. Engages on the SECOND pointer — a plain tap must not freeze
 * an in-flight snap animation, but a new pinch interrupts it (hypothesis 8) — or after touch slop
 * on one finger while already zoomed. The fling stop is SEQUENCED with the controlled mutations by
 * the engage/buffer contract in PinchZoomState (#937 framing, Sol). A 1× single pointer observes
 * WITHOUT consuming : every existing gesture stays intact, and a second pointer landing mid-drag
 * (armed swipe included) still upgrades this same gesture into a pinch on the next event. At 1×
 * while engaged, the controlled pan degrades gracefully (panX clamped to 0, dispatchRawDelta 1:1).
 */
private fun AwaitPointerEventScope.handleMagnifierFrame(
    state: PinchZoomState,
    listState: LazyListState,
    event: PointerEvent,
    wasEngaged: Boolean,
    tracking: MagnifierGestureTracking,
): Boolean {
    val pressed = event.changes.count { it.pressed }
    val zoomedPan = !wasEngaged && state.zoomed && pressed == 1 && tracking.zoomedPanReady(
        event = event,
        touchSlopPx = viewConfiguration.touchSlop,
    )
    val engaging = pressed >= 2 || wasEngaged || zoomedPan
    if (!engaging) return false
    if (!wasEngaged) state.engage(listState)
    if (pressed >= 2) {
        if (!tracking.hadPinch) {
            // Latch + reset : no velocity inherited from before/inside the pinch (Sol framing).
            tracking.hadPinch = true
            tracking.velocityTracker.resetTracking()
        }
        applyPinchFrame(state, listState, event, size.width.toFloat())
    } else {
        applyPanFrame(state, listState, event, size.width.toFloat(), tracking)
    }
    event.changes.forEach { it.consume() }
    return true
}

/** One one-finger pan frame while zoomed: bounded panX, real list scroll on Y (÷ scale). */
private fun applyPanFrame(
    state: PinchZoomState,
    listState: LazyListState,
    event: PointerEvent,
    widthPx: Float,
    tracking: MagnifierGestureTracking,
) {
    // firstOrNull (validation 5.5) : a transient frame where every pointer just lifted must not
    // crash — the release branch of the loop handles it on the next event.
    val change = event.changes.firstOrNull { it.pressed } ?: return
    tracking.velocityTracker.addPosition(change.uptimeMillis, change.position)
    val delta = change.position - change.previousPosition
    val scale = state.scale.floatValue
    state.panX.floatValue = panStep(state.panX.floatValue, delta.x, scale, widthPx)
    state.moveContentUp(-delta.y, listState)
    state.lastAnchorX = change.position.x
    state.lastAnchorY = change.position.y
}

/**
 * One pinch frame: anchored scale step (tested math) + finger pan folded in. The PREVIOUS
 * centroid anchors the step (gate Sol r1) : pinchStep works around the pre-move position, then
 * the centroid translation is applied once through `pan` — anchoring on the CURRENT centroid
 * would re-inject the translation with the wrong base (1×→2×, centroid 400→500 : panX must be
 * −300, not −400) and the pinch would slide under the fingers. The step's scrollByPx is
 * superseded by the panY-aware drift (bottom-edge contract amendment).
 */
private fun applyPinchFrame(
    state: PinchZoomState,
    listState: LazyListState,
    event: PointerEvent,
    widthPx: Float,
) {
    // calculateZoom guards its degenerate cases, but a non-positive ratio would trip
    // pinchStep's precondition mid-gesture — neutralise it defensively.
    val zoom = event.calculateZoom().takeIf { it > 0f } ?: 1f
    val centroid = event.calculateCentroid(useCurrent = false)
    val pan = event.calculatePan()
    val scaleOld = state.scale.floatValue
    val panYOld = state.panY.floatValue
    val zoomStep = pinchStep(
        current = state.currentTransform(),
        centroidX = centroid.x,
        centroidY = centroid.y,
        zoomFactor = zoom,
        widthPx = widthPx,
    )
    state.apply(zoomStep)
    val scale = state.scale.floatValue
    state.panX.floatValue = panStep(state.panX.floatValue, pan.x, scale, widthPx)
    // Y drift of the content point under the (previous) centroid, panY included: where it
    // would land minus the finger — moved back up, finger pan folded in.
    val viewportY = (centroid.y - panYOld) / scaleOld
    val drift = viewportY * scale + state.panY.floatValue - centroid.y
    state.moveContentUp(drift - pan.y, listState)
    state.lastAnchorX = centroid.x + pan.x
    state.lastAnchorY = centroid.y + pan.y
}
