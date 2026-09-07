package fr.forumhfr.redface2.feature.settings

import fr.forumhfr.redface2.core.domain.preferences.AppLauncherIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLauncherIconSwitcherTest {

    @Test
    @Suppress("DEPRECATION") // All historical manifest aliases remain mapped.
    fun `each icon maps to its manifest-qualified alias`() {
        assertEquals(LAUNCHER_CLASSIC_ALIAS, launcherAliasFor(AppLauncherIcon.CLASSIC))
        assertEquals(LAUNCHER_RF1_ALIAS, launcherAliasFor(AppLauncherIcon.RF1))
        assertEquals(LAUNCHER_DARK_ALIAS, launcherAliasFor(AppLauncherIcon.DARK))
        assertEquals(LAUNCHER_ROSE_ALIAS, launcherAliasFor(AppLauncherIcon.ROSE))
        assertEquals(LAUNCHER_RED_ALIAS, launcherAliasFor(AppLauncherIcon.RED))
    }

    @Test
    fun `only Classic and RF1 are selectable`() {
        assertEquals(listOf(AppLauncherIcon.CLASSIC, AppLauncherIcon.RF1), AppLauncherIcon.selectable)
    }

    @Test
    fun `component states enable exactly the selected alias`() {
        AppLauncherIcon.entries.forEach { selected ->
            val states = componentStatesFor(selected)

            assertEquals(5, states.size)
            assertEquals(launcherAliasFor(selected), states.entries.single { it.value }.key)
            assertEquals(1, states.values.count { it })
            assertTrue(states.keys.all { it.startsWith("fr.forumhfr.redface2.Launcher") })
        }
    }
}
