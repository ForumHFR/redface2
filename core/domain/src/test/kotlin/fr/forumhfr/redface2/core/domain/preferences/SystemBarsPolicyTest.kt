package fr.forumhfr.redface2.core.domain.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #518 + #1388 — pure policy [appSystemBars]: which system bars the single window owner hides, and
 * how the visible ones are drawn, for every combination of the immersive setting, the scroll-driven
 * reveal, the image viewer's published intent and the effective app theme.
 */
class SystemBarsPolicyTest {

    @Test
    fun `without the viewer the policy is exactly the historical 518 behaviour`() {
        for (immersive in BOOLEANS) {
            for (revealed in BOOLEANS) {
                val bars = bars(immersive = immersive, navBarRevealed = revealed, viewerActive = false)
                assertFalse("the status bar is never hidden outside the viewer", bars.hideStatusBar)
                assertEquals(immersive && !revealed, bars.hideNavigationBar)
            }
        }
    }

    @Test
    fun `the viewer chrome visible without the setting leaves both bars alone`() {
        val bars = bars(immersive = false, viewerActive = true, chromeVisible = true)

        // Nothing is hidden, yet the state is NOT the outside-the-viewer one: the icons go light for
        // the black backdrop drawn under the bars.
        assertEquals(
            SystemBarsState(hideStatusBar = false, hideNavigationBar = false, lightSystemBarIcons = true),
            bars,
        )
        assertFalse(bars.anyHidden)
    }

    @Test
    fun `the viewer chrome hidden is a real fullscreen, setting or not`() {
        for (immersive in BOOLEANS) {
            val bars = bars(immersive = immersive, viewerActive = true, chromeVisible = false)
            assertEquals(
                SystemBarsState(hideStatusBar = true, hideNavigationBar = true, lightSystemBarIcons = true),
                bars,
            )
            assertTrue(bars.anyHidden)
        }
    }

    @Test
    fun `the setting keeps the navigation bar hidden while the viewer chrome shows`() {
        val bars = bars(immersive = true, viewerActive = true, chromeVisible = true)

        assertEquals(
            SystemBarsState(hideStatusBar = false, hideNavigationBar = true, lightSystemBarIcons = true),
            bars,
        )
    }

    @Test
    fun `a scroll reveal brings an immersive navigation bar back on the reading screens`() {
        assertFalse(
            bars(immersive = true, navBarRevealed = true, viewerActive = false).hideNavigationBar,
        )
        assertTrue(
            bars(immersive = true, navBarRevealed = false, viewerActive = false).hideNavigationBar,
        )
    }

    @Test
    fun `a scroll reveal is ignored for as long as the viewer is up`() {
        // Decision C: « immersive ⇒ navigation bar hidden » holds in the viewer WITHOUT exception,
        // so a topic left with a revealed bar cannot leak a visible bar into the viewer.
        assertTrue(
            bars(immersive = true, navBarRevealed = true, viewerActive = true, chromeVisible = true)
                .hideNavigationBar,
        )
        assertTrue(
            bars(immersive = true, navBarRevealed = true, viewerActive = true, chromeVisible = false)
                .hideNavigationBar,
        )
        assertTrue(
            bars(immersive = false, navBarRevealed = true, viewerActive = true, chromeVisible = false)
                .hideNavigationBar,
        )
        // Without the setting, a reveal changes nothing while the chrome is up either.
        assertFalse(
            bars(immersive = false, navBarRevealed = true, viewerActive = true, chromeVisible = true)
                .hideNavigationBar,
        )
    }

    @Test
    fun `the reveal flag is inert over the whole viewer-active half of the table`() {
        for ((immersive, chrome) in BOOLEAN_PAIRS) {
            assertEquals(
                bars(immersive, navBarRevealed = true, viewerActive = true, chromeVisible = chrome),
                bars(immersive, navBarRevealed = false, viewerActive = true, chromeVisible = chrome),
            )
        }
    }

    @Test
    fun `the chrome flag is inert while the viewer is not active`() {
        for (immersive in BOOLEANS) {
            for (revealed in BOOLEANS) {
                assertEquals(
                    bars(immersive, revealed, viewerActive = false, chromeVisible = true),
                    bars(immersive, revealed, viewerActive = false, chromeVisible = false),
                )
            }
        }
    }

    @Test
    fun `the viewer forces light system bar icons whatever the theme, over the whole table`() {
        // #1388 — the viewer paints a black backdrop, so the clock and the navigation glyphs are only
        // legible in white. Outside it the contrast is the effective app theme (#286), nothing else.
        forEachCombination { immersive, revealed, active, chrome ->
            for (dark in BOOLEANS) {
                val bars = bars(immersive, revealed, active, chrome, darkTheme = dark)
                assertEquals(active || dark, bars.lightSystemBarIcons)
            }
        }
    }

    @Test
    fun `the icon contrast is the only thing the theme decides`() {
        forEachCombination { immersive, revealed, active, chrome ->
            val light = bars(immersive, revealed, active, chrome, darkTheme = false)
            val dark = bars(immersive, revealed, active, chrome, darkTheme = true)
            assertEquals(light.hideStatusBar, dark.hideStatusBar)
            assertEquals(light.hideNavigationBar, dark.hideNavigationBar)
        }
    }

    @Test
    fun `the status bar is hidden exactly by the viewer fullscreen, over the whole table`() {
        forEachCombination { immersive, revealed, active, chrome ->
            val bars = bars(immersive, revealed, active, chrome)
            assertEquals(active && !chrome, bars.hideStatusBar)
        }
    }

    @Test
    fun `anyHidden is exactly the transient-swipe condition`() {
        forEachCombination { immersive, revealed, active, chrome ->
            val bars = bars(immersive, revealed, active, chrome)
            assertEquals(bars.hideStatusBar || bars.hideNavigationBar, bars.anyHidden)
        }
    }

    private fun bars(
        immersive: Boolean,
        navBarRevealed: Boolean = false,
        viewerActive: Boolean = false,
        chromeVisible: Boolean = true,
        darkTheme: Boolean = false,
    ) = appSystemBars(
        immersive = immersive,
        navBarRevealed = navBarRevealed,
        viewerActive = viewerActive,
        chromeVisible = chromeVisible,
        darkTheme = darkTheme,
    )

    // Two passes over the pairs rather than four nested loops: same 16 rows, half the nesting.
    private fun forEachCombination(block: (Boolean, Boolean, Boolean, Boolean) -> Unit) {
        for ((immersive, revealed) in BOOLEAN_PAIRS) {
            for ((active, chrome) in BOOLEAN_PAIRS) {
                block(immersive, revealed, active, chrome)
            }
        }
    }
}

private val BOOLEANS = listOf(true, false)
private val BOOLEAN_PAIRS = BOOLEANS.flatMap { first -> BOOLEANS.map { first to it } }
