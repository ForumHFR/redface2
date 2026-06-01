package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize
import fr.forumhfr.redface2.core.model.PostInline
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #224 (option A) — pure unit coverage for [imageDisplayBox], the intrinsic sizing of inline `[img]`:
 * measured native size, no-upscale + absolute cap (INLINE_IMAGE_MAX_*), then the relative `0.9× width`
 * cap. #253 — while the measurement is in flight the box falls back to a one-line square
 * (INLINE_IMAGE_MIN_HEIGHT_SP), not the 240×180 bucket, so ContentScale.Fit can't flash a tiny emoji giant.
 */
class InlineImageDisplayBoxTest {

    private val url = "https://forum.hardware.fr/images/foo.png"
    private val image = PostInline.InlineImage(url = url, description = null)

    private fun box(measured: IntSize?, maxWidthSp: Int): InlineMediaBox =
        imageDisplayBox(image, mapOf(url to measured), maxWidthSp)

    @Test
    fun `small reaction image keeps its native size (no upscale, no empty frame)`() {
        // The whole point of A: an 80×60 reaction no longer sits in a 240×180 empty box. Above the
        // min-height floor, so untouched by the upscale.
        val b = box(measured = IntSize(80, 60), maxWidthSp = 400)
        assertEquals(80f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(60f, b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `tiny cc-image emoji is upscaled to the min readable height`() {
        // A 16×16 cc-image emoji is illegible at native size → box floored to INLINE_IMAGE_MIN_HEIGHT_SP
        // (filled by ContentScale.Fit), aspect preserved (16×16 stays square).
        val b = box(measured = IntSize(16, 16), maxWidthSp = 400)
        assertEquals(INLINE_IMAGE_MIN_HEIGHT_SP.toFloat(), b.placeholderHeight.value, TOLERANCE)
        assertEquals(INLINE_IMAGE_MIN_HEIGHT_SP.toFloat(), b.placeholderWidth.value, TOLERANCE)
    }

    @Test
    fun `large photo is capped to the absolute bucket preserving aspect`() {
        // 4000×3000 (4:3) → scaled by 240/4000 → 240×180.
        val b = box(measured = IntSize(4000, 3000), maxWidthSp = 400)
        assertEquals(240f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(180f, b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `tall image is bounded by the absolute height cap`() {
        // 100×1000 → scaled by 200/1000 → 20×200 (INLINE_IMAGE_MAX_HEIGHT_SP).
        val b = box(measured = IntSize(100, 1000), maxWidthSp = 400)
        assertEquals(20f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(INLINE_IMAGE_MAX_HEIGHT_SP.toFloat(), b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `relative cap shrinks even a small measured image in a narrow quote`() {
        // 80×60, container relative cap 40 → 40×30 (4:3 preserved).
        val b = box(measured = IntSize(80, 60), maxWidthSp = 40)
        assertEquals(40f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(30f, b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `cold cache falls back to a one-line square (no giant Fit flash)`() {
        // #253 — before measurement the box is INLINE_IMAGE_MIN_HEIGHT_SP square, not the 240×180
        // bucket: ContentScale.Fit then can't upscale a 16×16 cc-image emoji into a giant 180×180
        // flash. Once the size lands the box becomes the real (capped) intrinsic size.
        val b = box(measured = null, maxWidthSp = 400)
        assertEquals(INLINE_IMAGE_MIN_HEIGHT_SP.toFloat(), b.placeholderWidth.value, TOLERANCE)
        assertEquals(INLINE_IMAGE_MIN_HEIGHT_SP.toFloat(), b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `cold cache fallback stays a small square even in a narrow quote`() {
        // The one-line cold square is already far below any container width, so the relative cap is a
        // no-op and it never overflows a deep quote.
        val b = box(measured = null, maxWidthSp = 180)
        assertEquals(INLINE_IMAGE_MIN_HEIGHT_SP.toFloat(), b.placeholderWidth.value, TOLERANCE)
        assertEquals(INLINE_IMAGE_MIN_HEIGHT_SP.toFloat(), b.placeholderHeight.value, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.5f
    }
}
