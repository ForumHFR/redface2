package fr.forumhfr.redface2.feature.topic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #300 — pure-geometry tests for the topic scrollbar, **fixed-size ordinal + sub-item interpolation**.
 * Composable behaviour (auto-hide, gesture, the offset spring) is not unit-tested here; the value is in
 * the position math, the drag→index inverse, and the regression guards: the thumb size is constant
 * (never derived from content), the position interpolates continuously within a post (no per-post step),
 * and a #197-style growth of the top post can move the thumb by at most one ordinal step.
 */
class TopicScrollbarTest {

    private val tolerance = 1e-4f

    // Keeps the 4-arg calls short. Defaults: a 10-item page, 100px items, at the very top.
    private fun metrics(index: Int, offset: Int = 0, size: Int = 100, total: Int = 10) =
        scrollbarMetrics(
            firstVisibleItemIndex = index,
            firstVisibleItemScrollOffset = offset,
            firstVisibleItemSize = size,
            totalItemsCount = total,
        )

    private fun oneOrdinalStep(total: Int) = (1f / (total - 1)) * (1f - THUMB_SIZE_FRACTION)

    @Test
    fun `returns null for a single item (no ordinal progress)`() {
        assertNull(metrics(index = 0, total = 1))
    }

    @Test
    fun `returns null when there is no item`() {
        assertNull(metrics(index = 0, total = 0))
    }

    @Test
    fun `offsetFraction is 0 at the very top`() {
        assertEquals(0f, metrics(0)!!.offsetFraction, tolerance)
    }

    @Test
    fun `offsetFraction reaches 1 minus sizeFraction at the last ordinal`() {
        val m = metrics(9)!!
        assertEquals(1f - m.sizeFraction, m.offsetFraction, tolerance)
    }

    @Test
    fun `offsetFraction clamps a transient out-of-range index`() {
        val m = metrics(20)!!
        assertEquals(1f - m.sizeFraction, m.offsetFraction, tolerance)
    }

    // --- Regression guards --------------------------------------------------------------------------

    @Test
    fun `thumb size is a constant, independent of page or position`() {
        assertEquals(THUMB_SIZE_FRACTION, metrics(0)!!.sizeFraction, tolerance)
        assertEquals(THUMB_SIZE_FRACTION, metrics(5)!!.sizeFraction, tolerance)
        assertEquals(THUMB_SIZE_FRACTION, metrics(500, total = 1000)!!.sizeFraction, tolerance)
    }

    @Test
    fun `sub-item interpolation moves the thumb continuously within a post`() {
        // Scrolling within the first post (offset 0 → mid) advances the thumb, but stays inside the
        // first ordinal step (never reaches the next anchor).
        val top = metrics(0, offset = 0)!!.offsetFraction
        val mid = metrics(0, offset = 50)!!.offsetFraction
        val nextAnchor = metrics(1, offset = 0)!!.offsetFraction
        assertTrue("interpolates upward within the post", mid > top)
        assertTrue("stays below the next anchor", mid < nextAnchor)
    }

    @Test
    fun `position is near-continuous across a post boundary (no per-post step)`() {
        // End of post 0 (offset clamped to size-1) vs start of post 1: the gap must be a tiny fraction
        // of one ordinal step, NOT a full step — that is what removes the "à-coup".
        val endOfFirst = metrics(0, offset = 99, size = 100)!!.offsetFraction
        val startOfSecond = metrics(1, offset = 0, size = 100)!!.offsetFraction
        val gap = startOfSecond - endOfFirst
        assertTrue("monotonic across the boundary", gap >= 0f)
        assertTrue("gap is far smaller than one ordinal step", gap < oneOrdinalStep(10) * 0.1f)
    }

    @Test
    fun `the last post ignores sub-item offset so progress never exceeds 1`() {
        // index == lastIndex forces itemFraction = 0; a non-zero offset must not push offset past the end.
        assertEquals(metrics(9, offset = 0)!!.offsetFraction, metrics(9, offset = 50)!!.offsetFraction, tolerance)
        assertEquals(1f - THUMB_SIZE_FRACTION, metrics(9, offset = 50)!!.offsetFraction, tolerance)
    }

    @Test
    fun `a zero item size falls back to the pure ordinal anchor`() {
        // size 0 (item not measured yet) must not divide by zero; itemFraction = 0.
        val ordinal = metrics(2, offset = 0)!!.offsetFraction
        val unmeasured = metrics(2, offset = 30, size = 0)!!.offsetFraction
        assertEquals(ordinal, unmeasured, tolerance)
    }

    @Test
    fun `an oversized offset is clamped within one ordinal step`() {
        // A transient offset larger than the item can't push the thumb past the next anchor.
        val clamped = metrics(0, offset = 10_000, size = 100)!!.offsetFraction
        assertTrue(clamped < metrics(1, offset = 0)!!.offsetFraction)
    }

    @Test
    fun `a top-post growth wobbles the thumb by less than one ordinal step`() {
        // #197 bound: same scroll offset, the top post grows 160→480 (px proxy). The thumb moves by less
        // than one ordinal step while that post is on top — acceptable vs the per-post jerk it removes.
        val before = metrics(3, offset = 120, size = 160)!!.offsetFraction
        val after = metrics(3, offset = 120, size = 480)!!.offsetFraction
        assertTrue(kotlin.math.abs(after - before) < oneOrdinalStep(10))
    }

    // --- targetIndexForDrag (drag → first-visible item index), inverse of the ordinal anchors --------

    @Test
    fun `targetIndexForDrag maps full travel to the last item`() {
        assertEquals(9, targetIndexForDrag(travelFraction = 1f, totalItemsCount = 10))
    }

    @Test
    fun `targetIndexForDrag maps zero travel to the first item`() {
        assertEquals(0, targetIndexForDrag(travelFraction = 0f, totalItemsCount = 10))
    }

    @Test
    fun `targetIndexForDrag maps half travel to the middle ordinal`() {
        assertEquals(5, targetIndexForDrag(travelFraction = 0.5f, totalItemsCount = 11))
    }

    @Test
    fun `targetIndexForDrag clamps an out-of-range travel fraction`() {
        assertEquals(9, targetIndexForDrag(travelFraction = 2f, totalItemsCount = 10))
        assertEquals(0, targetIndexForDrag(travelFraction = -1f, totalItemsCount = 10))
    }

    @Test
    fun `targetIndexForDrag returns 0 on a degenerate count`() {
        assertEquals(0, targetIndexForDrag(travelFraction = 1f, totalItemsCount = 1))
        assertEquals(0, targetIndexForDrag(travelFraction = 1f, totalItemsCount = 0))
    }

    @Test
    fun `drag and the post-anchor position are mutual inverses`() {
        // At a post anchor (offset 0) the thumb-travel fraction is offsetFraction / (1 - sizeFraction) =
        // i / (total-1); feeding it back must recover i.
        val total = 10
        for (i in 0 until total) {
            val m = metrics(index = i, offset = 0, total = total)!!
            val travelFraction = m.offsetFraction / (1f - m.sizeFraction)
            assertEquals(i, targetIndexForDrag(travelFraction, total))
        }
    }
}
