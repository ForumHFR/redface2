package fr.forumhfr.redface2.core.model

import fr.forumhfr.redface2.core.model.editor.EditorPreviewDirection
import fr.forumhfr.redface2.core.model.editor.EditorPreviewKey
import fr.forumhfr.redface2.core.model.editor.EditorPreviewWidthClass
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorPreviewKeyTest {
    @Test fun `width classes use current window breakpoints`() {
        listOf(360f to EditorPreviewWidthClass.Compact, 599f to EditorPreviewWidthClass.Compact,
            600f to EditorPreviewWidthClass.Medium, 839f to EditorPreviewWidthClass.Medium,
            840f to EditorPreviewWidthClass.Expanded).forEach { (width, expected) ->
            assertEquals(expected, EditorPreviewWidthClass.fromWidthDp(width))
        }
    }

    @Test fun `all direction and width pairs have independent stable keys`() {
        val keys = EditorPreviewDirection.entries.flatMap { direction ->
            EditorPreviewWidthClass.entries.map { EditorPreviewKey(direction, it).storageKey }
        }
        assertEquals(6, keys.toSet().size)
        assertEquals("editor_preview_source_dp_vertical_compact", keys.first())
        assertEquals("editor_preview_source_dp_horizontal_expanded", keys.last())
    }
}
