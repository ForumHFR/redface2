package fr.forumhfr.redface2.feature.settings

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.domain.preferences.AppLauncherIcon
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLauncherIconApplicationTest {

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `selected alias is enabled before all others are disabled without killing the app`() {
        val context = RecordingContext(application, SUFFIXED_APPLICATION_ID)

        applyLauncherIcon(context, AppLauncherIcon.ROSE)

        assertEquals(AppLauncherIcon.entries.size, context.calls.size)
        val enableCall = context.calls.first()
        assertEquals(SUFFIXED_APPLICATION_ID, enableCall.component.packageName)
        assertEquals(LAUNCHER_ROSE_ALIAS, enableCall.component.className)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, enableCall.newState)

        val disableCalls = context.calls.drop(1)
        assertEquals(
            setOf(LAUNCHER_CLASSIC_ALIAS, LAUNCHER_DARK_ALIAS, LAUNCHER_RED_ALIAS),
            disableCalls.map { it.component.className }.toSet(),
        )
        assertTrue(disableCalls.all { it.newState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED })
        assertTrue(context.calls.all { it.flags == PackageManager.DONT_KILL_APP })
    }

    private class RecordingContext(
        base: Context,
        private val currentPackageName: String,
    ) : ContextWrapper(base) {
        val calls = mutableListOf<ComponentStateCall>()
        private val recordingPackageManager = mockk<PackageManager> {
            every { setComponentEnabledSetting(any(), any(), any()) } answers {
                calls += ComponentStateCall(
                    component = firstArg(),
                    newState = secondArg(),
                    flags = thirdArg(),
                )
            }
        }

        override fun getPackageName(): String = currentPackageName

        override fun getPackageManager(): PackageManager = recordingPackageManager
    }

    private data class ComponentStateCall(
        val component: ComponentName,
        val newState: Int,
        val flags: Int,
    )

    private companion object {
        const val SUFFIXED_APPLICATION_ID = "fr.forumhfr.redface2.dev.debug"
    }
}
