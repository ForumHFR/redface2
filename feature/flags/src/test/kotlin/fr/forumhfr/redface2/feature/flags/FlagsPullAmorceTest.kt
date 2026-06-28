package fr.forumhfr.redface2.feature.flags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #728 — visibility rule of the pull indicator (the redface puck) and the pull-refresh choreography
 * helpers: the « settling » guard (« ça repop en fin de load », XaTriX), the manual-refresh persistence
 * (M3 « keep the indicator in view until the activity completes »), and the content-push hold.
 */
class FlagsPullAmorceTest {

    @Test
    fun `shown while actively pulling`() {
        assertTrue(
            shouldShowPullIndicator(
                distanceFraction = 0.5f,
                isRefreshing = false,
                manualRefresh = false,
                settling = false,
            ),
        )
    }

    @Test
    fun `hidden at rest`() {
        assertFalse(
            shouldShowPullIndicator(
                distanceFraction = 0f,
                isRefreshing = false,
                manualRefresh = false,
                settling = false,
            ),
        )
    }

    @Test
    fun `shown during a MANUAL refresh as the hero, even with no pull distance`() {
        // M3 « keep the indicator in view until the activity completes » — the puck persists through a
        // gesture-driven refresh.
        assertTrue(
            shouldShowPullIndicator(
                distanceFraction = 0f,
                isRefreshing = true,
                manualRefresh = true,
                settling = false,
            ),
        )
    }

    @Test
    fun `hidden during an AUTO refresh even WITH leftover pull distance (no double indicator)`() {
        // Must-fix (Codex): an auto/cold refresh that overlaps a residual drag must NOT show the puck —
        // the thin top bar is the only cue then, never both.
        assertFalse(
            shouldShowPullIndicator(
                distanceFraction = 0.5f,
                isRefreshing = true,
                manualRefresh = false,
                settling = false,
            ),
        )
    }

    @Test
    fun `hidden while settling after refresh even with leftover pull distance`() {
        // After a refresh, isRefreshing clears while distanceFraction is still animating back to 0.
        // Without the settling guard the puck re-pops for those frames.
        assertFalse(
            shouldShowPullIndicator(
                distanceFraction = 0.5f,
                isRefreshing = false,
                manualRefresh = false,
                settling = true,
            ),
        )
    }

    @Test
    fun `anyRefreshing is true when either list refreshes`() {
        assertTrue(anyRefreshing(isRefreshing = true, dtIsRefreshing = false))
        assertTrue(anyRefreshing(isRefreshing = false, dtIsRefreshing = true))
        assertTrue(anyRefreshing(isRefreshing = true, dtIsRefreshing = true))
        assertFalse(anyRefreshing(isRefreshing = false, dtIsRefreshing = false))
    }

    @Test
    fun `pullHoldTarget holds the content down only during a manual refresh`() {
        assertEquals(1f, pullHoldTarget(manualRefresh = true, isRefreshing = true), 0f)
        assertEquals(0f, pullHoldTarget(manualRefresh = true, isRefreshing = false), 0f)
        assertEquals(0f, pullHoldTarget(manualRefresh = false, isRefreshing = true), 0f)
    }
}
