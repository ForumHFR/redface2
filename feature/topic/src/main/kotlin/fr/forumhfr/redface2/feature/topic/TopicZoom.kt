package fr.forumhfr.redface2.feature.topic

import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// POC #182 (issue #935) — global ephemeral magnifier, RF1 spirit. THROWAWAY BRANCH: this file
// exists to measure the §4 gate criteria (gesture matrix, P95 frames, sharpness, heap); the
// production tranche (#937) will re-derive from the POC relevé. Contract summary (§2.1):
// 1× = every existing gesture intact ; PINCHING = a second pointer cancels any uncommitted swipe,
// scale anchors the content under the centroid ; ZOOMED = one-finger pan (X = bounded layer
// translation, Y = real list scrolling + bounded panY complement at the bottom edge) while
// swipe/PTR/double-tap/selection are suspended AND taps/long-presses are deterministically INERT
// (REPLIED MODE, acted at POC iter 2 : child hit-testing does not follow the draw-only layer —
// interact = reset via the chip or the snap, then tap) ; release ≤ 1.03 snaps back to rest ;
// reset on page AND topic change (state keyed on the full route identity).

private const val ZOOM_SNAP_MILLIS = 200

/**
 * POC #182 — true while the reader is magnified (> 1×). Read by the post cards to suspend text
 * selection (the `selectable` seam) without threading a parameter through the whole call chain.
 */
internal val LocalTopicZoomed = compositionLocalOf { false }

/**
 * POC #182 — the magnifier state. Scale and panX are [mutableFloatStateOf] so the draw phase reads
 * them per frame without recomposition (same pattern as the swipe's dragOffset). The state lives in
 * the Loaded composition keyed on the page identity: a page or topic change resets to 1× by
 * construction (ephemeral contract — no rememberSaveable, process restore lands at 1×).
 */
internal class TopicZoomState(private val animationScope: CoroutineScope) {
    val scale = mutableFloatStateOf(1f)
    val panX = mutableFloatStateOf(0f)

    /**
     * CONTRACT AMENDMENT (POC iter 1, S25 field report) — bounded vertical layer translation,
     * complement of the real list scroll. The pure「Y = real scrolling」model cannot show the
     * BOTTOM of the page zoomed: at max scroll the anchoring delta clamps and the last post
     * escapes below the visible `H/scale` window. panY ∈ [H×(1−scale), 0] pans across the SCALED
     * RENDER of the already-composed viewport only — the §2.1 virtualisation objection (never
     * reveal uncomposed items) stays honoured. Non-zero ONLY once the list scroll is exhausted;
     * unwound FIRST when panning back. To fold into §2.1 + TopicZoomMath tests at step 3 (#937).
     */
    val panY = mutableFloatStateOf(0f)

    /** True past the snap threshold — gates swipe/PTR/double-tap/selection at the call sites. */
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

    /** Called once per gesture, when the magnifier takes ownership (2nd pointer or zoomed pan). */
    fun engage(listState: LazyListState) {
        releaseJob?.cancel()
        pendingDeltaScreenPx = 0f
        stopFlingJob = animationScope.launch { listState.stopScroll() }
    }

    /**
     * #937 framing (Sol) — the previous page's state must not keep mutating the SHARED LazyList
     * after `remember(pageKey)` replaced it : a 200 ms settle outliving its composition would
     * scroll the NEW page. Called from a DisposableEffect in [rememberTopicZoomState]. The floats
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
    fun moveContentUp(deltaScreenPx: Float, listState: LazyListState) {
        // Fling stop still in flight : buffer instead of racing it (see [engage]).
        val stop = stopFlingJob
        if (stop != null && !stop.isCompleted) {
            pendingDeltaScreenPx += deltaScreenPx
            return
        }
        val delta = deltaScreenPx + pendingDeltaScreenPx
        pendingDeltaScreenPx = 0f
        applyContentDelta(delta, listState)
    }

    private fun applyContentDelta(deltaScreenPx: Float, listState: LazyListState) {
        val s = scale.floatValue
        val minPanY = viewportHeightPx * (1f - s)
        if (deltaScreenPx >= 0f) {
            val consumed = listState.dispatchRawDelta(deltaScreenPx / s)
            val rest = deltaScreenPx - consumed * s
            panY.floatValue = (panY.floatValue - rest).coerceIn(minPanY, 0f)
        } else {
            val down = -deltaScreenPx
            // Both bounds on the unwind (gate Sol) : equivalent under the invariant, robust if the
            // state ever lands momentarily out of range (e.g. a reclamp raced a frame).
            val oldPanY = panY.floatValue
            val newPanY = (oldPanY + down).coerceIn(minPanY, 0f)
            val usedByPanY = newPanY - oldPanY
            panY.floatValue = newPanY
            val remaining = down - usedByPanY
            if (remaining > 0f) listState.dispatchRawDelta(-remaining / s)
        }
    }

    /**
     * Animated, ANCHORED return to a settled scale: snap to 1× or back to the 2.5× ceiling from
     * the rubber band over [ZOOM_SNAP_MILLIS], keeping the content point under (anchorX, anchorY)
     * stationary — the naive unanchored settle shrank the content toward the top-left origin with
     * no scroll compensation, so a release from deep zoom visibly jumped (S25 field report, POC
     * iter 1). Each frame re-anchors pan X and dispatches the unscaled scroll delta, exactly the
     * per-event pinch correction. Interruptible: a new pinch cancels [releaseJob].
     */
    fun settleAnchoredTo(targetScale: Float, anchorX: Float, anchorY: Float, listState: LazyListState) {
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
                val viewportY = (anchorY - panY.floatValue) / prevScale
                scale.floatValue = frameScale
                reclampPanY()
                val drift = viewportY * frameScale + panY.floatValue - anchorY
                moveContentUp(drift, listState)
                prevScale = frameScale
            }
        }
    }
}

@Composable
internal fun rememberTopicZoomState(pageKey: Any, animationScope: CoroutineScope): TopicZoomState {
    // Keyed on the FULL route identity (cat, post, page) — never the page alone : since #895 the
    // topic composition survives page changes of the SAME topic, and two topics at the same page
    // must not share a zoom (§2.1). The DisposableEffect cancels the replaced state's animation
    // jobs : they run on the composition-scoped animationScope, which SURVIVES the key change —
    // an orphaned 200 ms settle would keep scrolling the new page (#937 framing, Sol).
    val state = remember(pageKey) { TopicZoomState(animationScope) }
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
 * While ZOOMED, the down itself is consumed (REPLIED MODE) : taps and long-presses are
 * deterministically inert at > 1× — child hit-testing does not follow the draw-only layer, so a
 * zoomed tap would land on untransformed coordinates (dead at best, an invisible neighbour at
 * worst). Interaction path at > 1× = reset (chip or snap) then tap.
 *
 * Sits BEFORE the zoom graphicsLayer in the modifier chain (same coordinate rule as
 * `topicPageSwipe`): centroids are read in the untransformed local space that `TopicZoomMath`
 * models. One state mutation set per pointer event.
 */
internal fun Modifier.topicMagnifier(
    state: TopicZoomState,
    listState: LazyListState,
): Modifier = pointerInput(state) {
    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // MODE REPLIÉ (contract fallback, acted at POC iter 2 — S25 field report) : child
        // hit-testing does NOT follow the draw-only zoom layer, so a tap at >1× lands on the
        // UNTRANSFORMED coordinates — dead at best, an invisible neighbour at worst (it only
        // « worked » near 1× where both spaces almost coincide). Taps and long-presses are
        // therefore deterministically INERT while zoomed : the down itself is consumed on the
        // Initial pass, children never even start a gesture. Interaction path at >1× = reset
        // (chip or snap) then tap — announced to testers before any A delivery (§7.5).
        if (state.zoomed) firstDown.consume()
        state.viewportWidthPx = size.width.toFloat()
        state.viewportHeightPx = size.height.toFloat()
        val engaged = trackMagnifierGesture(state, listState)
        if (!engaged) return@awaitEachGesture
        // Release: snap near-rest scales back to 1×, cap rubber-banded ones — ANCHORED on the last
        // gesture position so the settle never jumps (panX converges into [0,0] on a snap to 1×).
        state.settleAnchoredTo(
            targetScale = resolveScaleOnRelease(state.scale.floatValue),
            anchorX = state.lastAnchorX,
            anchorY = state.lastAnchorY,
            listState = listState,
        )
    }
}

/**
 * The magnifier event loop — Initial pass, one iteration per pointer event. Returns whether the
 * gesture ever ENGAGED (2nd pointer, or one-finger while zoomed). Once engaged, capture is held
 * until the last `up` even if the scale came back to exactly 1× mid-gesture (gate Sol r1) — and
 * the final up frame is consumed too (bench matrix finding : both fingers lifting in the SAME
 * frame otherwise let an armed sibling swipe complete and COMMIT).
 */
private suspend fun AwaitPointerEventScope.trackMagnifierGesture(
    state: TopicZoomState,
    listState: LazyListState,
): Boolean {
    var engaged = false
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val released = event.changes.none { it.pressed }
        if (released && engaged) event.changes.forEach { it.consume() }
        if (released) return engaged
        engaged = handleMagnifierFrame(state, listState, event, engaged)
    }
}

/**
 * One pointer frame of the magnifier. Engages on the SECOND pointer — a plain tap must not freeze
 * an in-flight snap animation, but a new pinch interrupts it (hypothesis 8) — or on one finger
 * while already zoomed. The fling stop is SEQUENCED with the controlled mutations by the
 * engage/buffer contract in TopicZoomState (#937 framing, Sol). A 1× single pointer observes
 * WITHOUT consuming : every existing gesture stays intact, and a second pointer landing mid-drag
 * (armed swipe included) still upgrades this same gesture into a pinch on the next event. At 1×
 * while engaged, the controlled pan degrades gracefully (panX clamped to 0, dispatchRawDelta 1:1).
 */
private fun AwaitPointerEventScope.handleMagnifierFrame(
    state: TopicZoomState,
    listState: LazyListState,
    event: PointerEvent,
    wasEngaged: Boolean,
): Boolean {
    val pressed = event.changes.count { it.pressed }
    val engaging = pressed >= 2 || wasEngaged || state.zoomed
    if (!engaging) return false
    if (!wasEngaged) state.engage(listState)
    if (pressed >= 2) {
        applyPinchFrame(state, listState, event, size.width.toFloat())
    } else {
        applyPanFrame(state, listState, event, size.width.toFloat())
    }
    event.changes.forEach { it.consume() }
    return true
}

/** One one-finger pan frame while zoomed: bounded panX, real list scroll on Y (÷ scale). */
private fun applyPanFrame(
    state: TopicZoomState,
    listState: LazyListState,
    event: PointerEvent,
    widthPx: Float,
) {
    // firstOrNull (validation 5.5) : a transient frame where every pointer just lifted must not
    // crash — the release branch of the loop handles it on the next event.
    val change = event.changes.firstOrNull { it.pressed } ?: return
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
    state: TopicZoomState,
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
