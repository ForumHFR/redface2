package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.forumhfr.redface2.core.domain.preferences.PostImageMaxWidth
import fr.forumhfr.redface2.core.model.PostInline
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §3 v1.6-1: inline content uses the density ceiling and the real px-to-dp-to-sp conversion.
 * The 16 sp floor belongs only to cold/cc slots; padding stays outside the bitmap.
 */
class InlineImageDisplayBoxTest {

    private val url = "https://forum.hardware.fr/images/foo.png"
    private val image = PostInline.InlineImage(url = url, description = null)

    private val d1 = Density(1f, 1f)
    private val d3 = Density(3f, 1f)

    @Test
    fun `E9 cold slot reserves the padding within a column narrower than 24 dp`() {
        val b = box(null, maxImageWidthPx = 12, maxWidthSp = 18, horizontalPadding = 8.dp)

        assertEquals(20.sp, b.placeholderWidth)
        assertEquals(12.sp, b.placeholderHeight)
    }

    @Test
    fun `E9 cold slot width cap uses the inverse font conversion`() {
        val density = Density(3f, 2f)
        val maxWidthPx = inlineImageMaxWidthPx(60f, PostImageMaxWidth.P100, horizontalPaddingPx = 24)
        val b = box(null, maxImageWidthPx = maxWidthPx, density = density, horizontalPadding = 8.dp)

        with(density) {
            assertEquals(60f, b.placeholderWidth.toDp().toPx(), 0.001f)
            assertEquals(36f, b.placeholderHeight.toDp().toPx(), 0.001f)
        }
    }

    @Test
    fun `E9 padding bound leaves ordinary cold slots at sixteen sp`() {
        listOf(PostImageMaxWidth.P90, PostImageMaxWidth.P95, PostImageMaxWidth.P99, PostImageMaxWidth.P100)
            .forEach { preset ->
                listOf(0.75f, 1f, 2.625f, 3f, 3.5f).forEach { screenDensity ->
                    val density = Density(screenDensity, 2f)
                    val maxWidthPx = with(density) {
                        inlineImageMaxWidthPx(
                            120.dp.toPx(),
                            preset,
                            horizontalPaddingPx = INLINE_IMAGE_HORIZONTAL_PADDING.roundToPx() * 2,
                        )
                    }
                    val b = box(
                        null,
                        maxImageWidthPx = maxWidthPx,
                        density = density,
                        horizontalPadding = 8.dp,
                    )

                    assertEquals(16.sp, b.placeholderHeight)
                    with(density) {
                        val paddingPx = b.placeholderWidth.toDp().toPx() - b.placeholderHeight.toDp().toPx()
                        assertEquals(8.dp.toPx(), paddingPx, TOLERANCE)
                    }
                }
            }
    }

    @Test
    fun `content ceiling follows density without multiplying font scale`() {
        listOf(0.75f, 1f, 2f, 2.625f, 3f, 3.5f).forEach { screenDensity ->
            val density = Density(screenDensity, 2f)
            val b = box(IntSize(80, 60), maxImageHeightPx = 1000, density = density)
            val expected = if (screenDensity < 1f) 1f else minOf(screenDensity, 3f)
            with(density) {
                assertEquals(80f * expected, b.placeholderWidth.toDp().toPx(), TOLERANCE)
                assertEquals(60f * expected, b.placeholderHeight.toDp().toPx(), TOLERANCE)
            }
            assertEquals(IntSize(80, 60), b.decodeSize)
        }
    }

    @Test
    fun `P99 and P100 reserve eight dp of padding when content is enlarged`() {
        listOf(PostImageMaxWidth.P99, PostImageMaxWidth.P100).forEach { width ->
            val maxWidthPx = inlineImageMaxWidthPx(300f, width, horizontalPaddingPx = 24)
            val b = box(
                IntSize(100, 50), maxImageWidthPx = maxWidthPx, maxImageHeightPx = 1200,
                density = d3, horizontalPadding = 8.dp,
            )
            assertEquals(276, maxWidthPx)
            assertEquals(100f, b.placeholderWidth.value, TOLERANCE)
            assertEquals(46f, b.placeholderHeight.value, TOLERANCE)
            assertEquals(IntSize(100, 50), b.decodeSize)
        }
    }

    @Test
    fun `density enlargement still uses the nonlinear inverse font conversion`() {
        val nonLinear = object : Density {
            override val density: Float = 3f
            override val fontScale: Float = 2f
            override fun androidx.compose.ui.unit.Dp.toSp(): androidx.compose.ui.unit.TextUnit =
                (value / 2.5f).sp
        }
        val b = box(
            IntSize(80, 60),
            maxImageHeightPx = 1200,
            density = nonLinear,
            horizontalPadding = 8.dp,
        )
        assertEquals(35.2f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(24f, b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `cold and cc slots ignore the density ceiling and keep their padding contract`() {
        listOf(0.75f, 3f, 3.5f).forEach { screenDensity ->
            val density = Density(screenDensity, 2f)
            val cold = box(null, density = density, horizontalPadding = 8.dp)
            assertEquals(16.sp, cold.placeholderHeight)
            with(density) {
                val paddingPx = cold.placeholderWidth.toDp().toPx() - cold.placeholderHeight.toDp().toPx()
                assertEquals(8.dp.toPx(), paddingPx, TOLERANCE)
            }
            val cc = imageDisplayBox(
                image = ccImage, measured = mapOf(ccUrl to IntSize(500, 500)), maxWidthSp = 400,
                maxImageWidthPx = 1200,
                maxImageHeightPx = 1200,
                density = density,
                horizontalPadding = 8.dp,
            )
            assertEquals(16.sp, cc.placeholderWidth)
            assertEquals(16.sp, cc.placeholderHeight)
            assertEquals(null, cc.decodeSize)
        }
    }

    @Suppress("LongParameterList") // One helper mirroring the production seam's full signature.
    private fun box(
        measured: IntSize?,
        maxImageWidthPx: Int = 400,
        maxImageHeightPx: Int = 200,
        density: Density = d1,
        maxWidthSp: Int = 400,
        horizontalPadding: Dp = 0.dp,
    ): InlineMediaBox = imageDisplayBox(
        image = image,
        measured = mapOf(url to measured),
        maxWidthSp = maxWidthSp,
        maxImageWidthPx = maxImageWidthPx,
        maxImageHeightPx = maxImageHeightPx,
        density = density,
        horizontalPadding = horizontalPadding,
    )

    // ---------- measured path : physical pixels ----------

    @Test
    fun `small reaction image keeps its native physical size at density 1`() {
        val b = box(measured = IntSize(80, 60))
        assertEquals(80f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(60f, b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `at density 3 a 300px source occupies 300sp under the caps`() {
        val b = box(measured = IntSize(300, 300), maxImageWidthPx = 1200, maxImageHeightPx = 900, density = d3)
        assertEquals(300f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(300f, b.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `fontScale keeps the physical size stable - the density round-trip is exact`() {
        // Gate Sol r1 (blocker #3) — THE invariant, robust to any font-scaling table: converting
        // the sp placeholder forward exactly like the text stack does (sp → dp → px through the
        // SAME density) lands back on the computed physical pixels. At fontScale 2 the Android
        // factory density carries the REAL non-linear converter (1:1 in the large-value region —
        // a linear ÷fontScale assumption is exactly what drifted).
        val density = Density(1f, 2f)
        val b = box(
            measured = IntSize(100, 100),
            maxImageWidthPx = 400,
            maxImageHeightPx = 400,
            density = density,
        )
        val roundTripPx = with(density) { b.placeholderHeight.toDp().toPx() }
        assertEquals(100f, roundTripPx, TOLERANCE)
    }

    @Test
    fun `the boundary conversion uses the density's REAL inverse font scaling - not a linear division`() {
        // Gate Sol r1 (blocker #3): API 34+ densities convert Dp↔Sp through a NON-linear platform
        // table. The box must go through the Density's OWN Dp.toSp() so the text stack's forward
        // conversion lands back on the computed physical pixels. A density with a pseudo-table
        // (÷2.5 instead of the linear ÷2) exposes any hand-rolled linear division.
        val nonLinear = object : Density {
            override val density: Float = 1f
            override val fontScale: Float = 2f
            override fun androidx.compose.ui.unit.Dp.toSp(): androidx.compose.ui.unit.TextUnit =
                (value / 2.5f).sp
        }
        val b = box(
            measured = IntSize(100, 100),
            maxImageWidthPx = 400,
            maxImageHeightPx = 400,
            density = nonLinear,
        )
        assertEquals(40f, b.placeholderWidth.value, TOLERANCE)
        assertEquals(40f, b.placeholderHeight.value, TOLERANCE)
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
    fun `placeholder width converts bitmap and dp padding to sp together`() {
        // E6 — one inverse conversion covers bitmap + 4 dp/side so the later padding subtraction
        // is its exact counterpart even under non-linear font scaling.
        val b = box(measured = IntSize(80, 60), horizontalPadding = 8.dp)
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
