package fr.forumhfr.redface2.navigation

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #666 follow-up — the shorter icon-only bottom bar is used in exactly ONE case: the phone bottom-bar
 * layout ([NavigationSuiteType.ShortNavigationBarCompact]) with the nav labels hidden. Every other layout
 * (rail / drawer on wide windows, or the suite hidden) and the labels-on case keep the adaptive suite. These
 * pin that single-case contract so a future layout-type tweak cannot silently shrink the rail/drawer.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
class ShouldUseCompactIconBarTest {

    @Test
    fun `compact phone bar with labels off uses the short icon-only bar`() {
        assertTrue(
            shouldUseCompactIconBar(
                navBarLabels = false,
                navLayoutType = NavigationSuiteType.ShortNavigationBarCompact,
            ),
        )
    }

    @Test
    fun `compact phone bar with labels on keeps the adaptive suite`() {
        assertFalse(
            shouldUseCompactIconBar(
                navBarLabels = true,
                navLayoutType = NavigationSuiteType.ShortNavigationBarCompact,
            ),
        )
    }

    @Test
    fun `rail layout with labels off keeps the adaptive suite`() {
        assertFalse(
            shouldUseCompactIconBar(
                navBarLabels = false,
                navLayoutType = NavigationSuiteType.NavigationRail,
            ),
        )
    }

    @Test
    fun `drawer layout with labels off keeps the adaptive suite`() {
        assertFalse(
            shouldUseCompactIconBar(
                navBarLabels = false,
                navLayoutType = NavigationSuiteType.NavigationDrawer,
            ),
        )
    }

    @Test
    fun `hidden suite with labels off keeps the adaptive suite`() {
        assertFalse(
            shouldUseCompactIconBar(
                navBarLabels = false,
                navLayoutType = NavigationSuiteType.None,
            ),
        )
    }
}
