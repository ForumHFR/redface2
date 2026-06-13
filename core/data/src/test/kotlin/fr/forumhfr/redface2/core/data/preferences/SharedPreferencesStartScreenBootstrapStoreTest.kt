package fr.forumhfr.redface2.core.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.domain.preferences.StartScreenChoice
import fr.forumhfr.redface2.core.domain.preferences.StartScreenPreference
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synchronous start-screen mirror over SharedPreferences (#458). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SharedPreferencesStartScreenBootstrapStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = SharedPreferencesStartScreenBootstrapStore(context)

    @Test
    fun `an empty mirror reads as the Drapeaux default`() {
        assertEquals(StartScreenPreference(), store.read())
    }

    @Test
    fun `writes round-trip a Forum category selection`() {
        store.write(StartScreenPreference(StartScreenChoice.FORUM, forumCatId = 13))

        assertEquals(StartScreenPreference(StartScreenChoice.FORUM, forumCatId = 13), store.read())
    }

    @Test
    fun `a category id stored under a non-Forum screen is ignored on read`() {
        // The atomic write keeps the pair consistent, but a manual edit / partial restore must
        // not resurrect a category on the FLAGS/MESSAGES screens.
        context.getSharedPreferences("start_screen_bootstrap", Context.MODE_PRIVATE)
            .edit()
            .putString("start_screen", StartScreenChoice.MESSAGES.name)
            .putInt("start_forum_cat", 13)
            .commit()

        assertEquals(StartScreenPreference(StartScreenChoice.MESSAGES), store.read())
    }

    @Test
    fun `an unknown stored screen degrades to FLAGS instead of crashing`() {
        context.getSharedPreferences("start_screen_bootstrap", Context.MODE_PRIVATE)
            .edit()
            .putString("start_screen", "DESKTOP")
            .commit()

        assertEquals(StartScreenChoice.FLAGS, store.read().screen)
    }
}
