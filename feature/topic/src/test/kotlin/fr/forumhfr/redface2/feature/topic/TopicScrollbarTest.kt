package fr.forumhfr.redface2.feature.topic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #300 — pure-geometry tests for the topic scrollbar, **fixed-size + ordinal** model. Composable
 * behaviour (auto-hide, gesture, the spring on the drawn offset) is not unit-tested here; the value is
 * in the position math, the drag→index inverse, and the regression guards for the "jumps + grows
 * suddenly" bug: the thumb size must be constant (never derived from content) and the position must be
 * a pure ordinal of the first-visible index (never derived from measured item sizes).
 */
class TopicScrollbarTest {

    private val tolerance = 1e-4f

    @Test
    fun `scrollbarMetrics returns null for a single item (no ordinal progress)`() {
        assertNull(scrollbarMetrics(firstVisibleItemIndex = 0, totalItemsCount = 1))
    }

    @Test
    fun `scrollbarMetrics returns null when there is no item`() {
        assertNull(scrollbarMetrics(firstVisibleItemIndex = 0, totalItemsCount = 0))
    }

    @Test
    fun `offsetFraction is 0 at the top of the page`() {
        val metrics = scrollbarMetrics(firstVisibleItemIndex = 0, totalItemsCount = 10)!!
        assertEquals(0f, metrics.offsetFraction, tolerance)
    }

    @Test
    fun `offsetFraction reaches 1 minus sizeFraction at the last ordinal`() {
        val metrics = scrollbarMetrics(firstVisibleItemIndex = 9, totalItemsCount = 10)!!
        assertEquals(1f - metrics.sizeFraction, metrics.offsetFraction, tolerance)
    }

    @Test
    fun `offsetFraction clamps a transient out-of-range index`() {
        // firstVisibleItemIndex should never exceed total, but a transient layoutInfo must not overshoot.
        val metrics = scrollbarMetrics(firstVisibleItemIndex = 20, totalItemsCount = 10)!!
        assertEquals(1f - metrics.sizeFraction, metrics.offsetFraction, tolerance)
    }

    // --- Regression guards for the "jumps + grows suddenly" bug -------------------------------------

    @Test
    fun `thumb size is a constant, independent of the page or position`() {
        // The two earlier models tied the size to a moving estimate (visible count, or viewport/estTotal);
        // that was the "grows suddenly" symptom. The fixed model must return the same size everywhere.
        assertEquals(THUMB_SIZE_FRACTION, scrollbarMetrics(0, 10)!!.sizeFraction, tolerance)
        assertEquals(THUMB_SIZE_FRACTION, scrollbarMetrics(5, 10)!!.sizeFraction, tolerance)
        assertEquals(THUMB_SIZE_FRACTION, scrollbarMetrics(500, 1000)!!.sizeFraction, tolerance)
    }

    @Test
    fun `position advances by equal ordinal steps`() {
        // Pure ordinal: each first-visible-index increment moves the thumb by the same amount,
        // (1/(total-1)) * (1 - sizeFraction) — no dependence on measured item heights.
        val m0 = scrollbarMetrics(0, 10)!!
        val m1 = scrollbarMetrics(1, 10)!!
        val m2 = scrollbarMetrics(2, 10)!!
        val firstStep = m1.offsetFraction - m0.offsetFraction
        val secondStep = m2.offsetFraction - m1.offsetFraction
        assertTrue("position is monotonic with the index", firstStep > 0f)
        assertEquals(firstStep, secondStep, tolerance)
        assertEquals((1f / 9f) * (1f - THUMB_SIZE_FRACTION), firstStep, tolerance)
    }

    // --- targetIndexForDrag (drag → first-visible item index), inverse of the ordinal mapping --------

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
        // 0.5 * (11 - 1) = 5.
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
    fun `drag and position are mutual inverses`() {
        // The thumb-travel fraction of ordinal i is offsetFraction / (1 - sizeFraction) = i / (total-1);
        // feeding it back must recover i.
        val total = 10
        for (i in 0 until total) {
            val metrics = scrollbarMetrics(firstVisibleItemIndex = i, totalItemsCount = total)!!
            val travelFraction = metrics.offsetFraction / (1f - metrics.sizeFraction)
            assertEquals(i, targetIndexForDrag(travelFraction, total))
        }
    }
}
