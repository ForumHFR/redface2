package fr.forumhfr.redface2.feature.topic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #300 — pure-geometry tests for the topic scrollbar. Composable behaviour (auto-hide, gesture) is not
 * unit-tested here; the value is in the position/size math and the drag→index mapping.
 */
class TopicScrollbarTest {

    private val tolerance = 1e-4f

    @Test
    fun `scrollbarMetrics returns null when there is no item`() {
        assertNull(
            scrollbarMetrics(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                firstVisibleItemSize = 100,
                visibleItemsCount = 0,
                totalItemsCount = 0,
            ),
        )
    }

    @Test
    fun `scrollbarMetrics returns null when no item is visible`() {
        assertNull(
            scrollbarMetrics(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                firstVisibleItemSize = 100,
                visibleItemsCount = 0,
                totalItemsCount = 10,
            ),
        )
    }

    @Test
    fun `scrollbarMetrics is non-null even when all items are visible (tall item, still scrollable)`() {
        // visibleItemsCount >= totalItemsCount must NOT be treated as "nothing to scroll": a single item
        // taller than the viewport is scrollable. The caller gates visibility on canScrollForward/Backward.
        val metrics = scrollbarMetrics(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            firstVisibleItemSize = 4000,
            visibleItemsCount = 1,
            totalItemsCount = 1,
        )
        assertNotNull(metrics)
    }

    @Test
    fun `offsetFraction is 0 at the top of the page`() {
        val metrics = scrollbarMetrics(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            firstVisibleItemSize = 100,
            visibleItemsCount = 5,
            totalItemsCount = 10,
        )!!
        assertEquals(0f, metrics.offsetFraction, tolerance)
        assertEquals(0.5f, metrics.sizeFraction, tolerance)
    }

    @Test
    fun `offsetFraction reaches 1 minus sizeFraction at the bottom of the page`() {
        val metrics = scrollbarMetrics(
            firstVisibleItemIndex = 5,
            firstVisibleItemScrollOffset = 0,
            firstVisibleItemSize = 100,
            visibleItemsCount = 5,
            totalItemsCount = 10,
        )!!
        assertEquals(1f - metrics.sizeFraction, metrics.offsetFraction, tolerance)
    }

    @Test
    fun `sizeFraction respects the minimum thumb floor on a very long page`() {
        val metrics = scrollbarMetrics(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            firstVisibleItemSize = 50,
            visibleItemsCount = 2,
            totalItemsCount = 200,
        )!!
        // raw = 2/200 = 0.01, floored to MIN_THUMB_FRACTION.
        assertEquals(MIN_THUMB_FRACTION, metrics.sizeFraction, tolerance)
    }

    @Test
    fun `sub-item scroll offset increases offsetFraction monotonically`() {
        val atTop = scrollbarMetrics(0, 0, 100, 5, 10)!!
        val scrolledWithinFirstItem = scrollbarMetrics(0, 50, 100, 5, 10)!!
        assertTrue(scrolledWithinFirstItem.offsetFraction > atTop.offsetFraction)
    }

    @Test
    fun `firstVisibleItemSize of zero does not crash and contributes no sub-item offset`() {
        val metrics = scrollbarMetrics(
            firstVisibleItemIndex = 2,
            firstVisibleItemScrollOffset = 30,
            firstVisibleItemSize = 0,
            visibleItemsCount = 5,
            totalItemsCount = 10,
        )!!
        assertEquals(0.2f, metrics.offsetFraction, tolerance)
    }

    @Test
    fun `targetIndexForDrag maps full travel below the visible window`() {
        assertEquals(7, targetIndexForDrag(travelFraction = 1f, visibleItemsCount = 3, totalItemsCount = 10))
    }

    @Test
    fun `targetIndexForDrag maps zero travel to the first item`() {
        assertEquals(0, targetIndexForDrag(travelFraction = 0f, visibleItemsCount = 3, totalItemsCount = 10))
    }

    @Test
    fun `targetIndexForDrag maps half travel to the middle of the scrollable range`() {
        assertEquals(4, targetIndexForDrag(travelFraction = 0.5f, visibleItemsCount = 2, totalItemsCount = 10))
    }

    @Test
    fun `targetIndexForDrag clamps an out-of-range travel fraction`() {
        assertEquals(7, targetIndexForDrag(travelFraction = 2f, visibleItemsCount = 3, totalItemsCount = 10))
        assertEquals(0, targetIndexForDrag(travelFraction = -1f, visibleItemsCount = 3, totalItemsCount = 10))
    }

    @Test
    fun `targetIndexForDrag never returns a negative index when visible exceeds total mid-drag`() {
        // Simulates totalItemsCount shrinking under the drag (e.g. a refresh removed posts).
        assertEquals(0, targetIndexForDrag(travelFraction = 1f, visibleItemsCount = 20, totalItemsCount = 10))
    }
}
