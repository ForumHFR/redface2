package fr.forumhfr.redface2.core.ui.post

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import fr.forumhfr.redface2.core.ui.theme.RedfaceAmoledColorScheme

/**
 * Resolves the opaque whole-post container used for HFR `messageModo` rows.
 *
 * Dynamic colour must not redefine this structural forum signal through Material's destructive
 * `error` role. The effective surface is the complete input: pure black identifies Redface AMOLED,
 * while every other scheme follows the same luminance split as the reading highlight palettes.
 */
@Composable
internal fun moderationHighlightColor(): Color {
    val surface = MaterialTheme.colorScheme.surface
    return remember(surface) { moderationHighlightColor(surface = surface) }
}

/** Pure palette decision extracted for exhaustive JVM coverage. */
internal fun moderationHighlightColor(surface: Color): Color = when {
    surface == RedfaceAmoledColorScheme.surface -> ModerationContainerAmoled
    surface.luminance() < DARK_SURFACE_LUMINANCE -> ModerationContainerDark
    else -> ModerationContainerLight
}

// #1112 — desaturated pink containers, deliberately distinct from EgoPost blue and EgoQuote
// violet. Opaque on purpose: the same token covers inset and full-width posts without depending on
// the background below it. AMOLED is darker than dark while retaining contrast with `onSurface`.
private val ModerationContainerLight = Color(0xFFF5DDE2)
private val ModerationContainerDark = Color(0xFF3A242B)
private val ModerationContainerAmoled = Color(0xFF241218)

private const val DARK_SURFACE_LUMINANCE = 0.5f
