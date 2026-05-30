package fr.forumhfr.redface2.core.ui.post

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #175 — pure JVM coverage of [intrinsicSmileyDisplaySize] (no-upscale + cap), the policy that
 * replaces the fixed buckets. The headline behaviour change vs the old `Fit` bucket is **no
 * upscale**: small sprites keep their native size instead of being blown up to 50×50.
 */
class IntrinsicSmileyDisplaySizeTest {

    @Test
    fun `dominant 70x50 perso passes through untouched (under caps)`() {
        assertEquals(PixelSize(70, 50), intrinsicSmileyDisplaySize(PixelSize(70, 50)))
    }

    @Test
    fun `micro 15x15 stays 15x15 — NO upscale (inverse of the old Fit bucket which blew it to 50x50)`() {
        assertEquals(PixelSize(15, 15), intrinsicSmileyDisplaySize(PixelSize(15, 15)))
        assertEquals(PixelSize(19, 19), intrinsicSmileyDisplaySize(PixelSize(19, 19)))
    }

    @Test
    fun `builtin ~16x16 stays small`() {
        assertEquals(PixelSize(16, 16), intrinsicSmileyDisplaySize(PixelSize(16, 16)))
    }

    @Test
    fun `oversized sprite is capped down by height, aspect ratio preserved`() {
        // 480×360, height cap 70 → scale 70/360 ≈ 0.194 → 93×70 (width 93 < 240 guard).
        assertEquals(PixelSize(93, 70), intrinsicSmileyDisplaySize(PixelSize(480, 360)))
    }

    @Test
    fun `abusively wide sprite is capped by the width guard`() {
        // 1000×100, scale = min(240/1000=0.24, 70/100=0.7, 1) = 0.24 → 240×24.
        assertEquals(PixelSize(240, 24), intrinsicSmileyDisplaySize(PixelSize(1000, 100)))
    }

    @Test
    fun `extreme aspect ratio is clamped to at least 1 per axis`() {
        // 1×100 → scale min(240/1, 70/100=0.7, 1) = 0.7 → width 0.7 rounds to 1 (not 0).
        val r = intrinsicSmileyDisplaySize(PixelSize(1, 100))
        assertEquals(1, r.width)
        assertEquals(70, r.height)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive native size is rejected`() {
        intrinsicSmileyDisplaySize(PixelSize(0, 50))
    }

    @Test
    fun `capToWidth is a no-op when the smiley already fits`() {
        assertEquals(PixelSize(70, 50), capToWidth(PixelSize(70, 50), maxWidthSp = 200))
    }

    @Test
    fun `capToWidth shrinks an over-wide smiley to the cap, preserving aspect ratio`() {
        // 240×70 in a narrow quote whose 90% width is 120sp → 120×35 (height halved with width).
        assertEquals(PixelSize(120, 35), capToWidth(PixelSize(240, 70), maxWidthSp = 120))
    }

    @Test
    fun `capToWidth is defensive against a zero or negative container width`() {
        assertEquals(PixelSize(70, 50), capToWidth(PixelSize(70, 50), maxWidthSp = 0))
    }
}
