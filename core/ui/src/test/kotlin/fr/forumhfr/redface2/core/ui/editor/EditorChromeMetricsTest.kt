package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure policy tests; no claim about the Android window adapter or the integrated text field. */
class EditorChromeMetricsTest {
    @Test
    fun `ED02 regular envelopes reserve the 160 dp floor exactly at 276 dp`() {
        val metrics = EditorChromeMetrics(compactHeight = false)
        assertEquals(48.dp, metrics.appBarHeight)
        assertEquals(56.dp, metrics.actionBarHeight)
        assertEquals(12.dp, metrics.columnSpacing)
        assertTrue(metrics.showFieldLabel)
        assertEquals(160.dp, metrics.draftHeight(276.dp))
        assertEquals(159.dp, metrics.draftHeight(275.dp))
        assertEquals(214.dp, metrics.draftHeight(330.dp))
        assertEquals(0.dp, metrics.draftHeight(20.dp))
        assertFalse(metrics.draftAllocation(275.dp, 24.dp, 16.dp, 3).meetsHeightFloor)
    }

    @Test
    fun `ED05 compact removes app bar spacing label and the 160 dp floor`() {
        val metrics = EditorChromeMetrics(compactHeight = true)
        assertEquals(0.dp, metrics.appBarHeight)
        assertEquals(48.dp, metrics.actionBarHeight)
        assertEquals(0.dp, metrics.columnSpacing)
        assertEquals(0.dp, metrics.draftHeightFloor)
        assertFalse(metrics.showFieldLabel)
        assertEquals(90.dp, metrics.draftHeight(138.dp))
        assertTrue(metrics.draftAllocation(138.dp, 24.dp, 16.dp, 3).meetsHeightFloor)
    }

    @Test
    fun `J keeps three and two line minima and reports the unresolved small window`() {
        val metrics = EditorChromeMetrics(compactHeight = true)
        // Injected R0a reference line/decoration measurements, not a substitute for BTF2 measurement.
        assertTrue(metrics.draftAllocation(136.dp, 24.dp, 16.dp, 3).meetsLineMinimum)
        assertFalse(metrics.draftAllocation(135.dp, 24.dp, 16.dp, 3).meetsLineMinimum)
        assertTrue(metrics.draftAllocation(130.dp, 33.dp, 16.dp, 2).meetsLineMinimum)
        assertFalse(metrics.draftAllocation(129.dp, 33.dp, 16.dp, 2).meetsLineMinimum)
        val insufficient = metrics.draftAllocation(89.dp, 33.dp, 16.dp, 2)
        assertEquals(41.dp, insufficient.height)
        assertEquals(0, insufficient.completeLines)
        assertFalse(insufficient.meetsLineMinimum)
    }

    @Test
    fun `P20 subtracts stable vertical insets once with a strict 480 dp threshold`() {
        listOf(0.dp to 0.dp, 24.dp to 30.dp, 24.dp to 48.dp).forEach { (top, bottom) ->
            val threshold = 480.dp + top + bottom
            assertTrue(EditorWindowMetrics(threshold - 0.1.dp, top, bottom).compactHeight)
            val exact = EditorWindowMetrics(threshold, top, bottom)
            assertFalse(exact.compactHeight)
            assertEquals(480.dp, exact.usableHeight)
            assertFalse(EditorWindowMetrics(threshold + 1.dp, top, bottom).compactHeight)
        }
    }

    @Test
    fun `P20 current multiwindow height changes class while content resizing cannot enter it`() {
        val window = EditorWindowMetrics(760.dp, 24.dp, 30.dp)
        val metrics = EditorChromeMetrics(window.compactHeight)
        assertEquals(590.dp, metrics.draftHeight(window.usableHeight))
        assertEquals(204.dp, metrics.draftHeight(window.usableHeight - 386.dp))
        assertFalse(window.compactHeight)
        assertTrue(window.copy(windowHeight = 470.dp).compactHeight)
        // A side navigation bar has no vertical inset: width is handled by the column's constraints.
        assertFalse(EditorWindowMetrics(504.dp, 24.dp, 0.dp).compactHeight)
    }
}
