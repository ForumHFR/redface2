package fr.forumhfr.redface2.core.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.forumhfr.redface2.core.domain.preferences.AccentPreset
import fr.forumhfr.redface2.core.domain.preferences.DarkSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.LightSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.PostHeaderEmphasis
import fr.forumhfr.redface2.core.domain.preferences.ThemeAccent
import fr.forumhfr.redface2.core.domain.preferences.ThemeBootstrap
import fr.forumhfr.redface2.core.domain.preferences.ThemeBootstrapStore
import fr.forumhfr.redface2.core.domain.preferences.ThemeColorPreferences
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ThemeBootstrapStore] over a tiny dedicated `SharedPreferences` file (#386).
 *
 * SharedPreferences (not DataStore) on purpose : the whole point of the mirror is a
 * synchronous read on the cold-start path, which DataStore cannot provide. The file holds
 * the handful of colour keys that affect the first-frame background. This does NOT reopen ADR-002
 * (credentials stay in DataStore + Keystore) — nothing sensitive lives here.
 */
@Singleton
class SharedPreferencesThemeBootstrapStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ThemeBootstrapStore {

    private val prefs by lazy { context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE) }

    override fun read(): ThemeBootstrap = ThemeBootstrap(
        // Defensive read, same stance as the repository's DataStore read : an unknown stored
        // value (downgrade, manual edit) falls back to SYSTEM instead of crashing.
        themeMode = prefs.getString(KEY_THEME_MODE, null)
            ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: ThemeMode.SYSTEM,
        accent = readThemeAccent(),
        lightSurfaceTone = prefs.getString(KEY_LIGHT_SURFACE_TONE, null)
            ?.let { stored -> LightSurfaceTone.entries.firstOrNull { it.name == stored } }
            ?: LightSurfaceTone.REDFACE1_GRAY,
        darkSurfaceTone = if (prefs.getBoolean(KEY_AMOLED_ENABLED, false)) {
            DarkSurfaceTone.AMOLED
        } else {
            DarkSurfaceTone.MATERIAL_TINTED
        },
        dynamicColorEnabled = prefs.getBoolean(KEY_DYNAMIC_COLOR_ENABLED, false),
        postHeaderEmphasis = prefs.getString(KEY_POST_HEADER_EMPHASIS, null)
            ?.let { stored -> PostHeaderEmphasis.entries.firstOrNull { it.name == stored } }
            ?: PostHeaderEmphasis.SUBTLE,
    )

    override fun writeThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    override fun writeThemeAccent(accent: ThemeAccent) {
        prefs.edit().apply {
            putThemeAccent(accent)
        }.apply()
    }

    override fun writeLightSurfaceTone(tone: LightSurfaceTone) {
        prefs.edit().putString(KEY_LIGHT_SURFACE_TONE, tone.name).apply()
    }

    override fun writeDarkSurfaceTone(tone: DarkSurfaceTone) {
        prefs.edit().putBoolean(KEY_AMOLED_ENABLED, tone == DarkSurfaceTone.AMOLED).apply()
    }

    override fun writeDynamicColorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR_ENABLED, enabled).apply()
    }

    override fun writePostHeaderEmphasis(emphasis: PostHeaderEmphasis) {
        prefs.edit().putString(KEY_POST_HEADER_EMPHASIS, emphasis.name).apply()
    }

    override fun writeThemeColorPreferences(preferences: ThemeColorPreferences) {
        prefs.edit().apply {
            putThemeAccent(preferences.accent)
            putString(KEY_LIGHT_SURFACE_TONE, preferences.lightSurfaceTone.name)
            putBoolean(KEY_AMOLED_ENABLED, preferences.darkSurfaceTone == DarkSurfaceTone.AMOLED)
            putBoolean(KEY_DYNAMIC_COLOR_ENABLED, preferences.dynamicColorEnabled)
            putString(KEY_POST_HEADER_EMPHASIS, preferences.postHeaderEmphasis.name)
        }.apply()
    }

    private fun readThemeAccent(): ThemeAccent {
        val stored = prefs.getString(KEY_ACCENT_COLOR, null)
        return when {
            stored == CUSTOM_ACCENT_STORAGE_VALUE -> readCustomAccent() ?: DEFAULT_ACCENT
            stored != null -> readPresetAccent(stored)
            else -> DEFAULT_ACCENT
        }
    }

    private fun readPresetAccent(stored: String): ThemeAccent =
        AccentPreset.entries
            .firstOrNull { it.name == stored }
            ?.let(ThemeAccent::Preset)
            ?: DEFAULT_ACCENT

    private fun readCustomAccent(): ThemeAccent.Custom? =
        prefs.getInt(KEY_ACCENT_CUSTOM_RGB, INVALID_RGB)
            .takeIf { it.isValidRgb() }
            ?.let(ThemeAccent::Custom)

    private fun android.content.SharedPreferences.Editor.putThemeAccent(accent: ThemeAccent) {
        when (accent) {
            is ThemeAccent.Preset -> {
                putString(KEY_ACCENT_COLOR, accent.preset.name)
                remove(KEY_ACCENT_CUSTOM_RGB)
            }
            is ThemeAccent.Custom -> {
                putString(KEY_ACCENT_COLOR, CUSTOM_ACCENT_STORAGE_VALUE)
                putInt(KEY_ACCENT_CUSTOM_RGB, accent.rgb)
            }
        }
    }

    private fun Int.isValidRgb(): Boolean = this in MIN_RGB..MAX_RGB

    private companion object {
        const val FILE_NAME = "theme_bootstrap"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_AMOLED_ENABLED = "amoled_enabled"
        const val KEY_ACCENT_COLOR = "accent_color"
        const val KEY_ACCENT_CUSTOM_RGB = "accent_custom_rgb"
        const val KEY_LIGHT_SURFACE_TONE = "light_surface_tone"
        const val KEY_DYNAMIC_COLOR_ENABLED = "dynamic_color_enabled"
        const val KEY_POST_HEADER_EMPHASIS = "post_header_emphasis"
        const val CUSTOM_ACCENT_STORAGE_VALUE = "CUSTOM"
        const val MIN_RGB = 0x000000
        const val MAX_RGB = 0xFFFFFF
        const val INVALID_RGB = -1

        val DEFAULT_ACCENT = ThemeAccent.Preset(AccentPreset.ROSE)
    }
}
