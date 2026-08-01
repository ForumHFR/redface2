package fr.forumhfr.redface2.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chantier C (#546) + #894 — extraction of the intra-topic search form (`transsearch.php`) hidden
 * fields, from topic-page fixtures (request side) AND from live-captured `transsearch` RESPONSE
 * fixtures (`transsearch_response_nonfilter_{anon,auth}.html`, 2026-07-12). The response captures
 * replaced a synthetic fixture that wrongly assumed responses carry a `firstnum` input — they do
 * NOT (the anchor only exists on normal topic pages), and requiring it made every response form
 * parse to null, dropping the `currentnum` cursor (#894 : non-filtered search always « Aucun
 * résultat » against live HFR).
 */
class TopicSearchFormParserTest {
    private val parser = TopicSearchFormParser()

    @Test
    fun `extracts hash_check, ids and firstnum from an authenticated multipage topic`() {
        // `topic_page_multipage.html` is an authenticated capture : the transsearch form carries a
        // non-empty (scrubbed) hash_check and the real ids/anchor (post=21748, cat=23, firstnum=520051).
        val form = requireNotNull(parser.parse(fixture("topic_page_multipage.html")))

        assertEquals(21748, form.topicId)
        assertEquals(23, form.cat)
        assertEquals(520051, form.firstnum)
        assertEquals(0, form.owntopic)
        assertTrue("non-empty hash_check ⇒ authenticated, search is allowed", form.hashCheck.isNotBlank())
        assertTrue(form.canSearch)
    }

    @Test
    fun `extracts the cat-IA owned-topic form with owntopic flag`() {
        // `write_ia_topic_page.html` is the authenticated cat=32 « IA » capture : post=7, cat=32,
        // owntopic=1, firstnum=16244 — proves owntopic is read verbatim (not assumed 0).
        val form = requireNotNull(parser.parse(fixture("write_ia_topic_page.html")))

        assertEquals(7, form.topicId)
        assertEquals(32, form.cat)
        assertEquals(16244, form.firstnum)
        assertEquals(1, form.owntopic)
        assertTrue(form.canSearch)
    }

    @Test
    fun `canSearch is false on a logged-out topic page whose hash_check is empty`() {
        // `topic_khakha_page_1.html` was captured logged-out : HFR still renders the transsearch
        // form but with an empty hash_check, which `transsearch.php` rejects. The affordance must
        // stay disabled (canSearch=false) while the ids are still extracted.
        val form = requireNotNull(parser.parse(fixture("topic_khakha_page_1.html")))

        assertEquals(84540, form.topicId)
        assertEquals(13, form.cat)
        assertTrue(requireNotNull(form.firstnum) > 0)
        assertEquals("", form.hashCheck)
        assertFalse("empty hash_check ⇒ search not available", form.canSearch)
    }

    @Test
    fun `currentNum is null on a normal topic page whose form ships no currentnum input`() {
        // The STATIC transsearch form HFR renders on a normal page has no `currentnum` input (its own
        // JS injects one client-side), so the parser must read it back as null — the affordance keys
        // « start a fresh search » on this. (Chantier B / #546.)
        val form = requireNotNull(parser.parse(fixture("topic_page_multipage.html")))

        assertNull("a normal page carries no currentnum cursor", form.currentNum)
    }

    @Test
    fun `parses a transsearch response form — currentnum cursor present, firstnum ABSENT (anon)`() {
        // #894 — live-captured non-filtered response (author-only search anchored at page 61 of the
        // RF2 topic). The response form carries the `currentnum` cursor (the anchored match) and NO
        // `firstnum` input : the parse must SUCCEED with firstnum=null, never degrade to null — a
        // null form silently drops the cursor and reports « Aucun résultat » on every live search.
        val form = requireNotNull(parser.parse(fixture("transsearch_response_nonfilter_anon.html")))

        assertEquals(35395, form.topicId)
        assertEquals(23, form.cat)
        assertNull("a transsearch response carries no firstnum anchor", form.firstnum)
        assertEquals(2789841, form.currentNum)
        assertFalse("anonymous capture ⇒ empty hash_check", form.canSearch)
    }

    @Test
    fun `parses a transsearch response form — authenticated twin keeps canSearch true`() {
        // #894 — same query captured authenticated (hash_check scrubbed to a non-empty placeholder) :
        // firstnum is absent from the response form in BOTH render modes (HFR serves different HTML
        // authenticated vs anonymous), while canSearch must still read true here.
        val form = requireNotNull(parser.parse(fixture("transsearch_response_nonfilter_auth.html")))

        assertEquals(35395, form.topicId)
        assertEquals(23, form.cat)
        assertNull("a transsearch response carries no firstnum anchor", form.firstnum)
        assertEquals(2789841, form.currentNum)
        assertTrue(form.canSearch)
    }

    @Test
    fun `returns null when the page carries no transsearch form`() {
        // A reply-only / synthetic page without the header search form must degrade to null so the
        // caller hides the search affordance instead of crashing.
        val html = """
            <html><body>
              <form action="/bddpost.php?config=hfr.inc">
                <input type="hidden" name="cat" value="23" />
                <input type="hidden" name="post" value="35395" />
                <input type="hidden" name="subcat" value="550" />
              </form>
            </body></html>
        """.trimIndent()

        assertNull(parser.parse(html))
    }

    @Test
    fun `returns null when the transsearch form is missing a required id`() {
        // `post` / `cat` stay REQUIRED (they key the POST) : a form missing one is unusable and must
        // degrade to null. `firstnum` deliberately absent from this list since #894.
        val html = """
            <html><body>
              <form action="/transsearch.php" method="post">
                <input type="hidden" name="hash_check" value="abc" />
                <input type="hidden" name="cat" value="23" />
                <input type="hidden" name="firstnum" value="2783602" />
              </form>
            </body></html>
        """.trimIndent()

        assertNull(parser.parse(html))
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) { "Fixture not found: $name" }.readText()
}
