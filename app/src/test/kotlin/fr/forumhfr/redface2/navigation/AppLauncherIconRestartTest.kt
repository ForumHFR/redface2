package fr.forumhfr.redface2.navigation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Process
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import fr.forumhfr.redface2.LauncherIconRestartActivity
import fr.forumhfr.redface2.LauncherIconRestartActivity.Companion.EXTRA_MAIN_PID
import fr.forumhfr.redface2.LauncherIconRestartActivity.Companion.EXTRA_TARGET_ALIAS_CLASS
import fr.forumhfr.redface2.core.domain.preferences.AppLauncherIcon
import fr.forumhfr.redface2.feature.settings.AppLauncherIconController
import fr.forumhfr.redface2.feature.settings.launcherAliasFor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLauncherIconRestartTest {
    private val events = mutableListOf<String>()
    private val launched = mutableListOf<Intent>()
    private val activity = mockk<Activity> {
        every { packageName } returns "fr.forumhfr.redface2.dev.debug"
        every { finishAffinity() } answers { events += "finish" }
        every { startActivity(any()) } answers {
            launched += firstArg<Intent>()
            events += "start"
        }
    }
    private val controller = mockk<AppLauncherIconController> {
        coEvery { recoverClassic() } coAnswers { events += "recover" }
    }

    @Test
    fun `restart finishes the old affinity before starting the separate restart activity`() = runTest {
        restartOnLauncherAlias(activity, AppLauncherIcon.RF1, controller)

        assertRestartIntent(launched.single(), AppLauncherIcon.RF1)
        assertEquals(listOf("finish", "start"), events)
        coVerify(exactly = 0) { controller.recoverClassic() }
    }

    @Test
    fun `failed restart handoff is recovered before restarting on Classic`() = runTest {
        every { activity.startActivity(any()) } answers {
            val intent = firstArg<Intent>()
            launched += intent
            if (intent.getStringExtra(EXTRA_TARGET_ALIAS_CLASS) == launcherAliasFor(AppLauncherIcon.RF1)) {
                events += "refused"
                throw ActivityNotFoundException("restart handoff failed")
            }
            events += "start"
        }

        restartOnLauncherAlias(activity, AppLauncherIcon.RF1, controller)

        assertEquals(listOf("finish", "refused", "recover", "start"), events)
        assertRestartIntent(launched.first(), AppLauncherIcon.RF1)
        assertRestartIntent(launched.last(), AppLauncherIcon.CLASSIC)
        coVerify(exactly = 1) { controller.recoverClassic() }
    }

    @Test
    fun `Classic recovery completes after the finishing activity cancels its restart coroutine`() = runTest {
        lateinit var restartJob: Job
        every { activity.startActivity(any()) } answers {
            val intent = firstArg<Intent>()
            launched += intent
            if (launched.size == 1) {
                events += "refused"
                restartJob.cancel()
                throw ActivityNotFoundException("restart handoff failed")
            }
            events += "start"
        }
        coEvery { controller.recoverClassic() } coAnswers {
            yield()
            events += "recover"
        }

        restartJob = launch { restartOnLauncherAlias(activity, AppLauncherIcon.RF1, controller) }
        restartJob.join()

        assertTrue(restartJob.isCancelled)
        assertEquals(listOf("finish", "refused", "recover", "start"), events)
        assertRestartIntent(launched.last(), AppLauncherIcon.CLASSIC)
        coVerify(exactly = 1) { controller.recoverClassic() }
    }

    @Test
    fun `cold launcher delivery restores Settings Display and gallery once`() {
        val settings = NavBackStack<NavKey>(SettingsRoute, SettingsColorsRoute)
        val forum = NavBackStack<NavKey>(ForumRoute)
        var selected = TopLevelDestination.Forum
        val delivery = IntentDelivery(launcherRestartIntent(activity, AppLauncherIcon.RF1), id = 0)

        assertTrue(shouldApplyDeepLinkDelivery(delivery.id, lastResolvedDeliveryId = null))
        val restored = restoreAppIconRoute(
            delivery.intent,
            { selected = it },
            mapOf(TopLevelDestination.Settings to settings, TopLevelDestination.Forum to forum),
        )

        assertTrue(restored)
        assertEquals(TopLevelDestination.Settings, selected)
        assertEquals(listOf(SettingsRoute, SettingsDisplayRoute, SettingsAppIconRoute), settings.toList())
        assertEquals(listOf(ForumRoute), forum.toList())
        assertFalse(shouldApplyDeepLinkDelivery(delivery.id, lastResolvedDeliveryId = delivery.id))
    }

    @Test
    fun `normal launcher intents and external views do not restore Settings`() {
        val stack = NavBackStack<NavKey>(SettingsRoute, SettingsColorsRoute)
        listOf(
            Intent(Intent.ACTION_MAIN),
            Intent(Intent.ACTION_VIEW).putExtra(EXTRA_RESTORE_ROUTE, APP_ICON_RESTORE_ROUTE),
        ).forEach { intent ->
            val restored = restoreAppIconRoute(
                intent,
                { error("Unexpected tab switch") },
                mapOf(TopLevelDestination.Settings to stack),
            )
            assertFalse(restored)
            assertEquals(listOf(SettingsRoute, SettingsColorsRoute), stack.toList())
        }
    }

    private fun assertRestartIntent(intent: Intent, icon: AppLauncherIcon) {
        assertEquals("fr.forumhfr.redface2.dev.debug", intent.component?.packageName)
        assertEquals(LauncherIconRestartActivity::class.java.name, intent.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK, intent.flags)
        assertEquals(Process.myPid(), intent.getIntExtra(EXTRA_MAIN_PID, -1))
        assertEquals(launcherAliasFor(icon), intent.getStringExtra(EXTRA_TARGET_ALIAS_CLASS))
        assertEquals(APP_ICON_RESTORE_ROUTE, intent.getStringExtra(EXTRA_RESTORE_ROUTE))
    }
}
