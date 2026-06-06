package fr.forumhfr.redface2.feature.topic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopicSwipeTest {

    // --- swipeTargetPage : bornes ---

    @Test
    fun `forward swipe from a middle page targets the next page`() {
        assertEquals(3, swipeTargetPage(currentPage = 2, totalPages = 5, forward = true))
    }

    @Test
    fun `forward swipe from the last page is blocked`() {
        assertNull(swipeTargetPage(currentPage = 5, totalPages = 5, forward = true))
    }

    @Test
    fun `backward swipe from a middle page targets the previous page`() {
        assertEquals(1, swipeTargetPage(currentPage = 2, totalPages = 5, forward = false))
    }

    @Test
    fun `backward swipe from the first page is blocked`() {
        assertNull(swipeTargetPage(currentPage = 1, totalPages = 5, forward = false))
    }

    @Test
    fun `single-page topic blocks both directions`() {
        assertNull(swipeTargetPage(currentPage = 1, totalPages = 1, forward = true))
        assertNull(swipeTargetPage(currentPage = 1, totalPages = 1, forward = false))
    }

    // --- swipeCommitDirection : seuils de commit + direction ---

    private val commitDistance = 200f
    private val flingThreshold = 900f

    @Test
    fun `no commit when both distance and velocity stay under their thresholds`() {
        assertNull(swipeCommitDirection(totalDx = 50f, velocityX = 100f, commitDistance, flingThreshold))
    }

    @Test
    fun `distance commit leftward is forward`() {
        assertEquals(true, swipeCommitDirection(totalDx = -250f, velocityX = 0f, commitDistance, flingThreshold))
    }

    @Test
    fun `distance commit rightward is backward`() {
        assertEquals(false, swipeCommitDirection(totalDx = 250f, velocityX = 0f, commitDistance, flingThreshold))
    }

    @Test
    fun `a fling decides direction over a small reversed displacement`() {
        // Thrown left (forward) but the finger lifted slightly to the right of its start (totalDx > 0,
        // under the distance threshold). The fling velocity carried the commit, so direction follows it.
        assertEquals(true, swipeCommitDirection(totalDx = 30f, velocityX = -1500f, commitDistance, flingThreshold))
    }

    @Test
    fun `a rightward fling is backward`() {
        assertEquals(false, swipeCommitDirection(totalDx = -30f, velocityX = 1500f, commitDistance, flingThreshold))
    }

    @Test
    fun `a degenerate zero orientation is a no-op`() {
        assertNull(swipeCommitDirection(totalDx = 0f, velocityX = 0f, commitDistancePx = 0f, flingThresholdPx = 0f))
    }
}
