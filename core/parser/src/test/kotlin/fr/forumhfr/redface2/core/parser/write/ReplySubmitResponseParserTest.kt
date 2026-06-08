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
        // #206 — topic id is the first integer of `sujet_{topicId}_{page}`.
        assertEquals(35_395, success.topicId)
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
        // #206 — same `sujet_{topicId}_{page}` segment carries the topic id for
        // reply/quote/edit-style refresh URLs.
        assertEquals(148_750, success.topicId)
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
        // #206 — edit-FP also uses the `sujet_{topicId}_{page}#t{N}` shape, so topicId
        // extraction stays pinned on real HFR bytes for topic-refresh flows.
        assertEquals(148_749, success.topicId)
    }

    @Test
    fun `create-topic success is classified via its own marker and exposes no topic id`() {
        // #214 — real capture (`write_create_topic_success_response.html`, 2026-05-29).
        // HFR's create-topic success uses « Votre message a été posté avec succès ! » —
        // distinct from reply/edit, so before the fix it fell through to Unknown and the app
        // showed an error although the topic WAS created. The refresh points to the category
        // listing (`…/liste_sujet-1.htm`), NOT to the new topic, so HFR returns no topic id :
        // topicId / targetPage / numreponse are all null and the caller lands on the listing.
        val html = readFixture("write_create_topic_success_response.html")
        val result = parser.parse(html)
        assertTrue("create-topic success must classify as Success — got $result", result is ReplySubmitResult.Success)
        val success = result as ReplySubmitResult.Success
        val refreshUrl = requireNotNull(success.refreshUrl) { "refreshUrl must be present" }
        assertTrue("refresh lands on the category listing", refreshUrl.contains("liste_sujet"))
        assertNull("create success has no thread segment → no topic id", success.topicId)
        assertNull(success.targetPage)
        assertNull(success.numreponse)
    }

    @Test
    fun `delete post success is classified and refreshes to the topic (#292)`() {
        // #292 — normal-post delete. Shared marker « Message effacé avec succès ! ». HFR refreshes
        // to the topic (`sujet_{id}_{page}.htm`), so the thread segment / topic id is recoverable.
        val html = readFixture("write_delete_post_success_response.html")
        val result = parser.parse(html)
        assertTrue("delete success must classify as Success — got $result", result is ReplySubmitResult.Success)
        val success = result as ReplySubmitResult.Success
        val refreshUrl = requireNotNull(success.refreshUrl) { "refreshUrl must be present" }
        assertTrue("normal-post delete refreshes to the topic", refreshUrl.contains("sujet_"))
        assertNotNull("topic id is recoverable on a normal-post delete", success.topicId)
    }

    @Test
    fun `delete whole-topic success refreshes to the listing and exposes no topic id (#292)`() {
        // #292 — first-post delete removes the whole topic: HFR refreshes to the sub-category
        // listing (`liste_sujet-1.htm`), so there is no thread segment → topicId is null.
        // `DefaultDeletePostRepository` reads that null to flag a whole-topic deletion.
        val html = readFixture("write_delete_topic_success_response.html")
        val result = parser.parse(html)
        assertTrue("delete-topic success must classify as Success — got $result", result is ReplySubmitResult.Success)
        val success = result as ReplySubmitResult.Success
        val refreshUrl = requireNotNull(success.refreshUrl) { "refreshUrl must be present" }
        assertTrue("whole-topic delete refreshes to the listing", refreshUrl.contains("liste_sujet"))
        assertNull("no thread segment on a listing refresh → no topic id", success.topicId)
    }

    @Test
    fun `a refresh page without any known success marker is not a false success`() {
        // #214 — classification keys on the explicit success sentences (reply/edit/create),
        // NOT on the mere presence of a `<meta refresh>`. A page that redirects somewhere but
        // carries no known success marker must stay Unknown (defensive : guards against a
        // future deploy where a refresh alone would be over-trusted). Listing refresh variant.
        val html = """
            <html><head>
              <meta http-equiv="Refresh" content="1; url=/hfr/cat/liste_sujet_1_2.htm" />
            </head><body><div class="hop">Opération effectuée.</div></body></html>
        """.trimIndent()
        assertEquals(
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown),
            parser.parse(html),
        )
    }

    @Test
    fun `a bare home refresh without a success marker stays Unknown`() {
        // #214 — same guard, home redirect : no known success sentence → Unknown, never a
        // false success.
        val html = """
            <html><head>
              <meta http-equiv="Refresh" content="1; url=/" />
            </head><body><div class="hop">Opération effectuée.</div></body></html>
        """.trimIndent()
        assertEquals(
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown),
            parser.parse(html),
        )
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
        // #206 — `liste_sujet` has no `sujet_{topicId}_{page}` segment, so topicId is null
        // and the create-topic navigation host falls back to the category refresh path.
        assertNull(result.topicId)
    }

    @Test
    fun `slug containing a listing token does not get mistaken for a thread segment`() {
        // #206 hardening — a hypothetical `liste_sujet_1_2.htm` listing URL must NOT be
        // parsed as a thread segment. The `(?<![a-z_])` lookbehind on `sujet_` rejects the
        // `liste_sujet_` form (preceded by an underscore) while still matching the real
        // `/…-sujet_N_M.htm` (preceded by `-` or `/`). This is a regex-robustness pin, not
        // a contract fixture — the real reply/quote/edit-FP fixtures above prove the shape.
        val html = """
            <html><head>
              <meta http-equiv="Refresh" content="1; url=/hfr/cat/liste_sujet_1_2.htm" />
            </head><body>
              <div class="hop">Votre réponse a été postée avec succès !</div>
            </body></html>
        """.trimIndent()
        val success = parser.parse(html) as ReplySubmitResult.Success
        assertNull("liste_sujet_ must not be read as a thread topic id", success.topicId)
        assertNull(success.targetPage)
    }

    private fun readFixture(name: String): String {
        val stream = requireNotNull(
            ReplySubmitResponseParserTest::class.java.classLoader?.getResourceAsStream("fixtures/$name"),
        ) { "Fixture not found: fixtures/$name" }
        return stream.bufferedReader().use { it.readText() }
    }
}
