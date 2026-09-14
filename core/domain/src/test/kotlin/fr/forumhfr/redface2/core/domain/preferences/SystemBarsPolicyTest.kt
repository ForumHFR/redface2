package fr.forumhfr.redface2.core.domain.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #518 + #1388 — pure policy [appSystemBars]: which system bars the single window owner hides, for
 * every combination of the immersive setting, the scroll-driven reveal, and the image viewer's
 * published intent.
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

        assertEquals(SystemBarsState(hideStatusBar = false, hideNavigationBar = false), bars)
        assertFalse(bars.anyHidden)
    }

    @Test
    fun `the viewer chrome hidden is a real fullscreen, setting or not`() {
        for (immersive in BOOLEANS) {
            val bars = bars(immersive = immersive, viewerActive = true, chromeVisible = false)
            assertEquals(SystemBarsState(hideStatusBar = true, hideNavigationBar = true), bars)
            assertTrue(bars.anyHidden)
        }
    }

    @Test
    fun `the setting keeps the navigation bar hidden while the viewer chrome shows`() {
        val bars = bars(immersive = true, viewerActive = true, chromeVisible = true)

        assertEquals(SystemBarsState(hideStatusBar = false, hideNavigationBar = true), bars)
    }

    @Test
    fun `a scroll reveal brings an immersive navigation bar back but never overrides fullscreen`() {
        assertFalse(
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
    ) = appSystemBars(
        immersive = immersive,
        navBarRevealed = navBarRevealed,
        viewerActive = viewerActive,
        chromeVisible = chromeVisible,
    )

    private fun forEachCombination(block: (Boolean, Boolean, Boolean, Boolean) -> Unit) {
        for (immersive in BOOLEANS) {
            for (revealed in BOOLEANS) {
                for (active in BOOLEANS) {
                    for (chrome in BOOLEANS) {
                        block(immersive, revealed, active, chrome)
                    }
                }
            }
        }
    }
}

private val BOOLEANS = listOf(true, false)
