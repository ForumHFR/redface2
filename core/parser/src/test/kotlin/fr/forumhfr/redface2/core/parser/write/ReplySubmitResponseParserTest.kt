package fr.forumhfr.redface2.core.parser.write

import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplySubmitResponseParserTest {

    private val parser = ReplySubmitResponseParser()

    @Test
    fun `success response extracts refresh URL and target page`() {
        val html = readFixture("write_reply_success_response.html")
        val result = parser.parse(html)
        assertTrue("Expected success, got $result", result is ReplySubmitResult.Success)
        val success = result as ReplySubmitResult.Success
        val refreshUrl = success.refreshUrl
        assertNotNull("Refresh URL must be parsed", refreshUrl)
        requireNotNull(refreshUrl)
        // The fixture refreshes to …/sujet_35395_20.htm#bas — page 20.
        assertEquals(20, success.targetPage)
        assertTrue(refreshUrl.contains("sujet_35395_20"))
        assertTrue(refreshUrl.endsWith("#bas"))
        // Issue #200 — plain reply anchors `#bas`, so the parser cannot extract a numreponse
        // and the topic screen falls back to scrolling to the end of the refreshed page.
        assertNull(success.numreponse)
    }

    @Test
    fun `edit success uses the matching French sentence and surfaces the refresh URL`() {
        // Phase 2D (#147) : the edit response carries `Votre message a été
        // édité avec succès !` and a refresh URL whose anchor is `#t{numreponse}`
        // (whereas reply's refresh anchors `#bas`). The shape of the refresh
        // header is otherwise identical, so the existing `parseSuccess` path
        // pulls `targetPage` from `sujet_{topic}_{page}`.
        val html = readFixture("write_edit_success_response.html")
        val result = parser.parse(html)
        assertTrue("edit success must classify as Success — got $result", result is ReplySubmitResult.Success)
        val success = result as ReplySubmitResult.Success
        assertEquals(20, success.targetPage)
        val refreshUrl = requireNotNull(success.refreshUrl) { "refreshUrl must be present" }
        assertTrue("refresh URL must anchor on the edited post", refreshUrl.contains("#t2784595"))
        // Issue #200 — edit anchors `#t{numreponse}`, so the parser surfaces the numreponse
        // and the navigation host can scroll to the edited post after the force refresh.
        assertEquals(2_784_595, success.numreponse)
    }

    @Test
    fun `quote success extracts the new post numreponse from the URL fragment`() {
        // Issue #200 — quote anchors `#t{numreponse}` (the freshly-created quote post)
        // so the topic screen can scroll directly to it after the force refresh.
        val html = readFixture("write_quote_success_response.html")
        val result = parser.parse(html)
        assertTrue("quote success must classify as Success — got $result", result is ReplySubmitResult.Success)
        val success = result as ReplySubmitResult.Success
        // The fixture refreshes to …redface2-temporaire-bbcode-sujet_148750_1.htm#t2523833
        assertEquals(1, success.targetPage)
        assertEquals(2_523_833, success.numreponse)
    }

    @Test
    fun `edit first post success extracts the FP numreponse from the URL fragment`() {
        // Issue #200 — edit-FP anchors `#t{numreponse}` on the FP id so the topic screen
        // scrolls back to the edited first post after the force refresh.
        val html = readFixture("write_edit_first_post_success_response.html")
        val result = parser.parse(html)
        assertTrue("edit FP success must classify as Success — got $result", result is ReplySubmitResult.Success)
        val success = result as ReplySubmitResult.Success
        // The fixture refreshes to …redface2-temporaire-ecriture-sujet_148749_1.htm#t2523829
        assertEquals(1, success.targetPage)
        assertEquals(2_523_829, success.numreponse)
    }

    @Test
    fun `empty message error is classified`() {
        val html = readFixture("write_empty_message_error.html")
        val result = parser.parse(html)
        assertEquals(
            ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage),
            result,
        )
    }

    @Test
    fun `invalid hash check is classified, even on the bare text response variant`() {
        val html = readFixture("write_invalid_token_error.html")
        val result = parser.parse(html)
        assertEquals(
            ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck),
            result,
        )
    }

    @Test
    fun `anti-flood error is classified`() {
        val html = readFixture("write_antiflood_error.html")
        val result = parser.parse(html)
        assertEquals(
            ReplySubmitResult.Failure(ReplyFailureReason.AntiFlood),
            result,
        )
    }

    @Test
    fun `locked topic error is classified`() {
        val html = readFixture("write_locked_topic_error.html")
        val result = parser.parse(html)
        assertEquals(
            ReplySubmitResult.Failure(ReplyFailureReason.TopicLocked),
            result,
        )
    }

    @Test
    fun `unknown response falls back to Unknown reason`() {
        val html = "<html><body>Page non documentée</body></html>"
        val result = parser.parse(html)
        assertEquals(
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown),
            result,
        )
    }

    @Test
    fun `success without parsable page leaves targetPage null but keeps refreshUrl`() {
        // Stripped-down success: meta refresh present but URL does not match the
        // sujet_X_Y pattern (HFR sometimes returns the front page after a flood).
        val html = """
            <html><head>
              <meta http-equiv="Refresh" content="1; url=/hfr/Programmation/Divers-6/liste_sujet-1.htm" />
            </head><body>
              <div class="hop">Votre réponse a été postée avec succès !</div>
            </body></html>
        """.trimIndent()
        val result = parser.parse(html) as ReplySubmitResult.Success
        assertNotNull(result.refreshUrl)
        assertNull(result.targetPage)
        // No `#t{N}` fragment either — the `liste_sujet` URL doesn't carry a post anchor.
        assertNull(result.numreponse)
    }

    private fun readFixture(name: String): String {
        val stream = requireNotNull(
            ReplySubmitResponseParserTest::class.java.classLoader?.getResourceAsStream("fixtures/$name"),
        ) { "Fixture not found: fixtures/$name" }
        return stream.bufferedReader().use { it.readText() }
    }
}
