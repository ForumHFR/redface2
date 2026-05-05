package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
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
    fun `perso bucket dimensions are 40sp x 40sp`() {
        // Lowered from 64sp after the post #74625731 bug capture: a 64sp placeholder in a
        // bodyMedium paragraph (lineHeight = 20sp) made line layout buckle, plus ContentScale.Fit
        // upscaled the small 15×15 perso to a blocky 64×64. 40sp keeps median 50×50 perso
        // readable while staying ≤ 2.5 × bodyMedium.lineHeight (cf. invariant test below).
        val box = PostMediaDisplayPolicy.persoSmiley
        assertEquals(40.sp, box.placeholderWidth)
        assertEquals(40.sp, box.placeholderHeight)
    }

    @Test
    fun `perso placeholder height stays at most 2x5 times bodyMedium lineHeight`() {
        // Type.kt fixes bodyMedium.lineHeight = 20.sp. The visual rule of thumb after PR #126
        // bug: a single inline smiley should not bump the line height past ~2.5× the surrounding
        // text rhythm, otherwise the paragraph reads as broken even if Compose technically lays
        // out without overlap. This guards against a future bump back to 64.
        val bodyMediumLineHeightSp = 20f
        assertTrue(
            "perso placeholder height ${PostMediaDisplayPolicy.persoSmiley.placeholderHeight} " +
                "must stay ≤ 2.5 × ${bodyMediumLineHeightSp}sp",
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
    fun `inline media content scale is Inside so small sprites are never upscaled`() {
        // Lock the policy intent: inline media (smileys + inline images) downscale to fit the
        // bucket, never upscale. ContentScale.Fit upscales 15×15 perso to bucket size (4× scale,
        // pixelated) — exactly what bit us in PR #126.
        assertSame(ContentScale.Inside, PostMediaDisplayPolicy.inlineMediaContentScale)
    }

    @Test
    fun `Inside sizing does not upscale the real HFR perso corpus`() {
        // Real GIFs sampled live from forum-images.hardware.fr (curl + file). Verifies the policy
        // produces the expected fit-without-upscale result for each typical size class:
        // - tiny perso (≤30 px): stay at native size, never upscaled to bucket
        // - median perso (50×50): downscaled to fit the 40×40 bucket
        // - wide perso (70×50): downscaled while preserving aspect ratio
        // - rare big perso (200×150): aggressively downscaled, but still fits the bucket
        val bucket = PixelSize(width = 40, height = 40)
        data class Case(val source: PixelSize, val expected: PixelSize, val label: String)
        val cases = listOf(
            Case(PixelSize(15, 15), PixelSize(15, 15), "tinostar (tiny square, no upscale)"),
            Case(PixelSize(39, 15), PixelSize(39, 15), "rofl (wide, native fits)"),
            Case(PixelSize(50, 50), PixelSize(40, 40), "median perso (uniform downscale)"),
            Case(PixelSize(70, 50), PixelSize(40, 29), "apges/eberhart (downscale, ratio preserved)"),
            Case(PixelSize(200, 150), PixelSize(40, 30), "rare oversize sprite (heavy downscale)"),
        )

        cases.forEach { (source, expected, label) ->
            val actual = insideScaledMediaSize(source = source, bucket = bucket)
            assertEquals("[$label] mismatch", expected, actual)
            assertTrue("[$label] width must not exceed source", actual.width <= source.width)
            assertTrue("[$label] height must not exceed source", actual.height <= source.height)
            assertTrue("[$label] width must fit bucket", actual.width <= bucket.width)
            assertTrue("[$label] height must fit bucket", actual.height <= bucket.height)
        }
    }
}
