package fr.forumhfr.redface2.core.domain.preferences

/** Complete colour preferences consumed by the app-root theme. */
data class ThemeColorPreferences(
    val accent: ThemeAccent = ThemeAccent.Preset(AccentPreset.ROSE),
    val lightSurfaceTone: LightSurfaceTone = LightSurfaceTone.REDFACE1_GRAY,
    val darkSurfaceTone: DarkSurfaceTone = DarkSurfaceTone.MATERIAL_TINTED,
    val dynamicColorEnabled: Boolean = false,
)
