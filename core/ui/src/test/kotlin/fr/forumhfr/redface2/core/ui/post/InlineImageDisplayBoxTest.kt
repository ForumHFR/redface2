package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize
import fr.forumhfr.redface2.core.model.PostInline
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #224 (option A) / #610 — pure unit coverage for [imageDisplayBox], the intrinsic sizing of inline
 * `[img]`: measured native size through the unified web-parity policy (no upscale, height ≤
 * IMAGE_MAX_HEIGHT_UNITS = web `max-height:200px`, width ≤ the relative `0.9 × contentWidth` = web
 * `max-width:90%` — the pre-#610 absolute 240 sp width cap is gone). #253 — while the measurement is
 * in flight the box falls back to a one-line square (INLINE_IMAGE_MIN_HEIGHT_SP), not the 240×180
 * bucket, so ContentScale.Fit can't flash a tiny emoji giant.
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
    fun `large photo is capped by the parity height cap preserving aspect`() {
        // #610 — 4000×3000 (4:3): the 200-height web cap bites first (200/3000 < 400/4000) → 267×200.
        // Before #610 the absolute 240 sp width cap gave 240×180.
        val b = box(measured = IntSize(4000, 3000), maxWidthSp = 400)
        assertEquals(267f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(IMAGE_MAX_HEIGHT_UNITS.toFloat(), b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `tall image is bounded by the parity height cap`() {
        // 100×1000 → scaled by 200/1000 → 20×200 (IMAGE_MAX_HEIGHT_UNITS, unchanged by #610).
        val b = box(measured = IntSize(100, 1000), maxWidthSp = 400)
        assertEquals(20f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(IMAGE_MAX_HEIGHT_UNITS.toFloat(), b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `image wider than the former absolute cap keeps its native size (#610)`() {
        // #610 before → after: a 300×150 source rendered 240×120 (absolute cap); it now stays native
        // because only the relative 0.9×container cap remains and 300 < 400.
        val b = box(measured = IntSize(300, 150), maxWidthSp = 400)
        assertEquals(300f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(150f, b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `portrait photo renders at the exact web parity size`() {
        // The #610 reference case (issue repro, 360×640 portrait): web `max-height:200px` →
        // 112.5×200, i.e. ~113×200. Same numbers as blockImageDisplaySize (parity test in
        // PostMediaDisplayPolicyTest).
        val b = box(measured = IntSize(360, 640), maxWidthSp = 324)
        assertEquals(113f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(IMAGE_MAX_HEIGHT_UNITS.toFloat(), b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `wide short banner stays within the relative width cap despite the min-height floor`() {
        // #246 (Codex) — the floor is the one sanctioned upscale and must stay bounded: a 250×10
        // banner floored to height 16 grows to 400×16, and the re-applied parity caps clamp it back
        // to the relative cap (300 here) → 300×12. Pre-#610 the bound was the absolute 240 sp cap.
        val b = box(measured = IntSize(250, 10), maxWidthSp = 300)
        assertEquals(300f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(12f, b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `wide short banner in a wide container is floored to one line`() {
        // Same source, container wide enough for the floored width: the #253 legibility floor wins
        // (400×16 ≤ caps) — the documented deviation from the web's strict no-upscale, kept so a
        // 10 px-tall source stays visible. On the web this renders 250×10.
        val b = box(measured = IntSize(250, 10), maxWidthSp = 400)
        assertEquals(400f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(INLINE_IMAGE_MIN_HEIGHT_SP.toFloat(), b.placeholderHeight.value, TOLERANCE)
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

    // #256 — the hfr-cc-image marker pins the box to the one-line square at render time.

    private val ccUrl = "https://cdn.example.org/emojis-micro/1f600.png?hfr-cc-image=true&raw=true"
    private val ccImage = PostInline.InlineImage(url = ccUrl, description = null)

    private fun ccBox(measured: IntSize?, maxWidthSp: Int): InlineMediaBox =
        imageDisplayBox(ccImage, mapOf(ccUrl to measured), maxWidthSp)

    @Test
    fun `cc-image marker pins the one-line square without any measurement`() {
        // The fast-path needs no measured size: the box is final from the very first frame.
        val b = ccBox(measured = null, maxWidthSp = 400)
        assertEquals(INLINE_IMAGE_MIN_HEIGHT_SP.toFloat(), b.placeholderWidth.value, TOLERANCE)
        assertEquals(INLINE_IMAGE_MIN_HEIGHT_SP.toFloat(), b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `cc-image marker ignores a measured size on record`() {
        // Even a (stale or warmed-elsewhere) 500×500 measurement must not resize a marked emoji:
        // the marker, not the measurement, is the contract.
        val b = ccBox(measured = IntSize(500, 500), maxWidthSp = 400)
        assertEquals(INLINE_IMAGE_MIN_HEIGHT_SP.toFloat(), b.placeholderWidth.value, TOLERANCE)
        assertEquals(INLINE_IMAGE_MIN_HEIGHT_SP.toFloat(), b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `cc-image square is still relative-capped in a pathologically narrow container`() {
        // Defensive parity with the cold fallback: the fixed square obeys the same relative cap.
        val b = ccBox(measured = null, maxWidthSp = 10)
        assertEquals(10f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(10f, b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `a false marker falls back to the normal measured path`() {
        // hfr-cc-image=false is NOT the marker: normal intrinsic sizing applies.
        val falseUrl = "https://cdn.example.org/pic.png?hfr-cc-image=false"
        val b = imageDisplayBox(
            PostInline.InlineImage(url = falseUrl, description = null),
            mapOf(falseUrl to IntSize(80, 60)),
            maxWidthSp = 400,
        )
        assertEquals(80f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(60f, b.placeholderHeight.value, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.5f
    }
}
