package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import org.jsoup.Jsoup
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
            assertNotNull("post #${post.numreponse} should have an AST", post.content)
            assertTrue(
                "post #${post.numreponse} AST should have at least one block",
                post.content.blocks.isNotEmpty(),
            )
        }
    }

    @Test
    fun `quotes in AST preserve author numreponse and page from citation href`() {
        val topic = pageParser.parse(fixture("topic_khakha_page_146.html"))

        val withQuote = topic.posts
            .firstOrNull { post -> post.content.blocks.any { it is PostBlock.Quote } }

        assertNotNull("at least one post on page 146 should contain a quote block", withQuote)
        val quote = withQuote!!.content.blocks
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
    fun `redface2 page 24 citation tables surface as Quote blocks`() {
        val topic = pageParser.parse(fixture("topic_redface2_p24.html"))

        val posts = topic.posts.associateBy { it.numreponse }
        listOf(
            2785212 to "Lt Ripley",
            2785217 to "Lt Ripley",
            2785218 to "garath_",
        ).forEach { (numreponse, expectedAuthor) ->
            val post = requireNotNull(posts[numreponse]) {
                "fixture should contain screenshot witness post #$numreponse"
            }
            val quotes = post.content.blocks.filterIsInstance<PostBlock.Quote>()

            assertTrue(
                "post #$numreponse should parse HFR <table class=\"citation\"> as Quote, " +
                    "not flatten the header `${expectedAuthor} a écrit :` into a paragraph",
                quotes.isNotEmpty(),
            )
            assertTrue(
                "post #$numreponse should expose quoted author $expectedAuthor, got=${quotes.map { it.author }}",
                quotes.any { it.author == expectedAuthor },
            )
            val renderedTextFragments = post.content.allInlines()
                .filterIsInstance<PostInline.Text>()
                .joinToString(" ") { it.value }
            assertFalse(
                "post #$numreponse should not keep the HFR citation header in rendered text; " +
                    "`$expectedAuthor a écrit :` belongs to the quote metadata, got=$renderedTextFragments",
                renderedTextFragments.contains("$expectedAuthor a écrit"),
            )
        }
    }

    @Test
    fun `logged-in oldcitation table surfaces as a Quote block`() {
        // Real content fragment captured from topic RF2 page 25 while LOGGED IN (post
        // #2785312, antiseptiqueIncolore citing XaTriX). HFR serves the citation as
        // <table class="oldcitation"> for accounts using the classic citation style,
        // whereas anonymous reads get <table class="citation">. Before the selector fix the
        // parser only knew "citation" → the whole quote was swallowed and the post rendered
        // as if it had no citation block (bug confirmed on S25, v0.3.21, logged in only).
        val loggedInQuoteHtml = """
            <div id="para2785312"><p></p><div class="container"><table class="oldcitation">
            <tr class="none"><td><b class="s1"><a href="/forum2.php?config=hfr.inc&amp;cat=23&amp;subcat=550&amp;post=35395&amp;page=25&amp;p=1&amp;numreponse=0&amp;new=0&amp;nojs=0#t2785311" class="Topic">XaTriX a écrit :</a></b>
            <hr size="1" /><p>Bon en fait RF1 et HFR+ ont aussi la nouvelle catégorie sans rien faire
            <img src="https://forum-images.hardware.fr/icones/smilies/lol.gif" alt=":lol:" title=":lol:" /><br /></p>
            <hr size="1" /></td></tr></table></div><p><br />Hfr4droid aussi finalement
            <div style="clear: both;"> </div></p></div>
        """.trimIndent()
        val contentElement = Jsoup.parse(loggedInQuoteHtml).selectFirst("div[id^=para]")

        val ast = PostContentParser().parse(contentElement).ast

        val quotes = ast.allBlocks().filterIsInstance<PostBlock.Quote>()
        assertTrue(
            "logged-in <table class=\"oldcitation\"> must be parsed as a Quote, not swallowed",
            quotes.isNotEmpty(),
        )
        assertEquals("XaTriX", quotes.first().author)
        // Known limitation pinned here: the logged-in oldcitation header href is a dynamic
        // `forum2.php?...page=N...#tM` link, NOT the static `sujet_<post>_<page>.htm#tN`
        // permalink that CITATION_HREF_REGEX matches. So page/numreponse stay null in
        // logged-in mode → scroll-to-cited-post is inactive when authenticated. Tracked by a
        // TODO next to CITATION_HREF_REGEX in PostContentParser; deliberate, Phase 2 work.
        assertEquals("page stays null for the logged-in forum2.php citation href", null, quotes.first().page)
        assertEquals(
            "numreponse stays null for the logged-in forum2.php citation href",
            null,
            quotes.first().numreponse,
        )
        // The reply text outside the citation survives, and the citation header is not
        // flattened into the rendered paragraph text.
        val renderedText = ast.allInlines()
            .filterIsInstance<PostInline.Text>()
            .joinToString(" ") { it.value }
        assertTrue(
            "reply body after the quote must remain, got=$renderedText",
            renderedText.contains("Hfr4droid aussi finalement"),
        )
        assertFalse(
            "citation header `XaTriX a écrit :` must not leak into rendered text, got=$renderedText",
            renderedText.contains("XaTriX a écrit"),
        )
    }

    @Test
    fun `logged-in oldquote table (bare quote) surfaces as a Quote block`() {
        // Real fragment captured from the cyclisme topic page 8270 while LOGGED IN (post
        // #74749781, Konovalov). A bare [quote] (no author) is served as <table class="quote">
        // anonymously but as <table class="oldquote"> for accounts using the classic citation
        // style — the "old"-prefixed sibling of oldcitation. Before adding "oldquote" to the
        // selectors the parser only knew "quote" → the whole bare quote was swallowed and rendered
        // as plain text (bug reported logged-in only, vélo topic).
        val loggedInBareQuoteHtml = """
            <div id="para74749781"><p></p><div class="container"><table class="oldquote">
            <tr class="none"><td><b class="s1">Citation :</b><hr size="1" /><p>Saranno considerate Granfondo tutte le manifestazioni superiori a 120 Km. Totali.<br /></p>
            <hr size="1" /></td></tr></table></div><p><br />Source : federciclismo.it</p></div>
        """.trimIndent()
        val contentElement = Jsoup.parse(loggedInBareQuoteHtml).selectFirst("div[id^=para]")

        val ast = PostContentParser().parse(contentElement).ast

        val quotes = ast.allBlocks().filterIsInstance<PostBlock.Quote>()
        assertTrue(
            "logged-in <table class=\"oldquote\"> must be parsed as a Quote, not swallowed",
            quotes.isNotEmpty(),
        )
        // A bare [quote] has no a.Topic author anchor → author/page/numreponse stay null.
        assertEquals("bare quote has no author", null, quotes.first().author)
        // The quoted body is preserved inside the Quote block.
        val quotedText = quotes.first().content.allInlines()
            .filterIsInstance<PostInline.Text>()
            .joinToString(" ") { it.value }
        assertTrue(
            "quoted body must be kept inside the Quote, got=$quotedText",
            quotedText.contains("Saranno considerate Granfondo"),
        )
        // The reply text after the quote survives outside, and neither the quoted body nor the
        // "Citation :" label leaks into the post's own rendered paragraph text.
        val renderedText = ast.allInlines()
            .filterIsInstance<PostInline.Text>()
            .joinToString(" ") { it.value }
        assertTrue(
            "reply body after the quote must remain, got=$renderedText",
            renderedText.contains("Source : federciclismo"),
        )
        assertFalse(
            "bare-quote `Citation :` label must not leak into rendered text, got=$renderedText",
            renderedText.contains("Citation :"),
        )
    }

    @Test
    fun `spoiler block is recognised and not flattened into paragraph`() {
        val topic = pageParser.parse(fixture("topic_khakha_page_146.html"))

        val spoilers = topic.posts.flatMap { post ->
            post.content.allBlocks().filterIsInstance<PostBlock.Spoiler>()
        }
        assertTrue("page 146 fixture contains at least one spoiler block", spoilers.isNotEmpty())
        val spoiler = spoilers.first()
        assertEquals("Spoiler", spoiler.label)
        assertTrue("spoiler content should not be empty", spoiler.content.blocks.isNotEmpty())
    }

    @Test
    fun `signature span surfaces as a separate signature AST and is stripped from the body`() {
        // #330 — HFR appends the author signature as a <span class="signature"> trailer inside
        // the post content div, after the body and any div.edited marker. It is parsed into its
        // own AST (rendered subdued, web parity) and removed from the body content.
        val html = """
            <div id="para1980664234"><p>Le corps du message.<div style="clear: both;"> </div></p>
            <div class="edited"><br />Message édité par alice le 16-03-2016&nbsp;à&nbsp;14:35:19</div>
            <br /><span class="signature"> ---------------
            <br />Ma signature avec un <b>mot</b> en gras.<br /><div style="clear: both;"> </div></span></div>
        """.trimIndent()
        val contentElement = Jsoup.parse(html).selectFirst("div[id^=para]")

        val parsed = PostContentParser().parse(contentElement)

        val signature = requireNotNull(parsed.signature) { "the signature span must surface" }
        val signatureText = signature.allInlines()
            .filterIsInstance<PostInline.Text>()
            .joinToString(" ") { it.value }
        assertTrue(
            "signature content must be parsed, got=$signatureText",
            signatureText.contains("Ma signature avec un"),
        )
        // The HFR « --------------- » separator that opens every signature span is server chrome,
        // not content — it must be stripped, never rendered as the first signature line (XaaT).
        assertFalse(
            "the HFR signature separator must not leak into the signature, got=$signatureText",
            signatureText.contains("---"),
        )
        // Inline formatting inside the signature survives (shared parseBlocks pipeline).
        assertTrue(
            "signature inline formatting must survive",
            signature.allInlines().any { it is PostInline.Strong },
        )
        // The signature text must NOT leak into the body AST.
        val bodyText = parsed.ast.allInlines()
            .filterIsInstance<PostInline.Text>()
            .joinToString(" ") { it.value }
        assertTrue("body must keep its own text, got=$bodyText", bodyText.contains("Le corps du message"))
        assertFalse(
            "signature must not leak into the body, got=$bodyText",
            bodyText.contains("Ma signature avec un"),
        )
    }

    @Test
    fun `post without a signature span yields a null signature`() {
        val html = """
            <div id="para1980664235"><p>Un message sans signature.<div style="clear: both;"> </div></p></div>
        """.trimIndent()
        val contentElement = Jsoup.parse(html).selectFirst("div[id^=para]")

        val parsed = PostContentParser().parse(contentElement)

        assertEquals("a post without a signature span must report null", null, parsed.signature)
    }

    @Test
    fun `signature span with only the separator and decoration edge-trims to null`() {
        // The « --------------- » separator line + the trailing `clear: both` spacer are HFR
        // boilerplate; a signature carrying nothing else must report null (no empty subdued block
        // under the post). Real-shape fixture: the separator dashes text node, a <br>, the spacer.
        val html = """
            <div id="para1980664236"><p>Corps.<div style="clear: both;"> </div></p>
            <br /><span class="signature"> ---------------
            <br /><div style="clear: both;"> </div></span></div>
        """.trimIndent()
        val contentElement = Jsoup.parse(html).selectFirst("div[id^=para]")

        val parsed = PostContentParser().parse(contentElement)

        assertEquals("a separator-only signature must report null", null, parsed.signature)
    }

    @Test
    fun `a quote whose quoted content embeds a spoiler stays a Quote`() {
        // #393 — post t2787065 (topic RF2-DEV page 6, the live repro) : XaTriX cites a post
        // shaped "Test / [spoiler]caca rose[/spoiler] / Suite". The container div used to be
        // classified SPOILER (descendant-matching selectFirst with spoiler tested first), so
        // the whole quote was swallowed and only its spoiler part rendered.
        val topic = pageParser.parse(fixture("topic_redface_dev_quote_spoiler_p6.html"))
        val post = requireNotNull(topic.posts.firstOrNull { it.numreponse == 2787065 }) {
            "fixture should contain the repro post t2787065"
        }

        val quotes = post.content.blocks.filterIsInstance<PostBlock.Quote>()
        assertTrue("the citation must surface as a Quote, not be swallowed by its inner spoiler", quotes.isNotEmpty())
        val quote = quotes.first()
        assertEquals("XaTriX", quote.author)

        val quotedText = quote.content.allInlines()
            .filterIsInstance<PostInline.Text>()
            .joinToString(" ") { it.value }
        assertTrue("text BEFORE the spoiler must survive, got=$quotedText", quotedText.contains("Test"))
        assertTrue("text AFTER the spoiler must survive, got=$quotedText", quotedText.contains("Suite"))
        assertTrue(
            "the embedded spoiler must stay a Spoiler block inside the quote",
            quote.content.allBlocks().filterIsInstance<PostBlock.Spoiler>().isNotEmpty(),
        )

        val replyText = post.content.allInlines()
            .filterIsInstance<PostInline.Text>()
            .joinToString(" ") { it.value }
        assertTrue("the reply below the quote must remain, got=$replyText", replyText.contains("Caca rose rose"))
    }

    @Test
    fun `a spoiler whose hidden content embeds a quote stays a Spoiler`() {
        // #393 counterpart — the OUTERMOST block table decides the kind in document order.
        // Synthetic fragment assembled from the two real shapes above (citation header from the
        // oldcitation test, spoiler wrapper from the khakha fixture) ; pins the inverse nesting
        // so the #393 fix cannot regress CitationIndex's "quote inside a spoiler" support.
        val spoilerWrappingQuoteHtml = """
            <div id="para999"><p></p><div class="container"><table class="spoiler">
            <tr class="none"><td><b class="s1Topic">Spoiler :</b>
            <div class="Topic masque"><div class="container"><table class="citation">
            <tr class="none"><td><b class="s1"><a href="/hfr/gsmgpspda/redface-dev-sujet_35421_6.htm#t2787063" class="Topic">XaTriX a écrit :</a></b>
            <hr size="1" /><p>caché</p><hr size="1" /></td></tr></table></div></div>
            </td></tr></table></div><p><br />après le spoiler</p></div>
        """.trimIndent()
        val contentElement = Jsoup.parse(spoilerWrappingQuoteHtml).selectFirst("div[id^=para]")

        val ast = PostContentParser().parse(contentElement).ast

        val topLevelSpoilers = ast.blocks.filterIsInstance<PostBlock.Spoiler>()
        assertTrue("the outer spoiler must stay a Spoiler", topLevelSpoilers.isNotEmpty())
        assertTrue(
            "the quote hidden inside the spoiler must surface as a nested Quote",
            topLevelSpoilers.first().content.allBlocks().filterIsInstance<PostBlock.Quote>().isNotEmpty(),
        )
    }

    @Test
    fun `mono-character builtin smileys are recognised with their BBCode token`() {
        val topic = pageParser.parse(fixture("topic_khakha_page_146.html"))

        val codes = topic.posts
            .flatMap { post -> post.content.allInlines() }
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
            .flatMap { post -> post.content.allInlines() }
            .filterIsInstance<PostInline.Smiley>()
            .mapNotNull { (it.kind as? SmileyKind.Builtin)?.code }
            .toSet()

        assertTrue("expected ;) builtin on page 1, found codes=$codes", codes.contains(";)"))
    }

    @Test
    fun `perso smiley alt syntax is recognised as Perso kind`() {
        val topic = pageParser.parse(fixture("topic_khakha_page_1.html"))

        val persoSmiley = topic.posts
            .flatMap { post -> post.content.allInlines() }
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
            .flatMap { post -> post.content.allInlines() }
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
    fun `deliberate empty line survives as two LineBreaks inside one paragraph`() {
        // #333/#280 — an authored empty line is emitted by HFR as `<br /><br />` between the two
        // text lines. The parser used to FLUSH the paragraph on every top-level <br>, so each
        // line became its own Paragraph block: the empty line collapsed into a dropped empty
        // paragraph (#333) and the renderer's inter-block gap replaced the natural line height
        // between every line (#280).
        val parser = PostContentParser()
        val element = jsoupBody(
            """
            <div id="para123"><p>ligne1<br /><br />ligne2</p></div>
            """.trimIndent(),
        )

        val result = parser.parse(element)

        val paragraphs = result.ast.blocks.filterIsInstance<PostBlock.Paragraph>()
        assertEquals(
            "both lines and the empty line belong to ONE paragraph, got=${result.ast.blocks}",
            1,
            paragraphs.size,
        )
        assertEquals(
            "the empty line must survive as two consecutive LineBreaks between the Text lines",
            listOf(
                PostInline.Text("ligne1"),
                PostInline.LineBreak,
                PostInline.LineBreak,
                PostInline.Text("ligne2"),
            ),
            paragraphs.single().inlines,
        )
    }

    @Test
    fun `single br keeps consecutive authored lines in the same paragraph`() {
        // #280 — real fixture witness: the answer post on the single-page topic separates its
        // sentences with single <br />s (`…comprendre le problème.<br />Je te propose…`). They
        // must stay in ONE paragraph with LineBreaks, not become one block per line.
        val topic = pageParser.parse(fixture("topic_page_single.html"))

        val paragraph = topic.posts
            .flatMap { it.content.allBlocks() }
            .filterIsInstance<PostBlock.Paragraph>()
            .firstOrNull { block ->
                block.inlines.filterIsInstance<PostInline.Text>()
                    .any { it.value.contains("comprendre le problème") }
            }

        assertNotNull("fixture should contain the multi-line answer paragraph", paragraph)
        assertTrue(
            "the next authored line stays in the SAME paragraph, got=${paragraph!!.inlines}",
            paragraph.inlines.filterIsInstance<PostInline.Text>()
                .any { it.value.contains("Je te propose") },
        )
        assertTrue(
            "the authored line boundary survives as an inline LineBreak",
            paragraph.inlines.any { it is PostInline.LineBreak },
        )
    }

    @Test
    fun `orphan nbsp runs between paragraphs survive as empty lines (real fixture)`() {
        // #466 — suite of #333/#280. HFR encodes a deliberate blank line BETWEEN two paragraphs
        // not as `<br /><br />` (the shape #423 already handled) but as an EXTRA `&nbsp;` inside
        // the orphan text node separating two sibling <p>. Real witness on the single-page topic
        // (post #9762063, captured fixture): `…C'est normal.</p>&nbsp;&nbsp;&nbsp;<p><br />Pour
        // trouver une solution…</p>` — 3 `&nbsp;` between the two paragraphs. The parser used to
        // swallow that whitespace and emit two separate Paragraph blocks, losing the blank lines.
        val topic = pageParser.parse(fixture("topic_page_single.html"))

        // Anchor on the UNIQUE authored second line "Pour trouver une solution". There are two
        // "C'est normal." occurrences in the fixture and only the second precedes the triple-nbsp
        // run, so anchoring on "C'est normal." picks the wrong (single-separator) paragraph. After
        // the fix, the second <p> folds INTO the preceding paragraph, so the paragraph holding
        // "Pour trouver une solution" also holds "C'est normal.", with the blank lines as LineBreaks.
        val paragraph = topic.posts
            .flatMap { it.content.allBlocks() }
            .filterIsInstance<PostBlock.Paragraph>()
            .firstOrNull { block ->
                block.inlines.filterIsInstance<PostInline.Text>()
                    .any { it.value.contains("Pour trouver une solution") }
            }

        assertNotNull("fixture should contain the folded paragraph", paragraph)
        assertTrue(
            "the line before the orphan-nbsp run must merge into the same paragraph, " +
                "got=${paragraph!!.inlines}",
            paragraph.inlines.filterIsInstance<PostInline.Text>()
                .any { it.value.contains("C'est normal.") },
        )
        // The triple `&nbsp;` run folds into blank lines (>= 2 LineBreaks) between the two authored
        // lines; the exact count for a bare run is pinned by the synthetic test below. A lone
        // `&nbsp;` separator never folds, so >= 2 here proves the multi-nbsp blank lines survived.
        val breaksBetween = run {
            val inlines = paragraph.inlines
            val from = inlines.indexOfLast {
                it is PostInline.Text && it.value.contains("C'est normal.")
            }
            val to = inlines.indexOfFirst {
                it is PostInline.Text && it.value.contains("Pour trouver une solution")
            }
            inlines.subList(from + 1, to).count { it is PostInline.LineBreak }
        }
        // EXACTLY 3: the triple `&nbsp;` run yields 3 separator breaks (1 boundary + 2 empty lines).
        // The second <p> opens with a border `<br />` (`<p><br />Pour…`) which must be edge-trimmed
        // (#466 Codex review) — were it kept it would push this to 4 and render a spurious 3rd empty
        // line. Pinning the exact count guards that border-break trim on the real fixture.
        assertEquals(
            "the triple orphan &nbsp; run must yield EXACTLY 3 LineBreaks (border <br> trimmed), " +
                "got=$breaksBetween",
            3,
            breaksBetween,
        )
    }

    @Test
    fun `orphan nbsp between two inline nodes stays word spacing not a separator - 466 codex`() {
        // #466 (Codex review) — a `&nbsp;` whitespace text node is HFR's inter-<p> separator ONLY
        // when a <p> follows it. Between two inline siblings it is genuine word spacing; the parser
        // used to drop it, concatenating `<strong>A</strong>&nbsp;<strong>B</strong>` with no space.
        // DERIVED FROM THE issue/Codex example, not a raw hfr-mcp capture.
        val parser = PostContentParser()
        val element = jsoupBody(
            "<div id=\"para1\"><strong>A</strong>&nbsp;<strong>B</strong></div>",
        )

        val result = parser.parse(element)

        val inlines = result.ast.blocks.filterIsInstance<PostBlock.Paragraph>().single().inlines
        assertTrue(
            "the &nbsp; between two inline nodes must survive as a spacing Text, got=$inlines",
            inlines.any { it is PostInline.Text && it.value == " " },
        )
    }

    @Test
    fun `merging paragraphs trims border breaks but keeps separator breaks - 466 codex`() {
        // #466 (Codex review) — when two <p> merge over a >=2 `&nbsp;` run, a trailing `<br>` on the
        // first <p> and a leading `<br>` on the second are BORDER breaks (the legacy sub-parse
        // edge-trimmed them via flushParagraph). Only the separator breaks (here 2) plus any INTERIOR
        // break survive. DERIVED FROM the issue #466 encoding, not a raw hfr-mcp capture.
        val parser = PostContentParser()
        val element = jsoupBody(
            "<div id=\"para1\"><p>A<br /></p>&nbsp;&nbsp;<p><br />B</p></div>",
        )

        val result = parser.parse(element)

        val paragraphs = result.ast.blocks.filterIsInstance<PostBlock.Paragraph>()
        assertEquals(
            "the two paragraphs must merge into ONE block, got=${result.ast.blocks}",
            1,
            paragraphs.size,
        )
        assertEquals(
            "2 orphan &nbsp; ⇒ 2 separator LineBreaks; the border <br>s of both <p> are trimmed",
            listOf(
                PostInline.Text("A"),
                PostInline.LineBreak,
                PostInline.LineBreak,
                PostInline.Text("B"),
            ),
            paragraphs.single().inlines,
        )
    }

    @Test
    fun `lone nbsp between a paragraph and an inline-classified list keeps them separate - 466 codex`() {
        // #466 (Codex review) — HFR emits `</p>&nbsp;<ul>…` where <ul> is classified INLINE. The
        // buffered inline-only <p> must be CLOSED at its boundary, NOT have the list content run into
        // it. DERIVED FROM the Codex example, not a raw hfr-mcp capture.
        val parser = PostContentParser()
        val element = jsoupBody(
            "<div id=\"para1\"><p>intro</p>&nbsp;<ul><li>item</li></ul></div>",
        )

        val result = parser.parse(element)

        val paragraphs = result.ast.blocks.filterIsInstance<PostBlock.Paragraph>()
        assertEquals(
            "the <p> and the following inline-classified <ul> must stay two blocks, got=${result.ast.blocks}",
            2,
            paragraphs.size,
        )
        assertTrue(
            "first block keeps the paragraph text",
            paragraphs[0].inlines.filterIsInstance<PostInline.Text>().any { it.value.contains("intro") },
        )
        assertTrue(
            "second block holds the list text, not merged into the paragraph",
            paragraphs[1].inlines.filterIsInstance<PostInline.Text>().any { it.value.contains("item") },
        )
    }

    @Test
    fun `multi nbsp after inline content before a paragraph does not fold - 466 codex`() {
        // #466 (Codex review) — a >=2 `&nbsp;` run folds into empty lines ONLY between two <p>. After
        // arbitrary buffered inline content (`<strong>A</strong>&nbsp;&nbsp;<p>B</p>`) it must NOT
        // merge: the two stay distinct blocks. DERIVED FROM the Codex example.
        val parser = PostContentParser()
        val element = jsoupBody(
            "<div id=\"para1\"><strong>A</strong>&nbsp;&nbsp;<p>B</p></div>",
        )

        val result = parser.parse(element)

        val paragraphs = result.ast.blocks.filterIsInstance<PostBlock.Paragraph>()
        assertEquals(
            "inline content + multi-nbsp + <p> must stay two blocks, got=${result.ast.blocks}",
            2,
            paragraphs.size,
        )
        assertEquals(
            "the second <p> stays its own block, not merged with the inline content",
            listOf(PostInline.Text("B")),
            paragraphs[1].inlines,
        )
    }

    @Test
    fun `top-level break between two paragraphs closes the merge window - 466 codex`() {
        // #466 (Codex review, round 3) — a top-level <br> between a buffered closed <p> and a later
        // <p> EXTENDS the running paragraph, so the buffer is no longer a pristine <p> body and the
        // >=2 `&nbsp;` run must NOT fold the two <p> into one block (`<p>A</p><br>&nbsp;&nbsp;<p>B</p>`
        // stays two blocks, matching legacy). DERIVED FROM the Codex example, not a raw hfr-mcp capture.
        val parser = PostContentParser()
        val element = jsoupBody(
            "<div id=\"para1\"><p>A</p><br />&nbsp;&nbsp;<p>B</p></div>",
        )

        val result = parser.parse(element)

        val paragraphs = result.ast.blocks.filterIsInstance<PostBlock.Paragraph>()
        assertEquals(
            "the intervening top-level <br> must keep the two <p> as separate blocks, got=${result.ast.blocks}",
            2,
            paragraphs.size,
        )
        assertEquals(
            "first block holds A with its border break trimmed",
            listOf(PostInline.Text("A")),
            paragraphs[0].inlines,
        )
        assertEquals(
            "second block holds B, not merged with A",
            listOf(PostInline.Text("B")),
            paragraphs[1].inlines,
        )
    }

    @Test
    fun `single orphan nbsp between paragraphs stays two separate blocks`() {
        // #466 guard — a LONE `&nbsp;` between two <p> is HFR's normal paragraph separator (it is
        // present between ~every pair of sibling <p>), NOT an authored blank line. Folding it in
        // would add a spurious empty line to virtually every multi-paragraph post, so the parser
        // must keep two distinct Paragraph blocks in that case (legacy behaviour preserved).
        //
        // NOTE: this HTML input is DERIVED FROM THE ISSUE #466 encoding (`</p>&nbsp;<p>`), not a
        // raw hfr-mcp capture — it isolates the single-separator boundary so the fix can be pinned
        // without depending on a fixture that happens to contain exactly two adjacent <p>. The
        // real `</p>&nbsp;&nbsp;&nbsp;<p>` multi-run case is covered by the fixture test above.
        val parser = PostContentParser()
        val element = jsoupBody(
            "<div id=\"para1\"><p>premier paragraphe</p>&nbsp;<p>second paragraphe</p></div>",
        )

        val result = parser.parse(element)

        val paragraphs = result.ast.blocks.filterIsInstance<PostBlock.Paragraph>()
        assertEquals(
            "a lone &nbsp; separator must keep two distinct Paragraph blocks, got=${result.ast.blocks}",
            2,
            paragraphs.size,
        )
        assertEquals("premier paragraphe", (paragraphs[0].inlines.single() as PostInline.Text).value)
        assertEquals("second paragraphe", (paragraphs[1].inlines.single() as PostInline.Text).value)
    }

    @Test
    fun `multiple orphan nbsp between paragraphs fold into one paragraph with empty lines`() {
        // #466 — focused boundary check on the EXACT encoding documented in the issue:
        // `<p>A</p>&nbsp;&nbsp;&nbsp;<p>B</p>` (3 orphan `&nbsp;`). DERIVED FROM THE ISSUE #466
        // encoding, not a raw hfr-mcp capture (the real multi-run shape is also pinned by the
        // `topic_page_single.html` fixture test above). A run of 3 ⇒ one paragraph boundary +
        // two authored empty lines, kept as 3 LineBreaks inside ONE paragraph.
        val parser = PostContentParser()
        val element = jsoupBody(
            "<div id=\"para1\"><p>A</p>&nbsp;&nbsp;&nbsp;<p>B</p></div>",
        )

        val result = parser.parse(element)

        val paragraphs = result.ast.blocks.filterIsInstance<PostBlock.Paragraph>()
        assertEquals(
            "the two paragraphs separated by a >=2 nbsp run must merge into ONE block, got=${result.ast.blocks}",
            1,
            paragraphs.size,
        )
        assertEquals(
            "3 orphan &nbsp; fold into 3 LineBreaks between A and B (1 boundary + 2 empty lines)",
            listOf(
                PostInline.Text("A"),
                PostInline.LineBreak,
                PostInline.LineBreak,
                PostInline.LineBreak,
                PostInline.Text("B"),
            ),
            paragraphs.single().inlines,
        )
    }

    @Test
    fun `breaks adjacent to block boundaries never leak to paragraph edges`() {
        // #333 — edge-trim invariant over real pages. The fixtures carry both directions:
        // `a écrit :</a></b><br /><br /><p>…` (leading, quote header, topic_page_single) and
        // `…:o" /><br /><br /></p>` (trailing, end of quoted content, topic_redface2_p24).
        // Breaks at a paragraph edge would duplicate the renderer's inter-block spacing.
        listOf("topic_page_single.html", "topic_redface2_p24.html").forEach { name ->
            val topic = pageParser.parse(fixture(name))

            topic.posts.flatMap { it.content.allBlocks() }
                .filterIsInstance<PostBlock.Paragraph>()
                .forEach { block ->
                    val edges = listOfNotNull(block.inlines.firstOrNull(), block.inlines.lastOrNull())
                    assertTrue(
                        "$name: paragraph edges must not be breaks or blank text, got=${block.inlines}",
                        edges.none {
                            it is PostInline.LineBreak || (it is PostInline.Text && it.value.isBlank())
                        },
                    )
                }
        }
    }

    @Test
    fun `span class u is recognised as Underline`() {
        val topic = pageParser.parse(fixture("topic_khakha_page_1.html"))

        val underlines = topic.posts
            .flatMap { post -> post.content.allInlines() }
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
            .filter { post -> post.content.blocks.any { it is PostBlock.Quote } }
            .forEach { post ->
                val authorsFromAst = post.content.blocks
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
            .flatMap { post -> post.content.allBlocks() }
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
            .flatMap { post -> post.content.allInlines() }
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
            .flatMap { post -> post.content.allInlines() }
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
    fun `fixed and code tables are recognised as their own block kinds`() {
        // Witness post on topic_redface2_p16 was authored explicitly to feed the parser:
        // XaTriX posted a series of [fixed] / [code] blocks and re-quoted them. The fixture
        // therefore contains 9 [fixed], 19 [code] (4 with a cpp language hint via <pre>).
        val topic = pageParser.parse(fixture("topic_redface2_p16.html"))

        val blocks = topic.posts.flatMap { post -> post.content.allBlocks() }
        val fixedBlocks = blocks.filterIsInstance<PostBlock.Fixed>()
        val codeBlocks = blocks.filterIsInstance<PostBlock.CodeBlock>()

        assertTrue(
            "page 16 fixture should expose [fixed] blocks; if 0 they fell back to inline text",
            fixedBlocks.isNotEmpty(),
        )
        assertTrue(
            "page 16 fixture should expose [code] blocks; if 0 they fell back to inline text",
            codeBlocks.isNotEmpty(),
        )
        assertTrue(
            "fixed text content should match what XaTriX posted (`Salut je suis un fixed`)",
            fixedBlocks.any { it.text == "Salut je suis un fixed" },
        )
        assertTrue(
            "default [code] body should be `je suis un truc de code`",
            codeBlocks.any { it.text == "je suis un truc de code" && it.language == null },
        )
    }

    @Test
    fun `code block with explicit language hint surfaces the pre class as language`() {
        val topic = pageParser.parse(fixture("topic_redface2_p16.html"))

        val coloredCpp = topic.posts
            .flatMap { post -> post.content.allBlocks() }
            .filterIsInstance<PostBlock.CodeBlock>()
            .firstOrNull { it.language == "cpp" }

        assertNotNull("expected at least one [code lang=cpp] block on page 16", coloredCpp)
        // HFR wraps the colored body in <pre class="cpp"><ol><li><div class="de1">...</div></li></ol></pre>.
        // Phase 1 contract per issue #79: the syntax-highlight spans (kw3 / me1 / st0) collapse
        // to raw source text — coloration is Phase 2 work.
        assertEquals("là du code en cpp", coloredCpp!!.text)
    }

    @Test
    fun `fixed and code blocks survive being nested inside a quote`() {
        // XaTriX re-quoted his own [fixed] / [code] dump — the quote walker must descend into the
        // citation and surface the wrapped blocks as Fixed/CodeBlock, not as flattened paragraphs.
        val topic = pageParser.parse(fixture("topic_redface2_p16.html"))

        val nestedFixed = topic.posts
            .flatMap { post -> post.content.allBlocks() }
            .filterIsInstance<PostBlock.Quote>()
            .flatMap { it.content.blocks.filterIsInstance<PostBlock.Fixed>() }
        val nestedCode = topic.posts
            .flatMap { post -> post.content.allBlocks() }
            .filterIsInstance<PostBlock.Quote>()
            .flatMap { it.content.blocks.filterIsInstance<PostBlock.CodeBlock>() }

        assertTrue(
            "at least one quote on page 16 wraps a [fixed] block — got=${nestedFixed.size}",
            nestedFixed.isNotEmpty(),
        )
        assertTrue(
            "at least one quote on page 16 wraps a [code] block — got=${nestedCode.size}",
            nestedCode.isNotEmpty(),
        )
    }

    @Test
    fun `synthetic fixed table is parsed as a single Fixed block`() {
        // Boundary check decoupled from the live capture: confirms the classifier picks
        // <table class="fixed"> before <table class="code"> falls through and prevents the
        // legacy regression where a [fixed] body was flattened into the surrounding paragraph.
        val parser = PostContentParser()
        val element = jsoupBody(
            """
            <div id="para123">
                <p>before</p>
                <div class="container">
                    <table class="fixed"><tr class="none"><td><p>line one<br>line two</p></td></tr></table>
                </div>
                <p>after</p>
            </div>
            """.trimIndent(),
        )

        val result = parser.parse(element)

        val fixed = result.ast.allBlocks().filterIsInstance<PostBlock.Fixed>()
        assertEquals("exactly one Fixed block expected", 1, fixed.size)
        assertEquals("line one\nline two", fixed.first().text)
    }

    @Test
    fun `witness fixture topic_page_multipage produces fixed and three code blocks`() {
        // Issue #79 explicitly cites this fixture (line 218) as the original witness for
        // [fixed] / [code] parsing. Asserting on its contents — not just on the richer
        // topic_redface2_p16 — closes the issue's "Périmètre" checkbox literally and protects
        // the historical baseline from silent regressions.
        val topic = pageParser.parse(fixture("topic_page_multipage.html"))

        val blocks = topic.posts.flatMap { post -> post.content.allBlocks() }
        val fixedBlocks = blocks.filterIsInstance<PostBlock.Fixed>()
        val codeBlocks = blocks.filterIsInstance<PostBlock.CodeBlock>()

        assertEquals("witness fixture has exactly one [fixed] block", 1, fixedBlocks.size)
        assertEquals("witness fixture has exactly three [code] blocks", 3, codeBlocks.size)

        val fixed = fixedBlocks.first().text
        assertTrue("fixed body must contain `Ceci est un test`, got=$fixed", fixed.contains("Ceci est un test"))
        assertTrue("fixed body must contain `abcdefghi`, got=$fixed", fixed.contains("abcdefghi"))
        assertTrue("fixed body must contain `123456789`, got=$fixed", fixed.contains("123456789"))

        val javaBlock = codeBlocks.firstOrNull { it.language == "java" }
        assertNotNull("expected one [code lang=java] block on the witness fixture", javaBlock)
        // The colored cpp/java shape wraps the source in <pre class="java"><ol><li><div class="de1">…</div></li>…
        // — wholeText() flattens the syntax-highlight spans so the body is the raw source line.
        // HFR encodes ` );` (space before the closing paren) and decodes &quot; into ".
        assertTrue(
            "java code body must contain the println call, got=${javaBlock!!.text}",
            javaBlock.text.contains("""System.out.println("Code Java" );"""),
        )
        assertEquals(
            "two plain [code] blocks (no <pre lang>) must surface language=null",
            2,
            codeBlocks.count { it.language == null },
        )
    }

    @Test
    fun `multi paragraph fixed cell preserves a separator between paragraphs`() {
        // Defensive coverage: HFR's current rendering wraps a single-paragraph [fixed] body in
        // one <p>, but if a future skin emits multiple <p> siblings inside the cell, wholeText()
        // would collapse them into a single line ("AB") because it does not insert whitespace
        // between block-level elements. extractCellText counters that by suffixing each <p>
        // with a newline before flattening.
        val parser = PostContentParser()
        val element = jsoupBody(
            """
            <div id="para123">
                <table class="fixed"><tr class="none"><td><p>line one</p><p>line two</p></td></tr></table>
            </div>
            """.trimIndent(),
        )

        val result = parser.parse(element)

        val fixed = result.ast.allBlocks().filterIsInstance<PostBlock.Fixed>().firstOrNull()
        assertNotNull("synthetic multi-paragraph [fixed] should still produce one Fixed block", fixed)
        assertTrue(
            "Fixed.text should keep paragraphs separated, got=${fixed!!.text}",
            fixed.text.contains("line one") && fixed.text.contains("line two") &&
                fixed.text.indexOf("line one") < fixed.text.indexOf("line two"),
        )
        assertTrue(
            "Fixed.text should not collapse paragraphs into `line oneline two`",
            !fixed.text.contains("line oneline two"),
        )
    }

    @Test
    fun `fixed and code blocks preserve leading indentation`() {
        val parser = PostContentParser()
        val element = jsoupBody(
            """
            <div id="para123">
                <table class="fixed"><tr class="none"><td><p>    fixed indentation<br>  fixed second</p></td></tr></table>
                <table class="code">
                    <tr class="none">
                        <td>
                            <b class="s1">Code :</b><br>
                            <ol id="code1" class="olcode">
                                <li>    val value = 42</li>
                                <li>        println(value)</li>
                            </ol>
                        </td>
                    </tr>
                </table>
            </div>
            """.trimIndent(),
        )

        val result = parser.parse(element)

        val fixed = result.ast.allBlocks().filterIsInstance<PostBlock.Fixed>().single()
        val code = result.ast.allBlocks().filterIsInstance<PostBlock.CodeBlock>().single()
        assertEquals("    fixed indentation\n  fixed second", fixed.text)
        assertEquals("    val value = 42\n        println(value)", code.text)
    }

    @Test
    fun `synthetic code table without pre wrapper has null language`() {
        val parser = PostContentParser()
        val element = jsoupBody(
            """
            <div id="para123">
                <div class="container">
                    <table class="code">
                        <tr class="none">
                            <td>
                                <b class="s1">Code :</b><br>
                                <ol id="code1" class="olcode">
                                    <li>line one</li>
                                    <li>line two</li>
                                </ol>
                            </td>
                        </tr>
                    </table>
                </div>
            </div>
            """.trimIndent(),
        )

        val result = parser.parse(element)

        val code = result.ast.allBlocks().filterIsInstance<PostBlock.CodeBlock>()
        assertEquals("exactly one CodeBlock expected", 1, code.size)
        assertEquals(null, code.first().language)
        // The "Code :" header is dropped; <li> children are joined with newlines.
        assertEquals("line one\nline two", code.first().text)
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
            is PostBlock.Fixed -> Unit
            is PostBlock.CodeBlock -> Unit
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
