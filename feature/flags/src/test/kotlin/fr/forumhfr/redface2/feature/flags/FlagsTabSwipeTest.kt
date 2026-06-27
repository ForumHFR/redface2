package fr.forumhfr.redface2.feature.flags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure cyclic tab-target geometry for the flags tab swipe (#663). Unlike the shared
 * [fr.forumhfr.redface2.core.ui.pager.swipeTargetPage] used by the topic/MP pagers (hard-bounded — a
 * topic must never wrap page N → page 1), flag tabs form a ring: swiping past the last tab lands on
 * the first and vice-versa. `null` only when there is nowhere to go (0 or 1 tab).
 */
class FlagsTabSwipeTest {

    @Test
    fun `forward from a middle tab advances by one`() {
        assertEquals(2, swipeTargetIndex(currentIndex = 1, tabCount = 4, forward = true))
    }

    @Test
    fun `backward from a middle tab steps back by one`() {
        assertEquals(1, swipeTargetIndex(currentIndex = 2, tabCount = 4, forward = false))
    }

    @Test
    fun `forward past the last tab wraps to the first`() {
        assertEquals(0, swipeTargetIndex(currentIndex = 3, tabCount = 4, forward = true))
    }

    @Test
    fun `backward before the first tab wraps to the last`() {
        assertEquals(3, swipeTargetIndex(currentIndex = 0, tabCount = 4, forward = false))
    }

    @Test
    fun `the wrap target is never the current tab`() {
        // Two tabs: both directions cross to the other tab, never re-select the current one (which
        // would shadow the re-tap «+lus» toggle).
        assertEquals(1, swipeTargetIndex(currentIndex = 0, tabCount = 2, forward = true))
        assertEquals(1, swipeTargetIndex(currentIndex = 0, tabCount = 2, forward = false))
    }

    @Test
    fun `a single tab has no swipe target in either direction`() {
        assertNull(swipeTargetIndex(currentIndex = 0, tabCount = 1, forward = true))
        assertNull(swipeTargetIndex(currentIndex = 0, tabCount = 1, forward = false))
    }

    @Test
    fun `an empty tab list has no swipe target`() {
        assertNull(swipeTargetIndex(currentIndex = 0, tabCount = 0, forward = true))
    }

    // #660 — Shared Axis X slide direction for the committed tab transition. A swipe carries an
    // authoritative direction (handles the cyclic wrap, #663); a tap falls back to the tabs' order.

    @Test
    fun `a forward swipe slides forward even when it wraps last to first`() {
        // Super (last) → Cyan (first) by a forward swipe: the index order alone (0 < 3) would read
        // « backward » and bring the new tab in from the WRONG side — the original #660 bug. The
        // gesture's own direction overrides it.
        assertTrue(flagsTabSlideForward(fromIndex = 3, toIndex = 0, swipeForward = true))
    }

    @Test
    fun `a backward swipe slides backward even when it wraps first to last`() {
        assertFalse(flagsTabSlideForward(fromIndex = 0, toIndex = 3, swipeForward = false))
    }

    @Test
    fun `a tap to a later tab slides forward`() {
        assertTrue(flagsTabSlideForward(fromIndex = 0, toIndex = 2, swipeForward = null))
    }

    @Test
    fun `a tap to an earlier tab slides backward`() {
        assertFalse(flagsTabSlideForward(fromIndex = 2, toIndex = 0, swipeForward = null))
    }
}
