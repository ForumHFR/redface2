package fr.forumhfr.redface2.core.ui

import fr.forumhfr.redface2.core.domain.preferences.AccentColor
import fr.forumhfr.redface2.core.ui.theme.RedfaceAmoledColorScheme
import fr.forumhfr.redface2.core.ui.theme.RedfaceDarkColorScheme
import fr.forumhfr.redface2.core.ui.theme.RedfaceLightColorScheme
import fr.forumhfr.redface2.core.ui.theme.RedfaceRedDarkColorScheme
import fr.forumhfr.redface2.core.ui.theme.RedfaceRedLightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * TU 2788511 — pure-JVM guard on [staticColorScheme]: the accent family must pick the right scheme,
 * `ROSE + AMOLED` must keep the historical manual scheme, and other AMOLED accents must stay
 * chromatic over black surfaces. The dynamic-colour branch (needing a Context) lives in the
 * composable and is out of scope here.
 */
class StaticColorSchemeTest {

    @Test
    fun `rose accent keeps the historical light and dark schemes`() {
        assertSame(
            RedfaceLightColorScheme,
            staticColorScheme(darkTheme = false, amoledTheme = false, accentColor = AccentColor.ROSE),
        )
        assertSame(
            RedfaceDarkColorScheme,
            staticColorScheme(darkTheme = true, amoledTheme = false, accentColor = AccentColor.ROSE),
        )
    }

    @Test
    fun `rouge REDFACE1 accent selects the red schemes`() {
        assertSame(
            RedfaceRedLightColorScheme,
            staticColorScheme(darkTheme = false, amoledTheme = false, accentColor = AccentColor.ROUGE_REDFACE1),
        )
        assertSame(
            RedfaceRedDarkColorScheme,
            staticColorScheme(darkTheme = true, amoledTheme = false, accentColor = AccentColor.ROUGE_REDFACE1),
        )
    }

    @Test
    fun `ROSE AMOLED keeps the historical manual AMOLED scheme`() {
        assertSame(
            RedfaceAmoledColorScheme,
            staticColorScheme(darkTheme = true, amoledTheme = true, accentColor = AccentColor.ROSE),
        )
    }

    @Test
    fun `ROUGE REDFACE1 AMOLED keeps the red accent over black surfaces`() {
        val scheme = staticColorScheme(
            darkTheme = true,
            amoledTheme = true,
            accentColor = AccentColor.ROUGE_REDFACE1,
        )

        assertNotSame(RedfaceAmoledColorScheme, scheme)
        assertNotEquals(RedfaceAmoledColorScheme.primary, scheme.primary)
        assertEquals(RedfaceAmoledColorScheme.surface, scheme.surface)
    }

    @Test
    fun `AMOLED only applies in dark theme`() {
        // amoledTheme is meaningless in light: the accent family still drives the light scheme.
        assertSame(
            RedfaceRedLightColorScheme,
            staticColorScheme(darkTheme = false, amoledTheme = true, accentColor = AccentColor.ROUGE_REDFACE1),
        )
    }
}
