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

    // --- swipeCommitDistancePx : seuil partagé geste/indice ---

    @Test
    fun `commit distance falls back to the minimum on a narrow page`() {
        assertEquals(72f, swipeCommitDistancePx(widthPx = 100f, minCommitPx = 72f), TOLERANCE)
    }

    @Test
    fun `commit distance follows the width fraction on a wide page`() {
        // 1000 * 0.20 = 200 > 72 → the fraction wins.
        assertEquals(200f, swipeCommitDistancePx(widthPx = 1000f, minCommitPx = 72f), TOLERANCE)
    }

    // --- swipeFollowOffset : translation visuelle (drag-follow + bord) ---

    private val commit = 200f

    @Test
    fun `below the commit point the page tracks the finger one to one`() {
        assertEquals(-100f, swipeFollowOffset(rawDx = -100f, commitDistancePx = commit, hasTarget = true), TOLERANCE)
    }

    @Test
    fun `at the commit point the offset equals the commit distance`() {
        assertEquals(-200f, swipeFollowOffset(rawDx = -200f, commitDistancePx = commit, hasTarget = true), TOLERANCE)
    }

    @Test
    fun `past the commit point the follow is damped (overpull resistance)`() {
        // 200 + (400 - 200) * 0.35 = 270.
        assertEquals(-270f, swipeFollowOffset(rawDx = -400f, commitDistancePx = commit, hasTarget = true), TOLERANCE)
    }

    @Test
    fun `the follow is sign-symmetric for a rightward drag`() {
        // 200 + (300 - 200) * 0.35 = 235.
        assertEquals(235f, swipeFollowOffset(rawDx = 300f, commitDistancePx = commit, hasTarget = true), TOLERANCE)
    }

    @Test
    fun `a blocked edge is damped and capped to a fraction of the commit distance`() {
        // No target that way: min(1000 * 0.30, 200 * 0.40) = min(300, 80) = 80.
        assertEquals(-80f, swipeFollowOffset(rawDx = -1000f, commitDistancePx = commit, hasTarget = false), TOLERANCE)
    }

    @Test
    fun `a small drag into a blocked edge stays in the damped regime`() {
        // min(100 * 0.30, 80) = 30.
        assertEquals(-30f, swipeFollowOffset(rawDx = -100f, commitDistancePx = commit, hasTarget = false), TOLERANCE)
    }

    @Test
    fun `a zero commit distance yields no offset`() {
        assertEquals(0f, swipeFollowOffset(rawDx = -100f, commitDistancePx = 0f, hasTarget = true), TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
