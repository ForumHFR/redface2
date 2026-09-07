package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.preferences.LightSurfaceTone

/** #1297 — automatic reading-tile outline, resolved once by RedfaceTheme for posts and quotes. */
internal val LocalReadingTileOutline = staticCompositionLocalOf<BorderStroke?> { null }

/** Use the selected tone, not surface luminance: other light palettes must remain borderless. */
internal fun tileOutlineFor(
    tone: LightSurfaceTone,
    darkTheme: Boolean,
    outlineColor: Color,
): BorderStroke? = if (!darkTheme && tone == LightSurfaceTone.WHITE) {
    BorderStroke(width = 1.dp, color = outlineColor)
} else {
    null
}
