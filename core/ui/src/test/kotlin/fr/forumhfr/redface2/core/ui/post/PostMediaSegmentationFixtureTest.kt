package fr.forumhfr.redface2.core.ui.post

import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.parser.TopicPageParser
import fr.forumhfr.redface2.core.ui.post.ParagraphSegment.InlineSegment
import fr.forumhfr.redface2.core.ui.post.ParagraphSegment.MediaRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * #876/#956 (Lot 1A) — EXHAUSTIVE segmentation pass over the REAL bench fixture
 * (`topic 148760`, anonymous capture of 2026-07-19 post-v1.4 synchronisation — charte :
 * fixtures come from live HFR and dictate exhaustiveness). Every §2-relevant bench case
 * (posts 2→14) gets its partition asserted here against the frozen contract v1.4
 * expectations ; quote/spoiler INNER paragraphs are partitioned by the test itself
 * (recursive call-site is Lot 1B's duty, the policy stays paragraph-scoped).
 *
 * Asset shorthand : diberie ids (`…/Picture/Get/f/<id>` or `/r/<id>`), Pages assets by name.
 */
class PostMediaSegmentationFixtureTest {

    companion object {
        private lateinit var bodies: List<PostContent>

        @JvmStatic
        @BeforeClass
        fun parseFixture() {
            val html = requireNotNull(
                PostMediaSegmentationFixtureTest::class.java
                    .getResource("/fixtures/topic_page_banc_images_876.html"),
            ) { "Fixture not found" }.readText()
            val topic = TopicPageParser().parse(html)
            // The 14 bench posts are XaTelitte's ; AAF interjections + community replies are
            // filtered out by the « POST n — » marker of each bench post body.
            bodies = topic.posts.map { it.content }.filter { content ->
                val first = firstText(content)
                first.startsWith("POST ") || first.startsWith("Banc de test images")
            }
            assertEquals("Le banc doit contenir les 14 posts XaTelitte", 14, bodies.size)
        }

        private fun firstText(content: PostContent): String {
            val para = content.blocks.filterIsInstance<PostBlock.Paragraph>().firstOrNull()
                ?: return ""
            return para.inlines.joinToString("") { inline ->
                when (inline) {
                    is fr.forumhfr.redface2.core.model.PostInline.Text -> inline.value
                    is fr.forumhfr.redface2.core.model.PostInline.Strong ->
                        inline.children.filterIsInstance<fr.forumhfr.redface2.core.model.PostInline.Text>()
                            .joinToString("") { it.value }
                    else -> ""
                }
            }.trim()
        }
    }

    /** Bench post N (1-based, XaTelitte numbering). */
    private fun post(n: Int): PostContent = bodies[n - 1]

    private fun paragraphs(content: PostContent): List<PostBlock.Paragraph> =
        content.blocks.filterIsInstance<PostBlock.Paragraph>()

    /** Partition of EVERY top-level paragraph of the post, concatenated in order. */
    private fun partitioned(content: PostContent): List<ParagraphSegment> =
        paragraphs(content).flatMap { partitionParagraph(it.inlines) }

    private fun mediaRuns(content: PostContent): List<MediaRun> =
        partitioned(content).filterIsInstance<MediaRun>()

    private fun ids(run: MediaRun): List<String> =
        run.images.map { it.image.url.substringAfterLast('/') }

    // ---------- post 1 (protocole) et post 12 (smileys) : zéro run ----------

    @Test
    fun `post 1 has no media run`() {
        assertEquals(0, mediaRuns(post(1)).size)
    }

    @Test
    fun `post 12 smileys stay prose with zero media run`() {
        val segments = partitioned(post(12))
        assertEquals(0, segments.filterIsInstance<MediaRun>().size)
        assertTrue(
            segments.filterIsInstance<InlineSegment>().flatMap { it.inlines }
                .any { it is fr.forumhfr.redface2.core.model.PostInline.Smiley },
        )
    }

    // ---------- post 2 : 7 singletons isolés → 7 runs d'une image ----------

    @Test
    fun `post 2 yields seven isolated singleton runs in order`() {
        val runs = mediaRuns(post(2))
        assertEquals(
            listOf("530315", "530317", "530318", "530319", "530320", "530325", "530324"),
            runs.map { ids(it).single() },
        )
    }

    // ---------- post 3 : seul 3_1 est un bloc ----------

    @Test
    fun `post 3 has exactly one run - the isolated 3_1 - others stay inline`() {
        val runs = mediaRuns(post(3))
        assertEquals(listOf(listOf("530321")), runs.map(::ids))
        // 3.2/3.3/3.5 images and the 3.4 linked image stay in prose :
        val proseImages = partitioned(post(3)).filterIsInstance<InlineSegment>()
            .flatMap { it.inlines }
            .count { hasImage(it) }
        assertEquals(4 + 1, proseImages) // 3.2 (1) + 3.3 (2) + 3.5 (1) + 3.4 lien (1)
    }

    private fun hasImage(inline: fr.forumhfr.redface2.core.model.PostInline): Boolean =
        when (inline) {
            is fr.forumhfr.redface2.core.model.PostInline.InlineImage -> true
            is fr.forumhfr.redface2.core.model.PostInline.Link -> inline.children.any(::hasImage)
            is fr.forumhfr.redface2.core.model.PostInline.Strong -> inline.children.any(::hasImage)
            is fr.forumhfr.redface2.core.model.PostInline.Emphasis -> inline.children.any(::hasImage)
            is fr.forumhfr.redface2.core.model.PostInline.Underline -> inline.children.any(::hasImage)
            is fr.forumhfr.redface2.core.model.PostInline.Strike -> inline.children.any(::hasImage)
            is fr.forumhfr.redface2.core.model.PostInline.Color -> inline.children.any(::hasImage)
            else -> false
        }

    // ---------- post 4 : galeries pures ----------

    @Test
    fun `post 4 galleries of 2 3 5 and 8 keep their sizes and order`() {
        val runs = mediaRuns(post(4))
        assertEquals(listOf(2, 3, 5, 8), runs.map { it.images.size })
        assertEquals(listOf("530326", "530328"), ids(runs[0]))
        assertEquals(listOf("530329", "530330", "530331"), ids(runs[1]))
        assertEquals(
            listOf("530326", "530328", "530329", "530330", "530331", "530332", "530333", "530334"),
            ids(runs[3]),
        )
    }

    // ---------- post 5 : galeries et texte mélangés ----------

    @Test
    fun `post 5 partitions text and galleries per contract - tinc fragments stay in place`() {
        val runs = mediaRuns(post(5))
        assertEquals(listOf(3, 3, 1, 1, 1, 2, 2), runs.map { it.images.size })
        // 5.3 : les fragments « 6 » et « filles » survivent comme prose entre les singletons.
        val proseTexts = partitioned(post(5)).filterIsInstance<InlineSegment>()
            .map { seg ->
                seg.inlines.filterIsInstance<fr.forumhfr.redface2.core.model.PostInline.Text>()
                    .joinToString("") { it.value }
            }
        assertTrue(proseTexts.any { it.contains("6") })
        assertTrue(proseTexts.any { it.contains("filles") })
    }

    // ---------- post 6 : miniatures liées ----------

    @Test
    fun `post 6 linked thumbnails - isolated ones are linked runs - 6_3 stays prose`() {
        val runs = mediaRuns(post(6))
        assertEquals(listOf(1, 3, 3), runs.map { it.images.size })
        // 6.1 : le href pointe la pleine taille.
        assertEquals("https://rehost.diberie.com/Picture/Get/f/530325", runs[0].images.single().linkUrl)
        // 6.2 : chaque mini /r/ porte son /f/ correspondant.
        assertEquals(
            listOf("530326", "530328", "530330"),
            runs[1].images.map { it.linkUrl!!.substringAfterLast('/') },
        )
        // 6.3 : la mini liée en pleine phrase reste en prose.
        val prose = partitioned(post(6)).filterIsInstance<InlineSegment>().flatMap { it.inlines }
        assertTrue(prose.any { it is fr.forumhfr.redface2.core.model.PostInline.Link && hasImage(it) })
    }

    // ---------- post 7 : conteneurs (partition récursive côté test) ----------

    private fun innerParagraphs(content: PostContent): List<PostBlock.Paragraph> =
        content.blocks.flatMap { block ->
            when (block) {
                is PostBlock.Paragraph -> listOf(block)
                is PostBlock.Quote -> innerParagraphs(block.content)
                is PostBlock.Spoiler -> innerParagraphs(block.content)
                else -> emptyList()
            }
        }

    @Test
    fun `post 7 quote and spoiler inner paragraphs partition like top level ones`() {
        val innerRuns = innerParagraphs(post(7))
            .flatMap { partitionParagraph(it.inlines) }
            .filterIsInstance<MediaRun>()
        // 7.1 singleton (530318) ; 7.2 galerie {526,528} ; 7.3 singleton imbriqué (530315) ;
        // 7.5 galerie de 3. 7.4 : image seule du spoiler = PostBlock.Image (déjà bloc au parse).
        assertEquals(listOf(1, 2, 1, 3), innerRuns.map { it.images.size })
        val standalone = post(7).blocks.flatMap { collectImageBlocks(it) }
        assertEquals(listOf("530315"), standalone.map { it.url.substringAfterLast('/') })
    }

    private fun collectImageBlocks(block: PostBlock): List<PostBlock.Image> = when (block) {
        is PostBlock.Image -> listOf(block)
        is PostBlock.Quote -> block.content.blocks.flatMap { collectImageBlocks(it) }
        is PostBlock.Spoiler -> block.content.blocks.flatMap { collectImageBlocks(it) }
        else -> emptyList()
    }

    // ---------- post 8 : liens et frontières ----------

    @Test
    fun `post 8 - naked url stays a text link - textual link bounds - bold joins`() {
        val runs = mediaRuns(post(8))
        // 8.2 : deux singletons isolés autour du lien texte ; 8.4 : galerie de 2 via wrapper.
        assertEquals(listOf(1, 1, 2), runs.map { it.images.size })
        assertEquals(listOf("530333", "530326"), ids(runs[2]))
        // 8.1 : l'URL nue est restée un LIEN TEXTE (aucune image, jamais un run, jamais supprimé).
        val links = partitioned(post(8)).filterIsInstance<InlineSegment>()
            .flatMap { it.inlines }
            .filterIsInstance<fr.forumhfr.redface2.core.model.PostInline.Link>()
        assertTrue(links.any { it.url.contains("530332") && !hasImage(it) })
        // 8.3 : le lien mixte reste entier en prose.
        assertTrue(links.any { hasImage(it) && it.children.any { c -> c is fr.forumhfr.redface2.core.model.PostInline.Text && c.value.isNotBlank() } })
    }

    // ---------- post 9 : GIF ----------

    @Test
    fun `post 9 gif topologies - small inline - big isolated - linked - gallery`() {
        val runs = mediaRuns(post(9))
        assertEquals(listOf(1, 1, 2), runs.map { it.images.size })
        assertEquals("530336", ids(runs[0]).single()) // 9.2
        assertEquals("https://rehost.diberie.com/Picture/Get/f/530336", runs[1].images.single().linkUrl) // 9.3
        assertEquals(listOf("530335", "530336"), ids(runs[2])) // 9.4
        // 9.1 : le petit GIF en phrase reste prose.
        val prose = partitioned(post(9)).filterIsInstance<InlineSegment>().flatMap { it.inlines }
        assertTrue(prose.any { it is fr.forumhfr.redface2.core.model.PostInline.InlineImage && it.url.contains("530335") })
    }

    // ---------- post 10 : exotiques et erreurs ----------

    @Test
    fun `post 10 - avif and svg isolated singletons - dead url galleries keep topology`() {
        val runs = mediaRuns(post(10))
        assertEquals(listOf(1, 1, 2, 3), runs.map { it.images.size })
        assertTrue(ids(runs[0]).single().contains("avif"))
        assertTrue(ids(runs[1]).single().contains("svg"))
        // 10.3 : la morte et son témoin forment UNE galerie de 2 (topologie structurelle).
        assertEquals(listOf("morte.jpg", "530334"), ids(runs[2]))
    }

    // ---------- post 11 : sélection (aucun run) ----------

    @Test
    fun `post 11 selection paragraph keeps everything prose`() {
        assertEquals(0, mediaRuns(post(11)).size)
    }

    // ---------- post 13 : la torture tinc ----------

    @Test
    fun `post 13 torture - two galleries with fragments then a spoiler gallery`() {
        val topRuns = mediaRuns(post(13))
        assertEquals(listOf(3, 2), topRuns.map { it.images.size })
        assertEquals(listOf("530326", "530328", "530329"), ids(topRuns[0]))
        assertEquals(listOf("530330", "530331"), ids(topRuns[1]))
        val spoilerRuns = innerParagraphs(post(13))
            .flatMap { partitionParagraph(it.inlines) }
            .filterIsInstance<MediaRun>()
            .filter { it !in topRuns }
        assertEquals(listOf(listOf("530332", "530333")), spoilerRuns.map(::ids))
    }

    // ---------- post 14 : EXIF ----------

    @Test
    fun `post 14 exif mire is an isolated singleton run`() {
        val runs = mediaRuns(post(14))
        assertEquals(1, runs.size)
        assertTrue(ids(runs[0]).single().contains("mire-exif-o6"))
    }
}
