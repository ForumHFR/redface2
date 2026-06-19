package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity

/**
 * Structural spacing metrics driven by the [DisplayDensity] preset (#287 lot B).
 *
 * Only the listing-row and post-card paddings that govern the reading rhythm are exposed here;
 * absolute chrome dimensions (gutters, FAB clearance) stay hard-coded where they live. Marked
 * [Immutable] so Compose treats the value as stable — providing it through a CompositionLocal
 * does not churn recompositions of readers when the preset does not change.
 *
 * [Comfort] reproduces EXACTLY the paddings shipped by the #398 structural pass (lot A), so the
 * default preset is a no-op visual change relative to the current build.
 */
@Immutable
data class DisplayMetrics(
    val cardBodyHorizontal: Dp,
    val cardBodyTop: Dp,
    val cardBodyBottom: Dp,
    val cardHeaderVertical: Dp,
    val listRowVertical: Dp,
    val postSpacing: Dp,
) {
    companion object {
        /** Historical rhythm (lot A): unchanged default. */
        val Comfort = DisplayMetrics(
            cardBodyHorizontal = 12.dp,
            cardBodyTop = 10.dp,
            cardBodyBottom = 8.dp,
            cardHeaderVertical = 6.dp,
            listRowVertical = 10.dp,
            postSpacing = 8.dp,
        )

        /** Denser feed (#287 beta feedback). */
        val Compact = DisplayMetrics(
            cardBodyHorizontal = 10.dp,
            cardBodyTop = 6.dp,
            cardBodyBottom = 5.dp,
            cardHeaderVertical = 4.dp,
            listRowVertical = 6.dp,
            postSpacing = 4.dp,
        )

        fun of(density: DisplayDensity): DisplayMetrics = when (density) {
            DisplayDensity.COMFORT -> Comfort
            DisplayDensity.COMPACT -> Compact
        }
    }
}

/**
 * Project CompositionLocal carrying the resolved [DisplayMetrics]. `staticCompositionLocalOf`
 * (not `compositionLocalOf`): the value changes only when the user switches preset, so we skip
 * the fine-grained read tracking and accept a single recomposition of the subtree on change.
 * Defaults to [DisplayMetrics.Comfort] for previews / hosts that do not provide it.
 */
val LocalDisplayMetrics = staticCompositionLocalOf { DisplayMetrics.Comfort }

/**
 * Project CompositionLocal carrying the #332 « fold long quotes » reading preference. Same
 * `staticCompositionLocalOf` rationale as [LocalDisplayMetrics]: the value changes only when the
 * user flips the toggle, so the subtree recomposes once on change. Defaults to `true` (the
 * historical fold) for previews / hosts that do not provide it; `RedfaceTheme` provides the
 * resolved value from [ReadingDisplaySettings.foldLongQuotes].
 */
val LocalFoldLongQuotes = staticCompositionLocalOf { true }

/**
 * Project CompositionLocal carrying the #105 « afficher l'ascenseur » reading preference. Same
 * `staticCompositionLocalOf` rationale as [LocalFoldLongQuotes]: the value changes only when the
 * user flips the toggle, so the subtree recomposes once on change. Defaults to `true` (the
 * historical scrollbar) for previews / hosts that do not provide it; `RedfaceTheme` provides the
 * resolved value from [ReadingDisplaySettings.showScrollbar]. Read by
 * [fr.forumhfr.redface2.core.ui.list.LazyListScrollbar], which renders nothing when `false`.
 */
val LocalShowScrollbar = staticCompositionLocalOf { true }

/**
 * Project CompositionLocal asking the post renderer to IGNORE the author's inline `[color]` styling
 * for the subtree it wraps (#553). HFR signatures embed colours chosen for the white web background;
 * rendered as-is on the app theme (especially dark) they read as unreadable/garish, so the signature
 * render site provides `true` and the text falls back to the theme's neutral colour. Defaults to
 * `false` (post bodies keep author colours). `staticCompositionLocalOf`: flips rarely, scoped reads.
 */
val LocalIgnoreInlineColors = staticCompositionLocalOf { false }
