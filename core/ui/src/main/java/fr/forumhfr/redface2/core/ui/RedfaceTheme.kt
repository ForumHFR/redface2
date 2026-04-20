package fr.forumhfr.redface2.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import fr.forumhfr.redface2.core.ui.theme.RedfaceAmoledColorScheme
import fr.forumhfr.redface2.core.ui.theme.RedfaceDarkColorScheme
import fr.forumhfr.redface2.core.ui.theme.RedfaceLightColorScheme
import fr.forumhfr.redface2.core.ui.theme.RedfaceShapes
import fr.forumhfr.redface2.core.ui.theme.RedfaceTypography

@Composable
fun RedfaceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoledTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        darkTheme && amoledTheme -> RedfaceAmoledColorScheme
        darkTheme -> RedfaceDarkColorScheme
        else -> RedfaceLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = RedfaceTypography,
        shapes = RedfaceShapes,
        content = content,
    )
}
