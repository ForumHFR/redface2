package fr.forumhfr.redface2.core.domain.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1388 — pure policy [viewerSystemBars]: which system bars the image viewer hides for each
 * combination of the #518 immersive setting and its own chrome (action bar) visibility.
 */
class ViewerSystemBarsTest {

    @Test
    fun `immersive off with the chrome visible hides nothing`() {
        val bars = viewerSystemBars(immersive = false, chromeVisible = true)

        assertEquals(ViewerBarsState(hideStatusBar = false, hideNavigationBar = false), bars)
        assertFalse(bars.anyHidden)
    }

    @Test
    fun `immersive off with the chrome hidden is a real fullscreen`() {
        val bars = viewerSystemBars(immersive = false, chromeVisible = false)

        assertEquals(ViewerBarsState(hideStatusBar = true, hideNavigationBar = true), bars)
        assertTrue(bars.anyHidden)
    }

    @Test
    fun `immersive on keeps the navigation bar hidden even while the chrome shows`() {
        val bars = viewerSystemBars(immersive = true, chromeVisible = true)

        assertEquals(ViewerBarsState(hideStatusBar = false, hideNavigationBar = true), bars)
        assertTrue(bars.anyHidden)
    }

    @Test
    fun `immersive on with the chrome hidden is a real fullscreen too`() {
        val bars = viewerSystemBars(immersive = true, chromeVisible = false)

        assertEquals(ViewerBarsState(hideStatusBar = true, hideNavigationBar = true), bars)
        assertTrue(bars.anyHidden)
    }

    @Test
    fun `the status bar follows the chrome alone and the setting only binds the navigation bar`() {
        for (immersive in listOf(true, false)) {
            for (chromeVisible in listOf(true, false)) {
                val bars = viewerSystemBars(immersive = immersive, chromeVisible = chromeVisible)
                assertEquals(!chromeVisible, bars.hideStatusBar)
                assertTrue(!chromeVisible || bars.hideNavigationBar == immersive)
            }
        }
    }

    @Test
    fun `anyHidden is exactly the transient-swipe condition`() {
        for (immersive in listOf(true, false)) {
            for (chromeVisible in listOf(true, false)) {
                val bars = viewerSystemBars(immersive = immersive, chromeVisible = chromeVisible)
                assertEquals(bars.hideStatusBar || bars.hideNavigationBar, bars.anyHidden)
            }
        }
    }
}
