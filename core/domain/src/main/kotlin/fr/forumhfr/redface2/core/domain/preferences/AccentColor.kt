package fr.forumhfr.redface2.core.domain.preferences

/**
 * Legacy two-choice accent enum still consumed by the current Display settings page.
 *
 * The app-root theme now uses [ThemeAccent] / [AccentPreset]. This type stays public until the
 * settings colour sub-page replaces the old rose/red selector; it maps to the same `accent_color`
 * DataStore key, so old and new callers remain coherent.
 */
enum class AccentColor {
    ROSE,
    ROUGE_REDFACE1,
}

fun AccentColor.toAccentPreset(): AccentPreset = when (this) {
    AccentColor.ROSE -> AccentPreset.ROSE
    AccentColor.ROUGE_REDFACE1 -> AccentPreset.ROUGE_REDFACE1
}

fun AccentPreset.toLegacyAccentColorOrNull(): AccentColor? = when (this) {
    AccentPreset.ROSE -> AccentColor.ROSE
    AccentPreset.ROUGE_REDFACE1 -> AccentColor.ROUGE_REDFACE1
    else -> null
}

fun ThemeAccent.toLegacyAccentColor(): AccentColor = when (this) {
    is ThemeAccent.Preset -> preset.toLegacyAccentColorOrNull() ?: AccentColor.ROSE
    is ThemeAccent.Custom -> AccentColor.ROSE
}
