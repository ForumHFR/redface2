package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostInline
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E2 — unit fragments from the issue's reproduction; no captured HFR fixture is modified. */
class PostContentParserCcImageTest {

    private val url = "https://example.org/e.png?hfr-cc-image=true"

    @Test
    fun `isolated cc image remains inline in a paragraph`() {
        val block = parse("<p><img src=\"$url\"></p>").single()

        assertEquals(PostBlock.Paragraph(listOf(PostInline.InlineImage(url, null))), block)
    }

    @Test
    fun `isolated cc image remains inline inside quote and spoiler containers`() {
        listOf("quote", "spoiler").forEach { tableClass ->
            val block = parse("<table class=\"$tableClass\"><tr><td><img src=\"$url\"></td></tr></table>")
                .single()
            val content = when (block) {
                is PostBlock.Quote -> block.content
                is PostBlock.Spoiler -> block.content
                else -> error("expected $tableClass, got $block")
            }
            assertEquals(PostBlock.Paragraph(listOf(PostInline.InlineImage(url, null))), content.blocks.single())
        }
    }

    @Test
    fun `encoded marker is inline while ambiguous and fragment markers remain block images`() {
        val encoded = "https://example.org/e.png?%68fr-cc-image=%74rue"
        assertTrue(parse("<p><img src=\"$encoded\"></p>").single() is PostBlock.Paragraph)
        listOf(
            "$url&amp;hfr-cc-image=false",
            "https://example.org/e.png#frag?hfr-cc-image=true",
        ).forEach { nonCc ->
            assertTrue(parse("<p><img src=\"$nonCc\"></p>").single() is PostBlock.Image)
        }
    }

    private fun parse(html: String): List<PostBlock> =
        PostContentParser().parse(Jsoup.parseBodyFragment(html).body()).ast.blocks
}
