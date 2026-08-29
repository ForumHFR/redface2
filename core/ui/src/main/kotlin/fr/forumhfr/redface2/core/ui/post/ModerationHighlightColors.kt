package fr.forumhfr.redface2.core.ui.post

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import fr.forumhfr.redface2.core.ui.theme.RedfaceAmoledColorScheme

/** Opaque RF1-derived colours for a complete HFR `messageModo` post. */
@Immutable
data class ModerationHighlightColors(
    val headerContainer: Color,
    val bodyContainer: Color,
    val subSurfaceContainer: Color,
    val onModeration: Color,
    val onModerationVariant: Color,
    val linkColor: Color,
)

/**
 * Resolves the structural moderation palette from the effective Material surface.
 *
 * Dynamic colour must not redefine this structural forum signal through Material's destructive
 * `error` or `primary` roles. Pure black identifies the static Redface AMOLED scheme; every other
 * scheme follows the same surface-luminance split as the reading highlight palettes. All six
 * values are opaque so inset and full-width posts render identically over any parent.
 */
@Composable
internal fun moderationHighlightColors(): ModerationHighlightColors {
    val surface = MaterialTheme.colorScheme.surface
    return remember(surface) { moderationHighlightColors(surface = surface) }
}

/** Pure palette decision extracted for exhaustive JVM coverage. */
internal fun moderationHighlightColors(surface: Color): ModerationHighlightColors = when {
    surface == RedfaceAmoledColorScheme.surface -> ModerationHighlightColors(
        headerContainer = ModerationHeaderAmoled,
        bodyContainer = ModerationBodyAmoled,
        subSurfaceContainer = ModerationSubSurfaceAmoled,
        onModeration = ModerationOn,
        onModerationVariant = ModerationOnVariant,
        linkColor = ModerationLink,
    )
    surface.luminance() < DARK_SURFACE_LUMINANCE -> ModerationHighlightColors(
        headerContainer = ModerationHeaderDark,
        bodyContainer = ModerationBodyDark,
        subSurfaceContainer = ModerationSubSurfaceDark,
        onModeration = ModerationOn,
        onModerationVariant = ModerationOnVariant,
        linkColor = ModerationLink,
    )
    else -> ModerationHighlightColors(
        // RF1 `styles.css`, `.post.moderation` — exact mono-theme values.
        headerContainer = ModerationHeaderLight,
        bodyContainer = ModerationBodyLight,
        subSurfaceContainer = ModerationSubSurfaceLight,
        onModeration = ModerationOn,
        onModerationVariant = ModerationOnVariant,
        linkColor = ModerationLink,
    )
}

/** Internal carrier for the already-resolved palette; only a moderation reading card provides it. */
internal val LocalModerationHighlightColors =
    staticCompositionLocalOf<ModerationHighlightColors?> { null }

private val ModerationHeaderLight = Color(0xFFB71C1C)
private val ModerationBodyLight = Color(0xFFD32F2F)
private val ModerationSubSurfaceLight = Color(0xFFC62828)

// Same red family as RF1, deepened for dark surfaces while preserving the three-tone hierarchy.
private val ModerationHeaderDark = Color(0xFF7F1010)
private val ModerationBodyDark = Color(0xFF991B1B)
private val ModerationSubSurfaceDark = Color(0xFF891515)

// AMOLED is deliberately deeper than dark and remains distinct from the pure-black app surface.
private val ModerationHeaderAmoled = Color(0xFF5D0A0A)
private val ModerationBodyAmoled = Color(0xFF731010)
private val ModerationSubSurfaceAmoled = Color(0xFF661010)

private val ModerationOn = Color(0xFFFFFFFF)
private val ModerationOnVariant = Color(0xFFFFF8F8)
private val ModerationLink = Color(0xFFFFF9C4)

private const val DARK_SURFACE_LUMINANCE = 0.5f
