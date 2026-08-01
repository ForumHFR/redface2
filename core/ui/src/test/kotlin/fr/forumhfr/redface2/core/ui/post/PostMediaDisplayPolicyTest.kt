package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import fr.forumhfr.redface2.core.ui.theme.RedfaceTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM contract on the bucket policy. The bucket dimensions are also exercised indirectly
 * through `PostRendererInlineTest` via the `InlineTextContent` placeholder, but pinning them here
 * means a regression on the dimensions surfaces with a focused failure rather than an opaque
 * Compose layout drift in the inline test.
 */
class PostMediaDisplayPolicyTest {

    @Test
    fun `builtin smileys map to the small bucket regardless of token shape`() {
        val terse = PostInline.Smiley(kind = SmileyKind.Builtin(":o"), imageUrl = "x")
        val extended = PostInline.Smiley(kind = SmileyKind.Builtin(":jap:"), imageUrl = "x")

        assertEquals(PostMediaDisplayPolicy.builtinSmiley, PostMediaDisplayPolicy.smileyBox(terse))
        assertEquals(PostMediaDisplayPolicy.builtinSmiley, PostMediaDisplayPolicy.smileyBox(extended))
    }

    @Test
    fun `perso smileys map to the perso bucket so iconic sprites are not crushed`() {
        val cosmo = PostInline.Smiley(kind = SmileyKind.Perso("cosmoschtroumpf"), imageUrl = "x")
        val variant = PostInline.Smiley(kind = SmileyKind.Perso("moonblood12:1"), imageUrl = "x")

        assertEquals(PostMediaDisplayPolicy.persoSmiley, PostMediaDisplayPolicy.smileyBox(cosmo))
        assertEquals(PostMediaDisplayPolicy.persoSmiley, PostMediaDisplayPolicy.smileyBox(variant))
    }

    // #249 / #610 — block-image display size: the exact web-parity box (no upscale, width ≤ 90% of
    // the column, height ≤ IMAGE_MAX_HEIGHT_UNITS), null when the size is unknown/invalid (legacy
    // placeholder slot). Doubles as the anti-CLS reservation (#249): the box is final pre-bitmap.

    @Test
    fun `block display size caps a 4-3 photo to the parity height, not the full width`() {
        // Column 400 dp → relative cap 360; 400×300 → the 200-height cap bites (0.667 < 0.9) →
        // 267×200. Before #610: full width 400 dp × 300 dp tall.
        val size = PostMediaDisplayPolicy.blockImageDisplaySize(
            measured = PixelSize(width = 400, height = 300),
            availableWidthDp = 400f,
        )
        assertEquals(PixelSize(267, IMAGE_MAX_HEIGHT_UNITS), size)
    }

    @Test
    fun `block display size keeps a small image native — no full-width upscale (#610)`() {
        // THE block-path #610 fix: 80×60 in a 400 dp column stays 80×60. Before #610 fillMaxWidth
        // upscaled it to 400 dp wide (300 dp tall, clamped to 160 min → visual blow-up).
        val size = PostMediaDisplayPolicy.blockImageDisplaySize(
            measured = PixelSize(width = 80, height = 60),
            availableWidthDp = 400f,
        )
        assertEquals(PixelSize(80, 60), size)
    }

    @Test
    fun `block display size defaults to the legacy 200 height cap when none is passed`() {
        // #842 — the DEFAULT [maxHeightDp] is the legacy inline value 200 (backward compat for the
        // pure policy). The issue-repro shape (360×640) with the default → ~113×200. In production the
        // renderer overrides this with the mobile-recalibrated [blockImageMaxHeightDp] (see the tests
        // below); the flat 200 was never a real web rule (no max-height on HFR post images).
        val size = PostMediaDisplayPolicy.blockImageDisplaySize(
            measured = PixelSize(width = 360, height = 640),
            availableWidthDp = 400f,
        )
        assertEquals(PixelSize(113, IMAGE_MAX_HEIGHT_UNITS), size)
    }

    @Test
    fun `block display size caps a very wide image to 90 percent of the column`() {
        // 2000×500 in a 400 dp column → width cap 360 bites (0.18 < 0.4) → 360×90. No 160 dp floor
        // any more: the min-height slot is placeholder-only since #610.
        val size = PostMediaDisplayPolicy.blockImageDisplaySize(
            measured = PixelSize(width = 2000, height = 500),
            availableWidthDp = 400f,
        )
        assertEquals(PixelSize(360, 90), size)
    }

    @Test
    fun `block display size is null when the size is unknown or invalid`() {
        assertEquals(null, PostMediaDisplayPolicy.blockImageDisplaySize(measured = null, availableWidthDp = 400f))
        assertEquals(
            null,
            PostMediaDisplayPolicy.blockImageDisplaySize(
                measured = PixelSize(width = 0, height = 300),
                availableWidthDp = 400f,
            ),
        )
        assertEquals(
            null,
            PostMediaDisplayPolicy.blockImageDisplaySize(
                measured = PixelSize(width = 400, height = 300),
                availableWidthDp = 0f,
            ),
        )
    }

    // #610 — the unified parity policy itself, and the inline/block parity it guarantees.

    @Test
    fun `imageParityDisplaySize never upscales and caps to the web rule`() {
        // native smaller than caps → untouched.
        assertEquals(PixelSize(80, 60), imageParityDisplaySize(PixelSize(80, 60), maxWidthUnits = 360))
        // height cap (web max-height:200px).
        assertEquals(
            PixelSize(113, IMAGE_MAX_HEIGHT_UNITS),
            imageParityDisplaySize(PixelSize(360, 640), maxWidthUnits = 360),
        )
        // width cap (the caller's 0.9 × container).
        assertEquals(PixelSize(360, 90), imageParityDisplaySize(PixelSize(2000, 500), maxWidthUnits = 360))
        // non-positive width cap = no width cap (defensive, mirrors capToWidth): only the height
        // cap applies → 2000×500 scaled by 200/500 → 800×200.
        assertEquals(
            PixelSize(800, IMAGE_MAX_HEIGHT_UNITS),
            imageParityDisplaySize(PixelSize(2000, 500), maxWidthUnits = 0),
        )
    }

    @Test
    fun `inline and block paths share the same parity policy at equal caps (#610)`() {
        // #610's shared math: for any measured native size, at the SAME caps the inline box (sp) and
        // the block box (dp) carry the SAME numbers — units differ, values match. The inline path adds
        // a one-line legibility floor (#253) that is a no-op for these ≥16-tall parity results. NB
        // #842: in production the two paths pass DIFFERENT height caps (inline 200 sp, block the
        // recalibrated dp), so on-screen sizes diverge by design — this pins the shared policy, not
        // the runtime caps (each image takes only one path per the width ≥ 240 promotion threshold).
        val columnWidthDp = 400f
        val relativeCapUnits = 360 // 0.9 × column, what the renderer passes on both paths
        listOf(
            PixelSize(360, 640),
            PixelSize(4000, 3000),
            PixelSize(80, 60),
            PixelSize(2000, 500),
            PixelSize(300, 150),
        ).forEach { native ->
            val inline = imageParityDisplaySize(native, maxWidthUnits = relativeCapUnits)
            // Pass the same default cap on both sides to compare the shared policy, not the caps.
            val block = PostMediaDisplayPolicy.blockImageDisplaySize(native, columnWidthDp)
            assertEquals("parity broken for $native", inline, block)
        }
    }

    // #842 — mobile recalibration of the BLOCK height cap (relative to the viewport), replacing the
    // flat 200 that squeezed square/portrait photos to ~48 % width on phones.

    @Test
    fun `blockImageMaxHeightDp floors at 400 and scales with tall viewports`() {
        // Floor wins on typical phones (0.5 × 700 = 350 < 400) so a near-square image reaches ~90 %
        // width; the fraction wins on taller viewports so the cap grows with the device.
        assertEquals(400, blockImageMaxHeightDp(screenHeightDp = 700))
        assertEquals(400, blockImageMaxHeightDp(screenHeightDp = 800))
        assertEquals(458, blockImageMaxHeightDp(screenHeightDp = 916))
        assertEquals(500, blockImageMaxHeightDp(screenHeightDp = 1000))
    }

    @Test
    fun `block display size lets a near-square photo fill ~90 percent width with the recalibrated cap`() {
        // #842 report shape: an ~800×800 LEGO box on a ~411 dp-wide column (relative cap 370). With the
        // flat 200 cap it rendered 200×200 (~48 % width); with the recalibrated cap (458 on a 916 dp
        // viewport) the 90 % WIDTH cap binds first → 370×370, i.e. the intended ~90 % width.
        val recalibrated = blockImageMaxHeightDp(screenHeightDp = 916)
        val fixed = PostMediaDisplayPolicy.blockImageDisplaySize(
            measured = PixelSize(width = 800, height = 800),
            availableWidthDp = 411f,
            maxHeightDp = recalibrated,
        )
        assertEquals(PixelSize(370, 370), fixed)
        // Contrast: the shipped 0.26.0 behaviour (flat 200) — the regression this fixes.
        assertEquals(
            PixelSize(200, 200),
            PostMediaDisplayPolicy.blockImageDisplaySize(
                measured = PixelSize(width = 800, height = 800),
                availableWidthDp = 411f,
            ),
        )
    }

    @Test
    fun `block display size keeps a tall portrait bounded but taller than the old 200 cap`() {
        // 800×1200 on a 411 dp column (relative cap 370), recalibrated cap 458: height binds →
        // 305×458. Bounded (no scroll-destroying blow-up) yet no longer crushed to 133×200.
        val fixed = PostMediaDisplayPolicy.blockImageDisplaySize(
            measured = PixelSize(width = 800, height = 1200),
            availableWidthDp = 411f,
            maxHeightDp = blockImageMaxHeightDp(screenHeightDp = 916),
        )
        assertEquals(PixelSize(305, 458), fixed)
    }

    @Test
    fun `block display size fills width for a landscape photo regardless of the height cap`() {
        // 1200×675 on a 411 dp column (relative cap 370): width binds (as before), the height cap is
        // irrelevant → 370×208, full ~90 % width. The recalibration never shrinks a landscape image.
        val fixed = PostMediaDisplayPolicy.blockImageDisplaySize(
            measured = PixelSize(width = 1200, height = 675),
            availableWidthDp = 411f,
            maxHeightDp = blockImageMaxHeightDp(screenHeightDp = 916),
        )
        assertEquals(PixelSize(370, 208), fixed)
    }

    @Test
    fun `builtin and perso buckets are distinct`() {
        // Sanity check: if these collapse to the same instance, the parser dispatch becomes
        // meaningless and every smiley renders at one size — regressing the whole point of B+.
        assertNotEquals(PostMediaDisplayPolicy.builtinSmiley, PostMediaDisplayPolicy.persoSmiley)
    }

    @Test
    fun `builtin bucket dimensions are 18sp x 18sp`() {
        val box = PostMediaDisplayPolicy.builtinSmiley
        assertEquals(18.sp, box.placeholderWidth)
        assertEquals(18.sp, box.placeholderHeight)
    }

    @Test
    fun `perso bucket dimensions are 70sp x 50sp`() {
        // Exhaustive wikismilies stats show HFR perso are mostly 50px tall, with 70×50 being
        // the dominant size by far. Match that real corpus shape instead of reserving a square
        // bucket that letterboxes common wide smileys.
        val box = PostMediaDisplayPolicy.persoSmiley
        assertEquals(70.sp, box.placeholderWidth)
        assertEquals(50.sp, box.placeholderHeight)
    }

    @Test
    fun `perso placeholder height stays below the previous broken 64sp bucket`() {
        // The visual rule of thumb after PR #126 bug: a single inline smiley should not bump the
        // line height back to the old 64sp bucket, otherwise the paragraph risks reading as
        // broken even if Compose technically lays out without overlap. Reads bodyMedium directly
        // from the project Typography so the invariant tracks future typography tweaks. Current
        // bodyMedium.lineHeight is 20sp, so 2.5× gives the corpus target 50sp.
        val bodyMediumLineHeightSp = RedfaceTypography.bodyMedium.lineHeight.value
        assertTrue(
            "perso placeholder height ${PostMediaDisplayPolicy.persoSmiley.placeholderHeight} " +
                "must stay ≤ 2.5 × ${bodyMediumLineHeightSp}sp (bodyMedium.lineHeight)",
            PostMediaDisplayPolicy.persoSmiley.placeholderHeight.value <= bodyMediumLineHeightSp * 2.5f,
        )
    }

    @Test
    fun `inline image bucket dimensions are 240sp x 180sp`() {
        val box = PostMediaDisplayPolicy.inlineImage
        assertEquals(240.sp, box.placeholderWidth)
        assertEquals(180.sp, box.placeholderHeight)
    }

    @Test
    fun `block image min height is 160dp`() {
        // #610 — UNMEASURED placeholder slot only: reserves a stable visual slot during
        // SubcomposeAsyncImage loading/error so the UX stays visible and the layout jump is bounded
        // (cf. PR #126 Codex review). A measured image uses its exact blockImageDisplaySize box.
        assertEquals(160.dp, PostMediaDisplayPolicy.blockImageMinHeight)
    }

    @Test
    fun `block image max height is 480dp`() {
        // #610 — UNMEASURED slot cap only (load-without-measurement can't blow up the post). The
        // measured path caps at IMAGE_MAX_HEIGHT_UNITS (200) via blockImageDisplaySize.
        assertEquals(480.dp, PostMediaDisplayPolicy.blockImageMaxHeight)
    }

    @Test
    fun `smiley content scale is Fit so tiny perso sprites stay readable`() {
        // Dogfood on v33 showed the opposite failure of PR #126: Inside kept 15×15 historical
        // perso at native size, making them nearly invisible on phones. Fit restores a readable
        // glyph while fillMaxSize keeps the line-height tied to the placeholder.
        assertSame(ContentScale.Fit, PostMediaDisplayPolicy.smileyContentScale)
    }

    @Test
    fun `inline image content scale is Fit so it fills its sized box`() {
        // #224/#253 — Inside left a tiny 16×16 cc-image emoji at native size, centred in its
        // (min-height-floored) box → illegible (dogfood). The no-upscale rule lives in the BOX sizing
        // (imageDisplayBox); Fit makes the bitmap FILL that box (floored emoji drawn at box size; a
        // large photo still scales DOWN into its capped box). Same reason as the smiley scale above.
        assertSame(ContentScale.Fit, PostMediaDisplayPolicy.inlineImageContentScale)
    }

    @Test
    fun `Fit sizing preserves ratio while matching the wikismilies corpus shape`() {
        // Real wikismilies crawl stats (34k+ perso) show a dominant 70×50 / W×50 shape. Verifies
        // the policy
        // produces the expected fit-to-bucket result for each typical size class:
        // - tiny perso (≤30 px): upscale to a readable phone-size glyph
        // - median perso (50×50): stays native-sized inside the HFR line
        // - dominant wide perso (70×50): stays native-sized, no letterboxing shrink
        // - rare big perso (200×150): aggressively downscale, but still fit the bucket
        val bucket = PixelSize(width = 70, height = 50)
        data class Case(val source: PixelSize, val expected: PixelSize, val label: String)
        val cases = listOf(
            Case(PixelSize(15, 15), PixelSize(50, 50), "tiny square (readable upscale)"),
            Case(PixelSize(19, 19), PixelSize(50, 50), "tiny 19×19 (readable upscale)"),
            Case(PixelSize(39, 15), PixelSize(70, 27), "wide tiny (readable upscale)"),
            Case(PixelSize(50, 50), PixelSize(50, 50), "median square perso (native height)"),
            Case(PixelSize(67, 50), PixelSize(67, 50), "third-most common wikismilies size"),
            Case(PixelSize(70, 50), PixelSize(70, 50), "dominant wikismilies size"),
            Case(PixelSize(70, 49), PixelSize(70, 49), "common near-50 height size"),
            Case(PixelSize(200, 150), PixelSize(67, 50), "rare oversize sprite (heavy downscale)"),
        )

        cases.forEach { (source, expected, label) ->
            val actual = fitScaledMediaSize(source = source, bucket = bucket)
            assertEquals("[$label] mismatch", expected, actual)
            assertTrue("[$label] width must fit bucket", actual.width <= bucket.width)
            assertTrue("[$label] height must fit bucket", actual.height <= bucket.height)
        }
    }

    @Test
    fun `extreme aspect ratios never collapse a dimension to zero`() {
        // 1×100 or 100×1 sources are user-uploadable on HFR (perso are user-generated GIFs);
        // without the coerceAtLeast(1) guard, ratios this extreme would round to a 0×N or N×0
        // size — visually invisible and technically "fitting". Pin the lower bound so the helper
        // stays usable beyond the regular HFR corpus.
        val bucket = PixelSize(70, 50)
        val tallStrip = fitScaledMediaSize(PixelSize(width = 1, height = 100), bucket)
        assertTrue("tall strip must keep width ≥ 1", tallStrip.width >= 1)
        assertEquals(50, tallStrip.height)

        val wideStrip = fitScaledMediaSize(PixelSize(width = 100, height = 1), bucket)
        assertEquals(70, wideStrip.width)
        assertTrue("wide strip must keep height ≥ 1", wideStrip.height >= 1)
    }

    // #416 — dead-sprite token box : the placeholder must fit the body-sized token, not the sprite.

    @Test
    fun `deadSmileyTokenBox width scales with the token length`() {
        val short = PostMediaDisplayPolicy.deadSmileyTokenBox(":o", maxWidthSp = 400)
        val long = PostMediaDisplayPolicy.deadSmileyTokenBox(":zorglub:", maxWidthSp = 400)
        assertEquals((2 * PostMediaDisplayPolicy.DEAD_SMILEY_TOKEN_CHAR_SP).sp, short.placeholderWidth)
        assertEquals((9 * PostMediaDisplayPolicy.DEAD_SMILEY_TOKEN_CHAR_SP).sp, long.placeholderWidth)
        assertTrue(long.placeholderWidth.value > short.placeholderWidth.value)
    }

    @Test
    fun `deadSmileyTokenBox clamps to the relative cap like sprites`() {
        // A long perso token ([:longcustomsmileyname]) inside a deep quote must not overflow the
        // narrow container — same `img { max-width }` philosophy as smileyDisplayBox.
        val box = PostMediaDisplayPolicy.deadSmileyTokenBox("[:averyverylongpersoname]", maxWidthSp = 60)
        assertEquals(60.sp, box.placeholderWidth)
    }

    @Test
    fun `deadSmileyTokenBox height is one body line`() {
        val box = PostMediaDisplayPolicy.deadSmileyTokenBox(":zorglub:", maxWidthSp = 400)
        assertEquals(PostMediaDisplayPolicy.DEAD_SMILEY_TOKEN_HEIGHT_SP.sp, box.placeholderHeight)
    }
}
