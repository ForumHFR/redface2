package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `quotes in AST preserve author numreponse and page from citation href`() {
        val topic = pageParser.parse(fixture("topic_khakha_page_146.html"))

        val withQuote = topic.posts
            .firstOrNull { post -> post.contentAst.blocks.any { it is PostBlock.Quote } }

        assertNotNull("at least one post on page 146 should contain a quote block", withQuote)
        val quote = withQuote!!.contentAst.blocks
            .filterIsInstance<PostBlock.Quote>()
            .first()
        assertNotNull("quote should have an author", quote.author)
        // The quote header anchor on page 146 fixtures targets sujet_<post>_<page>.htm#t<numreponse>
        // so both fields are extractable. Asserting they're populated, not specific values, because
        // different posts on the page cite different numreponses.
        assertNotNull("page should be extracted from citation href", quote.page)
        assertNotNull("numreponse should be extracted from citation href", quote.numreponse)
        assertEquals("citations on page 146 always reference page 146", 146, quote.page)
    }

    @Test
    fun `spoiler block is recognised and not flattened into paragraph`() {
        val topic = pageParser.parse(fixture("topic_khakha_page_146.html"))

        val spoilers = topic.posts.flatMap { post ->
            post.contentAst.allBlocks().filterIsInstance<PostBlock.Spoiler>()
        }
        assertTrue("page 146 fixture contains at least one spoiler block", spoilers.isNotEmpty())
        val spoiler = spoilers.first()
        assertEquals("Spoiler", spoiler.label)
        assertTrue("spoiler content should not be empty", spoiler.content.blocks.isNotEmpty())
    }

    @Test
    fun `mono-character builtin smileys are recognised with their BBCode token`() {
        val topic = pageParser.parse(fixture("topic_khakha_page_146.html"))

        val codes = topic.posts
            .flatMap { post -> post.contentAst.allInlines() }
            .filterIsInstance<PostInline.Smiley>()
            .mapNotNull { (it.kind as? SmileyKind.Builtin)?.code }
            .toSet()

        // page 146 fixture contains :), :D, :o — keep the leading colon so the BBCode token is the
        // canonical id (a naive strip would collapse :) and ;) to the same ")").
        assertTrue("expected :) builtin on page 146, found codes=$codes", codes.contains(":)"))
        assertTrue("expected :D builtin on page 146, found codes=$codes", codes.contains(":D"))
        assertTrue("expected :o builtin on page 146, found codes=$codes", codes.contains(":o"))
    }

    @Test
    fun `semicolon-prefixed wink is recognised as a builtin distinct from colon-prefixed`() {
        val topic = pageParser.parse(fixture("topic_khakha_page_1.html"))

        val codes = topic.posts
            .flatMap { post -> post.contentAst.allInlines() }
            .filterIsInstance<PostInline.Smiley>()
            .mapNotNull { (it.kind as? SmileyKind.Builtin)?.code }
            .toSet()

        assertTrue("expected ;) builtin on page 1, found codes=$codes", codes.contains(";)"))
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
        // Specifically check the strip preserves the inner space — naive regex bugs would
        // produce "obam" or "obam haha]" and silently pass the boundary assertion above.
        assertEquals("obam haha", name)
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
        // The BBCode token (with surrounding colons or leading `;`) is the canonical identity
        // — stripping the marker would conflate `:)` and `;)` to the same `)`.
        assertTrue(
            "builtin code should keep its BBCode marker, got=$code",
            code.startsWith(':') || code.startsWith(';'),
        )
    }

    @Test
    fun `inline br nested inside a styled span is kept as LineBreak`() {
        val parser = PostContentParser()
        val element = jsoupBody(
            """
            <div id="para123">
                <strong>premier<br>second</strong>
            </div>
            """.trimIndent(),
        )

        val result = parser.parse(element)

        val strong = result.ast.allInlines().filterIsInstance<PostInline.Strong>().first()
        val kinds = strong.children.map { it::class.simpleName }
        // Without LineBreak handling, the <br> would be dropped and the two text fragments
        // would collapse into a single Text — losing the author-intended visual break.
        assertTrue(
            "Strong children should expose a LineBreak between the two Text fragments, got=$kinds",
            strong.children.any { it is PostInline.LineBreak },
        )
    }

    @Test
    fun `span class u is recognised as Underline`() {
        val topic = pageParser.parse(fixture("topic_khakha_page_1.html"))

        val underlines = topic.posts
            .flatMap { post -> post.contentAst.allInlines() }
            .filterIsInstance<PostInline.Underline>()

        assertTrue(
            "page 1 fixture uses <span class=\"u\"> for underline — at least one expected",
            underlines.isNotEmpty(),
        )
    }

    @Test
    fun `inline image with non-http scheme is rejected`() {
        val parser = PostContentParser()
        val element = jsoupBody(
            """
            <div id="para123">
                <img src="data:image/png;base64,AAA" alt="">
                <img src="javascript:alert(1)" alt="">
                <img src="/forum2/icone.gif" alt="ok">
            </div>
            """.trimIndent(),
        )

        val result = parser.parse(element)

        val imageUrls = result.ast.allInlines()
            .filterIsInstance<PostInline.InlineImage>()
            .map { it.url }
        assertFalse(
            "data: image src must be dropped before reaching the renderer",
            imageUrls.any { it.startsWith("data:", ignoreCase = true) },
        )
        assertFalse(
            "javascript: image src must be dropped before reaching the renderer",
            imageUrls.any { it.startsWith("javascript:", ignoreCase = true) },
        )
        assertTrue(
            "absolute HFR path should be normalised to forum.hardware.fr",
            imageUrls.contains("https://forum.hardware.fr/forum2/icone.gif"),
        )
    }

    @Test
    fun `null content yields a deleted placeholder paragraph`() {
        val parser = PostContentParser()

        val result = parser.parse(null)

        assertTrue(
            "AST should not be empty so the renderer never falls back to a blank post",
            result.ast.blocks.isNotEmpty(),
        )
        val text = result.ast.blocks
            .filterIsInstance<PostBlock.Paragraph>()
            .flatMap { it.inlines }
            .filterIsInstance<PostInline.Text>()
            .joinToString("") { it.value }
        assertEquals("[Message supprimé]", text)
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
    fun `quotedAuthors equals the distinct list of quote authors from the AST`() {
        val topic = pageParser.parse(fixture("topic_khakha_page_2.html"))

        topic.posts
            .filter { post -> post.contentAst.blocks.any { it is PostBlock.Quote } }
            .forEach { post ->
                val authorsFromAst = post.contentAst.blocks
                    .filterIsInstance<PostBlock.Quote>()
                    .mapNotNull { it.author }
                    .distinct()
                assertEquals(
                    "post #${post.numreponse}: quotedAuthors must mirror AST quote authors exactly",
                    authorsFromAst,
                    post.quotedAuthors,
                )
            }
    }

    @Test
    fun `anonymous quote table is recognised as a Quote without author`() {
        val topic = pageParser.parse(fixture("topic_page_multipage.html"))

        val anonymousQuotes = topic.posts
            .flatMap { post -> post.contentAst.allBlocks() }
            .filterIsInstance<PostBlock.Quote>()
            .filter { it.author == null }

        assertTrue(
            "topic_page_multipage fixture contains <table class=\"quote\"> witness posts",
            anonymousQuotes.isNotEmpty(),
        )
        val anonymous = anonymousQuotes.first()
        // Anonymous [quote] has no a.Topic — page/numreponse stay null too.
        assertEquals("anonymous quote should have no page", null, anonymous.page)
        assertEquals("anonymous quote should have no numreponse", null, anonymous.numreponse)
        assertTrue(
            "anonymous quote content should not be empty",
            anonymous.content.blocks.isNotEmpty(),
        )
    }

    @Test
    fun `case-mismatched alt and title yield the title-cased builtin token`() {
        // Witness post on topic_page_multipage.html ships <img alt=":d" title=":D"> for the
        // legacy lowercase variant of the biggrin emoticon. The AST must not produce two
        // distinct builtins for what HFR stores as the same drawable.
        val topic = pageParser.parse(fixture("topic_page_multipage.html"))

        val codes = topic.posts
            .flatMap { post -> post.contentAst.allInlines() }
            .filterIsInstance<PostInline.Smiley>()
            .mapNotNull { (it.kind as? SmileyKind.Builtin)?.code }
            .toSet()

        assertTrue(
            "title-cased :D should be the canonical token, found codes=$codes",
            codes.contains(":D"),
        )
        assertFalse(
            "lowercase :d should not surface as a separate builtin token, codes=$codes",
            codes.contains(":d"),
        )
    }

    @Test
    fun `perso smiley variant suffix is preserved in the kind name`() {
        // Fixture topic_posts_page contains [:g0od:2] — HFR uses the trailing :N to pick
        // a variant of the same custom smiley. The colon must stay inside the name.
        val topic = pageParser.parse(fixture("topic_posts_page.html"))

        val variantPerso = topic.posts
            .flatMap { post -> post.contentAst.allInlines() }
            .filterIsInstance<PostInline.Smiley>()
            .mapNotNull { it.kind as? SmileyKind.Perso }
            .firstOrNull { ':' in it.name }

        assertNotNull("expected a perso smiley with a `:N` variant suffix", variantPerso)
        assertTrue(
            "variant suffix should be kept inside the perso name, got=${variantPerso!!.name}",
            variantPerso.name.matches(Regex(""".+:\d+""")),
        )
    }

    @Test
    fun `non-http schemes other than data and javascript are also rejected`() {
        val parser = PostContentParser()
        val element = jsoupBody(
            """
            <div id="para123">
                <a href="mailto:foo@example.com">mail</a>
                <a href="vbscript:msgbox(1)">vb</a>
                <a href="file:///etc/passwd">file</a>
                <img src="mailto:foo@example.com" alt="">
                <img src="vbscript:msgbox(1)" alt="">
                <img src="file:///etc/passwd" alt="">
            </div>
            """.trimIndent(),
        )

        val result = parser.parse(element)

        val linkUrls = result.ast.allInlines().filterIsInstance<PostInline.Link>().map { it.url }
        val imageUrls = result.ast.allInlines().filterIsInstance<PostInline.InlineImage>().map { it.url }
        listOf("mailto:", "vbscript:", "file:").forEach { scheme ->
            assertFalse(
                "$scheme links must not survive the sanitizer, links=$linkUrls",
                linkUrls.any { it.startsWith(scheme, ignoreCase = true) },
            )
            assertFalse(
                "$scheme image src must not survive the sanitizer, images=$imageUrls",
                imageUrls.any { it.startsWith(scheme, ignoreCase = true) },
            )
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

/**
 * Walks the AST depth-first and yields every [PostBlock] node, going into quote/spoiler containers.
 */
private fun PostContent.allBlocks(): List<PostBlock> {
    val out = mutableListOf<PostBlock>()
    collectBlocks(this.blocks, out)
    return out
}

private fun collectBlocks(blocks: List<PostBlock>, out: MutableList<PostBlock>) {
    blocks.forEach { block ->
        out += block
        when (block) {
            is PostBlock.Quote -> collectBlocks(block.content.blocks, out)
            is PostBlock.Spoiler -> collectBlocks(block.content.blocks, out)
            else -> Unit
        }
    }
}

private fun walkBlocks(blocks: List<PostBlock>, out: MutableList<PostInline>) {
    blocks.forEach { block ->
        when (block) {
            is PostBlock.Paragraph -> walkInlines(block.inlines, out)
            is PostBlock.Quote -> walkBlocks(block.content.blocks, out)
            is PostBlock.Spoiler -> walkBlocks(block.content.blocks, out)
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
