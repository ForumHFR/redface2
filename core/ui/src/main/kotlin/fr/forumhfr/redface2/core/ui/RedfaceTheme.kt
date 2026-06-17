package fr.forumhfr.redface2.core.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import fr.forumhfr.redface2.core.ui.theme.DisplayMetrics
import fr.forumhfr.redface2.core.ui.theme.LocalDisplayMetrics
import fr.forumhfr.redface2.core.ui.theme.LocalFoldLongQuotes
import fr.forumhfr.redface2.core.ui.theme.ReadingDisplaySettings
import fr.forumhfr.redface2.core.ui.theme.RedfaceAmoledColorScheme
import fr.forumhfr.redface2.core.ui.theme.RedfaceDarkColorScheme
import fr.forumhfr.redface2.core.ui.theme.RedfaceLightColorScheme
import fr.forumhfr.redface2.core.ui.theme.RedfaceShapes
import fr.forumhfr.redface2.core.ui.theme.RedfaceTypography
import fr.forumhfr.redface2.core.ui.theme.scaledForReading

@Composable
fun RedfaceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoledTheme: Boolean = false,
    // #287 — reading presets (density + font scale) bundled into one param to keep the parameter
    // list within detekt's LongParameterList budget.
    reading: ReadingDisplaySettings = ReadingDisplaySettings(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme && amoledTheme -> RedfaceAmoledColorScheme
        darkTheme -> RedfaceDarkColorScheme
        else -> RedfaceLightColorScheme
    }

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
