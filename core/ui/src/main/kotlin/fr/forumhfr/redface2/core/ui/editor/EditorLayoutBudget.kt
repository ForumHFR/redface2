package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Minimum draft viewport reserved by every full-screen editor. */
val EDITOR_DRAFT_MIN_HEIGHT = 160.dp

/**
 * Maximum height of the scrollable controls/chrome zone above a weighted editor field.
 *
 * On a regular window the zone receives only what remains after [fieldMin] and the inter-zone
 * spacing have been reserved. On a shorter window the controls receive one third of the available
 * height, up to the 48 dp needed to expose an action target; once that target fits, extra pixels go
 * back to the draft until [fieldMin] is restored. With 100 dp total, that leaves about 55 dp for the
 * draft after the 12 dp gap instead of the previous 48 dp from the 40/60 split. Roomy displays keep
 * the historical 360 dp cap.
 */
fun editorControlsMaxHeight(available: Dp, fieldMin: Dp): Dp {
    val safeAvailable = available.coerceAtLeast(0.dp)
    val heightAfterFieldReserve =
        (safeAvailable - fieldMin - EDITOR_ZONE_SPACING).coerceAtLeast(0.dp)
    val shortWindowFloor = (safeAvailable * EDITOR_CONTROLS_SHORT_WINDOW_FRACTION)
        .coerceAtMost(EDITOR_CONTROLS_MIN_USABLE_HEIGHT)
    return maxOf(heightAfterFieldReserve, shortWindowFloor)
        .coerceAtMost(EDITOR_CONTROLS_MAX_HEIGHT)
}

private const val EDITOR_CONTROLS_SHORT_WINDOW_FRACTION = 1f / 3f
private val EDITOR_CONTROLS_MAX_HEIGHT = 360.dp
private val EDITOR_CONTROLS_MIN_USABLE_HEIGHT = 48.dp
private val EDITOR_ZONE_SPACING = 12.dp
