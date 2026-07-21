package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * #876/#957 (Lot 1B) — block-image COLD cap per the frozen contract v1.4 §3
 * ([AMENDEMENT-Lot0-3], formulation Sol) : the HOST-side composable reads the window
 * container and the per-edge occupied insets, the PURE functions below do the arithmetic
 * (JVM-testable, no Compose types). Applied to the COLD §6 slot ONLY in this lot — the
 * MEASURED path shares this clamped cap since #959 (Lot 3)
 * (cadrage #957 r4, résolution minimale actée r5).
 */

/** `hauteurUtilePx = max(0, container − union(top) − union(bottom))` — no intermediate rounding. */
internal fun usefulWindowHeightPx(containerHeightPx: Int, topInsetPx: Int, bottomInsetPx: Int): Int =
    (containerHeightPx - topInsetPx - bottomInsetPx).coerceAtLeast(0)

/** `capBlocPx = min(hauteurUtilePx, max(400dp→px, 0,5 × hauteurUtilePx))`. */
internal fun blockImageColdCapPx(usefulHeightPx: Int, floor400DpPx: Int): Int =
    minOf(usefulHeightPx, maxOf(floor400DpPx, (usefulHeightPx * 0.5f).roundToInt()))

/** Cold slot §6 : width = 0,9 × available ; height = min(capBloc, max(160 dp, 0,75 × width)). */
internal fun coldBlockSlotDp(availableWidthDp: Float, capBlocDp: Float): Pair<Float, Float> {
    val width = availableWidthDp * COLD_BLOCK_WIDTH_FRACTION
    val height = minOf(capBlocDp, maxOf(COLD_BLOCK_MIN_HEIGHT_DP, width * COLD_BLOCK_RATIO))
    return width to height
}

// #959/[AMENDEMENT-v1.5-1] — locked alias of the dedicated image width fraction (single source
// of truth; the equality is pinned by ImageDisplaySizePolicyTest).
internal const val COLD_BLOCK_WIDTH_FRACTION = IMAGE_RELATIVE_MAX_WIDTH_FRACTION
internal const val COLD_BLOCK_MIN_HEIGHT_DP = 160f
internal const val COLD_BLOCK_RATIO = 0.75f

/**
 * Host-side reader : container height from [LocalWindowInfo] (the WINDOW, correct in
 * split-screen — mesuré E11 : 301 dp utiles sur S10e en split), insets union PER EDGE
 * (`max(systemBars, displayCutout)` top and bottom — overlapping zones are not deducted
 * twice). Returns the cap in Dp units (Float) for the cold slot computation.
 */
@Composable
internal fun rememberBlockImageColdCapDp(): Float {
    val density = LocalDensity.current
    val containerHeightPx = LocalWindowInfo.current.containerSize.height
    val systemBars = WindowInsets.systemBars
    val cutout = WindowInsets.displayCutout
    val topPx = maxOf(systemBars.getTop(density), cutout.getTop(density))
    val bottomPx = maxOf(systemBars.getBottom(density), cutout.getBottom(density))
    val floorPx = with(density) { 400.dp.roundToPx() }
    val capPx = blockImageColdCapPx(
        usefulHeightPx = usefulWindowHeightPx(containerHeightPx, topPx, bottomPx),
        floor400DpPx = floorPx,
    )
    return with(density) { capPx.toDp().value }
}
