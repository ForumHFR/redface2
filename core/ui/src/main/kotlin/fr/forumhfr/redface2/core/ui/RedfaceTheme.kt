package fr.forumhfr.redface2.core.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import fr.forumhfr.redface2.core.domain.preferences.AccentColor
import fr.forumhfr.redface2.core.ui.theme.DisplayMetrics
import fr.forumhfr.redface2.core.ui.theme.LocalDisplayMetrics
import fr.forumhfr.redface2.core.ui.theme.LocalFoldLongQuotes
import fr.forumhfr.redface2.core.ui.theme.ReadingDisplaySettings
import fr.forumhfr.redface2.core.ui.theme.RedfaceAmoledColorScheme
import fr.forumhfr.redface2.core.ui.theme.RedfaceDarkColorScheme
import fr.forumhfr.redface2.core.ui.theme.RedfaceLightColorScheme
import fr.forumhfr.redface2.core.ui.theme.RedfaceRedDarkColorScheme
import fr.forumhfr.redface2.core.ui.theme.RedfaceRedLightColorScheme
import fr.forumhfr.redface2.core.ui.theme.RedfaceShapes
import fr.forumhfr.redface2.core.ui.theme.RedfaceTypography
import fr.forumhfr.redface2.core.ui.theme.scaledForReading

@Composable
// LongParameterList: a theme composable legitimately takes several orthogonal, defaulted inputs
// (dark / amoled / accent / reading bundle / dynamic / content), each with a distinct call-site —
// same stance as MaterialTheme. The reading presets are already bundled (#287); bundling the color
// flags too would not improve clarity here.
@Suppress("LongParameterList")
fun RedfaceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoledTheme: Boolean = false,
    // TU 2788511 — accent colour family (rose by default, vivid « REDFACE1 » red on request).
    accentColor: AccentColor = AccentColor.ROSE,
    // #287 — reading presets (density + font scale) bundled into one param to keep the parameter
    // list within detekt's LongParameterList budget.
    reading: ReadingDisplaySettings = ReadingDisplaySettings(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = redfaceColorScheme(context, darkTheme, amoledTheme, accentColor, dynamicColor)

    CompositionLocalProvider(
        LocalDisplayMetrics provides DisplayMetrics.of(reading.density),
        // #332 — expose the « fold long quotes » preference to the post renderer (read via
        // LocalFoldLongQuotes.current in QuoteBlock) so flipping the toggle re-renders posts.
        LocalFoldLongQuotes provides reading.foldLongQuotes,
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
 * dynamic colour (Android 12+) wins, then AMOLED dark, then the [accentColor] family, then the
 * default rose scheme. AMOLED is intentionally accent-agnostic (it is its own near-black variant).
 */
private fun redfaceColorScheme(
    context: Context,
    darkTheme: Boolean,
    amoledTheme: Boolean,
    accentColor: AccentColor,
    dynamicColor: Boolean,
): ColorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    else -> staticColorScheme(darkTheme, amoledTheme, accentColor)
}

/**
 * Static (non-dynamic) scheme resolution: AMOLED dark wins, then the [accentColor] family, then the
 * default rose scheme. AMOLED is intentionally accent-agnostic (it is its own near-black variant).
 * `internal` so it is unit-testable without an Android [Context] (the dynamic-colour branch, which
 * needs one, stays in [redfaceColorScheme]).
 */
internal fun staticColorScheme(
    darkTheme: Boolean,
    amoledTheme: Boolean,
    accentColor: AccentColor,
): ColorScheme = when {
    darkTheme && amoledTheme -> RedfaceAmoledColorScheme
    accentColor == AccentColor.ROUGE_REDFACE1 ->
        if (darkTheme) RedfaceRedDarkColorScheme else RedfaceRedLightColorScheme
    darkTheme -> RedfaceDarkColorScheme
    else -> RedfaceLightColorScheme
}
