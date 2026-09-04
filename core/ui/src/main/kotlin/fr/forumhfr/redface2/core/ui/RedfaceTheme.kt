package fr.forumhfr.redface2.core.ui

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import fr.forumhfr.redface2.core.domain.preferences.AccentColor
import fr.forumhfr.redface2.core.domain.preferences.DarkSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.LightSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.ThemeAccent
import fr.forumhfr.redface2.core.domain.preferences.ThemeColorPreferences
import fr.forumhfr.redface2.core.domain.preferences.toAccentPreset
import fr.forumhfr.redface2.core.ui.browser.LocalAlwaysAskLinkApp
import fr.forumhfr.redface2.core.ui.theme.DisplayMetrics
import fr.forumhfr.redface2.core.ui.theme.LocalDisplayMetrics
import fr.forumhfr.redface2.core.ui.theme.LocalFoldLongQuotes
import fr.forumhfr.redface2.core.ui.theme.LocalMediaDisplayProfile
import fr.forumhfr.redface2.core.ui.theme.LocalPostImageCorners
import fr.forumhfr.redface2.core.ui.theme.LocalPostImageMaxWidth
import fr.forumhfr.redface2.core.ui.theme.LocalSmileyPickerDecoration
import fr.forumhfr.redface2.core.ui.theme.LocalShowScrollbar
import fr.forumhfr.redface2.core.ui.theme.ReadingDisplaySettings
import fr.forumhfr.redface2.core.ui.theme.RedfaceColorSchemeOptions
import fr.forumhfr.redface2.core.ui.theme.RedfaceShapes
import fr.forumhfr.redface2.core.ui.theme.RedfaceTypography
import fr.forumhfr.redface2.core.ui.theme.buildRedfaceColorScheme
import fr.forumhfr.redface2.core.ui.theme.scaledForReading
import fr.forumhfr.redface2.core.ui.theme.withRedfaceSlateTertiary
import fr.forumhfr.redface2.core.ui.theme.withRedfaceSurfaceTones

@Composable
// LongParameterList: a theme composable legitimately takes several orthogonal, defaulted inputs
// (legacy dark/amoled/accent, colour bundle, reading bundle, chooser policy, content), each with
// a distinct call-site. Same stance as MaterialTheme; PR2 can drop the legacy colour parameters.
@Suppress("LongParameterList")
fun RedfaceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoledTheme: Boolean = false,
    // TU 2788511 — accent colour family (rose by default, vivid « REDFACE1 » red on request).
    accentColor: AccentColor = AccentColor.ROSE,
    themeColorPreferences: ThemeColorPreferences? = null,
    // #287 — reading presets (density + font scale) bundled into one param to keep the parameter
    // list within detekt's LongParameterList budget.
    reading: ReadingDisplaySettings = ReadingDisplaySettings(),
    // #1207 — chooser policy is global and read by the external-link menu leaves.
    alwaysAskLinkApp: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val resolvedColorPreferences = themeColorPreferences ?: legacyThemeColorPreferences(
        amoledTheme = amoledTheme,
        accentColor = accentColor,
        dynamicColor = dynamicColor,
    )
    val colorScheme = remember(context, darkTheme, resolvedColorPreferences) {
        redfaceColorScheme(context, darkTheme, resolvedColorPreferences)
    }

    CompositionLocalProvider(
        LocalDisplayMetrics provides DisplayMetrics.of(reading.density),
        // #332 — expose the « fold long quotes » preference to the post renderer (read via
        // LocalFoldLongQuotes.current in QuoteBlock) so flipping the toggle re-renders posts.
        LocalFoldLongQuotes provides reading.foldLongQuotes,
        // #105 — expose the « afficher l'ascenseur » preference to the reading scrollbar (read via
        // LocalShowScrollbar.current in LazyListScrollbar) so flipping the toggle hides/shows it.
        LocalShowScrollbar provides reading.showScrollbar,
        // #973 — expose the block-GIF display profile to the post renderer (read via
        // LocalMediaDisplayProfile.current in BlockImage) so switching S/M/L re-sizes eligible GIFs.
        LocalMediaDisplayProfile provides reading.mediaDisplayProfile,
        // #991 — expose the maximum fImage width to all post content-image paths.
        LocalPostImageMaxWidth provides reading.postImageMaxWidth,
        // #985 — expose the selected corner shape to all post content-image paths.
        LocalPostImageCorners provides reading.postImageCorners,
        // #989 — expose the picker's cell delimiter (read via LocalSmileyPickerDecoration.current in
        // SmileyPickerGrid) so switching the setting re-decorates the grid.
        LocalSmileyPickerDecoration provides reading.smileyPickerDecoration,
        LocalAlwaysAskLinkApp provides alwaysAskLinkApp,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            // #287 — scale the reading typography by the font-size preset (identity at M).
            typography = RedfaceTypography.scaledForReading(reading.fontScale.factor),
            shapes = RedfaceShapes,
            content = content,
        )
    }
}

/**
 * Resolves the effective [ColorScheme] from the theme inputs. Extracted from [RedfaceTheme] to keep
 * the composable simple (detekt complexity) and the precedence explicit:
 * dynamic colour (Android 12+) wins, then static legacy/manual or seeded colour generation, then
 * the requested Redface surface tone.
 */
private fun redfaceColorScheme(
    context: Context,
    darkTheme: Boolean,
    preferences: ThemeColorPreferences,
): ColorScheme = when {
    preferences.dynamicColorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        dynamicColorScheme(context, darkTheme, preferences)
    else -> buildRedfaceColorScheme(
        RedfaceColorSchemeOptions(
            accent = preferences.accent,
            darkTheme = darkTheme,
            lightSurfaceTone = preferences.lightSurfaceTone,
            darkSurfaceTone = preferences.darkSurfaceTone,
        ),
    )
}

/**
 * Static (non-dynamic) scheme resolution kept for legacy callers and JVM tests.
 *
 * ROSE + AMOLED preserves the historical manual AMOLED scheme. Other AMOLED accents keep their
 * chromatic seed and only replace the dark surfaces with true black.
 */
internal fun staticColorScheme(
    darkTheme: Boolean,
    amoledTheme: Boolean,
    accentColor: AccentColor,
): ColorScheme = buildRedfaceColorScheme(
    RedfaceColorSchemeOptions(
        accent = ThemeAccent.Preset(accentColor.toAccentPreset()),
        darkTheme = darkTheme,
        lightSurfaceTone = LightSurfaceTone.MATERIAL_TINTED,
        darkSurfaceTone = if (amoledTheme) DarkSurfaceTone.AMOLED else DarkSurfaceTone.MATERIAL_TINTED,
    ),
)

/**
 * Dynamic colour supplies chromatic roles; Redface still owns the surface tone and slate tertiary.
 */
@RequiresApi(Build.VERSION_CODES.S)
private fun dynamicColorScheme(
    context: Context,
    darkTheme: Boolean,
    preferences: ThemeColorPreferences,
): ColorScheme {
    val scheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    return scheme
        .withRedfaceSurfaceTones(
            darkTheme = darkTheme,
            lightSurfaceTone = preferences.lightSurfaceTone,
            darkSurfaceTone = preferences.darkSurfaceTone,
        )
        .withRedfaceSlateTertiary(darkTheme)
}

private fun legacyThemeColorPreferences(
    amoledTheme: Boolean,
    accentColor: AccentColor,
    dynamicColor: Boolean,
): ThemeColorPreferences = ThemeColorPreferences(
    accent = ThemeAccent.Preset(accentColor.toAccentPreset()),
    lightSurfaceTone = LightSurfaceTone.MATERIAL_TINTED,
    darkSurfaceTone = if (amoledTheme) DarkSurfaceTone.AMOLED else DarkSurfaceTone.MATERIAL_TINTED,
    dynamicColorEnabled = dynamicColor,
)

/**
 * MainActivity cold-start bridge: resolve the same first-frame background as [RedfaceTheme] from
 * the synchronous bootstrap mirror before the first composition exists.
 */
fun redfaceBootstrapWindowBackground(
    context: Context,
    darkTheme: Boolean,
    preferences: ThemeColorPreferences,
): androidx.compose.ui.graphics.Color = redfaceColorScheme(context, darkTheme, preferences).background
