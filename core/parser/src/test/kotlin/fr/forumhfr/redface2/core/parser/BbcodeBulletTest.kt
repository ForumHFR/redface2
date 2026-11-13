package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostInline
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BbcodeBulletTest {
    private val parser = BbcodeContentParser()

    @Test
    fun `adjacent bullets start distinct lines and preserve inline formatting`() {
        val ast = parser.parse("intro[*][b]one[/b][*][email]a@b.c[/email]")
        assertEquals(listOf(PostInline.Text("intro"), PostInline.LineBreak, PostInline.Text("• "),
            PostInline.Strong(listOf(PostInline.Text("one"))), PostInline.LineBreak, PostInline.Text("• "),
            PostInline.Link("mailto:a@b.c", listOf(PostInline.Text("a@b.c")))),
            (ast.blocks.single() as PostBlock.Paragraph).inlines)
    }

    @Test
    fun `bullet at the beginning of a line adds no extra line break`() {
        val expected = listOf(PostInline.Text("• one"), PostInline.LineBreak, PostInline.Text("• two"))
        listOf("[*]one\n[*]two", "[*]one\r\n[*]two", "[*]one[*]two").forEach { bbcode ->
            assertEquals(expected, (parser.parse(bbcode).blocks.single() as PostBlock.Paragraph).inlines)
        }
    }

    @Test
    fun `empty bullet remains visible and raw blocks preserve the original marker`() {
        assertEquals(listOf(PostInline.Text("• ")),
            (parser.parse("[*]").blocks.single() as PostBlock.Paragraph).inlines)
        assertEquals(listOf(PostBlock.Fixed("[*]one")), parser.parse("[fixed][*]one[/fixed]").blocks)
        assertEquals(listOf(PostBlock.CodeBlock("[*]one", "cpp")), parser.parse("[cpp][*]one[/cpp]").blocks)
    }

    @Test
    fun `bullets inside inline formatting quotes and spoilers are rendered`() {
        val paragraph = PostBlock.Paragraph(listOf(PostInline.Text("• one"), PostInline.LineBreak,
            PostInline.Text("• two")))
        val inline = parser.parse("[b][*]one[*]two[/b]").blocks.single() as PostBlock.Paragraph
        assertEquals(listOf(PostInline.Strong(paragraph.inlines)), inline.inlines)
        val quote = parser.parse("[quote][*]one[*]two[/quote]").blocks.single() as PostBlock.Quote
        assertEquals(listOf(paragraph), quote.content.blocks)
        val spoiler = parser.parse("[spoiler][*]one[*]two[/spoiler]").blocks.single() as PostBlock.Spoiler
        assertEquals(listOf(paragraph), spoiler.content.blocks)
    }

    @Test
    fun `leading formatted bullet separates from the preceding paragraph text`() {
        val item = listOf(PostInline.Text("• un"))
        val formats = listOf(
            "[b][*]un[/b]" to PostInline.Strong(item),
            "[b]\n[*]un[/b]" to PostInline.Strong(item),
            "[b]\r\n[*]un[/b]" to PostInline.Strong(item),
            "[i][*]un[/i]" to PostInline.Emphasis(item),
            "[u][*]un[/u]" to PostInline.Underline(item),
            "[strike][*]un[/strike]" to PostInline.Strike(item),
            "[#FF0000][*]un[/#FF0000]" to PostInline.Color("#FF0000", item),
            "[url=https://example.com][*]un[/url]" to PostInline.Link("https://example.com", item),
            "[email=a@b.c][*]un[/email]" to PostInline.Link("mailto:a@b.c", item),
            "[b][i][*]un[/i][/b]" to PostInline.Strong(listOf(PostInline.Emphasis(item))),
        )
        formats.forEach { (bbcode, formatted) ->
            val inlines = (parser.parse("avant$bbcode").blocks.single() as PostBlock.Paragraph).inlines
            assertEquals(bbcode, listOf(PostInline.Text("avant"), PostInline.LineBreak, formatted), inlines)
        }
    }

    @Test
    fun `formatted bullets at paragraph and line starts add no extra line break`() {
        val item = PostInline.Strong(listOf(PostInline.Text("• un")))
        listOf("[b][*]un[/b]", "\n[b][*]un[/b]").forEach { bbcode ->
            assertEquals(listOf(item), (parser.parse(bbcode).blocks.single() as PostBlock.Paragraph).inlines)
        }
        val afterBreak = parser.parse("avant\n[b][*]un[/b]").blocks.single() as PostBlock.Paragraph
        assertEquals(listOf(PostInline.Text("avant"), PostInline.LineBreak, item), afterBreak.inlines)
        val italic = parser.parse("[i][*]a[*]b[/i]").blocks.single() as PostBlock.Paragraph
        assertEquals(listOf(PostInline.Emphasis(listOf(PostInline.Text("• a"), PostInline.LineBreak,
            PostInline.Text("• b")))), italic.inlines)
    }

    @Test
    fun `raw sequences inside formatting keep literal bullet markers`() {
        // Nested raw blocks still display their tags; only the bullet conversion is a regression here.
        listOf("fixed", "code", "cpp", "img").forEach { tag ->
            val raw = "[$tag][*]x[/$tag]"
            val paragraph = parser.parse("[b]$raw[/b]").blocks.single() as PostBlock.Paragraph
            assertEquals(tag, listOf(PostInline.Strong(listOf(PostInline.Text(raw)))), paragraph.inlines)
        }
        val nested = parser.parse("[b][fixed][i][*]x[/i][/fixed][/b]")
            .blocks.single() as PostBlock.Paragraph
        assertEquals(listOf(PostInline.Strong(listOf(PostInline.Text("[fixed]"),
            PostInline.Emphasis(listOf(PostInline.Text("[*]x"))), PostInline.Text("[/fixed]")))), nested.inlines)
    }

    @Test
    fun `raw blocks preserve bullet markers inside formatting`() {
        val raw = "[b][*]x[/b]"
        assertEquals(listOf(PostBlock.CodeBlock(raw, null)), parser.parse("[code]$raw[/code]").blocks)
        assertEquals(listOf(PostBlock.Fixed(raw)), parser.parse("[fixed]$raw[/fixed]").blocks)
        assertEquals(listOf(PostBlock.CodeBlock(raw, "cpp")), parser.parse("[cpp]$raw[/cpp]").blocks)
    }

    @Test
    fun `preview bullets match the real HFR reading list without changing the AST model`() {
        // Existing capture, topic_page_multipage.html:161-162. No fabricated HTML fixture.
        val html = requireNotNull(javaClass.getResource("/fixtures/topic_page_multipage.html")).readText()
        val list = requireNotNull(Jsoup.parse(html).selectFirst("div[id^=para] ul"))
        val items = list.children().take(2).map { it.text() }
        val container = requireNotNull(list.parent()).clone()
        val sample = requireNotNull(container.selectFirst("ul"))
        container.childNodes().filter { it != sample }.forEach { it.remove() }
        sample.children().drop(2).forEach { it.remove() }
        val reading = PostContentParser().parse(container).ast
        assertEquals(reading, PostContentParser().parse(sample).ast)
        val preview = parser.parse(items.joinToString("\n") { "[*]$it" })
        // HTML source whitespace is collapsed by the reading parser; compare visible item boundaries.
        val readingInlines = (reading.blocks.single() as PostBlock.Paragraph).inlines.map {
            if (it is PostInline.Text) it.copy(value = it.value.trim()) else it
        }
        val previewInlines = (preview.blocks.single() as PostBlock.Paragraph).inlines
        assertEquals(listOf(PostInline.Text("• ${items[0]}"), PostInline.LineBreak,
            PostInline.Text("• ${items[1]}")), readingInlines)
        assertEquals(readingInlines, previewInlines)
    }

    @Test
    fun `real HFR reading list keeps every bullet and inline strike formatting`() {
        val html = requireNotNull(javaClass.getResource("/fixtures/topic_page_multipage.html")).readText()
        val list = requireNotNull(Jsoup.parse(html).selectFirst("div[id^=para] ul"))
        val reading = PostContentParser().parse(list).ast
        val inlines = (reading.blocks.single() as PostBlock.Paragraph).inlines
        assertEquals(list.childrenSize(), inlines.filterIsInstance<PostInline.Text>().count {
            it.value.startsWith("• ")
        })
        assertEquals(list.childrenSize() - 1, inlines.count { it == PostInline.LineBreak })
        val struckItems = list.select("li > strike").map { it.text() }
        assertTrue(struckItems.isNotEmpty())
        assertEquals(struckItems, inlines.filterIsInstance<PostInline.Strike>().map { strike ->
            strike.children.filterIsInstance<PostInline.Text>().joinToString("") { it.value }
        })
    }
}
