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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
// translation, Y = REAL list scrolling) while swipe/PTR/double-tap/selection are suspended ;
// release ≤ 1.03 snaps back to rest ; reset on page/topic change (composition-keyed state).

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
     * Gate Sol (panY amendment) : a LazyList fling still running when the magnifier engages would
     * be a SECOND producer mutating the scroll under our dispatchRawDelta corrections. One-shot
     * cancellation via a UserInput-priority no-op scroll session.
     */
    fun stopListFling(listState: LazyListState) {
        animationScope.launch { listState.stopScroll() }
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
internal fun rememberTopicZoomState(pageKey: Int, animationScope: CoroutineScope): TopicZoomState =
    // Keyed on the loaded page: since #895 the topic composition survives page changes, so the
    // reset-on-page-change contract hangs on this key. A topic change replaces the whole route
    // (fresh composition), covering the reset-on-topic-change leg.
    remember(pageKey) { TopicZoomState(animationScope) }

/**
 * POC #182 — the magnifier gesture. Listens on the INITIAL pass so that, once a second pointer is
 * down, consuming the position changes starves every child/sibling detector in their Main pass:
 * the in-flight page swipe sees consumed changes and cancels into its spring-back (experimental
 * cancellation — the tested hardening is #936), the list stops scrolling, PTR never engages.
 * Plain taps are NOT consumed (down/up pass through), which is exactly the « nominal mode »
 * hypothesis of the #935 matrix (taps/long-press preserved at > 1×) — measured, not assumed.
 *
 * Sits BEFORE the zoom graphicsLayer in the modifier chain (same coordinate rule as
 * `topicPageSwipe`): centroids are read in the untransformed local space that `TopicZoomMath`
 * models. One state mutation set per pointer event; frame coalescing is asserted at the relevé.
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
        var engaged = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.count { it.pressed }
            if (pressed == 0) {
                // Matrix finding (bench, line « pinch après swipe armé ») : when both fingers lift
                // in the SAME frame, exiting without consuming lets a sibling detector see ITS
                // pointer released cleanly — the armed page swipe then completes and COMMITS the
                // accumulated offset. An engaged magnifier owns the whole gesture, ups included.
                if (engaged) event.changes.forEach { it.consume() }
                break
            }
            when {
                pressed >= 2 -> {
                    if (!engaged) {
                        // Engage on the SECOND pointer only: a plain tap must not freeze an
                        // in-flight snap animation, but a new pinch interrupts it (hypothesis 8).
                        state.releaseJob?.cancel()
                        state.stopListFling(listState)
                        engaged = true
                    }
                    // calculateZoom guards its degenerate cases, but a non-positive ratio would
                    // trip pinchStep's precondition mid-gesture — neutralise it defensively.
                    val zoom = event.calculateZoom().takeIf { it > 0f } ?: 1f
                    // PREVIOUS centroid (gate Sol r1) : pinchStep anchors around the pre-move
                    // position, then the centroid translation is applied once through `pan`.
                    // Anchoring on the CURRENT centroid would re-inject the translation with the
                    // wrong base (1×→2×, centroid 400→500 : panX must be −300, not −400) and the
                    // matrix would measure a pinch that slides under the fingers.
                    val centroid = event.calculateCentroid(useCurrent = false)
                    val pan = event.calculatePan()
                    val scaleOld = state.scale.floatValue
                    val panYOld = state.panY.floatValue
                    val zoomStep = pinchStep(
                        current = state.currentTransform(),
                        centroidX = centroid.x,
                        centroidY = centroid.y,
                        zoomFactor = zoom,
                        widthPx = size.width.toFloat(),
                    )
                    // X anchoring + new scale come from the tested math; the step's scrollByPx is
                    // superseded by the panY-aware drift below (bottom-edge contract amendment).
                    state.apply(zoomStep)
                    val scale = state.scale.floatValue
                    state.panX.floatValue =
                        panStep(state.panX.floatValue, pan.x, scale, size.width.toFloat())
                    // Y drift of the content point under the (previous) centroid, panY included:
                    // where it would land minus the finger — moved back up, finger pan folded in.
                    val viewportY = (centroid.y - panYOld) / scaleOld
                    val drift = viewportY * scale + state.panY.floatValue - centroid.y
                    state.moveContentUp(drift - pan.y, listState)
                    state.lastAnchorX = centroid.x + pan.x
                    state.lastAnchorY = centroid.y + pan.y
                    event.changes.forEach { it.consume() }
                }
                engaged || state.zoomed -> {
                    // One-finger pan while zoomed — and, once ENGAGED, capture is held until the
                    // last `up` even if the scale came back to exactly 1× mid-gesture (gate Sol
                    // r1) : releasing the consumption there would let swipe/scroll/PTR re-engage
                    // in the middle of the same physical gesture. At 1× the controlled path
                    // degrades gracefully (panX clamped to 0, dispatchRawDelta 1:1).
                    if (!engaged) {
                        state.releaseJob?.cancel()
                        state.stopListFling(listState)
                        engaged = true
                    }
                    val change = event.changes.first { it.pressed }
                    val delta = change.position - change.previousPosition
                    val scale = state.scale.floatValue
                    state.panX.floatValue =
                        panStep(state.panX.floatValue, delta.x, scale, size.width.toFloat())
                    state.moveContentUp(-delta.y, listState)
                    state.lastAnchorX = change.position.x
                    state.lastAnchorY = change.position.y
                    event.changes.forEach { it.consume() }
                }
                else -> {
                    // 1× single pointer: observe WITHOUT consuming — every existing gesture stays
                    // intact, and a second pointer landing mid-drag (armed swipe included) still
                    // upgrades this same gesture into a pinch on the next event.
                }
            }
        }
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
