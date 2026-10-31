package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.EditorSmileySource
import org.junit.Assert.assertEquals
import org.junit.Test

class SmileyDetailImageSizeTest {
    @Test
    fun `builtin stays modest while a dominant perso fits the preview with its aspect ratio`() {
        assertEquals(DpSize(32.dp, 32.dp), smileyDetailImageSize(EditorSmileySource.BUILTIN, null))
        assertEquals(DpSize(96.dp, 69.dp), smileyDetailImageSize(EditorSmileySource.WIKI, IntSize(70, 50)))
    }

    @Test
    fun `tiny perso enlargement is capped and large images fit both axes`() {
        assertEquals(DpSize(23.dp, 23.dp), smileyDetailImageSize(EditorSmileySource.WIKI, IntSize(15, 15)))
        assertEquals(DpSize(36.dp, 72.dp), smileyDetailImageSize(EditorSmileySource.WIKI, IntSize(100, 200)))
        assertEquals(DpSize(96.dp, 72.dp), smileyDetailImageSize(EditorSmileySource.HFR_FAVORITE, null))
    }
}
