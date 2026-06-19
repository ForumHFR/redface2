package fr.forumhfr.redface2.core.domain.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #518 follow-up — pure policy [shouldRevealNavBar]: which scroll facts reveal the hidden system
 * navigation bar for each mode.
 */
class ShouldRevealNavBarTest {

    @Test
    fun `MANUAL never reveals regardless of scroll`() {
        for (atBottom in listOf(true, false)) {
            for (up in listOf(true, false)) {
                assertFalse(shouldRevealNavBar(ImmersiveNavBarReveal.MANUAL, atBottom, up))
            }
        }
    }

    @Test
    fun `AT_BOTTOM reveals only at the bottom`() {
        assertTrue(shouldRevealNavBar(ImmersiveNavBarReveal.AT_BOTTOM, atBottom = true, scrollingUp = false))
        assertTrue(shouldRevealNavBar(ImmersiveNavBarReveal.AT_BOTTOM, atBottom = true, scrollingUp = true))
        assertFalse(shouldRevealNavBar(ImmersiveNavBarReveal.AT_BOTTOM, atBottom = false, scrollingUp = true))
        assertFalse(shouldRevealNavBar(ImmersiveNavBarReveal.AT_BOTTOM, atBottom = false, scrollingUp = false))
    }

    @Test
    fun `ON_SCROLL_UP reveals when scrolling up or at the bottom, hides when scrolling down`() {
        assertTrue(shouldRevealNavBar(ImmersiveNavBarReveal.ON_SCROLL_UP, atBottom = false, scrollingUp = true))
        assertTrue(shouldRevealNavBar(ImmersiveNavBarReveal.ON_SCROLL_UP, atBottom = true, scrollingUp = false))
        assertFalse(shouldRevealNavBar(ImmersiveNavBarReveal.ON_SCROLL_UP, atBottom = false, scrollingUp = false))
    }
}
