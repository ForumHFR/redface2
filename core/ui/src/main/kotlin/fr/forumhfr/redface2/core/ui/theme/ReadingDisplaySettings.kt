package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.runtime.Immutable
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference

/**
 * Bundle of the two #287 reading presets passed to `RedfaceTheme` as a single parameter.
 *
 * Grouping [density] and [fontScale] keeps `RedfaceTheme` within detekt's `LongParameterList`
 * budget (the default threshold would otherwise trip once both presets are wired alongside
 * darkTheme / amoledTheme / dynamicColor / content). [Immutable] for Compose stability.
 */
@Immutable
data class ReadingDisplaySettings(
    val density: DisplayDensity = DisplayDensity.COMFORT,
    val fontScale: FontScalePreference = FontScalePreference.M,
    // #332 — whether a long top-level citation folds to a one-line header by default. `true`
    // (default) keeps the historical fold; `false` disables it so a long quote renders expanded.
    val foldLongQuotes: Boolean = true,
    // #105 — whether the intra-page reading scrollbar is shown. `true` (default) keeps the
    // historical ascenseur; `false` hides it entirely (topic pages AND private-message threads).
    val showScrollbar: Boolean = true,
)
