package fr.forumhfr.redface2.feature.topic

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `an armed leftward distance wins over a contradictory rightward fling`() {
        // Dragged firmly left past the commit distance (armed: page-follow, glow and haptic all say
        // "next page"), then the finger flicked back rightward at lift-off (positive velocity over the
        // fling threshold). The armed distance must win → next page, matching the feedback the user
        // already saw; the reverse fling must NOT open the previous page.
        assertEquals(true, swipeCommitDirection(totalDx = -250f, velocityX = 1500f, commitDistance, flingThreshold))
    }

    @Test
    fun `an armed rightward distance wins over a contradictory leftward fling`() {
        // Symmetric: dragged firmly right past the commit distance, finger flicked back leftward.
        assertEquals(false, swipeCommitDirection(totalDx = 250f, velocityX = -1500f, commitDistance, flingThreshold))
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
    fun `past the commit point the follow is a bounded tanh overpull`() {
        // overpull = 200 * 0.5 = 100 ; excess = 400 - 200 = 200.
        // 200 + 100 * tanh(200 / 100) = 200 + 100 * tanh(2) ≈ 296.4027.
        val offset = swipeFollowOffset(rawDx = -400f, commitDistancePx = commit, hasTarget = true)
        assertEquals(-296.4027f, offset, TOLERANCE)
    }

    @Test
    fun `the overpull is sign-symmetric for a rightward drag`() {
        // overpull = 100 ; excess = 300 - 200 = 100. 200 + 100 * tanh(1) ≈ 276.1594.
        val offset = swipeFollowOffset(rawDx = 300f, commitDistancePx = commit, hasTarget = true)
        assertEquals(276.1594f, offset, TOLERANCE)
    }

    @Test
    fun `the overpull saturates to a bounded cap on a very long drag`() {
        // As the drag grows the tanh saturates: travel → commit + overpull = 200 + 100 = 300,
        // never beyond. A huge drag must not slide the page arbitrarily far off-screen.
        val far = swipeFollowOffset(rawDx = -100_000f, commitDistancePx = commit, hasTarget = true)
        assertEquals(-300f, far, 0.5f)
        // At/under the asymptote (commit + overpull), never beyond a half-page extra past the commit.
        assertTrue("overpull $far exceeded the bound", abs(far) <= commit + commit * 0.5f)
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

    // --- swipeArmed : franchissement du seuil (tick haptique + indice) ---

    @Test
    fun `the swipe is not armed below the commit distance`() {
        assertFalse(swipeArmed(offsetPx = -150f, commitDistancePx = commit))
    }

    @Test
    fun `the swipe arms exactly at the commit distance`() {
        assertTrue(swipeArmed(offsetPx = -200f, commitDistancePx = commit))
    }

    @Test
    fun `the swipe stays armed past the commit distance, both signs`() {
        assertTrue(swipeArmed(offsetPx = -260f, commitDistancePx = commit))
        assertTrue(swipeArmed(offsetPx = 260f, commitDistancePx = commit))
    }

    @Test
    fun `a zero commit distance is never armed`() {
        assertFalse(swipeArmed(offsetPx = 100f, commitDistancePx = 0f))
    }

    // --- swipeEdgeHintAlpha : opacite de l'indice de bord (rampe + bump d'armement) ---

    @Test
    fun `the edge hint is invisible at rest`() {
        assertEquals(0f, swipeEdgeHintAlpha(progress = 0f), TOLERANCE)
    }

    @Test
    fun `the edge hint ramps to the pre-armed ceiling at the commit point`() {
        // progress 0.5 → 0.5 * 0.5 = 0.25 ; progress 1.0 → 0.5 (EDGE_HINT_MAX_ALPHA).
        assertEquals(0.25f, swipeEdgeHintAlpha(progress = 0.5f), TOLERANCE)
        assertEquals(0.5f, swipeEdgeHintAlpha(progress = 1f), TOLERANCE)
    }

    @Test
    fun `the edge hint is continuous across the arming point`() {
        // Just above 1.0 must equal just below 1.0 (no flicker jump).
        val justBelow = swipeEdgeHintAlpha(progress = 0.999f)
        val justAbove = swipeEdgeHintAlpha(progress = 1.001f)
        assertEquals(justBelow, justAbove, 0.01f)
    }

    @Test
    fun `the edge hint brightens to the armed accent past the threshold`() {
        // ramp window 0.15 ; progress 1.075 is halfway → 0.5 + (0.7 - 0.5) * 0.5 = 0.6.
        assertEquals(0.6f, swipeEdgeHintAlpha(progress = 1.075f), TOLERANCE)
    }

    @Test
    fun `the edge hint saturates at the armed accent and never exceeds it`() {
        // Full ramp at progress 1.15 → 0.7 ; deep overpull stays clamped at 0.7.
        assertEquals(0.7f, swipeEdgeHintAlpha(progress = 1.15f), TOLERANCE)
        assertEquals(0.7f, swipeEdgeHintAlpha(progress = 5f), TOLERANCE)
    }

    // --- blocked-edge glow suppression contract (feel-lens fix) ---
    // topicPageSwipeEdge gates the glow on `swipeTargetPage(...) == null` for the dragged direction:
    // at a wall the damped follow still moves the page (so the wall is felt) but no glow lights up,
    // since no neighbour page is being brought in. These two pure functions are the gate's inputs.

    @Test
    fun `at a blocked edge the page still moves but the direction has no target`() {
        // Last page dragged leftward (forward = next): damped wall produces a non-zero offset...
        val offset = swipeFollowOffset(rawDx = -1000f, commitDistancePx = commit, hasTarget = false)
        assertTrue("the wall should still give a little", abs(offset) > 0f)
        // ...yet there is no target page that way, so the call-site suppresses the glow.
        assertNull(swipeTargetPage(currentPage = 5, totalPages = 5, forward = true))
    }

    @Test
    fun `away from a blocked edge the same drag direction has a target`() {
        // From a middle page the forward direction has a target, so the glow is allowed.
        assertEquals(3, swipeTargetPage(currentPage = 2, totalPages = 5, forward = true))
        assertEquals(1, swipeTargetPage(currentPage = 2, totalPages = 5, forward = false))
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
