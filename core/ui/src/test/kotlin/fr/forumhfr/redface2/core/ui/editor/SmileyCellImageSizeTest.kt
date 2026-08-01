package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.EditorSmileySource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #816 (thibw) — the picker mirrors HFR's real scale contrast : builtin sprites near-native
 * (small), perso smileys filling most of the 48.dp cell (large). One uniform size cannot do
 * both — this pins the per-source contract.
 */
class SmileyCellImageSizeTest {

    @Test
    fun `builtin sprites render near their native scale`() {
        assertEquals(20.dp, smileyCellImageSize(EditorSmileySource.BUILTIN))
    }

    @Test
    fun `perso smileys fill most of the cell`() {
        assertEquals(44.dp, smileyCellImageSize(EditorSmileySource.WIKI))
    }
}
