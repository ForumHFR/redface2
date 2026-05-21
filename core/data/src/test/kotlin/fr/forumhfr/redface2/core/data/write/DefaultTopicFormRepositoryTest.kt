package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.model.write.EditFirstPostContext
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.write.ReplySubmitResponseParser
import fr.forumhfr.redface2.core.parser.write.TopicFormParser
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Repository-level tests for Phase 2D #148 (edit first post). Pins the wire
 * contract :
 *
 *  - GET targets `message.php?…&numreponse={firstPost}`.
 *  - POST targets `bdd.php?config=hfr.inc`.
 *  - POST body carries the modified subject + content, the selected subcat,
 *    `numreponse` of the FP, and `numrep=""`.
 *  - `password` and `delete` never reach the wire (the FP form ships a
 *    « Effacer l'intégralité du sujet » checkbox).
 *  - Diagnostics never leak `hash_check`, the BBCode content, or the
 *    `numreponse` via the refresh URL.
 */
class DefaultTopicFormRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HfrClient
    private lateinit var diagnostics: DiagnosticsLog
    private lateinit var repository: DefaultTopicFormRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val okHttp = OkHttpClient.Builder().build()
        client = HfrClient(
            authenticated = okHttp,
            anonymous = okHttp,
            baseUrl = server.url("/"),
            ioDispatcher = Dispatchers.Unconfined,
        )
        diagnostics = DiagnosticsLog()
        repository = DefaultTopicFormRepository(
            hfrClient = client,
            topicFormParser = TopicFormParser(),
            replySubmitResponseParser = ReplySubmitResponseParser(),
            diagnostics = diagnostics,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `GET FP form hits message_php with numreponse and full id tuple`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_edit_first_post_form.html")))
        val context = EditFirstPostContext(
            cat = 10,
            subcat = 388,
            topicId = 148_749,
            page = 1,
            numreponse = 2_523_829,
        )
        val form = repository.fetchEditFirstPostForm(context)
        assertEquals("[Redface2] Topic temporaire de test écriture", form.subject)
        assertEquals(388, form.selectedSubcat)

        val recorded = server.takeRequest()
        val url = recorded.requestUrl!!
        assertEquals("message.php", url.pathSegments.first())
        assertEquals("10", url.queryParameter("cat"))
        assertEquals("148749", url.queryParameter("post"))
        assertEquals("388", url.queryParameter("subcat"))
        assertEquals("1", url.queryParameter("page"))
        assertEquals("2523829", url.queryParameter("numreponse"))
    }

    @Test
    fun `POST FP edit hits bdd_php with sujet, subcat, numreponse rempli and numrep empty`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_edit_first_post_form.html")))
        server.enqueue(MockResponse().setBody(fixture("write_edit_first_post_success_response.html")))
        val context = EditFirstPostContext(
            cat = 10,
            subcat = 388,
            topicId = 148_749,
            page = 1,
            numreponse = 2_523_829,
        )
        val form = repository.fetchEditFirstPostForm(context)
        val result = repository.submitEditFirstPost(
            context = context,
            form = form,
            subject = "[Redface2] Sujet renommé",
            bbcodeContent = "Edited FP body",
            selectedSubcat = 388,
            options = ReplyFormOptions(signatureEnabled = true),
        )
        assertTrue("FP edit must classify as Success — got $result", result is ReplySubmitResult.Success)

        server.takeRequest() // drop GET
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("bdd.php", recorded.requestUrl!!.pathSegments.first())

        val body = parseFormBody(recorded.body.readUtf8())
        assertEquals("[Redface2] Sujet renommé", body["sujet"])
        assertEquals("Edited FP body", body["content_form"])
        assertEquals("388", body["subcat"])
        assertEquals("2523829", body["numreponse"])
        assertEquals("numrep stays empty for an FP edit", "", body["numrep"])
        assertEquals("10", body["cat"])
        assertEquals("148749", body["post"])
        assertEquals("1", body["page"])
        assertEquals("1", body["signature"])
        assertFalse("password must never reach HFR", body.containsKey("password"))
        assertFalse(
            "delete must never reach HFR on an FP edit — destructive flow is out of scope",
            body.containsKey("delete"),
        )
        // Poll fields : the fixture has no active sondage, so nothing poll-
        // related should be on the wire. Before the poll-source-of-truth fix,
        // empty `textreponse0..10` and date inputs were leaking through
        // `hiddenFields` even though no sondage was active.
        listOf(
            "have_sondage", "textreponse0", "textreponse1", "textreponse5", "textreponse10",
            "allowvisitor", "max_votes", "jour", "mois", "annee", "heure", "minute",
        ).forEach { name ->
            assertFalse(
                "$name must not be POSTed when no sondage is active — got body=$body",
                body.containsKey(name),
            )
        }
    }

    @Test
    fun `synthetic poll fields are forwarded exactly once through TopicPollForm`() = runTest {
        // Build a tiny synthetic form whose only purpose is to exercise the
        // poll-passthrough branch (the real fixture has have_sondage unchecked).
        // The point of this test is to prove that when TopicPollForm.fields
        // carries poll keys, the wire body contains them once — not twice and
        // not zero times.
        val syntheticForm = """<html><body><form action="bdd.php?config=hfr.inc">
            <input type="hidden" name="hash_check" value="HASH" />
            <input type="hidden" name="pseudo" value="me" />
            <input type="text" name="sujet" value="Topic with poll" />
            <textarea name="content_form">Body</textarea>
            <select name="subcat"><option value="388" selected="selected">Divers</option></select>
            <input type="checkbox" name="have_sondage" value="1" checked="checked" />
            <input name="textreponse0" value="Yes" />
            <input name="textreponse1" value="No" />
            <select name="max_votes"><option value="2" selected="selected">2</option></select>
            <input type="text" name="jour" value="31" />
            <input type="text" name="mois" value="12" />
            <input type="text" name="annee" value="2026" />
        </form></body></html>"""

        server.enqueue(MockResponse().setBody(syntheticForm))
        server.enqueue(MockResponse().setBody(fixture("write_edit_first_post_success_response.html")))

        val context = EditFirstPostContext(
            cat = 10, subcat = 388, topicId = 148_749, page = 1, numreponse = 2_523_829,
        )
        val form = repository.fetchEditFirstPostForm(context)
        assertTrue("Synthetic form must surface an active poll", form.poll.present)
        repository.submitEditFirstPost(
            context = context,
            form = form,
            subject = "Topic with poll",
            bbcodeContent = "Body",
            selectedSubcat = 388,
        )

        server.takeRequest() // GET
        val recorded = server.takeRequest()
        val rawBody = recorded.body.readUtf8()
        val pairs = rawBody.split('&')

        // Each poll key must appear exactly once on the wire.
        listOf("have_sondage=1", "textreponse0=Yes", "textreponse1=No", "max_votes=2",
            "jour=31", "mois=12", "annee=2026").forEach { pair ->
            assertEquals(
                "Poll pair '$pair' must appear exactly once — body=$rawBody",
                1,
                pairs.count { it == pair },
            )
        }
    }

    @Test
    fun `success diagnostics do not leak numreponse via refreshUrl`() = runTest {
        // FP refresh URL anchors `#t{numreponse}` (see
        // write_edit_first_post_success_response.html). Same regression guard
        // as `DefaultEditPostRepositoryTest` — the diagnostics buffer is
        // user-visible in the alpha panel.
        server.enqueue(MockResponse().setBody(fixture("write_edit_first_post_form.html")))
        server.enqueue(MockResponse().setBody(fixture("write_edit_first_post_success_response.html")))
        val context = EditFirstPostContext(
            cat = 10,
            subcat = 388,
            topicId = 148_749,
            page = 1,
            numreponse = 2_523_829,
        )
        val form = repository.fetchEditFirstPostForm(context)
        repository.submitEditFirstPost(
            context = context,
            form = form,
            subject = "[Redface2] Sujet renommé",
            bbcodeContent = "Edited FP body",
            selectedSubcat = 388,
        )

        val records = diagnostics.entries.value
        listOf("2523829", "#t2523829", "refreshUrl=/hfr/").forEach { forbidden ->
            assertFalse(
                "Diagnostics buffer must not contain '$forbidden' — records: ${records.map { it.message }}",
                records.any { it.message.contains(forbidden) },
            )
        }
        assertTrue(
            "At least one record must surface FP submit success",
            records.any { it.message.contains("POST FP edit Success") },
        )
    }

    private fun fixture(name: String): String =
        requireNotNull(
            DefaultTopicFormRepositoryTest::class.java.classLoader?.getResourceAsStream("fixtures/$name"),
        ) { "Fixture not found: fixtures/$name" }.bufferedReader().use { it.readText() }

    private fun parseFormBody(body: String): Map<String, String> =
        body.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val (k, v) = pair.split('=', limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }
}
