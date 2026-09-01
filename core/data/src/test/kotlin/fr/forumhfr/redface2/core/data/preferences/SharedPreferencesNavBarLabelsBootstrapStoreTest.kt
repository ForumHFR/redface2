package fr.forumhfr.redface2.core.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synchronous nav-bar-labels mirror over SharedPreferences (#1138). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SharedPreferencesNavBarLabelsBootstrapStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = SharedPreferencesNavBarLabelsBootstrapStore(context)

    @Test
    fun `an empty mirror reads as the labels-shown default`() {
        // #666 default: an untouched mirror seeds « labels shown », matching the DataStore default.
        assertTrue(store.read())
    }

    @Test
    fun `a write round-trips a hidden-labels selection`() {
        store.write(false)

        assertFalse(store.read())
    }

    @Test
    fun `the last write wins`() {
        store.write(false)
        store.write(true)

        assertTrue(store.read())
    }
}
