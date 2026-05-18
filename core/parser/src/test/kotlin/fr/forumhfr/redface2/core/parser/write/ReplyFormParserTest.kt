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
    fun `simple reply form has an empty initialContent`() {
        val html = readFixture("write_reply_form_open_topic.html")
        val form = parser.parse(html).getOrThrow()
        assertEquals("Reply-simple form must not prefill content_form", "", form.initialContent)
    }

    @Test
    fun `quote form initialContent carries the prefilled quotemsg block`() {
        val html = readFixture("write_quote_form_test_post.html")
        val form = parser.parse(html).getOrThrow()

        // HFR prefilled `<textarea name="content_form">` with the cited block. The
        // first parameter inside `[quotemsg=…]` is the cited numreponse (2784595 in
        // this capture). The second parameter is opaque (server-controlled position
        // id, never recompute client-side), so we don't pin its exact value here ;
        // we only assert that the prefix lands and the closing tag is present.
        assertTrue(
            "initialContent must start with the cited numreponse — got ${form.initialContent.take(80)}",
            form.initialContent.startsWith("[quotemsg=2784595,"),
        )
        assertTrue(
            "initialContent must close the quotemsg block — got ${form.initialContent.takeLast(40)}",
            form.initialContent.contains("[/quotemsg]"),
        )
    }

    @Test
    fun `quote form with rich BBCode preserves the prefilled markup verbatim`() {
        val html = readFixture("write_quote_form_bbcode_rich.html")
        val form = parser.parse(html).getOrThrow()

        // The bbcode_rich fixture is a temporary topic post containing several BBCode
        // tags ; HFR ships it back inside the quote prefill exactly as it was emitted.
        // The capture's `[quotemsg=2523833,1,1214571]` header is fixed across replays
        // (it's the cited post, not the position), so we can pin it.
        assertTrue(
            "Quote header must reference the cited post — got ${form.initialContent.take(80)}",
            form.initialContent.startsWith("[quotemsg=2523833,1,1214571]"),
        )
        // A handful of BBCode markers that the rich fixture is known to carry. Pinning
        // the exhaustive list would make the test fragile on a future re-capture ; we
        // pick three representative markers that prove the verbatim payload survived
        // Jsoup's textarea decoding (wholeText() must not collapse them).
        listOf("[b]", "[/b]", "[i]").forEach { marker ->
            assertTrue(
                "Quote prefill must include BBCode marker '$marker' verbatim",
                form.initialContent.contains(marker),
            )
        }
        assertTrue(
            "Quote prefill must close the citation block",
            form.initialContent.contains("[/quotemsg]"),
        )
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
