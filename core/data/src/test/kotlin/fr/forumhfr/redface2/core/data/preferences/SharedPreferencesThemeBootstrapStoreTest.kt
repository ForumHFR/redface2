package fr.forumhfr.redface2.core.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.domain.preferences.AccentPreset
import fr.forumhfr.redface2.core.domain.preferences.DarkSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.LightSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.PostHeaderEmphasis
import fr.forumhfr.redface2.core.domain.preferences.ThemeAccent
import fr.forumhfr.redface2.core.domain.preferences.ThemeBootstrap
import fr.forumhfr.redface2.core.domain.preferences.ThemeColorPreferences
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synchronous theme mirror over SharedPreferences (#386). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SharedPreferencesThemeBootstrapStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = SharedPreferencesThemeBootstrapStore(context)

    @Before
    fun clearMirror() {
        context.getSharedPreferences("theme_bootstrap", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `an empty mirror reads as the SYSTEM defaults`() {
        assertEquals(ThemeBootstrap(), store.read())
    }

    @Test
    fun `per-key writes round-trip a forced dark AMOLED selection`() {
        store.writeThemeMode(ThemeMode.DARK)
        store.writeAmoledEnabled(true)

        assertEquals(ThemeBootstrap(ThemeMode.DARK, amoledEnabled = true), store.read())
    }

    @Test
    fun `writing one key never clobbers the other`() {
        store.writeThemeMode(ThemeMode.DARK)
        store.writeAmoledEnabled(true)
        store.writeThemeMode(ThemeMode.LIGHT)

        assertEquals(ThemeBootstrap(ThemeMode.LIGHT, amoledEnabled = true), store.read())
    }

    @Test
    fun `an unknown stored mode degrades to SYSTEM instead of crashing`() {
        // Same defensive stance as the repository's DataStore read (downgrade / manual edit).
        context.getSharedPreferences("theme_bootstrap", Context.MODE_PRIVATE)
            .edit()
            .putString("theme_mode", "PURPLE")
            .commit()

        assertEquals(ThemeMode.SYSTEM, store.read().themeMode)
    }

    @Test
    fun `colour preferences round-trip through the mirror`() {
        val preferences = ThemeColorPreferences(
            accent = ThemeAccent.Custom(rgb = 0x123456),
            lightSurfaceTone = LightSurfaceTone.WHITE,
            darkSurfaceTone = DarkSurfaceTone.AMOLED,
            dynamicColorEnabled = true,
            postHeaderEmphasis = PostHeaderEmphasis.VIVID,
        )

        store.writeThemeColorPreferences(preferences)

        assertEquals(preferences, store.read().colorPreferences)
    }

    @Test
    fun `writing a preset accent removes stale custom RGB from the mirror`() {
        store.writeThemeAccent(ThemeAccent.Custom(rgb = 0xFF00FF))
        store.writeThemeAccent(ThemeAccent.Preset(AccentPreset.GREEN))

        assertEquals(ThemeAccent.Preset(AccentPreset.GREEN), store.read().accent)
    }

    @Test
    fun `unknown colour values degrade to defaults instead of crashing`() {
        context.getSharedPreferences("theme_bootstrap", Context.MODE_PRIVATE)
            .edit()
            .putString("accent_color", "MAGENTA")
            .putString("light_surface_tone", "PAPER")
            .putString("post_header_emphasis", "LOUD")
            .putBoolean("dynamic_color_enabled", true)
            .commit()

        assertEquals(
            ThemeBootstrap(dynamicColorEnabled = true),
            store.read(),
        )
    }
}
