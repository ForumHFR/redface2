package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #555 — the pure budget behind the editor's {top zone + field} weighted box : the top zone
 * (draft banner, error banners, quote cards) gets whatever the available height leaves ABOVE
 * the field's guaranteed minimum, bounded by the roomy-display cap. Pinning it here is what
 * keeps « the field can never be crushed to zero by the IME + cards » true (thibw, dev 0.26.1).
 */
class EditorCardsZoneBudgetTest {

    @Test
    fun `roomy display keeps the historical cap`() {
        // 700 − 96 − 12 = 592 → capped at 360 (banner + « Tout vider » + ~4 cards).
        assertEquals(360.dp, editorTopZoneMaxHeight(available = 700.dp))
    }

    @Test
    fun `short window hands the top zone only what the field minimum leaves`() {
        // 300 − 96 − 12 = 192 → below the cap, the top zone shrinks.
        assertEquals(192.dp, editorTopZoneMaxHeight(available = 300.dp))
        // 200 − 96 − 12 = 92.
        assertEquals(92.dp, editorTopZoneMaxHeight(available = 200.dp))
    }

    @Test
    fun `tiny window gives everything to the field`() {
        // 100 − 96 − 12 < 0 → clamped to zero, the field takes the whole zone.
        assertEquals(0.dp, editorTopZoneMaxHeight(available = 100.dp))
    }

    @Test
    fun `field minimum plus spacing is the exact break-even point`() {
        assertEquals(0.dp, editorTopZoneMaxHeight(available = EDITOR_FIELD_MIN_HEIGHT + 12.dp))
    }
}
