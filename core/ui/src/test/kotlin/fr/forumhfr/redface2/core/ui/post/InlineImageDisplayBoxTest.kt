package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import fr.forumhfr.redface2.core.model.PostInline
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #959 (Lot 3, contrat v1.5 §3) — pure unit coverage for [imageDisplayBox], the inline `[img]`
 * placeholder resolution, now DENSITY-AWARE: the measured path works entirely in PHYSICAL pixels
 * ([imageDisplaySizePx] — no-upscale means 1 source px never spreads past 1 screen px) and the
 * result converts back to sp at the boundary through the host's [Density] (density × fontScale),
 * so the physical size is stable under any density/fontScale. The legibility floor 16 is GONE
 * from the measured path (Sol r1 blocker #2): [INLINE_IMAGE_PLACEHOLDER_MIN_HEIGHT_SP] only
 * shapes the COLD placeholder slot and the #256 cc fast-path square, which both stay sp-based
 * and unchanged.
 */
class InlineImageDisplayBoxTest {

    private val url = "https://forum.hardware.fr/images/foo.png"
    private val image = PostInline.InlineImage(url = url, description = null)

    private val d1 = Density(1f, 1f)
    private val d3 = Density(3f, 1f)

    @Suppress("LongParameterList") // One helper mirroring the production seam's full signature.
    private fun box(
        measured: IntSize?,
        maxImageWidthPx: Int = 400,
        maxImageHeightPx: Int = 200,
        density: Density = d1,
        maxWidthSp: Int = 400,
        paddingSp: Int = 0,
    ): InlineMediaBox = imageDisplayBox(
        image = image,
        measured = mapOf(url to measured),
        maxWidthSp = maxWidthSp,
        maxImageWidthPx = maxImageWidthPx,
        maxImageHeightPx = maxImageHeightPx,
        density = density,
        horizontalPaddingSp = paddingSp,
    )

    // ---------- measured path : physical pixels ----------

    @Test
    fun `small reaction image keeps its native physical size at density 1`() {
        val b = box(measured = IntSize(80, 60))
        assertEquals(80f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(60f, b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `no physical upscale - at density 3 a 300px source occupies 100sp`() {
        // THE density-aware pivot of the lot: 300 native px = 300 physical px on screen = 100 sp
        // at density 3 (before #959 the same source occupied 300 sp = 900 physical px, a ×3 blur).
        val b = box(measured = IntSize(300, 300), maxImageWidthPx = 1200, maxImageHeightPx = 900, density = d3)
        assertEquals(100f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(100f, b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `fontScale keeps the physical size stable - the sp box shrinks accordingly`() {
        // 100 px at density 1 / fontScale 2 → 50 sp placeholder; the text stack multiplies sp by
        // fontScale at layout, so the on-screen box is back to 100 physical px. Bigger text does
        // NOT blow bitmaps up.
        val b = box(
            measured = IntSize(100, 100),
            maxImageWidthPx = 400,
            maxImageHeightPx = 400,
            density = Density(1f, 2f),
        )
        assertEquals(50f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(50f, b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `large photo is capped by the inline height cap with the height derived from the width`() {
        // 4000×3000 into maxW=400/maxH=200 px: scale = 200/3000 = 0.0667 → w = round(266.8) = 267,
        // h = round(267 × 3000/4000) = round(200.25) = 200.
        val b = box(measured = IntSize(4000, 3000))
        assertEquals(267f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(200f, b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `portrait photo derives its height from the rounded width - one px past the cap accepted`() {
        // §3 letter (pinned): 360×640 into maxW=324/maxH=200 px → scale=0.3125, w=round(112.5)=113,
        // h=round(113 × 640/360)=round(200.9)=201 — derived from the ROUNDED width, one px past
        // the cap by construction (the caps constrain the scale, not the rounded result).
        val b = box(measured = IntSize(360, 640), maxImageWidthPx = 324)
        assertEquals(113f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(201f, b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `tall image is bounded by the height cap`() {
        // 100×1000 → scale 0.2 → 20×200 (derivation exact here).
        val b = box(measured = IntSize(100, 1000))
        assertEquals(20f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(200f, b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `the measured path has no legibility floor anymore - a 10px-tall banner renders 250x10`() {
        // Sol r1 blocker #2 / web-exact: the pre-#959 floor grew a 250×10 banner to 300×12 (or
        // 400×16); the floor now only concerns the COLD placeholder. On the web this renders
        // 250×10 — so do we.
        val b = box(measured = IntSize(250, 10), maxImageWidthPx = 300)
        assertEquals(250f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(10f, b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `a tiny measured image stays at its native physical size - no floor`() {
        // A measured 10×10 (NOT cc-marked) renders 10×10 px. The real cc emojis carry the #256
        // marker and take the fast-path square below — they never reach this path.
        val b = box(measured = IntSize(10, 10))
        assertEquals(10f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(10f, b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `relative width cap shrinks even a small measured image in a narrow quote`() {
        // 80×60 into maxW=40 px → 40×30 (4:3 preserved through the derivation).
        val b = box(measured = IntSize(80, 60), maxImageWidthPx = 40)
        assertEquals(40f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(30f, b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `the placeholder padding rides the sp box after the px conversion`() {
        // §4 — 4 dp/side converted to sp by the caller lands on the PLACEHOLDER width only,
        // after the px→sp boundary conversion (bitmap box untouched).
        val b = box(measured = IntSize(80, 60), paddingSp = 8)
        assertEquals(88f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(60f, b.placeholderHeight.value, TOLERANCE)
    }

    // ---------- cold slot : sp-based placeholder, unchanged ----------

    @Test
    fun `cold cache falls back to the one-line placeholder square`() {
        // #253 — before measurement the SLOT is INLINE_IMAGE_PLACEHOLDER_MIN_HEIGHT_SP square
        // (sp: it is a text-line hitbox, not an image size).
        val b = box(measured = null)
        assertEquals(INLINE_IMAGE_PLACEHOLDER_MIN_HEIGHT_SP.toFloat(), b.placeholderWidth.value, TOLERANCE)
        assertEquals(INLINE_IMAGE_PLACEHOLDER_MIN_HEIGHT_SP.toFloat(), b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `cold cache fallback stays a small square even in a narrow quote`() {
        val b = box(measured = null, maxWidthSp = 180)
        assertEquals(INLINE_IMAGE_PLACEHOLDER_MIN_HEIGHT_SP.toFloat(), b.placeholderWidth.value, TOLERANCE)
        assertEquals(INLINE_IMAGE_PLACEHOLDER_MIN_HEIGHT_SP.toFloat(), b.placeholderHeight.value, TOLERANCE)
    }

    // ---------- #256 cc fast-path : sp square, unchanged ----------

    private val ccUrl = "https://cdn.example.org/emojis-micro/1f600.png?hfr-cc-image=true&raw=true"
    private val ccImage = PostInline.InlineImage(url = ccUrl, description = null)

    private fun ccBox(measured: IntSize?, maxWidthSp: Int): InlineMediaBox = imageDisplayBox(
        image = ccImage,
        measured = mapOf(ccUrl to measured),
        maxWidthSp = maxWidthSp,
        maxImageWidthPx = 400,
        maxImageHeightPx = 200,
        density = d1,
    )

    @Test
    fun `cc-image marker pins the one-line square without any measurement`() {
        val b = ccBox(measured = null, maxWidthSp = 400)
        assertEquals(INLINE_IMAGE_PLACEHOLDER_MIN_HEIGHT_SP.toFloat(), b.placeholderWidth.value, TOLERANCE)
        assertEquals(INLINE_IMAGE_PLACEHOLDER_MIN_HEIGHT_SP.toFloat(), b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `cc-image marker ignores a measured size on record`() {
        val b = ccBox(measured = IntSize(500, 500), maxWidthSp = 400)
        assertEquals(INLINE_IMAGE_PLACEHOLDER_MIN_HEIGHT_SP.toFloat(), b.placeholderWidth.value, TOLERANCE)
        assertEquals(INLINE_IMAGE_PLACEHOLDER_MIN_HEIGHT_SP.toFloat(), b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `cc-image square is still relative-capped in a pathologically narrow container`() {
        val b = ccBox(measured = null, maxWidthSp = 10)
        assertEquals(10f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(10f, b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `a false marker falls back to the normal measured path`() {
        // hfr-cc-image=false is NOT the marker: normal physical-pixel sizing applies.
        val falseUrl = "https://cdn.example.org/emojis-micro/1f600.png?hfr-cc-image=false"
        val b = imageDisplayBox(
            image = PostInline.InlineImage(url = falseUrl, description = null),
            measured = mapOf(falseUrl to IntSize(80, 60)),
            maxWidthSp = 400,
            maxImageWidthPx = 400,
            maxImageHeightPx = 200,
            density = d1,
        )
        assertEquals(80f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(60f, b.placeholderHeight.value, TOLERANCE)
    }
}

private const val TOLERANCE = 0.5f
