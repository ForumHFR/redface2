package fr.forumhfr.redface2.feature.flags

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #603 (XaTriX dogfood) — visibility rule of the pull « amorce » indicator, including the
 * post-refresh « settling » guard that fixes the « ça repop en fin de load » bug.
 */
class FlagsPullAmorceTest {

    @Test
    fun `shown while actively pulling`() {
        assertTrue(
            shouldShowPullAmorce(isRefreshing = false, distanceFraction = 0.5f, settling = false),
        )
    }

    @Test
    fun `hidden at rest`() {
        assertFalse(
            shouldShowPullAmorce(isRefreshing = false, distanceFraction = 0f, settling = false),
        )
    }

    @Test
    fun `hidden during refresh`() {
        assertFalse(
            shouldShowPullAmorce(isRefreshing = true, distanceFraction = 0.5f, settling = false),
        )
    }

    @Test
    fun `hidden while settling after refresh even with leftover pull distance`() {
        // The bug: after a refresh, isRefreshing clears while distanceFraction is still animating back
        // to 0. Without the settling guard the indicator re-pops for those frames.
        assertFalse(
            shouldShowPullAmorce(isRefreshing = false, distanceFraction = 0.5f, settling = true),
        )
    }
}
