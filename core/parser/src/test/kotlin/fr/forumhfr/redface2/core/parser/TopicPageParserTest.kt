package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.Topic
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

    // ─── canReply / subcat = bddpost reply form presence (#213) ─────────────────

    @Test
    fun `canReply true and subcat read from the bddpost reply form on an authenticated open topic`() {
        // #213 — postability is driven by the presence of the `bddpost` reply form,
        // which HFR renders only on an authenticated, non-locked topic. The POST
        // subcat is the `input[name=subcat]` of THAT form (550 on this Android
        // topic), not the fast-search widget value that also ships on the page.
        val topic = parser.parse(fixture("topic_page_multipage.html"))

        assertTrue("authenticated open topic carries the bddpost form", topic.canReply)
        assertEquals(550, topic.subcat)
    }

    @Test
    fun `canReply true and subcat read from the bddpost form on the posts page fixture`() {
        // Same contract on a second real authenticated capture — the reply form
        // subcat here is 553. Proves we read the value from the form, not a hard
        // coded constant.
        val topic = parser.parse(fixture("topic_posts_page.html"))

        assertTrue(topic.canReply)
        assertEquals(553, topic.subcat)
    }

    @Test
    fun `canReply false and subcat SUBCAT_UNKNOWN on a locked topic without a reply form`() {
        // #213 — `write_locked_topic_page.html` is a real topic page captured on a
        // locked topic : HFR drops the `bddpost` reply form. The page still ships a
        // fast-search `input[name=subcat]` (value 0), but that widget is NOT the
        // reply form, so we must not treat it as a postable subcat. No form ⇒
        // canReply=false and subcat falls back to the SUBCAT_UNKNOWN sentinel.
        val topic = parser.parse(fixture("write_locked_topic_page.html"))

        assertFalse("locked topic has no bddpost form", topic.canReply)
        assertEquals(Topic.SUBCAT_UNKNOWN, topic.subcat)
    }

    @Test
    fun `canReply false and subcat SUBCAT_UNKNOWN on a logged-out topic page`() {
        // `topic_khakha_page_1.html` was captured logged-out : HFR returns a
        // different HTML in anon vs auth (cf. memory reference_hfr_auth_vs_anon_html)
        // and does not render the `bddpost` reply form. The page only carries the
        // fast-search widget subcat (432), which #213 explicitly stops trusting :
        // the widget value is useless for writing (you cannot post logged-out) and
        // wrongly enabled the reply buttons. No form ⇒ read-only.
        val topic = parser.parse(fixture("topic_khakha_page_1.html"))

        assertFalse("logged-out topic page has no bddpost form", topic.canReply)
        assertEquals(Topic.SUBCAT_UNKNOWN, topic.subcat)
    }

    @Test
    fun `canReply false and subcat SUBCAT_UNKNOWN on the single-page logged-out fixture`() {
        // `topic_page_single.html` (cat=1, subcat widget 253) is another logged-out
        // capture with no `bddpost` form. Confirms we drop the old widget-fallback :
        // the subcat is only meaningful for writing, which is impossible logged-out.
        val topic = parser.parse(fixture("topic_page_single.html"))

        assertFalse(topic.canReply)
        assertEquals(Topic.SUBCAT_UNKNOWN, topic.subcat)
    }

    @Test
    fun `canReply false when the page only ships a non-reply form action`() {
        // Regression for the selector contract : the search widget action
        // (`/forum1.php`) and the `bdd.php` edit endpoint must NOT be mistaken for a
        // reply form. Only `form[action*=bddpost.php]` flips canReply. A page whose
        // sole `subcat` input lives in a `/forum1.php` form is read-only.
        val html = """
            <html><body>
              <input type="hidden" name="cat" value="13" />
              <input type="hidden" name="post" value="84540" />
              <form action="/forum1.php">
                <input type="hidden" name="subcat" value="111" />
              </form>
              <table><tbody>
                <tr class="fondForum2Title"><td><h3>Search-widget-only page</h3></td></tr>
                <tr class="fondForum2PagesHaut"><td class="left"><b>1</b></td></tr>
              </tbody></table>
            </body></html>
        """.trimIndent()
        val topic = parser.parse(html)
        assertFalse("forum1.php form must not enable canReply", topic.canReply)
        assertEquals(Topic.SUBCAT_UNKNOWN, topic.subcat)
    }

    @Test
    fun `canReply true with subcat zero when the bddpost form carries subcat zero (cat without subcat)`() {
        // #213 core contract — a category WITHOUT a sub-category (e.g. cat=32
        // « Intelligence artificielle ») renders the `bddpost` reply form with
        // `subcat=0`. That value is postable (proven by a live capture of the IA
        // reply form, see protocol-hfr.md § POST bddpost.php). The full real IA
        // topic-page fixture is a follow-up `plus` ; until it is captured & cleaned
        // we pin the `subcat=0` branch here so the parser keeps `0` instead of
        // collapsing it to the SUBCAT_UNKNOWN sentinel. This is the only HTML this
        // test synthesises ; the auth/locked branches above use real fixtures.
        val html = """
            <html><body>
              <input type="hidden" name="cat" value="32" />
              <input type="hidden" name="post" value="123456" />
              <form name="hop" action="/bddpost.php?config=hfr.inc">
                <input type="hidden" name="cat" value="32" />
                <input type="hidden" name="subcat" value="0" />
                <input type="hidden" name="owntopic" value="0" />
              </form>
              <table><tbody>
                <tr class="fondForum2Title"><td><h3>IA topic without subcat</h3></td></tr>
                <tr class="fondForum2PagesHaut"><td class="left"><b>1</b></td></tr>
              </tbody></table>
            </body></html>
        """.trimIndent()
        val topic = parser.parse(html)
        assertTrue("subcat=0 reply form is postable", topic.canReply)
        assertEquals(0, topic.subcat)
    }

    @Test
    fun `subcat is read from the bddpost form not the fast-search widget when they differ`() {
        // Source-of-truth proof (the real auth fixtures carry the SAME value in both
        // the fast-search widget and the bddpost form, so they cannot distinguish the
        // source). Here the fast-search `/forum1.php` widget says subcat=999 while the
        // `bddpost` reply form says subcat=550 : the parser MUST return 550, proving it
        // reads `form[action*=bddpost.php] input[name=subcat]` and never the widget.
        val html = """
            <html><body>
              <input type="hidden" name="cat" value="23" />
              <input type="hidden" name="post" value="35395" />
              <form action="/forum1.php">
                <input type="hidden" name="subcat" value="999" />
              </form>
              <form name="hop" action="/bddpost.php?config=hfr.inc">
                <input type="hidden" name="cat" value="23" />
                <input type="hidden" name="subcat" value="550" />
              </form>
              <table><tbody>
                <tr class="fondForum2Title"><td><h3>Mismatched subcat inputs</h3></td></tr>
                <tr class="fondForum2PagesHaut"><td class="left"><b>1</b></td></tr>
              </tbody></table>
            </body></html>
        """.trimIndent()
        val topic = parser.parse(html)
        assertTrue(topic.canReply)
        assertEquals(550, topic.subcat)
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

    // ─── cat IA (cat=32, no sub-category) — #213 / quote (#146) ─────────────────

    @Test
    fun `canReply and subcat zero are robust on the authenticated IA browser-saved topic page`() {
        // #213 / #146 — `write_ia_topic_page.html` is a real authenticated capture of
        // the cat=32 « Intelligence artificielle » topic (no sub-category : subcat=0).
        // This fixture is a browser-save: Firefox already preserved/materialized toolbar
        // links that the OkHttp app path may receive as raw `md_*cryptlink` spans. It is
        // therefore valid to pin the topic write contract (reply form + subcat=0), but
        // NOT valid to assert a non-null `quoteRef` here. The UI gate for « Citer » is
        // covered in `TopicActionGatesTest` and no longer depends on `quoteRef`.
        val topic = parser.parse(fixture("write_ia_topic_page.html"))

        assertEquals("cat IA", 32, topic.cat)
        assertEquals("topic id", 7, topic.post)
        // The authenticated IA topic ships the bddpost reply form with subcat=0
        // (postable) — canReply must be true and subcat kept verbatim at 0.
        assertTrue("authenticated IA topic carries the bddpost form", topic.canReply)
        assertEquals(0, topic.subcat)

        assertTrue("IA topic must have posts", topic.posts.isNotEmpty())
        assertEquals(16244, topic.posts.first().numreponse)
    }

    @Test
    fun `existing topic fixtures keep their quoteRef after the IA capture is added`() {
        // Regression guard for FIX 1 : whatever selector parses the IA quote link
        // must NOT alter the quoteRef contract on the pre-existing real fixtures.
        val khakha2 = parser.parse(fixture("topic_khakha_page_2.html"))
        val byNumreponse = khakha2.posts.associateBy { it.numreponse }
        assertEquals(0, byNumreponse[16628071]?.quoteRef)
        assertEquals(2, byNumreponse[16628106]?.quoteRef)
        assertEquals(5, byNumreponse[16628222]?.quoteRef)

        // The multipage fixture is authenticated and must keep stable quoteRefs too.
        val multipage = parser.parse(fixture("topic_page_multipage.html"))
        assertTrue(
            "authenticated multipage fixture must keep at least one non-null quoteRef",
            multipage.posts.any { it.quoteRef != null },
        )
    }

    @Test
    fun `isFirstPostOwner is true on page 1 when the first post toolbar exposes an edit link`() {
        // Phase 2D #148 — the « Modifier le premier message » action only fires
        // when (a) we are on page 1 (HFR's FP lives there by definition) and
        // (b) HFR rendered an edit link on the first post toolbar. We don't
        // peek at the topic author client-side ; the server is the source of
        // truth. The same selector logic that drives `Post.isEditable` flips
        // `Topic.isFirstPostOwner` on the parsed topic.
        val html = """
            <html><body>
              <input name="cat" value="10" />
              <input name="post" value="148749" />
              <input name="subcat" value="388" />
              <table><tbody>
                <tr class="fondForum2Title">
                  <th class="messCase1">Auteur</th>
                  <th><h3>Owned FP topic</h3></th>
                </tr>
              </tbody></table>
              <table class="messagetable"><tbody>
                <tr class="message">
                  <td class="messCase1"><a name="t2523829"></a><b class="s2">OwnerUser</b></td>
                  <td class="messCase2">
                    <div class="toolbar"><div class="left">
                      Posté le 17-05-2026&nbsp;à&nbsp;14:00:00
                      <a href="/message.php?config=hfr.inc&amp;cat=10&amp;post=148749&amp;page=1&amp;p=1&amp;subcat=388&amp;sondage=0&amp;owntopic=0&amp;new=0&amp;numreponse=2523829">edit</a>
                    </div></div>
                    <div id="para2523829"><p>FP body</p></div>
                  </td>
                </tr>
              </tbody></table>
            </body></html>
        """.trimIndent()
        val topic = parser.parse(html)
        assertEquals(1, topic.page)
        assertTrue("FP edit link must promote isFirstPostOwner", topic.isFirstPostOwner)
        assertTrue("First post must surface isEditable", topic.posts.single().isEditable)
    }

    @Test
    fun `isFirstPostOwner is false on page 2 even when a post on that page is editable`() {
        // We render page=2 explicitly (top pager `<b>2</b>`) — the parser
        // resolves `pageInfo.current = 2` and must therefore refuse to promote
        // a later editable post to first-post-ownership. The « Modifier le
        // premier message » action only makes sense on page 1.
        val html = """
            <html><body>
              <input name="cat" value="10" />
              <input name="post" value="148749" />
              <input name="subcat" value="388" />
              <table>
                <tbody>
                  <tr class="cBackHeader fondForum2PagesHaut">
                    <td class="padding">
                      <div class="left">
                        <b>Page&nbsp;:&nbsp;</b>
                        <a href="?page=1" class="cHeader">1</a>
                        <b>2</b>
                      </div>
                    </td>
                  </tr>
                  <tr class="fondForum2Title">
                    <th class="messCase1">Auteur</th>
                    <th><h3>Page 2 of an owned topic</h3></th>
                  </tr>
                </tbody>
              </table>
              <table class="messagetable"><tbody>
                <tr class="message">
                  <td class="messCase1"><a name="t77777"></a><b class="s2">OwnerUser</b></td>
                  <td class="messCase2">
                    <div class="toolbar"><div class="left">
                      Posté le 18-05-2026&nbsp;à&nbsp;10:00:00
                      <a href="/message.php?config=hfr.inc&amp;cat=10&amp;post=148749&amp;page=2&amp;p=1&amp;subcat=388&amp;sondage=0&amp;owntopic=0&amp;new=0&amp;numreponse=77777">edit</a>
                    </div></div>
                    <div id="para77777"><p>later editable post</p></div>
                  </td>
                </tr>
              </tbody></table>
            </body></html>
        """.trimIndent()
        val topic = parser.parse(html)
        assertEquals(2, topic.page)
        assertTrue("editable post still surfaces isEditable", topic.posts.single().isEditable)
        assertFalse("FP ownership must stay false on page 2", topic.isFirstPostOwner)
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
        // `quoteRef` null; the UI decides visibility from `Topic.canReply`, not
        // from this optional ref.
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

    // ─── profileId (#208) ───────────────────────────────────────────────────────

    @Test
    fun `profileId is extracted from profil link on khakha page 1`() {
        val topic = parser.parse(fixture("topic_khakha_page_1.html"))
        // The first post on khakha p1 is by Mora1651 (profil-599674.htm).
        val firstPost = topic.posts.first()
        assertEquals(
            "First post on khakha page 1 should have profileId=599674",
            599674,
            firstPost.profileId,
        )
    }

    @Test
    fun `all real posts on khakha page 1 have a non-null profileId`() {
        val topic = parser.parse(fixture("topic_khakha_page_1.html"))
        // The khakha fixture contains one « Publicité » ad row without a profile
        // link. All other posts have a real user profile link.
        val postsWithProfile = topic.posts.filter { it.profileId != null }
        assertTrue(
            "At least half the posts should have a profileId",
            postsWithProfile.size >= topic.posts.size / 2,
        )
    }

    @Test
    fun `profileId is null for a post with no profile link`() {
        // Synthesised minimal page: one post without any profil- link.
        val html = """
            <html><body>
              <input name="cat" value="13" />
              <input name="post" value="84540" />
              <input name="subcat" value="432" />
              <table><tbody>
                <tr class="fondForum2Title">
                  <th class="messCase1">Auteur</th>
                  <th><h3>No profile page</h3></th>
                </tr>
              </tbody></table>
              <table class="messagetable"><tbody>
                <tr class="message">
                  <td class="messCase1"><a name="t12345"></a><b class="s2">Publicité</b></td>
                  <td class="messCase2">
                    <div class="toolbar"><div class="left">
                      Posté le 01-01-2024&nbsp;à&nbsp;10:00:00
                      <img src="profile.gif" title="Voir son profil">
                    </div></div>
                    <div id="para12345"><p>ad content</p></div>
                  </td>
                </tr>
              </tbody></table>
            </body></html>
        """.trimIndent()
        val topic = parser.parse(html)
        assertNull(
            "Post without profil link should have null profileId",
            topic.posts.single().profileId,
        )
    }

    private fun fixture(name: String): String {
        return requireNotNull(javaClass.getResource("/fixtures/$name")) {
            "Fixture not found: $name"
        }.readText()
    }
}
