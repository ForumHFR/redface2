package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Minimum draft viewport reserved by every full-screen editor. */
val EDITOR_DRAFT_MIN_HEIGHT = 160.dp

/**
 * Maximum height of the scrollable controls/chrome zone above a weighted editor field.
 *
 * On a regular window the zone receives only what remains after [fieldMin] and the inter-zone
 * spacing have been reserved. On a window too short to keep both useful, controls receive 40% of
 * the available height: metadata stays reachable while the draft retains the larger 60% share.
 * Roomy displays keep the historical 360 dp cap.
 */
fun editorControlsMaxHeight(available: Dp, fieldMin: Dp): Dp {
    val safeAvailable = available.coerceAtLeast(0.dp)
    val heightAfterFieldReserve =
        (safeAvailable - fieldMin - EDITOR_ZONE_SPACING).coerceAtLeast(0.dp)
    val shortWindowShare = safeAvailable * EDITOR_CONTROLS_SHORT_WINDOW_FRACTION
    return maxOf(heightAfterFieldReserve, shortWindowShare)
        .coerceAtMost(EDITOR_CONTROLS_MAX_HEIGHT)
}

private const val EDITOR_CONTROLS_SHORT_WINDOW_FRACTION = 0.4f
private val EDITOR_CONTROLS_MAX_HEIGHT = 360.dp
private val EDITOR_ZONE_SPACING = 12.dp
