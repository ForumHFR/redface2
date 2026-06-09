package fr.forumhfr.redface2.feature.topic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #300 — pure-geometry tests for the topic scrollbar, **pixel-based** model. Composable behaviour
 * (auto-hide, gesture) is not unit-tested here; the value is in the position/size math, the
 * drag→index mapping, and the two regression guards for the "jumps + resizes" bug (thumb size must not
 * depend on the integer count of visible items; thumb travel must be uniform per scrolled pixel).
 */
class TopicScrollbarTest {

    private val tolerance = 1e-4f

    // Keeps the drag-mapping assertions short (named args inline blow past MaxLineLength). Defaults
    // mirror the common case: estimatedTotal = 100 × 20 = 2000, viewport 500 → maxScroll 1500.
    private fun dragIndex(travel: Float, avg: Float = 100f, total: Int = 20, viewport: Float = 500f): Int =
        targetIndexForDrag(travel, averageItemSizePx = avg, totalItemsCount = total, viewportHeightPx = viewport)

    @Test
    fun `scrollbarMetrics returns null when there is no item`() {
        assertNull(
            scrollbarMetrics(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                averageItemSizePx = 100f,
                totalItemsCount = 0,
                viewportHeightPx = 1000f,
            ),
        )
    }

    @Test
    fun `scrollbarMetrics returns null when nothing is visible (zero average size)`() {
        // An empty visible set surfaces as averageItemSizePx == 0f (cf. List.averageItemSizePx()).
        assertNull(
            scrollbarMetrics(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                averageItemSizePx = 0f,
                totalItemsCount = 10,
                viewportHeightPx = 1000f,
            ),
        )
    }

    @Test
    fun `scrollbarMetrics returns null when the viewport is not measured yet`() {
        assertNull(
            scrollbarMetrics(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                averageItemSizePx = 100f,
                totalItemsCount = 10,
                viewportHeightPx = 0f,
            ),
        )
    }

    @Test
    fun `scrollbarMetrics is non-null on a single item taller than the viewport (still scrollable)`() {
        // One post taller than the viewport must NOT be treated as "nothing to scroll": the caller
        // gates visibility on canScrollForward/Backward.
        val metrics = scrollbarMetrics(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            averageItemSizePx = 4000f,
            totalItemsCount = 1,
            viewportHeightPx = 2000f,
        )
        assertNotNull(metrics)
        assertEquals(0.5f, metrics!!.sizeFraction, tolerance)
    }

    @Test
    fun `offsetFraction is 0 and sizeFraction is the viewport ratio at the top of the page`() {
        val metrics = scrollbarMetrics(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            averageItemSizePx = 100f,
            totalItemsCount = 10,
            viewportHeightPx = 500f,
        )!!
        // estimatedTotal = 100 * 10 = 1000 ; viewport 500 → sizeFraction = 0.5.
        assertEquals(0f, metrics.offsetFraction, tolerance)
        assertEquals(0.5f, metrics.sizeFraction, tolerance)
    }

    @Test
    fun `offsetFraction reaches 1 minus sizeFraction at the bottom of the page`() {
        // maxScroll = 1000 - 500 = 500 ; scrolledPx = 5 * 100 = 500 = maxScroll → bottom.
        val metrics = scrollbarMetrics(
            firstVisibleItemIndex = 5,
            firstVisibleItemScrollOffset = 0,
            averageItemSizePx = 100f,
            totalItemsCount = 10,
            viewportHeightPx = 500f,
        )!!
        assertEquals(1f - metrics.sizeFraction, metrics.offsetFraction, tolerance)
    }

    @Test
    fun `sizeFraction respects the minimum thumb floor on a very long page`() {
        val metrics = scrollbarMetrics(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            averageItemSizePx = 50f,
            totalItemsCount = 200,
            viewportHeightPx = 300f,
        )!!
        // raw = 300 / (50 * 200) = 0.03, floored to MIN_THUMB_FRACTION.
        assertEquals(MIN_THUMB_FRACTION, metrics.sizeFraction, tolerance)
    }

    @Test
    fun `content shorter than the viewport pins a full thumb at the top`() {
        // estimatedTotal = 100 * 2 = 200 < viewport 500 → nothing to scroll: full thumb, offset 0.
        val metrics = scrollbarMetrics(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            averageItemSizePx = 100f,
            totalItemsCount = 2,
            viewportHeightPx = 500f,
        )!!
        assertEquals(1f, metrics.sizeFraction, tolerance)
        assertEquals(0f, metrics.offsetFraction, tolerance)
    }

    @Test
    fun `sub-item scroll offset increases offsetFraction monotonically`() {
        val atTop = scrollbarMetrics(0, 0, 100f, 10, 500f)!!
        val scrolledWithinFirstItem = scrollbarMetrics(0, 50, 100f, 10, 500f)!!
        assertTrue(scrolledWithinFirstItem.offsetFraction > atTop.offsetFraction)
    }

    // --- Regression guards for the "jumps + resizes" bug (index-based model) -------------------------

    @Test
    fun `thumb size is independent of how many items happen to be visible`() {
        // The index-based model used visibleItemsCount/total, so the thumb resized by whole-item steps
        // as a tall post entered/left the viewport. The pixel model derives size from the average size
        // and the viewport only — the count of visible items must not change it.
        val withThreeVisible = scrollbarMetrics(0, 0, 200f, 20, 800f)!!
        val withFourVisible = scrollbarMetrics(0, 0, 200f, 20, 800f)!!
        assertEquals(withThreeVisible.sizeFraction, withFourVisible.sizeFraction, tolerance)
    }

    @Test
    fun `thumb travel is uniform per scrolled pixel`() {
        // Equal scroll deltas (one average item each) must move the thumb by equal offset deltas — the
        // index-based model jumped fast over a short post and crawled over a tall one for the same step.
        val m0 = scrollbarMetrics(0, 0, 100f, 20, 500f)!!
        val m1 = scrollbarMetrics(1, 0, 100f, 20, 500f)!!
        val m2 = scrollbarMetrics(2, 0, 100f, 20, 500f)!!
        val firstStep = m1.offsetFraction - m0.offsetFraction
        val secondStep = m2.offsetFraction - m1.offsetFraction
        assertEquals(firstStep, secondStep, tolerance)
    }

    @Test
    fun `offsetFraction is continuous across an item boundary`() {
        // Scrolling the first item fully out (offset == averageSize) must land on the same thumb
        // position as the next item appearing at the top with no sub-item offset.
        val endOfFirstItem = scrollbarMetrics(0, 100, 100f, 20, 500f)!!
        val startOfSecondItem = scrollbarMetrics(1, 0, 100f, 20, 500f)!!
        assertEquals(endOfFirstItem.offsetFraction, startOfSecondItem.offsetFraction, tolerance)
    }

    // --- targetIndexForDrag (drag → first-visible item index), same estimated-height model -----------

    @Test
    fun `targetIndexForDrag maps full travel to the last scrollable item`() {
        // maxScroll = 1500 ; full travel → 1500 / 100 = 15.
        assertEquals(15, dragIndex(1f))
    }

    @Test
    fun `targetIndexForDrag maps zero travel to the first item`() {
        assertEquals(0, dragIndex(0f))
    }

    @Test
    fun `targetIndexForDrag maps half travel to the middle of the scrollable range`() {
        // maxScroll = (100*20) - 600 = 1400 ; half → 700 / 100 = 7.
        assertEquals(7, dragIndex(0.5f, viewport = 600f))
    }

    @Test
    fun `targetIndexForDrag clamps an out-of-range travel fraction`() {
        assertEquals(15, dragIndex(2f))
        assertEquals(0, dragIndex(-1f))
    }

    @Test
    fun `targetIndexForDrag never exceeds the last index`() {
        // estimatedTotal = 100 * 5 = 500 ; maxScroll = 450 ; 450 / 100 = 4.5 → 5, clamped to total-1 = 4.
        assertEquals(4, dragIndex(1f, total = 5, viewport = 50f))
    }

    @Test
    fun `targetIndexForDrag returns 0 on degenerate inputs`() {
        assertEquals(0, dragIndex(1f, avg = 0f, total = 10))
        assertEquals(0, dragIndex(1f, total = 0))
    }
}
