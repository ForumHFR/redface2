package fr.forumhfr.redface2.core.domain.preferences

/**
 * Last persisted theme selection, readable SYNCHRONOUSLY during the cold-start window (#386).
 *
 * DataStore's first emission can take over a second on a cold start ; until it lands the app
 * used to render with the [ThemeMode.SYSTEM] default, flashing the OS theme at any user who
 * forced the opposite one (light flash on a forced-dark app under a light OS). The window
 * background and the first composition both need the effective theme immediately, hence this
 * mirror. Defaults match [ThemeColorPreferences] until the user changes the theme.
 */
data class ThemeBootstrap(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: ThemeAccent = ThemeAccent.Preset(AccentPreset.ROSE),
    val lightSurfaceTone: LightSurfaceTone = LightSurfaceTone.REDFACE1_GRAY,
    val darkSurfaceTone: DarkSurfaceTone = DarkSurfaceTone.MATERIAL_TINTED,
    val dynamicColorEnabled: Boolean = false,
    val postHeaderEmphasis: PostHeaderEmphasis = PostHeaderEmphasis.SUBTLE,
) {
    constructor(
        themeMode: ThemeMode,
        amoledEnabled: Boolean,
    ) : this(
        themeMode = themeMode,
        darkSurfaceTone = if (amoledEnabled) DarkSurfaceTone.AMOLED else DarkSurfaceTone.MATERIAL_TINTED,
    )

    val amoledEnabled: Boolean
        get() = darkSurfaceTone == DarkSurfaceTone.AMOLED

    val colorPreferences: ThemeColorPreferences
        get() = ThemeColorPreferences(
            accent = accent,
            lightSurfaceTone = lightSurfaceTone,
            darkSurfaceTone = darkSurfaceTone,
            dynamicColorEnabled = dynamicColorEnabled,
            postHeaderEmphasis = postHeaderEmphasis,
        )
}

/**
 * Synchronous mirror of the persisted theme preferences (#386). Written by the preferences
 * repository on every theme change (and backfilled from the observed DataStore value, so
 * users who picked their theme before the mirror existed converge on first launch) ; read
 * on the cold-start path BEFORE the first DataStore emission. DataStore stays the source of
 * truth — if the two ever diverge (partial restore, cleared mirror), the DataStore value
 * wins as soon as it is hydrated. Colour preferences are mirrored too because custom seeds,
 * dynamic colour and surface tones can change the first-frame window background.
 *
 * Writes are PER KEY on purpose : theme mode, accent and surface toggles can be persisted from
 * independent coroutines, and a read-modify-write of the whole set could lose another field.
 */
interface ThemeBootstrapStore {
    fun read(): ThemeBootstrap
    fun writeThemeMode(mode: ThemeMode)
    fun writeThemeAccent(accent: ThemeAccent) = Unit
    fun writeLightSurfaceTone(tone: LightSurfaceTone) = Unit
    fun writeDarkSurfaceTone(tone: DarkSurfaceTone) = Unit
    fun writeDynamicColorEnabled(enabled: Boolean) = Unit
    fun writePostHeaderEmphasis(emphasis: PostHeaderEmphasis) = Unit

    fun writeThemeColorPreferences(preferences: ThemeColorPreferences) {
        writeThemeAccent(preferences.accent)
        writeLightSurfaceTone(preferences.lightSurfaceTone)
        writeDarkSurfaceTone(preferences.darkSurfaceTone)
        writeDynamicColorEnabled(preferences.dynamicColorEnabled)
        writePostHeaderEmphasis(preferences.postHeaderEmphasis)
    }

    fun writeAmoledEnabled(enabled: Boolean) {
        writeDarkSurfaceTone(if (enabled) DarkSurfaceTone.AMOLED else DarkSurfaceTone.MATERIAL_TINTED)
    }
}
