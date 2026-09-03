package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.runtime.Immutable
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.domain.preferences.MediaDisplayProfile
import fr.forumhfr.redface2.core.domain.preferences.PostImageMaxWidth
import fr.forumhfr.redface2.core.domain.preferences.SmileyPickerDecoration

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
    // #973 (§8 [AMENDEMENT-v1.5-2]) — enlargement profile of eligible block GIFs (S/M/L). Default
    // M (×1,5), the shipped choice (XaTriX 26/07); provided as LocalMediaDisplayProfile.
    val mediaDisplayProfile: MediaDisplayProfile = MediaDisplayProfile.M,
    // #991 — maximum fImage width of content images. Default P95 preserves the historical 0.95 cap
    // and is provided as LocalPostImageMaxWidth.
    val postImageMaxWidth: PostImageMaxWidth = PostImageMaxWidth.DEFAULT,
    // #989 — cell delimiter of the smiley picker. NONE by default (XaTriX): the delimiter is an aid
    // to SELECTION on a very heterogeneous corpus, never a change of thumbnail size — the preset
    // that enlarged small smileys was rejected for making the picker lie about the published size
    // (#1022). Provided as LocalSmileyPickerDecoration.
    val smileyPickerDecoration: SmileyPickerDecoration = SmileyPickerDecoration.NONE,
)
