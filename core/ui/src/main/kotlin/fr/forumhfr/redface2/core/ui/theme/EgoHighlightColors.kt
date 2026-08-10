package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Opaque containers used by the EgoQuote and EgoPost reading highlights. */
@Immutable
data class EgoHighlightColors(
    val quoteContainer: Color,
    val postContainer: Color,
)

/**
 * Resolves the Ego containers from the effective Material surface.
 *
 * RedfaceTheme does not expose its AMOLED flag in composition. Its pure-black surface is unique
 * among the static schemes, so it is the stable discriminator here; the remaining schemes follow
 * the same surface-luminance split as CreatorHighlight. Every returned colour is opaque so a
 * highlight looks identical in an inset card and over the full-width topic background.
 */
@Composable
fun egoHighlightColors(): EgoHighlightColors {
    val surface = MaterialTheme.colorScheme.surface
    return remember(surface) { egoHighlightColors(surface = surface) }
}

/** Pure palette decision extracted for exhaustive JVM coverage. */
internal fun egoHighlightColors(surface: Color): EgoHighlightColors = when {
    surface == RedfaceAmoledColorScheme.surface -> EgoHighlightColors(
        quoteContainer = EgoQuoteContainerAmoled,
        postContainer = EgoPostContainerAmoled,
    )
    surface.luminance() < DARK_SURFACE_LUMINANCE -> EgoHighlightColors(
        quoteContainer = EgoQuoteContainerDark,
        postContainer = EgoPostContainerDark,
    )
    else -> EgoHighlightColors(
        quoteContainer = EgoQuoteContainerLight,
        postContainer = EgoPostContainerLight,
    )
}

/**
 * Canonical pseudo of the authenticated topic reader when EgoQuote is enabled, otherwise `null`.
 * The topic body is the only production provider; MP threads, signatures and editor previews keep
 * the safe `null` default. Static like every other reading local of this module
 * ([fr.forumhfr.redface2.core.ui.theme.LocalBlockedQuoteAuthors],
 * [fr.forumhfr.redface2.core.ui.theme.LocalFoldLongQuotes]): the value only flips on
 * login/logout or a preference toggle, and reads are scoped to quote frames. A static local
 * invalidates the provider's content on a flip, which RECOMPOSES it — it does not recreate the
 * subtree, so `rememberSaveable` fold state survives either way.
 */
val LocalEgoQuotePseudo = staticCompositionLocalOf<String?> { null }

// #874 — opaque EgoPost/EgoQuote containers. They live here rather than in `Color.kt` for the same
// reason as `FlagPalette` and `CreatorHighlight`: these are optional feature surfaces, not semantic
// Material roles consumed app-wide, and the file that owns the resolution owns the values. Opaque
// on purpose — a raw alpha would composite differently over the card and over the full-width
// background, so the same highlight would not match between the two display modes.
private val EgoPostContainerLight = Color(0xFFE4EDFF)
private val EgoQuoteContainerLight = Color(0xFFEDE7FF)
private val EgoPostContainerDark = Color(0xFF16233A)
private val EgoQuoteContainerDark = Color(0xFF241C3D)
private val EgoPostContainerAmoled = Color(0xFF0A1526)
private val EgoQuoteContainerAmoled = Color(0xFF150F28)

private const val DARK_SURFACE_LUMINANCE = 0.5f
