package fr.forumhfr.redface2.core.parser.write

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyFormParserTest {

    private val parser = ReplyFormParser()

    @Test
    fun `extracts hash_check, hidden fields and sujet from the authenticated reply form`() {
        val html = readFixture("write_reply_form_open_topic.html")
        val form = parser.parse(html).getOrThrow()

        assertFalse("Authenticated form must not be flagged as anonymous", form.isAnonymous)
        assertEquals("REDACTED_HASH_CHECK", form.hashCheck)
        assertEquals("Redface 2 — PHASE 2 @ ALPHA", form.sujet)

        // Static contract fields HFR expects to receive verbatim on POST.
        assertEquals("23", form.hiddenFields["cat"])
        assertEquals("550", form.hiddenFields["subcat"])
        assertEquals("35395", form.hiddenFields["post"])
        assertEquals("20", form.hiddenFields["page"])
        assertEquals("1100", form.hiddenFields["verifrequet"])
        assertEquals("hfr.inc", form.hiddenFields["config"])
        assertEquals("cache", form.hiddenFields["cache"])
        assertEquals("0", form.hiddenFields["sond"])
        assertEquals("0", form.hiddenFields["owntopic"])
        assertEquals("0", form.hiddenFields["new"])

        // numreponse / numrep are empty in a simple reply.
        assertEquals("", form.hiddenFields["numreponse"])
        assertEquals("", form.hiddenFields["numrep"])

        // Authenticated form carries the user's pseudo as a visible field — we forward it.
        assertEquals("xatelitte", form.hiddenFields["pseudo"])

        // Sensitive: password must never be forwarded, even when blank in source.
        assertFalse("password must be stripped from hiddenFields", form.hiddenFields.containsKey("password"))
    }

    @Test
    fun `flags the anonymous composer and skips the pseudo field`() {
        val html = readFixture("write_reply_anonymous_form.html")
        val form = parser.parse(html).getOrThrow()

        assertTrue("Anonymous form must be flagged", form.isAnonymous)
        assertFalse("pseudo must be skipped on anonymous form", form.hiddenFields.containsKey("pseudo"))
        assertFalse("password must be skipped on anonymous form", form.hiddenFields.containsKey("password"))

        // hash_check still present even on anonymous form — we just refuse to use it.
        assertNotNull(form.hashCheck)
        assertEquals("REDACTED_HASH_CHECK", form.hashCheck)
    }

    @Test
    fun `forced-form on locked topic still parses but the post will be refused server-side`() {
        // HFR serves the composer when you craft message.php URL on a locked topic, but
        // the POST is rejected. The parser must still produce a usable form (so the
        // caller can attempt the POST and surface the typed "TopicLocked" error).
        val html = readFixture("write_reply_locked_topic_forced_form.html")
        val form = parser.parse(html).getOrThrow()
        assertNotNull(form.hashCheck)
        assertEquals("REDACTED_HASH_CHECK", form.hashCheck)
    }

    @Test
    fun `fails fast when hash_check is missing`() {
        val html = """<html><body><form action="/bddpost.php?config=hfr.inc" method="post">
            <input type="hidden" name="cat" value="23" />
            <input type="hidden" name="post" value="1" />
        </form></body></html>"""
        val result = parser.parse(html)
        assertTrue(result.isFailure)
    }

    @Test
    fun `fails when no bddpost form is present at all`() {
        val html = """<html><body><p>unrelated page</p></body></html>"""
        val result = parser.parse(html)
        assertTrue(result.isFailure)
    }

    private fun readFixture(name: String): String {
        val stream = requireNotNull(
            ReplyFormParserTest::class.java.classLoader?.getResourceAsStream("fixtures/$name"),
        ) { "Fixture not found: fixtures/$name" }
        return stream.bufferedReader().use { it.readText() }
    }
}
