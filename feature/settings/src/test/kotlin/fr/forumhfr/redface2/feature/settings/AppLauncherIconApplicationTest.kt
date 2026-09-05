package fr.forumhfr.redface2.feature.settings

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.domain.preferences.AppLauncherIcon
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLauncherIconApplicationTest {

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `selected alias is enabled before all others are disabled without killing the app`() {
        AppLauncherIcon.entries.forEach { selected ->
            val context = RecordingContext(application, SUFFIXED_APPLICATION_ID)

            applyLauncherIcon(context, selected)

            assertEquals(8, context.calls.size)
            val enableCall = context.calls.first()
            assertEquals(SUFFIXED_APPLICATION_ID, enableCall.component.packageName)
            assertEquals(launcherAliasFor(selected), enableCall.component.className)
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, enableCall.newState)

            val disableCalls = context.calls.drop(1)
            assertEquals(
                AppLauncherIcon.entries.filter { it != selected }.map(::launcherAliasFor).toSet(),
                disableCalls.map { it.component.className }.toSet(),
            )
            assertEquals(listOf(selected), context.activeIcons())
            assertTrue(disableCalls.all { it.newState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED })
            assertTrue(context.calls.all { it.flags == PackageManager.DONT_KILL_APP })
        }
    }

    @Test
    fun `coherent default and explicitly enabled aliases are no-ops`() = runTest {
        val contexts = listOf(RecordingContext(application, SUFFIXED_APPLICATION_ID) to AppLauncherIcon.CLASSIC) +
            AppLauncherIcon.selectable.map { contextWithActive(it) to it }
        contexts.forEach { (context, icon) ->
            val preferences = preferences(icon)
            val controller = AppLauncherIconController(context, preferences, StandardTestDispatcher(testScheduler))

            controller.reconcile()

            assertTrue(context.calls.isEmpty())
            coVerify(exactly = 0) { preferences.setAppLauncherIcon(any()) }
        }
    }

    @Test
    fun `RF1 preference repairs an active Classic alias without rewriting the preference`() = runTest {
        val context = contextWithActive(AppLauncherIcon.CLASSIC)
        val preferences = preferences(AppLauncherIcon.RF1)
        val controller = AppLauncherIconController(context, preferences, StandardTestDispatcher(testScheduler))

        controller.reconcile()
        controller.reconcile()

        assertEquals(listOf(AppLauncherIcon.RF1), context.activeIcons())
        assertEquals(8, context.calls.size)
        coVerify(exactly = 0) { preferences.setAppLauncherIcon(any()) }
    }

    @Test
    @Suppress("DEPRECATION") // Real 0.54.0 PackageManager state; DataStore already maps ROSE to Classic.
    fun `active Rose migrates to Classic and persists exactly once`() = runTest {
        val context = contextWithActive(AppLauncherIcon.ROSE)
        val preferences = preferences(AppLauncherIcon.CLASSIC)
        val controller = AppLauncherIconController(context, preferences, StandardTestDispatcher(testScheduler))

        controller.reconcile()
        controller.reconcile()

        assertEquals(listOf(AppLauncherIcon.CLASSIC), context.activeIcons())
        assertEquals(LAUNCHER_CLASSIC_ALIAS, context.calls.first().component.className)
        coVerify(exactly = 1) { preferences.setAppLauncherIcon(AppLauncherIcon.CLASSIC) }
    }

    @Test
    fun `no active alias recovers Classic even when RF1 was persisted`() = runTest {
        val context = contextWithActive()
        val preferences = preferences(AppLauncherIcon.RF1)
        val controller = AppLauncherIconController(context, preferences, StandardTestDispatcher(testScheduler))

        controller.reconcile()

        assertEquals(listOf(AppLauncherIcon.CLASSIC), context.activeIcons())
        coVerify(exactly = 1) { preferences.setAppLauncherIcon(AppLauncherIcon.CLASSIC) }
    }

    @Test
    fun `enable only touches the target alias`() {
        val context = contextWithActive(AppLauncherIcon.CLASSIC)

        enableLauncherAlias(context, AppLauncherIcon.RF1)

        val call = context.calls.single()
        assertEquals(SUFFIXED_APPLICATION_ID, call.component.packageName)
        assertEquals(LAUNCHER_RF1_ALIAS, call.component.className)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, call.newState)
        assertEquals(PackageManager.DONT_KILL_APP, call.flags)
    }

    @Test
    fun `apply persists then enables the target alone so the restart task survives`() = runTest {
        val context = contextWithActive(AppLauncherIcon.CLASSIC)
        val preferences = preferences(AppLauncherIcon.CLASSIC)
        val controller = AppLauncherIconController(context, preferences, StandardTestDispatcher(testScheduler))

        controller.apply(AppLauncherIcon.RF1)

        coVerify(exactly = 1) { preferences.setAppLauncherIcon(AppLauncherIcon.RF1) }
        assertEquals(LAUNCHER_RF1_ALIAS, context.calls.single().component.className)
        assertEquals(listOf(AppLauncherIcon.CLASSIC, AppLauncherIcon.RF1), context.activeIcons())
    }

    @Test
    fun `multiple selectable aliases are reduced to the preference without re-enabling it`() = runTest {
        AppLauncherIcon.selectable.forEach { selected ->
            val context = contextWithActive(
                AppLauncherIcon.CLASSIC,
                AppLauncherIcon.RF1,
                AppLauncherIcon.MONOGRAM,
                AppLauncherIcon.BUBBLES,
                AppLauncherIcon.CHIP,
            )
            val preferences = preferences(selected)
            val controller = AppLauncherIconController(context, preferences, StandardTestDispatcher(testScheduler))

            controller.reconcile()
            controller.reconcile()

            assertEquals(listOf(selected), context.activeIcons())
            assertEquals(7, context.calls.size)
            assertTrue(context.calls.none { it.component.className == launcherAliasFor(selected) })
            assertTrue(context.calls.all { it.newState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED })
            coVerify(exactly = 0) { preferences.setAppLauncherIcon(any()) }
        }
    }

    @Test
    fun `apply waits for the storage commit and startup repair cannot race the switch`() = runTest {
        val context = contextWithActive(AppLauncherIcon.CLASSIC)
        val preferences = preferences(AppLauncherIcon.CLASSIC)
        val commit = CompletableDeferred<Unit>()
        val persisted = MutableStateFlow(AppLauncherIcon.CLASSIC)
        every { preferences.observeAppLauncherIcon() } returns persisted
        coEvery { preferences.setAppLauncherIcon(any()) } coAnswers {
            commit.await()
            persisted.value = firstArg()
        }
        val controller = AppLauncherIconController(context, preferences, StandardTestDispatcher(testScheduler))

        val apply = launch { controller.apply(AppLauncherIcon.RF1) }
        runCurrent()
        val repair = launch { controller.reconcile() }
        runCurrent()
        assertTrue(context.calls.isEmpty())
        commit.complete(Unit)
        apply.join()
        repair.join()

        assertEquals(AppLauncherIcon.RF1, persisted.value)
        assertEquals(listOf(AppLauncherIcon.RF1), context.activeIcons())
        assertEquals(8, context.calls.size)
    }

    @Test
    fun `failed persistence does not change any component`() = runTest {
        val context = contextWithActive(AppLauncherIcon.CLASSIC)
        val preferences = preferences(AppLauncherIcon.CLASSIC)
        coEvery { preferences.setAppLauncherIcon(any()) } throws IllegalStateException("storage unavailable")
        val controller = AppLauncherIconController(context, preferences, StandardTestDispatcher(testScheduler))

        val failure = runCatching { controller.apply(AppLauncherIcon.RF1) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(context.calls.isEmpty())
    }

    private fun preferences(icon: AppLauncherIcon): UserPreferencesRepository {
        val current = MutableStateFlow(icon)
        return mockk {
            every { observeAppLauncherIcon() } returns current
            coEvery { setAppLauncherIcon(any()) } coAnswers { current.value = firstArg() }
        }
    }

    private fun contextWithActive(vararg icons: AppLauncherIcon): RecordingContext = RecordingContext(
        application,
        SUFFIXED_APPLICATION_ID,
        AppLauncherIcon.entries.associate { icon ->
            launcherAliasFor(icon) to if (icon in icons) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
        },
    )

    private class RecordingContext(
        base: Context,
        private val currentPackageName: String,
        initialStates: Map<String, Int> = emptyMap(),
    ) : ContextWrapper(base) {
        val calls = mutableListOf<ComponentStateCall>()
        private val states = initialStates.toMutableMap()
        private val recordingPackageManager = mockk<PackageManager> {
            every { getComponentEnabledSetting(any()) } answers {
                states[firstArg<ComponentName>().className] ?: PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            }
            every { setComponentEnabledSetting(any(), any(), any()) } answers {
                calls += ComponentStateCall(
                    component = firstArg(),
                    newState = secondArg(),
                    flags = thirdArg(),
                )
                states[firstArg<ComponentName>().className] = secondArg()
                assertTrue("Each component mutation must keep a launchable alias", activeIcons().isNotEmpty())
            }
        }

        fun activeIcons(): List<AppLauncherIcon> = AppLauncherIcon.entries.filter { icon ->
            val state = states[launcherAliasFor(icon)] ?: PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == AppLauncherIcon.CLASSIC)
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
