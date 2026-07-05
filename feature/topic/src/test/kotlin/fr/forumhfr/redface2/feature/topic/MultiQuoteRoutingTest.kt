package fr.forumhfr.redface2.feature.topic

import fr.forumhfr.redface2.core.model.editor.WritingSurfacePreset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #806 — the writing-surface routing table (preset × quote count → surface), decided at tap time
 * by [writingSurfaceFor]. Pins SHEET to the exact 0.25.1 behaviour (#604 lot 3, mockup P3 : « le
 * cas qui force le plein écran », threshold 3 as a named constant) so a future tweak is a
 * deliberate table change, not an off-by-one.
 */
class MultiQuoteRoutingTest {

    private fun assertSurface(expected: WritingSurface, preset: WritingSurfacePreset, quoteCount: Int) {
        assertEquals(
            "preset=$preset quoteCount=$quoteCount",
            expected,
            writingSurfaceFor(preset, quoteCount),
        )
    }

    @Test
    fun `SHEET preset pins the 0_25_1 behaviour - sheet up to two cards, full editor from three`() {
        // 0 = the reply FAB ; 1 = « Citer » ; 2 = « Citer 2 » — all stay in the quick-reply sheet.
        assertSurface(WritingSurface.SHEET, WritingSurfacePreset.SHEET, quoteCount = 0)
        assertSurface(WritingSurface.SHEET, WritingSurfacePreset.SHEET, quoteCount = 1)
        assertSurface(WritingSurface.SHEET, WritingSurfacePreset.SHEET, quoteCount = 2)
        // #604 lot 3 — from the named threshold up, the full-screen editor takes over.
        assertSurface(
            WritingSurface.FULL_EDITOR,
            WritingSurfacePreset.SHEET,
            quoteCount = MULTI_QUOTE_FULL_EDITOR_THRESHOLD,
        )
        assertSurface(WritingSurface.FULL_EDITOR, WritingSurfacePreset.SHEET, quoteCount = 4)
    }

    @Test
    fun `SHEET_EXCEPT_QUOTES preset keeps the sheet for plain replies and escalates any citation`() {
        assertSurface(WritingSurface.SHEET, WritingSurfacePreset.SHEET_EXCEPT_QUOTES, quoteCount = 0)
        assertSurface(WritingSurface.FULL_EDITOR, WritingSurfacePreset.SHEET_EXCEPT_QUOTES, quoteCount = 1)
        assertSurface(WritingSurface.FULL_EDITOR, WritingSurfacePreset.SHEET_EXCEPT_QUOTES, quoteCount = 2)
        assertSurface(WritingSurface.FULL_EDITOR, WritingSurfacePreset.SHEET_EXCEPT_QUOTES, quoteCount = 3)
    }

    @Test
    fun `FULL_EDITOR preset always opens the full-screen editor`() {
        assertSurface(WritingSurface.FULL_EDITOR, WritingSurfacePreset.FULL_EDITOR, quoteCount = 0)
        assertSurface(WritingSurface.FULL_EDITOR, WritingSurfacePreset.FULL_EDITOR, quoteCount = 1)
        assertSurface(WritingSurface.FULL_EDITOR, WritingSurfacePreset.FULL_EDITOR, quoteCount = 2)
        assertSurface(WritingSurface.FULL_EDITOR, WritingSurfacePreset.FULL_EDITOR, quoteCount = 3)
    }
}
