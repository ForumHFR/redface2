package fr.forumhfr.redface2.core.ui.post

import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import fr.forumhfr.redface2.core.ui.post.ParagraphSegment.InlineSegment
import fr.forumhfr.redface2.core.ui.post.ParagraphSegment.MediaRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #876/#956 (Lot 1A) — unit coverage of the pure segmentation policy, mirroring the frozen
 * contract v1.4 §2 invariants (I2.1-I2.13 of `matrice-invariants-876.md`) and the bench
 * topologies (topic 148760). The REAL-fixture exhaustive pass lives in
 * [PostMediaSegmentationFixtureTest] ; this file pins each rule in isolation, model-built.
 */
class PostMediaSegmentationTest {

    // ---------- helpers ----------

    private fun img(n: Int) = PostInline.InlineImage(
        url = "https://rehost.diberie.com/Picture/Get/f/$n",
        description = null,
    )

    private fun ccImg() = PostInline.InlineImage(
        url = "https://forum-images.hardware.fr/images/cc.png?hfr-cc-image=true",
        description = null,
    )

    private fun text(value: String) = PostInline.Text(value)
    private val br = PostInline.LineBreak
    private val blank = PostInline.Text("  \n ")
    private fun smiley() = PostInline.Smiley(
        kind = SmileyKind.Builtin(":)"),
        imageUrl = "https://forum-images.hardware.fr/icones/smile.gif",
    )

    private fun linkImgs(href: String, vararg images: PostInline) =
        PostInline.Link(url = href, children = images.toList())

    private fun runUrls(segment: ParagraphSegment): List<String> =
        (segment as MediaRun).images.map { it.image.url.substringAfterLast('/') }

    private fun kinds(segments: List<ParagraphSegment>): String =
        segments.joinToString("") { if (it is MediaRun) "M" else "I" }

    // ---------- I2.1 : run ≥ 2 images → BLOC, toujours ----------

    @Test
    fun `two images separated by a line break form one media run`() {
        val out = partitionParagraph(listOf(img(1), br, img(2)))
        assertEquals("M", kinds(out))
        assertEquals(listOf("1", "2"), runUrls(out[0]))
    }

    @Test
    fun `adjacent images without separator form one media run`() {
        val out = partitionParagraph(listOf(img(1), img(2)))
        assertEquals("M", kinds(out))
        assertEquals(listOf("1", "2"), runUrls(out[0]))
    }

    @Test
    fun `gallery of two flanked by text on both sides is still a block run`() {
        val out = partitionParagraph(listOf(text("avant"), img(1), br, img(2), text("après")))
        assertEquals("IMI", kinds(out))
        assertEquals(listOf("1", "2"), runUrls(out[1]))
    }

    // ---------- I2.2 : singleton isolé (G1) → BLOC ----------

    @Test
    fun `singleton between line breaks is a block run and breaks are consumed`() {
        val out = partitionParagraph(listOf(text("titre"), br, img(1), br, text("suite")))
        assertEquals("IMI", kinds(out))
        val before = out[0] as InlineSegment
        val after = out[2] as InlineSegment
        assertEquals(listOf<PostInline>(text("titre")), before.inlines)
        assertEquals(listOf<PostInline>(text("suite")), after.inlines)
    }

    @Test
    fun `singleton at paragraph start followed by a line break is isolated`() {
        val out = partitionParagraph(listOf(img(1), br, text("texte")))
        assertEquals("MI", kinds(out))
    }

    @Test
    fun `singleton alone in the paragraph is isolated`() {
        val out = partitionParagraph(listOf(img(1)))
        assertEquals("M", kinds(out))
    }

    @Test
    fun `multi br around a block run is fully consumed`() {
        val out = partitionParagraph(listOf(text("t"), br, br, img(1), br, br, text("s")))
        assertEquals("IMI", kinds(out))
        assertTrue((out[0] as InlineSegment).inlines.none { it is PostInline.LineBreak })
        assertTrue((out[2] as InlineSegment).inlines.none { it is PostInline.LineBreak })
    }

    @Test
    fun `blank text is transparent for the isolation test`() {
        val out = partitionParagraph(listOf(text("t"), br, blank, img(1), blank, br, text("s")))
        assertEquals("IMI", kinds(out))
    }

    // ---------- I2.3 : singleton en prose → INLINE ----------

    @Test
    fun `image directly followed by text stays inline in the prose segment`() {
        val out = partitionParagraph(listOf(text("avant "), img(1), text(" après")))
        assertEquals("I", kinds(out))
        assertTrue((out[0] as InlineSegment).inlines.contains(img(1)))
    }

    @Test
    fun `image at start with text right after stays inline - bench 3_5`() {
        val out = partitionParagraph(listOf(img(1), text(" et la phrase continue")))
        assertEquals("I", kinds(out))
    }

    @Test
    fun `line breaks around an inline image are preserved in prose`() {
        // Isolated on the LEFT only : right neighbour is text → inline ; the left br must survive.
        val out = partitionParagraph(listOf(text("t"), br, img(1), text("suite")))
        assertEquals("I", kinds(out))
        val inlines = (out[0] as InlineSegment).inlines
        assertEquals(listOf<PostInline>(text("t"), br, img(1), text("suite")), inlines)
    }

    @Test
    fun `two small images in the same sentence both stay inline - bench 3_3`() {
        val out = partitionParagraph(
            listOf(text("Une première "), img(1), text(" puis une seconde "), img(2), text(" fin.")),
        )
        assertEquals("I", kinds(out))
    }

    // ---------- I2.4/G3 : lien mixte texte+image = InlineSegment entier ----------

    @Test
    fun `mixed text and image link stays entirely in prose`() {
        val mixed = PostInline.Link(
            url = "https://forum.hardware.fr",
            children = listOf(text("voir "), img(1), text(" ici")),
        )
        val out = partitionParagraph(listOf(br, mixed, br))
        assertEquals("I", kinds(out))
        assertTrue((out[0] as InlineSegment).inlines.contains(mixed))
    }

    // ---------- I2.5 : cc-image jamais membre, borne de run ----------

    @Test
    fun `cc image bounds a run like a smiley`() {
        val out = partitionParagraph(listOf(img(1), br, ccImg(), br, img(2)))
        // cc splits the candidates : two isolated singletons around an inline cc.
        assertEquals("MIM", kinds(out))
        assertTrue((out[1] as InlineSegment).inlines.any { it is PostInline.InlineImage })
    }

    @Test
    fun `linked cc image stays in prose even when the link is image only - bench 3_4`() {
        val ccLink = linkImgs("https://forum.hardware.fr", ccImg())
        val out = partitionParagraph(listOf(text("avant "), ccLink, text(" après")))
        assertEquals("I", kinds(out))
        assertTrue((out[0] as InlineSegment).inlines.contains(ccLink))
    }

    // ---------- I2.6 : smiley borne ----------

    @Test
    fun `smiley splits an image sequence into two runs`() {
        val out = partitionParagraph(listOf(img(1), br, smiley(), br, img(2)))
        assertEquals("MIM", kinds(out))
    }

    // ---------- I2.7 : wrappers transparents ----------

    @Test
    fun `bold wrapped image joins the run - bench 8_4`() {
        val wrapped = PostInline.Strong(listOf(img(2)))
        val out = partitionParagraph(listOf(img(1), br, wrapped))
        assertEquals("M", kinds(out))
        assertEquals(listOf("1", "2"), runUrls(out[0]))
    }

    @Test
    fun `nested wrappers of members are members`() {
        val nested = PostInline.Strong(listOf(PostInline.Emphasis(listOf(blank, img(2)))))
        val out = partitionParagraph(listOf(img(1), br, nested))
        assertEquals("M", kinds(out))
        assertEquals(listOf("1", "2"), runUrls(out[0]))
    }

    @Test
    fun `wrapper containing text is a boundary`() {
        val boldText = PostInline.Strong(listOf(text("titre")))
        val out = partitionParagraph(listOf(img(1), br, boldText, br, img(2)))
        assertEquals("MIM", kinds(out))
    }

    // ---------- I2.8 : liens comme bornes / membres ----------

    @Test
    fun `textual link bounds two singleton runs - bench 8_2`() {
        val textLink = PostInline.Link("https://forum.hardware.fr", listOf(text("un lien")))
        val out = partitionParagraph(listOf(img(1), br, textLink, br, img(2)))
        assertEquals("MIM", kinds(out))
    }

    @Test
    fun `image only link joins the run and href sticks to each image`() {
        val l1 = linkImgs("https://x/full1", img(1))
        val l2 = linkImgs("https://x/full2", img(2))
        val out = partitionParagraph(listOf(l1, br, l2))
        assertEquals("M", kinds(out))
        val images = (out[0] as MediaRun).images
        assertEquals(listOf("https://x/full1", "https://x/full2"), images.map { it.linkUrl })
    }

    @Test
    fun `image only link with two images contributes both with the same href`() {
        val l = linkImgs("https://x/full", img(1), img(2))
        val out = partitionParagraph(listOf(l))
        assertEquals("M", kinds(out))
        val images = (out[0] as MediaRun).images
        assertEquals(listOf("https://x/full", "https://x/full"), images.map { it.linkUrl })
    }

    @Test
    fun `isolated image only link singleton is a linked block - bench 6_1`() {
        val l = linkImgs("https://x/full", img(1))
        val out = partitionParagraph(listOf(text("t"), br, l, br, text("s")))
        assertEquals("IMI", kinds(out))
        assertEquals("https://x/full", (out[1] as MediaRun).images.single().linkUrl)
    }

    @Test
    fun `image only link in prose dissolves to inline - bench 6_3`() {
        val l = linkImgs("https://x/full", img(1))
        val out = partitionParagraph(listOf(text("avant "), l, text(" après")))
        assertEquals("I", kinds(out))
        assertTrue((out[0] as InlineSegment).inlines.contains(l))
    }

    @Test
    fun `link without any image is never a run member nor dropped`() {
        val empty = PostInline.Link("https://x", emptyList())
        val out = partitionParagraph(listOf(br, empty, br))
        assertEquals("I", kinds(out))
        assertTrue((out[0] as InlineSegment).inlines.contains(empty))
    }

    // ---------- I2.10/I2.12 : séparateurs ----------

    @Test
    fun `sequence of breaks and blanks without image is not a run`() {
        val out = partitionParagraph(listOf(text("a"), br, blank, br, text("b")))
        assertEquals("I", kinds(out))
        assertEquals(5, (out[0] as InlineSegment).inlines.size)
    }

    @Test
    fun `breaks between two runs bounded by text are consumed with their runs`() {
        // bench 5_3 : img BR "6" BR img BR "filles" BR img — three isolated singletons.
        val out = partitionParagraph(
            listOf(img(1), br, text("6"), br, img(2), br, text("filles"), br, img(3)),
        )
        assertEquals("MIMIM", kinds(out))
        assertEquals(listOf<PostInline>(text("6")), (out[1] as InlineSegment).inlines)
        assertEquals(listOf<PostInline>(text("filles")), (out[3] as InlineSegment).inlines)
    }

    // ---------- ordre, fusion et cas vides ----------

    @Test
    fun `empty input yields no segment`() {
        assertEquals(0, partitionParagraph(emptyList()).size)
    }

    @Test
    fun `prose only paragraph is a single inline segment`() {
        val out = partitionParagraph(listOf(text("juste du texte "), smiley(), text(" fin")))
        assertEquals("I", kinds(out))
    }

    @Test
    fun `no empty inline segment is emitted between two consecutive runs`() {
        // Two galleries split by a smiley boundary : the smiley itself is the prose between.
        val out = partitionParagraph(listOf(img(1), img(2), smiley(), img(3), img(4)))
        assertEquals("MIM", kinds(out))
        assertEquals(listOf<PostInline>(smiley()), (out[1] as InlineSegment).inlines)
    }

    @Test
    fun `dissolved singleton merges with surrounding prose into one segment`() {
        // Non-isolated image between texts : everything stays ONE prose segment.
        val out = partitionParagraph(listOf(text("a"), img(1), text("b"), br, img(2), text("c")))
        assertEquals("I", kinds(out))
        assertEquals(6, (out[0] as InlineSegment).inlines.size)
    }

    @Test
    fun `segment order preserves the original image order`() {
        val out = partitionParagraph(
            listOf(img(5), br, img(6), br, text("mid"), br, img(7), br, img(8)),
        )
        assertEquals("MIM", kinds(out))
        assertEquals(listOf("5", "6"), runUrls(out[0]))
        assertEquals(listOf("7", "8"), runUrls(out[2]))
    }
}
