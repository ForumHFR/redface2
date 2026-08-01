package fr.forumhfr.redface2.feature.topic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #604 lot 3 — the « Citer N » threshold boundary (mockup P3 : « le cas qui force le plein
 * écran », cadrage Codex : 3 citations = plein écran, constante nommée). Pins the boundary so
 * a future tweak is a deliberate constant change, not an off-by-one.
 */
class MultiQuoteRoutingTest {

    @Test
    fun `one or two cards stay in the quick-reply sheet`() {
        // 0 is unreachable from the « Citer N » FAB (it only renders armed) — documented inert.
        assertFalse(multiQuoteOpensFullEditor(0))
        assertFalse(multiQuoteOpensFullEditor(1))
        assertFalse(multiQuoteOpensFullEditor(2))
    }

    @Test
    fun `three cards and up force the full-screen editor`() {
        assertTrue(multiQuoteOpensFullEditor(MULTI_QUOTE_FULL_EDITOR_THRESHOLD))
        assertTrue(multiQuoteOpensFullEditor(4))
    }
}
