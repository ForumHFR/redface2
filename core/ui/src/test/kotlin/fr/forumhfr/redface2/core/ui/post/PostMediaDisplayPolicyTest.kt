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
    fun `perso bucket dimensions are 56sp x 56sp`() {
        // 40sp fixed the line overlap but was too conservative on phone screens: median 50×50
        // HFR perso lost readability. 56sp plus ContentScale.Fit restores a readable visual size
        // while keeping the reserved line below the old broken 64sp bucket.
        val box = PostMediaDisplayPolicy.persoSmiley
        assertEquals(56.sp, box.placeholderWidth)
        assertEquals(56.sp, box.placeholderHeight)
    }

    @Test
    fun `perso placeholder height stays below the previous broken 64sp bucket`() {
        // The visual rule of thumb after PR #126 bug: a single inline smiley should not bump the
        // line height back to the old 64sp bucket, otherwise the paragraph risks reading as
        // broken even if Compose technically lays out without overlap. Reads bodyMedium directly
        // from the project Typography so the invariant tracks future typography tweaks. Current
        // bodyMedium.lineHeight is 20sp, so 2.8× gives the readability target 56sp.
        val bodyMediumLineHeightSp = RedfaceTypography.bodyMedium.lineHeight.value
        assertTrue(
            "perso placeholder height ${PostMediaDisplayPolicy.persoSmiley.placeholderHeight} " +
                "must stay ≤ 2.8 × ${bodyMediumLineHeightSp}sp (bodyMedium.lineHeight)",
            PostMediaDisplayPolicy.persoSmiley.placeholderHeight.value <= bodyMediumLineHeightSp * 2.8f,
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
        // Reserves a stable visual slot during SubcomposeAsyncImage loading/error to avoid a
        // layout jump when the bitmap finally resolves (cf. PR #126 Codex review).
        assertEquals(160.dp, PostMediaDisplayPolicy.blockImageMinHeight)
    }

    @Test
    fun `block image max height is 480dp`() {
        // Soft cap so a 4000x3000 RAW screenshot can't blow up the post and break scrolling.
        // Bumping this would also regress the cache estimates discussed in the policy KDoc.
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
    fun `inline image content scale is Inside so arbitrary small images are not upscaled`() {
        // Unlike smileys, inline [img] content can be an arbitrary small picture; keep no-upscale
        // there to avoid turning a tiny linked image into a 240×180 thumbnail.
        assertSame(ContentScale.Inside, PostMediaDisplayPolicy.inlineImageContentScale)
    }

    @Test
    fun `Fit sizing preserves ratio while making the real HFR perso corpus readable`() {
        // Real GIFs sampled live from forum-images.hardware.fr (curl + file). Verifies the policy
        // produces the expected fit-to-bucket result for each typical size class:
        // - tiny perso (≤30 px): upscale to a readable phone-size glyph
        // - median perso (50×50): upscale slightly to the 56×56 readability target
        // - wide perso (70×50): downscale while preserving aspect ratio
        // - rare big perso (200×150): aggressively downscale, but still fit the bucket
        val bucket = PixelSize(width = 56, height = 56)
        data class Case(val source: PixelSize, val expected: PixelSize, val label: String)
        val cases = listOf(
            Case(PixelSize(15, 15), PixelSize(56, 56), "tinostar (tiny square, readable upscale)"),
            Case(PixelSize(39, 15), PixelSize(56, 22), "rofl (wide, readable upscale)"),
            Case(PixelSize(50, 50), PixelSize(56, 56), "median perso (slight readability upscale)"),
            Case(PixelSize(56, 56), PixelSize(56, 56), "exact-fit (bucket frontier)"),
            Case(PixelSize(70, 50), PixelSize(56, 40), "apges/eberhart (downscale, ratio preserved)"),
            Case(PixelSize(200, 150), PixelSize(56, 42), "rare oversize sprite (heavy downscale)"),
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
        val bucket = PixelSize(56, 56)
        val tallStrip = fitScaledMediaSize(PixelSize(width = 1, height = 100), bucket)
        assertTrue("tall strip must keep width ≥ 1", tallStrip.width >= 1)
        assertEquals(56, tallStrip.height)

        val wideStrip = fitScaledMediaSize(PixelSize(width = 100, height = 1), bucket)
        assertEquals(56, wideStrip.width)
        assertTrue("wide strip must keep height ≥ 1", wideStrip.height >= 1)
    }
}
