package fr.forumhfr.redface2.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chantier C (#546) — extraction of the intra-topic search form (`transsearch.php`) hidden fields
 * from existing topic-page fixtures. The `transsearch` RESPONSE is never asserted here : it was
 * NEVER captured live, so there is no response fixture to round-trip (see the class KDoc of
 * `TopicSearchFormParser`). These tests cover only the request-side extraction the parser owns.
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
        assertTrue(form.firstnum > 0)
        assertEquals("", form.hashCheck)
        assertFalse("empty hash_check ⇒ search not available", form.canSearch)
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
        // A transsearch form without `firstnum` is unusable for the search anchor : degrade to null.
        val html = """
            <html><body>
              <form action="/transsearch.php" method="post">
                <input type="hidden" name="hash_check" value="abc" />
                <input type="hidden" name="post" value="35395" />
                <input type="hidden" name="cat" value="23" />
              </form>
            </body></html>
        """.trimIndent()

        assertNull(parser.parse(html))
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) { "Fixture not found: $name" }.readText()
}
