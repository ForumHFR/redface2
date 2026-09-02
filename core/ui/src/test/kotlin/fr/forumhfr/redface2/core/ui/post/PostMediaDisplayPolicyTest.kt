package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.forumhfr.redface2.core.domain.preferences.PostImageMaxWidth
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
    fun `cold block slot follows the v1_4 formula`() {
        // #957 — §6 cold : width fImage×available ; height min(cap, max(160, 0,75×width)).
        // #991 keeps P95 as the default: 360×0,95 = 342, h = 0,75×342.
        val (w, h) = coldBlockSlotDp(
            availableWidthDp = 360f,
            capBlocDp = 400f,
            postImageMaxWidth = PostImageMaxWidth.P95,
        )
        assertEquals(342f, w, 0.01f)
        assertEquals(256.5f, h, 0.01f)
        // Plancher 160 sur une colonne étroite ; cap qui borde en split-screen (E11 : 301 dp).
        assertEquals(160f, coldBlockSlotDp(120f, 400f, PostImageMaxWidth.P95).second, 0.01f)
        assertEquals(301f, coldBlockSlotDp(800f, 301f, PostImageMaxWidth.P95).second, 0.01f)
    }

    @Test
    fun `cold block slot width follows the selected post image preset`() {
        val p90 = coldBlockSlotDp(
            availableWidthDp = 360f,
            capBlocDp = 400f,
            postImageMaxWidth = PostImageMaxWidth.P90,
        )
        val p100 = coldBlockSlotDp(
            availableWidthDp = 360f,
            capBlocDp = 400f,
            postImageMaxWidth = PostImageMaxWidth.P100,
        )

        assertEquals(324f, p90.first, 0.01f)
        assertEquals(243f, p90.second, 0.01f)
        assertEquals(360f, p100.first, 0.01f)
        assertEquals(270f, p100.second, 0.01f)
    }

    @Test
    fun `cold cap px clamps the useful window height per amendement Lot0-3`() {
        // S10e split réel (E11) : utile 903 px @3.0, plancher 400dp=1200px → cap = 903 (301 dp).
        assertEquals(903, blockImageColdCapPx(usefulHeightPx = 903, floor400DpPx = 1200))
        // Portrait S10e : utile 1950 px → 0,70 × 1950 = 1365 > plancher 1200 → cap = 1365.
        // Depuis [AMENDEMENT-v1.5-5] (#993) la FRACTION gouverne en portrait ; le plancher 400 dp
        // ne sert plus qu'en fenêtre courte (cas split ci-dessus).
        assertEquals(1365, blockImageColdCapPx(usefulHeightPx = 1950, floor400DpPx = 1200))
        // La cible de la décision #993 : fenêtre utile RÉELLE mesurée par sonde sur S10e portrait
        // (2124 px = 708 dp @d3) → 0,70 × 2124 = 1487 px ≈ 496 dp, soit les ~500 dp arbitrés.
        assertEquals(1487, blockImageColdCapPx(usefulHeightPx = 2124, floor400DpPx = 1200))
        assertEquals(0, usefulWindowHeightPx(100, 60, 60))
    }

    @Test
    fun `cold cap crosses from the floor to the fraction regime between 1714 and 1715 px`() {
        // #993 — LA frontière de régime du domaine (plancher 1200 px = 400 dp @d3), le seul
        // endroit où le comportement change de main. En Float, 0,70f = 0,699999988… donc
        // 1714 × 0,70f = 1199,79993f → round = 1200 : le plancher gouverne encore. Pour 1715 le
        // produit RÉEL vaut 1200,49998 (strictement SOUS 1200,5) mais l'arrondi au plus proche de
        // la multiplication Float tombe EXACTEMENT sur 1200,5f, puis roundToInt (ties vers +∞)
        // donne 1201 : la fraction prend la main. Épinglé pour que ce double arrondi ne bouge pas
        // silencieusement (changement de type, de coefficient ou d'ordre des opérations).
        assertEquals(1200, blockImageColdCapPx(usefulHeightPx = 1714, floor400DpPx = 1200))
        assertEquals(1201, blockImageColdCapPx(usefulHeightPx = 1715, floor400DpPx = 1200))
    }

    @Test
    fun `cold cap fraction rounds an odd useful height to the nearest pixel`() {
        // Complément au témoin de frontière ci-dessus : l'arrondi de la fraction, isolé loin des
        // deux autres régimes. 1001 × 0,70 = 700,7 → 701 ; une TRONCATURE donnerait 700, et ni le
        // plancher (400) ni le clamp à la hauteur utile (1001) ne peuvent masquer le résultat —
        // le témoin discrimine donc l'arrondi seul.
        assertEquals(701, blockImageColdCapPx(usefulHeightPx = 1001, floor400DpPx = 400))
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
