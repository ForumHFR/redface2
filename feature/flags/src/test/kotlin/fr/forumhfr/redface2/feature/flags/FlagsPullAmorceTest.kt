package fr.forumhfr.redface2.feature.flags

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #728 — visibility rule of the pull indicator (the redface puck) and the pull-refresh choreography
 * helpers: the « settling » guard (« ça repop en fin de load », XaTriX) and the manual-refresh
 * persistence (M3 « keep the indicator in view until the activity completes »). The content-push is now
 * driven purely by the live pull distance (no hold helper), so there is nothing extra to unit-test there.
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
    fun `trackManualRefresh is a no-op when not armed`() = runTest {
        assertFalse(trackManualRefresh(armed = false, refreshing = MutableStateFlow(true)))
    }

    @Test
    fun `trackManualRefresh stays armed through the refresh then disarms on completion`() = runTest {
        val refreshing = MutableStateFlow(false)
        val tracked = async { trackManualRefresh(armed = true, refreshing = refreshing) }
        runCurrent()
        refreshing.value = true // the manual refresh actually starts
        runCurrent()
        assertTrue("still armed while the refresh is running", tracked.isActive)
        refreshing.value = false // the refresh completes
        assertFalse(tracked.await()) // disarmed once it lands
    }

    @Test
    fun `trackManualRefresh disarms after the grace when the pull is a no-op`() = runTest {
        // The pull is throttled to a no-op (no refresh ever starts): the grace timeout must disarm the
        // flag so the thin top bar is never suppressed forever. runTest auto-advances the virtual clock
        // past MANUAL_REFRESH_GRACE_MS.
        assertFalse(trackManualRefresh(armed = true, refreshing = MutableStateFlow(false)))
    }
}
