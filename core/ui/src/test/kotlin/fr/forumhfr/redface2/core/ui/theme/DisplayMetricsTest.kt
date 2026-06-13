package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the [DisplayMetrics.Comfort] preset to the EXACT paddings shipped by the #398 structural
 * pass (lot A). This is the cheapest guard for the most expensive risk of #287 lot B: editing any
 * of these numbers (tuning Compact, a refactor, a copy-paste) would silently regress the DEFAULT
 * rendering with the CI staying green. If a value here must change, it is a deliberate product
 * decision and this test should change with it.
 */
class DisplayMetricsTest {

    @Test
    fun `Comfort reproduces the lot A shipped rhythm exactly`() {
        val comfort = DisplayMetrics.Comfort
        assertEquals(12.dp, comfort.cardBodyHorizontal)
        assertEquals(10.dp, comfort.cardBodyTop)
        assertEquals(8.dp, comfort.cardBodyBottom)
        assertEquals(6.dp, comfort.cardHeaderVertical)
        assertEquals(10.dp, comfort.listRowVertical)
        assertEquals(8.dp, comfort.postSpacing)
    }

    @Test
    fun `of maps each density to its preset`() {
        assertEquals(DisplayMetrics.Comfort, DisplayMetrics.of(DisplayDensity.COMFORT))
        assertEquals(DisplayMetrics.Compact, DisplayMetrics.of(DisplayDensity.COMPACT))
    }

    @Test
    fun `Compact is strictly denser than Comfort on every vertical metric`() {
        val comfort = DisplayMetrics.Comfort
        val compact = DisplayMetrics.Compact
        assertEquals(true, compact.cardBodyTop.value < comfort.cardBodyTop.value)
        assertEquals(true, compact.cardBodyBottom.value < comfort.cardBodyBottom.value)
        assertEquals(true, compact.cardHeaderVertical.value < comfort.cardHeaderVertical.value)
        assertEquals(true, compact.listRowVertical.value < comfort.listRowVertical.value)
        assertEquals(true, compact.postSpacing.value < comfort.postSpacing.value)
    }
}
