package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BbcodeContentParserTest {

    private val parser = BbcodeContentParser()

    @Test
    fun `empty input yields empty AST`() {
        assertEquals(PostContent(blocks = emptyList()), parser.parse(""))
    }

    @Test
    fun `plain text becomes a single paragraph`() {
        val ast = parser.parse("hello world")
        val block = ast.blocks.single() as PostBlock.Paragraph
        assertEquals(listOf(PostInline.Text("hello world")), block.inlines)
    }

    @Test
    fun `bold wraps selection`() {
        val ast = parser.parse("[b]hello[/b]")
        val paragraph = ast.blocks.single() as PostBlock.Paragraph
        val strong = paragraph.inlines.single() as PostInline.Strong
        assertEquals(listOf(PostInline.Text("hello")), strong.children)
    }

    @Test
    fun `italic underline strike are recognised`() {
        val ast = parser.parse("[i]a[/i] [u]b[/u] [strike]c[/strike]")
        val paragraph = ast.blocks.single() as PostBlock.Paragraph
        assertTrue(paragraph.inlines.any { it is PostInline.Emphasis })
        assertTrue(paragraph.inlines.any { it is PostInline.Underline })
        assertTrue(paragraph.inlines.any { it is PostInline.Strike })
    }

    @Test
    fun `nested formatting stays nested`() {
        val ast = parser.parse("[b][i]combo[/i][/b]")
        val paragraph = ast.blocks.single() as PostBlock.Paragraph
        val strong = paragraph.inlines.single() as PostInline.Strong
        val emphasis = strong.children.single() as PostInline.Emphasis
        assertEquals(listOf(PostInline.Text("combo")), emphasis.children)
    }

    @Test
    fun `url with target produces a link`() {
        val ast = parser.parse("[url=https://example.com]label[/url]")
        val paragraph = ast.blocks.single() as PostBlock.Paragraph
        val link = paragraph.inlines.single() as PostInline.Link
        assertEquals("https://example.com", link.url)
        assertEquals(listOf(PostInline.Text("label")), link.children)
    }

    @Test
    fun `url without target uses the text as target`() {
        val ast = parser.parse("[url]https://example.com[/url]")
        val paragraph = ast.blocks.single() as PostBlock.Paragraph
        val link = paragraph.inlines.single() as PostInline.Link
        assertEquals("https://example.com", link.url)
    }

    @Test
    fun `url rejects unsafe schemes and keeps raw bbcode visible`() {
        val ast = parser.parse("[url=javascript:alert(1)]label[/url]")
        val paragraph = ast.blocks.single() as PostBlock.Paragraph
        assertTrue("javascript: URL must not reach PostInline.Link", paragraph.inlines.none { it is PostInline.Link })
        val text = paragraph.inlines.filterIsInstance<PostInline.Text>().joinToString(separator = "") { it.value }
        assertEquals("[url=javascript:alert(1)]label[/url]", text)
    }

    @Test
    fun `url accepts absolute HFR paths by normalising them`() {
        val ast = parser.parse("[url=/hfr/gsmgpspda/android/redface-sujet_35395_1.htm]topic[/url]")
        val paragraph = ast.blocks.single() as PostBlock.Paragraph
        val link = paragraph.inlines.single() as PostInline.Link
        assertEquals("https://forum.hardware.fr/hfr/gsmgpspda/android/redface-sujet_35395_1.htm", link.url)
    }

    @Test
    fun `email becomes a mailto link`() {
        val ast = parser.parse("[email]a@b.c[/email]")
        val paragraph = ast.blocks.single() as PostBlock.Paragraph
        val link = paragraph.inlines.single() as PostInline.Link
        assertEquals("mailto:a@b.c", link.url)
    }

    @Test
    fun `color shortcut becomes PostInline Color`() {
        val ast = parser.parse("[#FF0000]rouge[/#FF0000]")
        val paragraph = ast.blocks.single() as PostBlock.Paragraph
        val color = paragraph.inlines.single() as PostInline.Color
        assertEquals("#FF0000", color.colorHex)
    }

    @Test
    fun `fixed becomes a block`() {
        val ast = parser.parse("[fixed]bloc fixe\nligne 2[/fixed]")
        val block = ast.blocks.single() as PostBlock.Fixed
        assertEquals("bloc fixe\nligne 2", block.text)
    }

    @Test
    fun `code maps language when present`() {
        val ast = parser.parse("[code=kotlin]fun foo(){}[/code]")
        val block = ast.blocks.single() as PostBlock.CodeBlock
        assertEquals("kotlin", block.language)
        assertEquals("fun foo(){}", block.text)
    }

    @Test
    fun `cpp tag maps to CodeBlock with language cpp`() {
        val ast = parser.parse("[cpp]int x = 1;[/cpp]")
        val block = ast.blocks.single() as PostBlock.CodeBlock
        assertEquals("cpp", block.language)
        assertEquals("int x = 1;", block.text)
    }

    @Test
    fun `spoiler becomes a block`() {
        val ast = parser.parse("[spoiler]surprise[/spoiler]")
        val block = ast.blocks.single() as PostBlock.Spoiler
        val paragraph = block.content.blocks.single() as PostBlock.Paragraph
        assertEquals(listOf(PostInline.Text("surprise")), paragraph.inlines)
    }

    @Test
    fun `img tag becomes an image block`() {
        val ast = parser.parse("[img]https://example.com/a.png[/img]")
        val block = ast.blocks.single() as PostBlock.Image
        assertEquals("https://example.com/a.png", block.url)
        assertNull(block.description)
    }

    @Test
    fun `img rejects unsafe schemes and keeps raw bbcode visible`() {
        val ast = parser.parse("[img]javascript:alert(1)[/img]")
        assertTrue("javascript: image URL must not reach PostBlock.Image", ast.blocks.none { it is PostBlock.Image })
        val paragraph = ast.blocks.single() as PostBlock.Paragraph
        val text = paragraph.inlines.filterIsInstance<PostInline.Text>().joinToString(separator = "") { it.value }
        assertEquals("[img]javascript:alert(1)[/img]", text)
    }

    @Test
    fun `img accepts absolute HFR paths by normalising them`() {
        val ast = parser.parse("[img]/images/forum/icon.gif[/img]")
        val block = ast.blocks.single() as PostBlock.Image
        assertEquals("https://forum.hardware.fr/images/forum/icon.gif", block.url)
    }

    @Test
    fun `quote becomes a Quote block with null metadata`() {
        val ast = parser.parse("[quote]anonyme[/quote]")
        val block = ast.blocks.single() as PostBlock.Quote
        assertNull(block.numreponse)
        assertNull(block.author)
        assertNull(block.page)
        val inner = block.content.blocks.single() as PostBlock.Paragraph
        assertEquals(listOf(PostInline.Text("anonyme")), inner.inlines)
    }

    @Test
    fun `quotemsg keeps the numreponse and treats the second parameter as opaque`() {
        val ast = parser.parse("[quotemsg=2523833,1,1214571]hello[/quotemsg]")
        val block = ast.blocks.single() as PostBlock.Quote
        assertEquals(2_523_833, block.numreponse)
        // Page (the page-locator metadata) stays null — the second param is opaque
        // per docs/specs/protocol-hfr.md, and never decoded as a page number here.
        assertNull(block.page)
        assertNull(block.author)
    }

    @Test
    fun `unknown tag falls back to raw text`() {
        val ast = parser.parse("[blink]nope[/blink]")
        val paragraph = ast.blocks.single() as PostBlock.Paragraph
        // The raw open and close tokens are kept verbatim, surrounding the inner text.
        val joined = paragraph.inlines.filterIsInstance<PostInline.Text>().joinToString(separator = "") { it.value }
        assertTrue(joined.contains("[blink]"))
        assertTrue(joined.contains("[/blink]"))
        assertTrue(joined.contains("nope"))
    }

    @Test
    fun `unclosed bold keeps the open tag as text without crashing`() {
        val ast = parser.parse("[b]oups")
        val paragraph = ast.blocks.single() as PostBlock.Paragraph
        assertTrue(paragraph.inlines.any { it is PostInline.Text && it.value.contains("[b]") })
    }

    @Test
    fun `unclosed quote degrades to plain text rather than producing an empty Quote block`() {
        val ast = parser.parse("[quote]hello")
        assertTrue("No Quote block must be emitted for unclosed [quote]", ast.blocks.none { it is PostBlock.Quote })
        val text = (ast.blocks.single() as PostBlock.Paragraph).inlines
            .filterIsInstance<PostInline.Text>().joinToString(separator = "") { it.value }
        assertEquals("[quote]hello", text)
    }

    @Test
    fun `unclosed fixed degrades to plain text rather than producing an empty Fixed block`() {
        val ast = parser.parse("[fixed]hello")
        assertTrue("No Fixed block must be emitted for unclosed [fixed]", ast.blocks.none { it is PostBlock.Fixed })
        val text = (ast.blocks.single() as PostBlock.Paragraph).inlines
            .filterIsInstance<PostInline.Text>().joinToString(separator = "") { it.value }
        assertEquals("[fixed]hello", text)
    }

    @Test
    fun `unclosed code degrades to plain text rather than producing an empty CodeBlock`() {
        val ast = parser.parse("[code]int x;")
        assertTrue("No CodeBlock must be emitted for unclosed [code]", ast.blocks.none { it is PostBlock.CodeBlock })
        val text = (ast.blocks.single() as PostBlock.Paragraph).inlines
            .filterIsInstance<PostInline.Text>().joinToString(separator = "") { it.value }
        assertEquals("[code]int x;", text)
    }

    @Test
    fun `unclosed cpp degrades to plain text rather than producing an empty CodeBlock`() {
        val ast = parser.parse("[cpp]int x;")
        assertTrue("No CodeBlock must be emitted for unclosed [cpp]", ast.blocks.none { it is PostBlock.CodeBlock })
    }

    @Test
    fun `unclosed spoiler degrades to plain text rather than producing an empty Spoiler block`() {
        val ast = parser.parse("[spoiler]hello")
        assertTrue(
            "No Spoiler block must be emitted for unclosed [spoiler]",
            ast.blocks.none { it is PostBlock.Spoiler },
        )
    }

    @Test
    fun `unclosed img degrades to plain text rather than losing the URL`() {
        val ast = parser.parse("[img]https://example.com/a.png")
        assertTrue("No Image block must be emitted for unclosed [img]", ast.blocks.none { it is PostBlock.Image })
        val text = (ast.blocks.single() as PostBlock.Paragraph).inlines
            .filterIsInstance<PostInline.Text>().joinToString(separator = "") { it.value }
        assertEquals("[img]https://example.com/a.png", text)
    }

    @Test
    fun `unclosed quotemsg degrades to plain text rather than producing an empty Quote block`() {
        val ast = parser.parse("[quotemsg=42,1,2]hello")
        assertTrue("No Quote block must be emitted for unclosed [quotemsg]", ast.blocks.none { it is PostBlock.Quote })
    }

    @Test
    fun `deeply nested quote degrades gracefully without StackOverflowError`() {
        val depth = 256
        val nested = buildString {
            repeat(depth) { append("[quote]") }
            append("payload")
            repeat(depth) { append("[/quote]") }
        }
        // Must not throw. The parser caps recursion via MAX_NESTING_DEPTH and falls back
        // to raw text inside the deepest levels.
        val ast = parser.parse(nested)
        assertNotNull(ast)
        assertTrue("Parser must keep at least one outer Quote block", ast.blocks.any { it is PostBlock.Quote })
    }

    @Test
    fun `deeply nested bold degrades gracefully without StackOverflowError`() {
        val depth = 256
        val nested = buildString {
            repeat(depth) { append("[b]") }
            append("payload")
            repeat(depth) { append("[/b]") }
        }
        val ast = parser.parse(nested)
        assertNotNull(ast)
        // We don't assert on the structure, only that the parser returns without crashing.
    }

    @Test
    fun `s alias is no longer recognised and stays raw text`() {
        // [s] used to alias [strike]. We removed the alias to keep the parser observable
        // surface aligned on HFR's real toolbar (which only emits [strike]).
        val ast = parser.parse("[s]hello[/s]")
        assertTrue("No Strike inline must be emitted for [s]", ast.blocks.none {
            it is PostBlock.Paragraph && it.inlines.any { node -> node is PostInline.Strike }
        })
        val text = (ast.blocks.single() as PostBlock.Paragraph).inlines
            .filterIsInstance<PostInline.Text>().joinToString(separator = "") { it.value }
        assertTrue(text.contains("[s]"))
        assertTrue(text.contains("[/s]"))
        assertTrue(text.contains("hello"))
    }

    @Test
    fun `paragraph break splits double newlines`() {
        val ast = parser.parse("para1\n\npara2")
        assertEquals(2, ast.blocks.size)
        val p1 = ast.blocks[0] as PostBlock.Paragraph
        val p2 = ast.blocks[1] as PostBlock.Paragraph
        assertEquals(listOf(PostInline.Text("para1")), p1.inlines)
        assertEquals(listOf(PostInline.Text("para2")), p2.inlines)
    }

    @Test
    fun `single newline becomes a LineBreak inside the same paragraph`() {
        val ast = parser.parse("line1\nline2")
        val paragraph = ast.blocks.single() as PostBlock.Paragraph
        assertEquals(
            listOf(PostInline.Text("line1"), PostInline.LineBreak, PostInline.Text("line2")),
            paragraph.inlines,
        )
    }

    @Test
    fun `bullet star is preserved as raw text without crashing`() {
        // We don't model BBCode lists yet — keep [*] visible so users see what they typed.
        val ast = parser.parse("[*] item 1")
        val paragraph = ast.blocks.single() as PostBlock.Paragraph
        assertTrue(paragraph.inlines.any { it is PostInline.Text && it.value.contains("[*]") })
    }

    @Test
    fun `stray close tag does not crash`() {
        val ast = parser.parse("hello [/b] world")
        // No crash, no infinite loop; text survives.
        assertNotNull(ast)
        val joined = (ast.blocks.single() as PostBlock.Paragraph).inlines
            .filterIsInstance<PostInline.Text>().joinToString(separator = "") { it.value }
        assertTrue(joined.contains("hello"))
        assertTrue(joined.contains("[/b]"))
        assertTrue(joined.contains("world"))
    }

    @Test
    fun `lone open bracket without closing is treated as text`() {
        val ast = parser.parse("a [ b")
        val paragraph = ast.blocks.single() as PostBlock.Paragraph
        val text = (paragraph.inlines.single() as PostInline.Text).value
        assertEquals("a [ b", text)
    }

    @Test
    fun `real fixture edit form content is parsed without crash`() {
        val content = realContentFormSnippet()
        val ast = parser.parse(content)
        assertTrue("Edit form fixture must yield at least one block", ast.blocks.isNotEmpty())
        // The fixture includes [fixed], [spoiler], [img], [url=…] and [b]/[i]/[u]/[strike].
        assertTrue(ast.blocks.any { it is PostBlock.Fixed })
        assertTrue(ast.blocks.any { it is PostBlock.Spoiler })
        assertTrue(ast.blocks.any { it is PostBlock.Image })
        val paragraphs = ast.blocks.filterIsInstance<PostBlock.Paragraph>()
        assertTrue(
            "Edit form fixture should contain inline-bold somewhere",
            paragraphs.any { p -> p.inlines.any { it is PostInline.Strong } },
        )
    }

    @Test
    fun `real fixture quote form content keeps the quotemsg block`() {
        val content = realQuoteFormSnippet()
        val ast = parser.parse(content)
        val quote = ast.blocks.singleOrNull { it is PostBlock.Quote } as? PostBlock.Quote
        assertNotNull("Quote form fixture must produce a quotemsg block", quote)
        assertEquals(2_523_833, quote!!.numreponse)
    }

    // The two snippets below mirror what we extracted from the real Phase 2A fixtures —
    // kept inline so the parser test does not have to read raw HTML from disk.

    private fun realContentFormSnippet(): String = """
        Test technique Redface2 #81 : capture du formulaire d'édition avec BBCode varié.

        [b]Gras[/b], [i]italique[/i], [u]souligné[/u], [strike]barré[/strike].

        [url=https://forum.hardware.fr/]Lien HFR[/url]

        [fixed]Bloc fixed
        ligne 2[/fixed]

        [spoiler]Spoiler de test[/spoiler]

        [img]https://forum-images.hardware.fr/themes_static/images_forum/1/icon_smile.gif[/img]

        Ce topic temporaire sera supprimé après capture.

        [i]Post par GPT-5 Codex[/i]
    """.trimIndent()

    private fun realQuoteFormSnippet(): String = """
        [quotemsg=2523833,1,1214571]Test technique Redface2 #81 : capture du formulaire d'édition avec BBCode varié.

        [b]Gras[/b], [i]italique[/i], [u]souligné[/u], [strike]barré[/strike].

        [fixed]Bloc fixed[/fixed]

        [spoiler]Spoiler[/spoiler]

        [url]https://example.com[/url][/quotemsg]
    """.trimIndent()
}
