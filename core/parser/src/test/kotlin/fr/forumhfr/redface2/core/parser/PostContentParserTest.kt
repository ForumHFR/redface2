package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostContentParserTest {
    private val pageParser = TopicPageParser()

    @Test
    fun `khakha opening page produces non empty AST for every post`() {
        val topic = pageParser.parse(fixture("topic_khakha_page_1.html"))

        topic.posts.forEach { post ->
            assertNotNull("post #${post.numreponse} should have an AST", post.contentAst)
            assertTrue(
                "post #${post.numreponse} AST should have at least one block",
                post.contentAst.blocks.isNotEmpty(),
            )
        }
    }

    @Test
    fun `quotes in AST preserve author and nest content`() {
        val topic = pageParser.parse(fixture("topic_khakha_page_146.html"))

        val withQuote = topic.posts
            .firstOrNull { post -> post.contentAst.blocks.any { it is PostBlock.Quote } }

        assertNotNull("at least one post on page 146 should contain a quote block", withQuote)
        val quote = withQuote!!.contentAst.blocks
            .filterIsInstance<PostBlock.Quote>()
            .first()
        assertNotNull("quote should have an author", quote.author)
        assertNull("numreponse is not extractable from rendered HTML", quote.numreponse)
        assertNull("page is not extractable from rendered HTML", quote.page)
    }

    @Test
    fun `perso smiley alt syntax is recognised as Perso kind`() {
        val topic = pageParser.parse(fixture("topic_khakha_page_1.html"))

        val persoSmiley = topic.posts
            .flatMap { post -> post.contentAst.allInlines() }
            .filterIsInstance<PostInline.Smiley>()
            .firstOrNull { it.kind is SmileyKind.Perso }

        assertNotNull("page 1 fixture contains [:obam haha] perso smileys", persoSmiley)
        val name = (persoSmiley!!.kind as SmileyKind.Perso).name
        assertTrue("perso name should not be wrapped in [: ]", !name.startsWith("[:") && !name.endsWith("]"))
    }

    @Test
    fun `builtin smiley alt syntax is recognised as Builtin kind`() {
        val topic = pageParser.parse(fixture("topic_khakha_page_2.html"))

        val builtinSmiley = topic.posts
            .flatMap { post -> post.contentAst.allInlines() }
            .filterIsInstance<PostInline.Smiley>()
            .firstOrNull { it.kind is SmileyKind.Builtin }

        assertNotNull("page 2 fixture contains :spamafote: builtin smiley", builtinSmiley)
        val code = (builtinSmiley!!.kind as SmileyKind.Builtin).code
        assertFalse("builtin code should not contain colons", code.contains(':'))
    }

    @Test
    fun `links keep only http https and absolute internal hrefs`() {
        val parser = PostContentParser()
        val element = jsoupBody(
            """
            <div id="para123">
                <a href="https://example.com/safe">safe</a>
                <a href="javascript:alert(1)">unsafe</a>
                <a href="data:text/html,malicious">also unsafe</a>
                <a href="/forum2/index.php">internal</a>
            </div>
            """.trimIndent(),
        )

        val result = parser.parse(element)

        val hrefs = result.ast.allInlines()
            .filterIsInstance<PostInline.Link>()
            .map { it.url }
        assertTrue(
            "https link should be preserved",
            hrefs.contains("https://example.com/safe"),
        )
        assertTrue(
            "internal link should be normalised to forum.hardware.fr",
            hrefs.contains("https://forum.hardware.fr/forum2/index.php"),
        )
        val jsLink = hrefs.any { it.startsWith("javascript:", ignoreCase = true) }
        val dataLink = hrefs.any { it.startsWith("data:", ignoreCase = true) }
        assertFalse("javascript: links must not appear in AST", jsLink)
        assertFalse("data: links must not appear in AST", dataLink)
    }

    @Test
    fun `quotedAuthors mirrors quote authors from AST`() {
        val topic = pageParser.parse(fixture("topic_khakha_page_2.html"))

        topic.posts
            .filter { post -> post.contentAst.blocks.any { it is PostBlock.Quote } }
            .forEach { post ->
                val authorsFromAst = post.contentAst.blocks
                    .filterIsInstance<PostBlock.Quote>()
                    .mapNotNull { it.author }
                authorsFromAst.forEach { expected ->
                    assertTrue(
                        "post #${post.numreponse}: $expected should be in quotedAuthors",
                        post.quotedAuthors.contains(expected),
                    )
                }
            }
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) {
            "Fixture not found: $name"
        }.readText()

    private fun jsoupBody(html: String): org.jsoup.nodes.Element {
        val document = org.jsoup.Jsoup.parseBodyFragment(html)
        return document.body().selectFirst("div[id^=para]") ?: document.body()
    }
}

/**
 * Walks the AST depth-first and yields every [PostInline] node, going into quote/spoiler/inline children.
 */
private fun PostContent.allInlines(): List<PostInline> {
    val out = mutableListOf<PostInline>()
    walkBlocks(this.blocks, out)
    return out
}

private fun walkBlocks(blocks: List<PostBlock>, out: MutableList<PostInline>) {
    blocks.forEach { block ->
        when (block) {
            is PostBlock.Paragraph -> walkInlines(block.inlines, out)
            is PostBlock.Quote -> walkBlocks(block.content.blocks, out)
            is PostBlock.Spoiler -> walkBlocks(block.content.blocks, out)
            is PostBlock.CodeBlock -> Unit
            is PostBlock.Image -> Unit
        }
    }
}

private fun walkInlines(inlines: List<PostInline>, out: MutableList<PostInline>) {
    inlines.forEach { inline ->
        out += inline
        when (inline) {
            is PostInline.Strong -> walkInlines(inline.children, out)
            is PostInline.Emphasis -> walkInlines(inline.children, out)
            is PostInline.Underline -> walkInlines(inline.children, out)
            is PostInline.Strike -> walkInlines(inline.children, out)
            is PostInline.Color -> walkInlines(inline.children, out)
            is PostInline.Link -> walkInlines(inline.children, out)
            else -> Unit
        }
    }
}
