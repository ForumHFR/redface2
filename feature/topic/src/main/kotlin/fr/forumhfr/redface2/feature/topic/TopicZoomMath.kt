package fr.forumhfr.redface2.feature.topic

import fr.forumhfr.redface2.core.ui.zoom.MAX_ZOOM_SCALE as SHARED_MAX_ZOOM_SCALE
import fr.forumhfr.redface2.core.ui.zoom.MIN_ZOOM_SCALE as SHARED_MIN_ZOOM_SCALE
import fr.forumhfr.redface2.core.ui.zoom.PinchStep as SharedPinchStep
import fr.forumhfr.redface2.core.ui.zoom.VerticalDistribution as SharedVerticalDistribution
import fr.forumhfr.redface2.core.ui.zoom.ZOOM_RELEASE_SNAP_THRESHOLD as SHARED_RELEASE_SNAP_THRESHOLD
import fr.forumhfr.redface2.core.ui.zoom.ZOOM_RUBBER_BAND_RANGE as SHARED_RUBBER_BAND_RANGE
import fr.forumhfr.redface2.core.ui.zoom.ZoomTransform as SharedZoomTransform
import fr.forumhfr.redface2.core.ui.zoom.anchoredVerticalDrift as sharedAnchoredVerticalDrift
import fr.forumhfr.redface2.core.ui.zoom.clampScaleDuringPinch as sharedClampScaleDuringPinch
import fr.forumhfr.redface2.core.ui.zoom.downwardDistribution as sharedDownwardDistribution
import fr.forumhfr.redface2.core.ui.zoom.panStep as sharedPanStep
import fr.forumhfr.redface2.core.ui.zoom.panXRange as sharedPanXRange
import fr.forumhfr.redface2.core.ui.zoom.panYRange as sharedPanYRange
import fr.forumhfr.redface2.core.ui.zoom.pinchStep as sharedPinchStep
import fr.forumhfr.redface2.core.ui.zoom.resolveScaleOnRelease as sharedResolveScaleOnRelease
import fr.forumhfr.redface2.core.ui.zoom.upwardListRequestViewportPx as sharedUpwardListRequestViewportPx
import fr.forumhfr.redface2.core.ui.zoom.upwardPanYAfterScroll as sharedUpwardPanYAfterScroll

// Thin compatibility façade: the existing topic math suite stays untouched and exercises the
// promoted calculations themselves. Keeping the old names here avoids rewriting evidence merely
// because its implementation crossed a module boundary.
internal const val MIN_ZOOM_SCALE = SHARED_MIN_ZOOM_SCALE
internal const val MAX_ZOOM_SCALE = SHARED_MAX_ZOOM_SCALE
internal const val ZOOM_RELEASE_SNAP_THRESHOLD = SHARED_RELEASE_SNAP_THRESHOLD
internal const val ZOOM_RUBBER_BAND_RANGE = SHARED_RUBBER_BAND_RANGE

internal typealias VerticalDistribution = SharedVerticalDistribution
internal typealias ZoomTransform = SharedZoomTransform
internal typealias PinchStep = SharedPinchStep

internal fun clampScaleDuringPinch(raw: Float): Float = sharedClampScaleDuringPinch(raw)

internal fun resolveScaleOnRelease(gestureScale: Float): Float =
    sharedResolveScaleOnRelease(gestureScale)

internal fun panXRange(scale: Float, widthPx: Float): ClosedFloatingPointRange<Float> =
    sharedPanXRange(scale, widthPx)

internal fun panYRange(
    scale: Float,
    viewportHeightPx: Float,
): ClosedFloatingPointRange<Float> = sharedPanYRange(scale, viewportHeightPx)

internal fun upwardListRequestViewportPx(deltaScreenPx: Float, scale: Float): Float =
    sharedUpwardListRequestViewportPx(deltaScreenPx, scale)

internal fun upwardPanYAfterScroll(
    deltaScreenPx: Float,
    consumedViewportPx: Float,
    panYOld: Float,
    scale: Float,
    viewportHeightPx: Float,
): Float = sharedUpwardPanYAfterScroll(
    deltaScreenPx,
    consumedViewportPx,
    panYOld,
    scale,
    viewportHeightPx,
)

internal fun downwardDistribution(
    deltaScreenPx: Float,
    panYOld: Float,
    scale: Float,
    viewportHeightPx: Float,
): VerticalDistribution = sharedDownwardDistribution(
    deltaScreenPx,
    panYOld,
    scale,
    viewportHeightPx,
)

internal fun anchoredVerticalDrift(
    anchorY: Float,
    panYOld: Float,
    scaleOld: Float,
    scaleNew: Float,
    panYNew: Float,
): Float = sharedAnchoredVerticalDrift(anchorY, panYOld, scaleOld, scaleNew, panYNew)

internal fun pinchStep(
    current: ZoomTransform,
    centroidX: Float,
    centroidY: Float,
    zoomFactor: Float,
    widthPx: Float,
): PinchStep = sharedPinchStep(current, centroidX, centroidY, zoomFactor, widthPx)

internal fun panStep(
    panXOld: Float,
    deltaX: Float,
    scale: Float,
    widthPx: Float,
): Float = sharedPanStep(panXOld, deltaX, scale, widthPx)
