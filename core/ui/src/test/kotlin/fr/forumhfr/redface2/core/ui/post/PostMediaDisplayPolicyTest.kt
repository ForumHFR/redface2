package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `perso smileys map to the larger 64sp bucket so iconic sprites are not crushed`() {
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
    fun `builtin bucket dimensions are 18sp x 18sp 18dp x 18dp`() {
        val box = PostMediaDisplayPolicy.builtinSmiley
        assertEquals(18.sp, box.placeholderWidth)
        assertEquals(18.sp, box.placeholderHeight)
        assertEquals(18.dp, box.modifierWidth)
        assertEquals(18.dp, box.modifierHeight)
    }

    @Test
    fun `perso bucket dimensions are 64sp x 64sp 64dp x 64dp`() {
        val box = PostMediaDisplayPolicy.persoSmiley
        assertEquals(64.sp, box.placeholderWidth)
        assertEquals(64.sp, box.placeholderHeight)
        assertEquals(64.dp, box.modifierWidth)
        assertEquals(64.dp, box.modifierHeight)
    }

    @Test
    fun `inline image bucket dimensions are 240sp x 180sp 240dp x 180dp`() {
        val box = PostMediaDisplayPolicy.inlineImage
        assertEquals(240.sp, box.placeholderWidth)
        assertEquals(180.sp, box.placeholderHeight)
        assertEquals(240.dp, box.modifierWidth)
        assertEquals(180.dp, box.modifierHeight)
    }

    @Test
    fun `block image max height is 480dp`() {
        // Soft cap so a 4000x3000 RAW screenshot can't blow up the post and break scrolling.
        // Bumping this would also regress the cache estimates discussed in the policy KDoc.
        assertEquals(480.dp, PostMediaDisplayPolicy.blockImageMaxHeight)
    }
}
