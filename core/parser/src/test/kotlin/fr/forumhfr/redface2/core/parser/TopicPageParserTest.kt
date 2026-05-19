package fr.forumhfr.redface2.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicPageParserTest {
    private val parser = TopicPageParser()

    @Test
    fun `parse multipage topic details`() {
        val topic = parser.parse(fixture("topic_page_multipage.html"))

        assertEquals(23, topic.cat)
        assertEquals(21748, topic.post)
        assertEquals("[Projet] HFR4droid 0.8.6 - 10k downloads, merci à tous", topic.title)
        assertEquals(1, topic.page)
        assertEquals(419, topic.totalPages)
        assertEquals(40, topic.posts.size)
        assertEquals(520051, topic.posts.first().numreponse)
        assertEquals("ToYonos", topic.posts.first().author)
    }

    @Test
    fun `parse single page topic details`() {
        val topic = parser.parse(fixture("topic_page_single.html"))

        assertEquals(1, topic.cat)
        assertEquals(999395, topic.post)
        assertEquals("S'allume difficilement et garde les actions du bouton de démarrage!", topic.title)
        assertEquals(1, topic.page)
        assertEquals(1, topic.totalPages)
        assertTrue(topic.posts.isNotEmpty())
    }

    @Test
    fun `parse forty posts from topic page`() {
        val topic = parser.parse(fixture("topic_posts_page.html"))

        assertEquals(23, topic.cat)
        assertEquals(29169, topic.post)
        assertEquals(1, topic.page)
        assertEquals(12, topic.totalPages)
        assertEquals(40, topic.posts.size)
        assertEquals(1885523, topic.posts.first().numreponse)
        assertTrue(topic.posts.first().author.isNotBlank())
    }

    @Test
    fun `extract quoted authors from nested citations`() {
        val topic = parser.parse(fixture("topic_page_multipage.html"))

        assertTrue(topic.posts.any { post -> "Origan" in post.quotedAuthors })
    }

    @Test
    fun `parse khakha opening page`() {
        val topic = parser.parse(fixture("topic_khakha_page_1.html"))

        assertEquals(13, topic.cat)
        assertEquals(84540, topic.post)
        assertEquals("[Topic Unique] Déféquer en toute sérénité, topic du kaka", topic.title)
        assertEquals(1, topic.page)
        assertEquals(152, topic.totalPages)
        assertEquals(40, topic.posts.size)
        assertEquals(16625217, topic.posts.first().numreponse)
        assertEquals("Mora1651", topic.posts.first().author)
        // The opening page contains both a builtin :o smiley and the [:aloy] perso variant —
        // checking the AST guarantees they reach the renderer with their canonical token.
        val smileyTokens = topic.posts
            .flatMap { post ->
                post.content.blocks
                    .filterIsInstance<fr.forumhfr.redface2.core.model.PostBlock.Paragraph>()
                    .flatMap { it.inlines }
                    .filterIsInstance<fr.forumhfr.redface2.core.model.PostInline.Smiley>()
            }
            .map { smiley ->
                when (val kind = smiley.kind) {
                    is fr.forumhfr.redface2.core.model.SmileyKind.Builtin -> kind.code
                    is fr.forumhfr.redface2.core.model.SmileyKind.Perso -> "[:${kind.name}]"
                }
            }
            .toSet()
        assertTrue(
            "expected :o or [:aloy] in smiley tokens, got=$smileyTokens",
            ":o" in smileyTokens || "[:aloy]" in smileyTokens,
        )
    }

    @Test
    fun `parse khakha page with poll`() {
        val topic = parser.parse(fixture("topic_khakha_page_2.html"))

        assertEquals(2, topic.page)
        assertEquals(152, topic.totalPages)
        assertEquals(41, topic.posts.size)
        assertEquals(16628071, topic.posts.first().numreponse)
        requireNotNull(topic.poll).also { poll ->
            assertEquals("Aimez-vous l'odeur de vos excréments?", poll.question)
            assertEquals(9, poll.options.size)
            assertTrue(poll.multipleChoice)
            assertEquals(176, poll.totalVotes)
            assertEquals("1. Non, c'est dégueu!", poll.options.first().text)
            assertEquals(34, poll.options.first().votes)
        }
    }

    @Test
    fun `parse khakha late page with nested quotes`() {
        val topic = parser.parse(fixture("topic_khakha_page_146.html"))

        assertEquals(146, topic.page)
        assertEquals(152, topic.totalPages)
        assertEquals(41, topic.posts.size)
        assertEquals(18085006, topic.posts.first().numreponse)
        assertTrue(topic.posts.any { post -> "justhynbrydhou" in post.quotedAuthors })
    }

    @Test
    fun `leave global post index unresolved when parsing an isolated topic page`() {
        val topic = parser.parse(fixture("topic_khakha_page_2.html"))

        assertNull(topic.posts.first().postIndex)
    }

    @Test
    fun `parse extracts the subcat from the HFR topic page so Phase 2C can build a reply URL`() {
        // `input[name=subcat]` appears multiple times on a HFR topic page (fast-search
        // header + reply form). The parser must pick a real value, not the
        // SUBCAT_UNKNOWN sentinel — Phase 2C's reply flow refuses to open the editor
        // when the topic carries the sentinel (cache pre-dating MIGRATION_3_4).
        val topic = parser.parse(fixture("topic_khakha_page_1.html"))

        assertEquals(432, topic.subcat)
        assertTrue(topic.hasSubcat)
    }

    @Test
    fun `parse falls back to SUBCAT_UNKNOWN when the topic HTML drops the subcat input`() {
        // Synthetic HTML is **explicitly** allowed for this regression check :
        // CLAUDE.md § Fixtures HTML requires real HFR captures via hfr-mcp for
        // anything that exercises the production parser path on a representative
        // page. Here we only exercise the *missing-input fallback branch* of
        // `optionalSubcat`, which by definition cannot be captured live (every
        // real HFR topic ships a `subcat` input). Building the minimal valid
        // shape locally is the cheapest way to pin the contract — if HFR ever
        // changes the topic layout to drop subcat altogether, real fixtures will
        // overtake this test.
        val html = """
            <html><body>
              <input type="hidden" name="cat" value="13" />
              <input type="hidden" name="post" value="84540" />
              <table><tbody>
                <tr class="fondForum2Title"><td><h3>Stripped topic</h3></td></tr>
                <tr class="fondForum2PagesHaut"><td class="left"><b>1</b></td></tr>
              </tbody></table>
            </body></html>
        """.trimIndent()
        val topic = parser.parse(html)
        assertEquals(fr.forumhfr.redface2.core.model.Topic.SUBCAT_UNKNOWN, topic.subcat)
        assertFalse(topic.hasSubcat)
    }

    @Test
    fun `parse picks the last subcat occurrence when multiple widgets share the field`() {
        // HFR ships `input[name=subcat]` in several widgets on a single topic page
        // (fast-search header + reply form). The reply form occurrence is the one
        // we want — it's emitted after the search widget. This regression test
        // synthesises two occurrences with different values to prove the
        // `optionalSubcat` `last()` choice is wired the way the KDoc claims.
        val html = """
            <html><body>
              <input type="hidden" name="cat" value="13" />
              <input type="hidden" name="post" value="84540" />
              <input type="hidden" name="subcat" value="111" />
              <table><tbody>
                <tr class="fondForum2Title"><td><h3>Topic with two subcat inputs</h3></td></tr>
                <tr class="fondForum2PagesHaut"><td class="left"><b>1</b></td></tr>
              </tbody></table>
              <form action="/bddpost.php?config=hfr.inc">
                <input type="hidden" name="subcat" value="432" />
              </form>
            </body></html>
        """.trimIndent()
        val topic = parser.parse(html)
        assertEquals(432, topic.subcat)
        assertTrue(topic.hasSubcat)
    }

    @Test
    fun `quoteRef extracted from quote link href on the khakha page 2 fixture`() {
        // page 2 of the khakha fixture exposes a stable ref distribution (0, 1, 2, …)
        // because each post sits at its own position in the page (40 posts visible).
        // Three representative posts cover the contract :
        //   - first quote-able post → ref=0
        //   - mid-page post           → ref=2
        //   - late post               → ref=5
        // We do not over-specify the rest of the page : the parser only promises
        // « whatever HFR put in the href » and a future capture might shift positions.
        val topic = parser.parse(fixture("topic_khakha_page_2.html"))

        val byNumreponse = topic.posts.associateBy { it.numreponse }
        // n°16628071 = Mora1651 (1st quote-able post on the page, ref=0 in fixture)
        assertEquals(0, byNumreponse[16628071]?.quoteRef)
        // n°16628106 = groux (ref=2)
        assertEquals(2, byNumreponse[16628106]?.quoteRef)
        // n°16628222 = Maverick (ref=5)
        assertEquals(5, byNumreponse[16628222]?.quoteRef)
    }

    @Test
    fun `isEditable is true when the toolbar exposes a message_php numreponse link`() {
        // Phase 2D (#147) — HFR renders an edit link on the post's left toolbar
        // only when the current authenticated session owns the post and the
        // topic is unlocked. We mirror the synthetic-fixture pattern used by
        // `quoteRef ignores …` ; full topic fixtures don't carry edit links
        // because they were captured with non-owner accounts.
        val html = """
            <html><body>
              <input name="cat" value="13" />
              <input name="post" value="84540" />
              <input name="subcat" value="432" />
              <table><tbody>
                <tr class="fondForum2Title">
                  <th class="messCase1">Auteur</th>
                  <th><h3>Editable post sanity</h3></th>
                </tr>
              </tbody></table>
              <table class="messagetable"><tbody>
                <tr class="message">
                  <td class="messCase1"><a name="t55555"></a><b class="s2">OwnerUser</b></td>
                  <td class="messCase2">
                    <div class="toolbar"><div class="left">
                      Posté le 19-05-2026&nbsp;à&nbsp;12:00:00
                      <a href="/message.php?config=hfr.inc&amp;cat=13&amp;post=84540&amp;page=2&amp;p=1&amp;subcat=432&amp;sondage=0&amp;owntopic=0&amp;new=0&amp;numreponse=55555">edit</a>
                    </div></div>
                    <div id="para55555"><p>edited content</p></div>
                  </td>
                </tr>
              </tbody></table>
            </body></html>
        """.trimIndent()
        val topic = parser.parse(html)
        val post = topic.posts.single()
        assertTrue("Owner post must surface isEditable=true", post.isEditable)
        assertTrue("Owner post must surface isOwnPost=true", post.isOwnPost)
    }

    @Test
    fun `isEditable is false when a numreponse link sits in the post body but not the toolbar`() {
        // Round-trip the same scope guard we already apply to `quoteRef` :
        // an inline link to another post's `message.php?…&numreponse=…` (which
        // a user can paste in their content) must NOT promote the host post
        // to editable.
        val html = """
            <html><body>
              <input name="cat" value="13" />
              <input name="post" value="84540" />
              <input name="subcat" value="432" />
              <table><tbody>
                <tr class="fondForum2Title">
                  <th class="messCase1">Auteur</th>
                  <th><h3>Body link sanity</h3></th>
                </tr>
              </tbody></table>
              <table class="messagetable"><tbody>
                <tr class="message">
                  <td class="messCase1"><a name="t66666"></a><b class="s2">OtherUser</b></td>
                  <td class="messCase2">
                    <div class="toolbar"><div class="left">
                      Posté le 19-05-2026&nbsp;à&nbsp;12:00:00
                    </div></div>
                    <div id="para66666"><p>See <a href="/message.php?cat=13&amp;numreponse=11111">that post</a></p></div>
                  </td>
                </tr>
              </tbody></table>
            </body></html>
        """.trimIndent()
        val topic = parser.parse(html)
        val post = topic.posts.single()
        assertFalse("Body link must not promote isEditable", post.isEditable)
        assertFalse("Body link must not promote isOwnPost", post.isOwnPost)
    }

    @Test
    fun `quoteRef ignores numrep links inside the post body and only reads the toolbar`() {
        // A user can quote another post inline in the body of their own post (HFR
        // renders such a link as a plain anchor to message.php?…&numrep=…&ref=N…).
        // `parseQuoteRef` must NOT pick that up : the « Citer » action belongs to
        // the toolbar of the post we're rendering, not to whatever it cites in
        // its content. Round 2 fix — scopes the lookup to POST_TOOLBAR_LEFT.
        val html = """
            <html><body>
              <input name="cat" value="13" />
              <input name="post" value="84540" />
              <input name="subcat" value="432" />
              <table><tbody>
                <tr class="fondForum2Title">
                  <th class="messCase1">Auteur</th>
                  <th><h3>Body link sanity</h3></th>
                </tr>
              </tbody></table>
              <table class="messagetable"><tbody>
                <tr class="message">
                  <td class="messCase1"><a name="t77777"></a><b class="s2">UserA</b></td>
                  <td class="messCase2">
                    <div class="toolbar"><div class="left">Posté le 18-05-2026&nbsp;à&nbsp;10:00:00</div></div>
                    <div id="para77777">
                      <p>See <a href="/message.php?config=hfr.inc&amp;cat=13&amp;post=84540&amp;numrep=12345&amp;ref=99&amp;page=1&amp;subcat=432">that other post</a></p>
                    </div>
                  </td>
                </tr>
              </tbody></table>
            </body></html>
        """.trimIndent()
        val topic = parser.parse(html)
        assertNull(
            "numrep link inside the post body must not be promoted to quoteRef",
            topic.posts.single().quoteRef,
        )
    }

    @Test
    fun `quoteRef ignores a numrep link whose params only carry foreign ref suffixes`() {
        // Belt-and-braces regression : if HFR ever adds an unrelated `…&myref=…`
        // or `…&referrer=…` to the toolbar (or a tracking attribute), our quote
        // detector must NOT treat it as a quote link. Round 1 review finding —
        // the previous `.contains("ref=")` matched any substring.
        val html = """
            <html><body>
              <input name="cat" value="13" />
              <input name="post" value="84540" />
              <input name="subcat" value="432" />
              <table><tbody>
                <tr class="fondForum2Title">
                  <th class="messCase1">Auteur</th>
                  <th><h3>Foreign suffix sanity</h3></th>
                </tr>
              </tbody></table>
              <table class="messagetable"><tbody>
                <tr class="message">
                  <td class="messCase1"><a name="t12345"></a><b class="s2">UserA</b></td>
                  <td class="messCase2">
                    <div class="toolbar"><div class="left">Posté le 18-05-2026&nbsp;à&nbsp;10:00:00</div></div>
                    <a href="/foo.php?numrep=12345&amp;myref=99&amp;referrer=bar">spurious</a>
                    <div id="para12345"><p>content</p></div>
                  </td>
                </tr>
              </tbody></table>
            </body></html>
        """.trimIndent()
        val topic = parser.parse(html)
        assertNull(
            "Foreign &myref / &referrer suffixes must not be picked up as quote ref",
            topic.posts.single().quoteRef,
        )
    }

    @Test
    fun `quoteRef is null for a post that has no quote link in the source HTML`() {
        // Synthesised minimal page : one post table without any `a[href*=numrep=]`
        // entry. Mirrors HFR's locked-topic special pages where the toolbar drops
        // the quote action. The parser must keep the post readable AND leave
        // `quoteRef` null so the UI suppresses the « Citer » button.
        //
        // The HFR selector requires a <table><tr class="fondForum2Title"><th><h3>
        // wrapper for the topic title and `td.messCase1 a[name^=t]` for the post
        // anchor, plus `td.messCase1 b.s2` for the author.
        val html = """
            <html><body>
              <input name="cat" value="13" />
              <input name="post" value="84540" />
              <input name="subcat" value="432" />
              <table>
                <tbody>
                  <tr class="fondForum2Title">
                    <th class="messCase1">Auteur</th>
                    <th><h3>Locked topic</h3></th>
                  </tr>
                </tbody>
              </table>
              <table class="messagetable">
                <tbody>
                  <tr class="message">
                    <td class="messCase1"><a name="t99999"></a><b class="s2">SomeAuthor</b></td>
                    <td class="messCase2">
                      <div class="toolbar"><div class="left">Posté le 18-05-2026&nbsp;à&nbsp;10:00:00</div></div>
                      <div id="para99999"><p>locked content</p></div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </body></html>
        """.trimIndent()
        val topic = parser.parse(html)
        val post = topic.posts.single()
        assertEquals(99999, post.numreponse)
        assertNull("quote-less post must expose null quoteRef", post.quoteRef)
    }

    private fun fixture(name: String): String {
        return requireNotNull(javaClass.getResource("/fixtures/$name")) {
            "Fixture not found: $name"
        }.readText()
    }
}
