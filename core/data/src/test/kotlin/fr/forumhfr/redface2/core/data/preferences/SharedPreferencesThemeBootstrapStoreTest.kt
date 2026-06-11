package fr.forumhfr.redface2.core.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.domain.preferences.ThemeBootstrap
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import org.junit.Assert.assertEquals
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

    @Test
    fun `an empty mirror reads as the SYSTEM defaults`() {
        assertEquals(ThemeBootstrap(ThemeMode.SYSTEM, amoledEnabled = false), store.read())
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
}
