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
        assertEquals("fr.forumhfr.redface2.LauncherMonogram", launcherAliasFor(AppLauncherIcon.MONOGRAM))
        assertEquals("fr.forumhfr.redface2.LauncherBubbles", launcherAliasFor(AppLauncherIcon.BUBBLES))
        assertEquals("fr.forumhfr.redface2.LauncherChip", launcherAliasFor(AppLauncherIcon.CHIP))
        assertEquals(LAUNCHER_DARK_ALIAS, launcherAliasFor(AppLauncherIcon.DARK))
        assertEquals(LAUNCHER_ROSE_ALIAS, launcherAliasFor(AppLauncherIcon.ROSE))
        assertEquals(LAUNCHER_RED_ALIAS, launcherAliasFor(AppLauncherIcon.RED))
    }

    @Test
    fun `gallery offers Classic RF1 and three original drawings`() {
        assertEquals(
            listOf(
                AppLauncherIcon.CLASSIC,
                AppLauncherIcon.RF1,
                AppLauncherIcon.MONOGRAM,
                AppLauncherIcon.BUBBLES,
                AppLauncherIcon.CHIP,
            ),
            AppLauncherIcon.selectable,
        )
    }

    @Test
    fun `restart guard accepts all eight declared aliases`() {
        AppLauncherIcon.entries.forEach { icon ->
            assertTrue(isKnownLauncherAlias(launcherAliasFor(icon)))
        }
    }

    @Test
    fun `component states enable exactly the selected alias`() {
        AppLauncherIcon.entries.forEach { selected ->
            val states = componentStatesFor(selected)

            assertEquals(8, states.size)
            assertEquals(launcherAliasFor(selected), states.entries.single { it.value }.key)
            assertEquals(1, states.values.count { it })
            assertTrue(states.keys.all { it.startsWith("fr.forumhfr.redface2.Launcher") })
        }
    }
}
