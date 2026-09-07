package fr.forumhfr.redface2

import android.app.Application
import android.content.Intent
import fr.forumhfr.redface2.LauncherIconRestartActivity.Companion.EXTRA_MAIN_PID
import fr.forumhfr.redface2.LauncherIconRestartActivity.Companion.EXTRA_TARGET_ALIAS_CLASS
import fr.forumhfr.redface2.core.domain.preferences.AppLauncherIcon
import fr.forumhfr.redface2.feature.settings.launcherAliasFor
import fr.forumhfr.redface2.navigation.APP_ICON_RESTORE_ROUTE
import fr.forumhfr.redface2.navigation.EXTRA_RESTORE_ROUTE
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class LauncherIconRestartActivityTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val processTerminator = mockk<ProcessTerminator>(relaxed = true)

    @Test
    fun `restart kills the main process then launches the target and finishes before exiting`() {
        val intent = Intent(application, LauncherIconRestartActivity::class.java)
            .putExtra(EXTRA_TARGET_ALIAS_CLASS, launcherAliasFor(AppLauncherIcon.RF1))
            .putExtra(EXTRA_RESTORE_ROUTE, APP_ICON_RESTORE_ROUTE)
            .putExtra(EXTRA_MAIN_PID, MAIN_PID)
        val controller = Robolectric.buildActivity(LauncherIconRestartActivity::class.java, intent)
        val activity = controller.get()
        activity.processTerminator = processTerminator
        val shadowApplication = shadowOf(application)
        var launchedAtExit: Intent? = null
        every { processTerminator.killProcess(MAIN_PID) } answers {
            assertFalse(activity.isFinishing)
            assertNull(shadowApplication.nextStartedActivity)
        }
        every { processTerminator.exit() } answers {
            assertTrue(activity.isFinishing)
            launchedAtExit = shadowApplication.nextStartedActivity
        }

        controller.create()

        val launched = requireNotNull(launchedAtExit)
        assertEquals(application.packageName, launched.component?.packageName)
        assertEquals(launcherAliasFor(AppLauncherIcon.RF1), launched.component?.className)
        assertEquals(Intent.ACTION_MAIN, launched.action)
        assertEquals(setOf(Intent.CATEGORY_LAUNCHER), launched.categories)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, launched.flags)
        assertEquals(APP_ICON_RESTORE_ROUTE, launched.getStringExtra(EXTRA_RESTORE_ROUTE))
        assertTrue(activity.isFinishing)
        assertNull(shadowApplication.nextStartedActivity)
        verifySequence {
            processTerminator.killProcess(MAIN_PID)
            processTerminator.exit()
        }
        controller.destroy()
    }

    @Test
    fun `missing alias finishes without launching or terminating either process`() {
        val intent = Intent(application, LauncherIconRestartActivity::class.java)
            .putExtra(EXTRA_RESTORE_ROUTE, APP_ICON_RESTORE_ROUTE)
            .putExtra(EXTRA_MAIN_PID, MAIN_PID)
        val controller = Robolectric.buildActivity(LauncherIconRestartActivity::class.java, intent)
        val activity = controller.get()
        activity.processTerminator = processTerminator

        controller.create()

        assertTrue(activity.isFinishing)
        assertNull(shadowOf(application).nextStartedActivity)
        verify(exactly = 0) {
            processTerminator.killProcess(any())
            processTerminator.exit()
        }
        controller.destroy()
    }

    @Test
    fun `missing main pid finishes without launching or terminating either process`() {
        val intent = Intent(application, LauncherIconRestartActivity::class.java)
            .putExtra(EXTRA_TARGET_ALIAS_CLASS, launcherAliasFor(AppLauncherIcon.RF1))
            .putExtra(EXTRA_RESTORE_ROUTE, APP_ICON_RESTORE_ROUTE)
        val controller = Robolectric.buildActivity(LauncherIconRestartActivity::class.java, intent)
        val activity = controller.get()
        activity.processTerminator = processTerminator

        controller.create()

        assertTrue(activity.isFinishing)
        assertNull(shadowOf(application).nextStartedActivity)
        verify(exactly = 0) {
            processTerminator.killProcess(any())
            processTerminator.exit()
        }
        controller.destroy()
    }

    @Test
    fun `unknown alias class is refused without launching or terminating either process`() {
        val intent = Intent(application, LauncherIconRestartActivity::class.java)
            .putExtra(EXTRA_TARGET_ALIAS_CLASS, "fr.forumhfr.redface2.LauncherEvil")
            .putExtra(EXTRA_RESTORE_ROUTE, APP_ICON_RESTORE_ROUTE)
            .putExtra(EXTRA_MAIN_PID, MAIN_PID)
        val controller = Robolectric.buildActivity(LauncherIconRestartActivity::class.java, intent)
        val activity = controller.get()
        activity.processTerminator = processTerminator

        controller.create()

        assertTrue(activity.isFinishing)
        assertNull(shadowOf(application).nextStartedActivity)
        verify(exactly = 0) {
            processTerminator.killProcess(any())
            processTerminator.exit()
        }
        controller.destroy()
    }

    private companion object {
        const val MAIN_PID = 1234
    }
}
