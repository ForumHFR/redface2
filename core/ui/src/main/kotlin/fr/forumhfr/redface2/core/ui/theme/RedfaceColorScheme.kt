package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeTonalSpot
import fr.forumhfr.redface2.core.domain.preferences.AccentPreset
import fr.forumhfr.redface2.core.domain.preferences.DarkSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.LightSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.ThemeAccent
import fr.forumhfr.redface2.core.domain.preferences.seedArgb

internal enum class RedfacePaletteStyle { TONAL_SPOT }

internal data class RedfaceColorSchemeOptions(
    val accent: ThemeAccent = ThemeAccent.Preset(AccentPreset.ROSE),
    val darkTheme: Boolean = false,
    val lightSurfaceTone: LightSurfaceTone = LightSurfaceTone.MATERIAL_TINTED,
    val darkSurfaceTone: DarkSurfaceTone = DarkSurfaceTone.MATERIAL_TINTED,
    val generation: RedfaceColorGenerationOptions = RedfaceColorGenerationOptions(),
)

internal data class RedfaceColorGenerationOptions(
    val paletteStyle: RedfacePaletteStyle = RedfacePaletteStyle.TONAL_SPOT,
    val contrastLevel: Double = DEFAULT_CONTRAST_LEVEL,
)

/** Pure JVM colour-scheme builder for static Redface palettes. */
internal fun buildRedfaceColorScheme(options: RedfaceColorSchemeOptions): ColorScheme {
    legacyColorScheme(options)?.let { return it }
    return generatedColorScheme(options)
        .withRedfaceSurfaceTones(
            darkTheme = options.darkTheme,
            lightSurfaceTone = options.lightSurfaceTone,
            darkSurfaceTone = options.darkSurfaceTone,
        )
        .withRedfaceSlateTertiary(options.darkTheme)
}

private fun legacyColorScheme(options: RedfaceColorSchemeOptions): ColorScheme? {
    val preset = (options.accent as? ThemeAccent.Preset)?.preset ?: return null
    return LegacyColorSchemes[options.toLegacySchemeKey(preset)]
}

private fun RedfaceColorSchemeOptions.toLegacySchemeKey(preset: AccentPreset): LegacySchemeKey =
    if (darkTheme) {
        LegacySchemeKey(preset = preset, darkTheme = true, darkSurfaceTone = darkSurfaceTone)
    } else {
        LegacySchemeKey(preset = preset, darkTheme = false, lightSurfaceTone = lightSurfaceTone)
    }

private data class LegacySchemeKey(
    val preset: AccentPreset,
    val darkTheme: Boolean,
    val lightSurfaceTone: LightSurfaceTone? = null,
    val darkSurfaceTone: DarkSurfaceTone? = null,
)

private val LegacyColorSchemes = mapOf(
    LegacySchemeKey(
        preset = AccentPreset.ROSE,
        darkTheme = false,
        lightSurfaceTone = LightSurfaceTone.MATERIAL_TINTED,
    ) to RedfaceLightColorScheme,
    LegacySchemeKey(
        preset = AccentPreset.ROSE,
        darkTheme = true,
        darkSurfaceTone = DarkSurfaceTone.MATERIAL_TINTED,
    ) to RedfaceDarkColorScheme,
    LegacySchemeKey(
        preset = AccentPreset.ROSE,
        darkTheme = true,
        darkSurfaceTone = DarkSurfaceTone.AMOLED,
    ) to RedfaceAmoledColorScheme,
    LegacySchemeKey(
        preset = AccentPreset.ROUGE_REDFACE1,
        darkTheme = false,
        lightSurfaceTone = LightSurfaceTone.MATERIAL_TINTED,
    ) to RedfaceRedLightColorScheme,
    LegacySchemeKey(
        preset = AccentPreset.ROUGE_REDFACE1,
        darkTheme = true,
        darkSurfaceTone = DarkSurfaceTone.MATERIAL_TINTED,
    ) to RedfaceRedDarkColorScheme,
)

private fun generatedColorScheme(options: RedfaceColorSchemeOptions): ColorScheme {
    val scheme = dynamicScheme(options)
    return if (options.darkTheme) {
        darkColorScheme(
            primary = scheme.primary.toColor(),
            onPrimary = scheme.onPrimary.toColor(),
            primaryContainer = scheme.primaryContainer.toColor(),
            onPrimaryContainer = scheme.onPrimaryContainer.toColor(),
            secondary = scheme.secondary.toColor(),
            onSecondary = scheme.onSecondary.toColor(),
            secondaryContainer = scheme.secondaryContainer.toColor(),
            onSecondaryContainer = scheme.onSecondaryContainer.toColor(),
            tertiary = scheme.tertiary.toColor(),
            onTertiary = scheme.onTertiary.toColor(),
            tertiaryContainer = scheme.tertiaryContainer.toColor(),
            onTertiaryContainer = scheme.onTertiaryContainer.toColor(),
            error = scheme.error.toColor(),
            onError = scheme.onError.toColor(),
            errorContainer = scheme.errorContainer.toColor(),
            onErrorContainer = scheme.onErrorContainer.toColor(),
            background = scheme.background.toColor(),
            onBackground = scheme.onBackground.toColor(),
            surface = scheme.surface.toColor(),
            onSurface = scheme.onSurface.toColor(),
            surfaceVariant = scheme.surfaceVariant.toColor(),
            onSurfaceVariant = scheme.onSurfaceVariant.toColor(),
            outline = scheme.outline.toColor(),
            outlineVariant = scheme.outlineVariant.toColor(),
            scrim = scheme.scrim.toColor(),
            inverseSurface = scheme.inverseSurface.toColor(),
            inverseOnSurface = scheme.inverseOnSurface.toColor(),
            inversePrimary = scheme.inversePrimary.toColor(),
            surfaceDim = scheme.surfaceDim.toColor(),
            surfaceBright = scheme.surfaceBright.toColor(),
            surfaceContainerLowest = scheme.surfaceContainerLowest.toColor(),
            surfaceContainerLow = scheme.surfaceContainerLow.toColor(),
            surfaceContainer = scheme.surfaceContainer.toColor(),
            surfaceContainerHigh = scheme.surfaceContainerHigh.toColor(),
            surfaceContainerHighest = scheme.surfaceContainerHighest.toColor(),
            surfaceTint = scheme.surfaceTint.toColor(),
        )
    } else {
        lightColorScheme(
            primary = scheme.primary.toColor(),
            onPrimary = scheme.onPrimary.toColor(),
            primaryContainer = scheme.primaryContainer.toColor(),
            onPrimaryContainer = scheme.onPrimaryContainer.toColor(),
            secondary = scheme.secondary.toColor(),
            onSecondary = scheme.onSecondary.toColor(),
            secondaryContainer = scheme.secondaryContainer.toColor(),
            onSecondaryContainer = scheme.onSecondaryContainer.toColor(),
            tertiary = scheme.tertiary.toColor(),
            onTertiary = scheme.onTertiary.toColor(),
            tertiaryContainer = scheme.tertiaryContainer.toColor(),
            onTertiaryContainer = scheme.onTertiaryContainer.toColor(),
            error = scheme.error.toColor(),
            onError = scheme.onError.toColor(),
            errorContainer = scheme.errorContainer.toColor(),
            onErrorContainer = scheme.onErrorContainer.toColor(),
            background = scheme.background.toColor(),
            onBackground = scheme.onBackground.toColor(),
            surface = scheme.surface.toColor(),
            onSurface = scheme.onSurface.toColor(),
            surfaceVariant = scheme.surfaceVariant.toColor(),
            onSurfaceVariant = scheme.onSurfaceVariant.toColor(),
            outline = scheme.outline.toColor(),
            outlineVariant = scheme.outlineVariant.toColor(),
            scrim = scheme.scrim.toColor(),
            inverseSurface = scheme.inverseSurface.toColor(),
            inverseOnSurface = scheme.inverseOnSurface.toColor(),
            inversePrimary = scheme.inversePrimary.toColor(),
            surfaceDim = scheme.surfaceDim.toColor(),
            surfaceBright = scheme.surfaceBright.toColor(),
            surfaceContainerLowest = scheme.surfaceContainerLowest.toColor(),
            surfaceContainerLow = scheme.surfaceContainerLow.toColor(),
            surfaceContainer = scheme.surfaceContainer.toColor(),
            surfaceContainerHigh = scheme.surfaceContainerHigh.toColor(),
            surfaceContainerHighest = scheme.surfaceContainerHighest.toColor(),
            surfaceTint = scheme.surfaceTint.toColor(),
        )
    }
}

private fun dynamicScheme(options: RedfaceColorSchemeOptions): DynamicScheme = when (options.generation.paletteStyle) {
    RedfacePaletteStyle.TONAL_SPOT -> SchemeTonalSpot(
        sourceColorHct = Hct.fromInt(options.accent.seedArgb()),
        isDark = options.darkTheme,
        contrastLevel = options.generation.contrastLevel,
    )
}

private fun Int.toColor(): Color = Color(toLong() and ARGB_LONG_MASK)

private const val DEFAULT_CONTRAST_LEVEL = 0.0
private const val ARGB_LONG_MASK = 0xFFFF_FFFFL
