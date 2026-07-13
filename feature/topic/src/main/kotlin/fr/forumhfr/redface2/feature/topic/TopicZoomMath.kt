package fr.forumhfr.redface2.feature.topic

import kotlin.math.atanh
import kotlin.math.tanh

internal const val MIN_ZOOM_SCALE = 1f
internal const val MAX_ZOOM_SCALE = 2.5f
internal const val ZOOM_RELEASE_SNAP_THRESHOLD = 1.03f

/** Maximum displayed overshoot past [MAX_ZOOM_SCALE] while the fingers keep squeezing. */
internal const val ZOOM_RUBBER_BAND_RANGE = 0.25f

// atanh(x) diverges at |x| = 1: displayed scales at the saturation asymptote are clamped just
// inside it so the inversion in pinchStep stays finite.
private const val RUBBER_BAND_INVERSION_CEILING = 0.999999f

/**
 * Display scale for the global magnifier gesture (#182).
 *
 * The floor is hard: pinching below 1× never shrinks the topic. Up to [MAX_ZOOM_SCALE], the raw
 * gesture scale is preserved. Past that ceiling, a SATURATING tanh rubber band (the PageSwipe
 * overpull pattern) bounds the displayed scale under `MAX + ZOOM_RUBBER_BAND_RANGE`: an unbounded
 * band let a hard squeeze keep growing the scale, and the centroid anchoring then kept scrolling
 * the list — the screen visibly drifted「by itself」at deep zoom (S25 field report, POC iter 1).
 * tanh is invertible on its range: [pinchStep] recovers the logical raw scale before applying the
 * next incremental zoom factor, so an already-resisted scale is not resisted a second time.
 *
 * The rubber band is visual gesture feedback, not a persisted bound. [resolveScaleOnRelease]
 * restores the 2.5× ceiling when the fingers lift.
 */
internal fun clampScaleDuringPinch(raw: Float): Float {
    require(raw.isFinite()) { "raw must be finite" }
    return when {
        raw <= MIN_ZOOM_SCALE -> MIN_ZOOM_SCALE
        raw <= MAX_ZOOM_SCALE -> raw
        else -> MAX_ZOOM_SCALE + ZOOM_RUBBER_BAND_RANGE * tanh(raw - MAX_ZOOM_SCALE)
    }
}

/**
 * Settled scale after a #182 magnifier gesture: near-rest values snap to 1× and rubber-band values
 * return to the hard 2.5× ceiling. Values between those thresholds are preserved.
 */
internal fun resolveScaleOnRelease(gestureScale: Float): Float {
    require(gestureScale.isFinite()) { "gestureScale must be finite" }
    return when {
        gestureScale <= ZOOM_RELEASE_SNAP_THRESHOLD -> MIN_ZOOM_SCALE
        gestureScale > MAX_ZOOM_SCALE -> MAX_ZOOM_SCALE
        else -> gestureScale
    }
}

/**
 * Horizontal translation allowed by #182 for a top-left transform origin. At 1×, or when
 * [widthPx] is zero, both endpoints are zero. [scale] must be at least 1× and [widthPx] non-negative.
 */
internal fun panXRange(scale: Float, widthPx: Float): ClosedFloatingPointRange<Float> {
    requireScaleAndWidth(scale, widthPx)
    return minPanX(scale, widthPx)..0f
}

/**
 * Zoom draw-layer state of the #182 magnifier: a uniform [scale] with a top-left transform origin
 * and a horizontal-only [panX] translation. There is deliberately no vertical translation — the
 * vertical axis is navigated by REAL list scrolling (the lazy list cannot reveal uncomposed items
 * through a layer translation).
 */
internal data class ZoomTransform(
    val scale: Float,
    val panX: Float,
)

/**
 * Pure result of one #182 pinch sample. [scaleNew] and [panXNew] are draw-layer values;
 * [scrollByPx] is an unscaled viewport-pixel delta for `LazyListState.scrollBy`.
 */
internal data class PinchStep(
    val scaleNew: Float,
    val panXNew: Float,
    val scrollByPx: Float,
)

/**
 * Maps one incremental pinch sample while keeping the content under the centroid anchored (#182).
 *
 * X uses the top-left layer mapping `screenX = viewportX * scale + panX`. Recovering
 * `viewportX = (centroidX - panXOld) / scaleOld` and solving at the new scale gives the corrected
 * pan. The pan is then clamped to [panXRange]; reaching an edge deliberately makes X anchoring
 * best-effort.
 *
 * Y is real list scrolling, never layer translation. A positive `scrollBy` moves content upward in
 * unscaled viewport pixels, so `(centroidY / scaleOld - scrollByPx) * scaleNew = centroidY` gives
 * `scrollByPx = centroidY / scaleOld - centroidY / scaleNew`.
 *
 * [current] carries the displayed scale. Above [MAX_ZOOM_SCALE], the linear rubber band is
 * inverted before applying [zoomFactor]; otherwise incremental samples would re-apply resistance
 * and a zoom-in could reduce the displayed scale. Inputs must be finite, the current scale at
 * least 1×, [zoomFactor] positive, and [widthPx] non-negative. Centroids may lie outside the
 * viewport.
 */
internal fun pinchStep(
    current: ZoomTransform,
    centroidX: Float,
    centroidY: Float,
    zoomFactor: Float,
    widthPx: Float,
): PinchStep {
    val scaleOld = current.scale
    val panXOld = current.panX
    requireScaleAndWidth(scaleOld, widthPx)
    require(panXOld.isFinite()) { "panXOld must be finite" }
    require(centroidX.isFinite()) { "centroidX must be finite" }
    require(centroidY.isFinite()) { "centroidY must be finite" }
    require(zoomFactor.isFinite() && zoomFactor > 0f) { "zoomFactor must be finite and positive" }

    if (zoomFactor == 1f) {
        val panX = panXOld.coerceIn(minPanX(scaleOld, widthPx), 0f)
        return PinchStep(scaleOld, panX, scrollByPx = 0f)
    }
    val scaleNew = clampScaleDuringPinch(rawScaleForDisplayedScale(scaleOld) * zoomFactor)
    val viewportX = (centroidX - panXOld) / scaleOld
    val correctedPanX = centroidX - viewportX * scaleNew
    val panXNew = correctedPanX.coerceIn(minPanX(scaleNew, widthPx), 0f)
    val scrollByPx = centroidY / scaleOld - centroidY / scaleNew
    return PinchStep(scaleNew, panXNew, scrollByPx)
}

/**
 * Applies one physical one-finger horizontal delta in the #182 zoomed state, clamped so the scaled
 * topic never exposes space outside the list width. Positive [deltaX] moves the layer right.
 */
internal fun panStep(
    panXOld: Float,
    deltaX: Float,
    scale: Float,
    widthPx: Float,
): Float {
    requireScaleAndWidth(scale, widthPx)
    require(panXOld.isFinite()) { "panXOld must be finite" }
    require(deltaX.isFinite()) { "deltaX must be finite" }
    return (panXOld + deltaX).coerceIn(minPanX(scale, widthPx), 0f)
}

private fun rawScaleForDisplayedScale(scale: Float): Float =
    if (scale <= MAX_ZOOM_SCALE) {
        scale
    } else {
        val saturation = ((scale - MAX_ZOOM_SCALE) / ZOOM_RUBBER_BAND_RANGE)
            .coerceAtMost(RUBBER_BAND_INVERSION_CEILING)
        MAX_ZOOM_SCALE + atanh(saturation)
    }

private fun minPanX(scale: Float, widthPx: Float): Float = widthPx * (MIN_ZOOM_SCALE - scale)

private fun requireScaleAndWidth(scale: Float, widthPx: Float) {
    require(scale.isFinite() && scale >= MIN_ZOOM_SCALE) { "scale must be finite and at least 1" }
    require(widthPx.isFinite() && widthPx >= 0f) { "widthPx must be finite and non-negative" }
}
