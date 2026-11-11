package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.model.editor.EditorPreviewDirection.Horizontal
import fr.forumhfr.redface2.core.model.editor.EditorPreviewDirection.Vertical
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPreviewBoundsTest {
    @Test fun `initial position is half the budget excluding the 18 dp divider`() {
        assertEquals(300f, bounds(618f).restore(null), 0f)
        assertEquals(300f, bounds(618f).restore(Float.NaN), 0f)
    }

    @Test fun `restoration is clamped for every available size and keyboard state`() {
        listOf(false, true).forEach { ime ->
            val bounds = requireNotNull(editorPreviewBounds(500f, Vertical, ime, 110f))
            assertEquals(if (ime) 110f else 160f, bounds.restore(-100f), 0f)
            assertEquals(372f, bounds.restore(900f), 0f)
        }
    }

    @Test fun `real three line field governs both open IME floors and fallback`() {
        assertNull(editorPreviewBounds(300f, Vertical, true, null))
        assertNull(editorPreviewBounds(337f, Vertical, true, 160f))
        assertEquals(160f, requireNotNull(editorPreviewBounds(338f, Vertical, true, 160f)).restore(null), 0f)
        assertNull(editorPreviewBounds(378f, Vertical, true, 160f, sourceExtra = 41f))
    }

    @Test fun `horizontal provisional width floor is independent of vertical stop`() {
        assertTrue(PREVIEW_HORIZONTAL_MIN_DP != PREVIEW_CLOSED_SOURCE_MIN_DP)
        assertNull(editorPreviewBounds(2 * PREVIEW_HORIZONTAL_MIN_DP + 17f, Horizontal, false, 110f))
        val bounds = requireNotNull(editorPreviewBounds(500f, Horizontal, true, 200f))
        assertEquals(PREVIEW_HORIZONTAL_MIN_DP, bounds.restore(0f), 0f)
        assertEquals(482f - PREVIEW_HORIZONTAL_MIN_DP, bounds.restore(900f), 0f)
    }

    @Test fun `overdrag never hides during movement and release requests one hide only`() {
        val bounds = bounds(500f)
        val drag = EditorPreviewDrag(bounds.restore(null))
        drag.move(-500f)
        assertTrue(drag.overdrag(bounds) < 0f)
        assertTrue(drag.release(bounds, elastic = true))
        assertFalse(drag.release(bounds, elastic = true))
        drag.move(1_000f)
        assertTrue(drag.raw < 0f)
    }

    @Test fun `returning inside floors cancels hide and non elastic directions never hide`() {
        val bounds = bounds(500f)
        val drag = EditorPreviewDrag(200f)
        drag.move(-400f); drag.move(400f)
        assertFalse(drag.release(bounds, elastic = true))
        val hardStop = EditorPreviewDrag(-100f)
        assertFalse(hardStop.release(bounds, elastic = false))
        val previewFloor = EditorPreviewDrag(600f)
        assertTrue(previewFloor.release(bounds, elastic = true))
    }

    private fun bounds(extent: Float) = requireNotNull(editorPreviewBounds(extent, Vertical, true, 100f))
}
