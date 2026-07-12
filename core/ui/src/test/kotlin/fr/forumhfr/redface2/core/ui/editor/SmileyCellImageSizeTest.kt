package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.EditorSmileySource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #816 (thibw) — the picker mirrors HFR's real scale contrast : builtin sprites near-native
 * (small), perso smileys getting most of the 48.dp cell. One uniform size cannot do both —
 * this pins the per-source contract.
 *
 * #871 (thibw) — a measured perso follows the posts' no-upscale + cap policy (#175) : native
 * size at the forum scale (1 px ≈ 1 dp) when it fits the cell, scaled down when it does not,
 * NEVER stretched past native (the 0.26.3 "small wiki smileys are zoomed and blurry" report).
 */
class SmileyCellImageSizeTest {

    @Test
    fun `builtin sprites render near their native scale`() {
        assertEquals(DpSize(20.dp, 20.dp), smileyCellImageSize(EditorSmileySource.BUILTIN, measuredPx = null))
    }

    @Test
    fun `unmeasured perso falls back to filling most of the cell`() {
        assertEquals(DpSize(44.dp, 44.dp), smileyCellImageSize(EditorSmileySource.WIKI, measuredPx = null))
    }

    @Test
    fun `small measured perso keeps its native size — NO upscale (#871)`() {
        assertEquals(DpSize(28.dp, 28.dp), smileyCellImageSize(EditorSmileySource.WIKI, IntSize(28, 28)))
        assertEquals(DpSize(15.dp, 15.dp), smileyCellImageSize(EditorSmileySource.WIKI, IntSize(15, 15)))
    }

    @Test
    fun `perso exactly at the cap passes through untouched`() {
        assertEquals(DpSize(44.dp, 44.dp), smileyCellImageSize(EditorSmileySource.WIKI, IntSize(44, 44)))
    }

    @Test
    fun `oversized perso is capped down to the cell, aspect ratio preserved`() {
        // Dominant 70×50 corpus size : scale 44/70 ≈ 0.629 → 44×31.
        assertEquals(DpSize(44.dp, 31.dp), smileyCellImageSize(EditorSmileySource.WIKI, IntSize(70, 50)))
    }

    @Test
    fun `degenerate measurement falls back to the cell-filling square`() {
        assertEquals(DpSize(44.dp, 44.dp), smileyCellImageSize(EditorSmileySource.WIKI, IntSize(0, 50)))
    }

    @Test
    fun `builtin ignores any measurement (never measured in production)`() {
        assertEquals(DpSize(20.dp, 20.dp), smileyCellImageSize(EditorSmileySource.BUILTIN, IntSize(70, 50)))
    }
}
