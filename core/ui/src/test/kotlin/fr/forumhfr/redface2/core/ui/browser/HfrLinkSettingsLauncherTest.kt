package fr.forumhfr.redface2.core.ui.browser

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1032 — the settings shortcut targets the precise Android screen per API level, and the status read
 * degrades to UNKNOWN below API 31 (its SELECTED/VERIFIED mapping is covered by the pure fn instead,
 * since Robolectric does not shadow DomainVerificationManager). Same RecordingContext pattern as
 * [ExternalBrowserLauncherTest]: startActivity is captured, never launched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HfrLinkSettingsLauncherTest {

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `API 31+ opens the open-by-default screen for this package`() {
        val context = RecordingContext(application, PACKAGE)

        assertTrue(openAppDefaultLinkSettings(context))

        val started = context.startedIntents.single()
        assertEquals(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, started.action)
        assertEquals("package:$PACKAGE".toUri(), started.data)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    @Config(sdk = [30])
    fun `pre-31 falls back to the application details screen`() {
        val context = RecordingContext(application, PACKAGE)

        assertTrue(openAppDefaultLinkSettings(context))

        val started = context.startedIntents.single()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals("package:$PACKAGE".toUri(), started.data)
    }

    @Test
    @Config(sdk = [30])
    fun `link handling status is unknown below API 31`() {
        assertEquals(
            HfrLinkHandlingStatus.UNKNOWN,
            hfrLinkHandlingStatus(RecordingContext(application, PACKAGE)),
        )
    }

    private class RecordingContext(
        base: Context,
        private val currentPackageName: String,
    ) : ContextWrapper(base) {
        val startedIntents = mutableListOf<Intent>()

        override fun getPackageName(): String = currentPackageName

        override fun startActivity(intent: Intent) {
            startedIntents += intent
        }
    }

    private companion object {
        const val PACKAGE = "fr.forumhfr.redface2"
    }
}
