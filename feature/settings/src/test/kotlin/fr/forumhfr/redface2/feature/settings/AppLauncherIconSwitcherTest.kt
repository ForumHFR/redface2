package fr.forumhfr.redface2.feature.settings

import fr.forumhfr.redface2.core.domain.preferences.AppLauncherIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLauncherIconSwitcherTest {

    @Test
    fun `each icon maps to its manifest-qualified alias`() {
        assertEquals(LAUNCHER_CLASSIC_ALIAS, launcherAliasFor(AppLauncherIcon.CLASSIC))
        assertEquals(LAUNCHER_DARK_ALIAS, launcherAliasFor(AppLauncherIcon.DARK))
        assertEquals(LAUNCHER_ROSE_ALIAS, launcherAliasFor(AppLauncherIcon.ROSE))
        assertEquals(LAUNCHER_RED_ALIAS, launcherAliasFor(AppLauncherIcon.RED))
    }

    @Test
    fun `component states enable exactly the selected alias`() {
        AppLauncherIcon.entries.forEach { selected ->
            val states = componentStatesFor(selected)

            assertEquals(AppLauncherIcon.entries.size, states.size)
            assertEquals(launcherAliasFor(selected), states.entries.single { it.value }.key)
            assertEquals(1, states.values.count { it })
            assertTrue(states.keys.all { it.startsWith("fr.forumhfr.redface2.Launcher") })
        }
    }
}
