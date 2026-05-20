package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.model.write.EditPostContext
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.write.ReplyFormParser
import fr.forumhfr.redface2.core.parser.write.ReplySubmitResponseParser
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Repository-level tests for the Phase 2D edit-post wire contract. Pins the
 * distinguishing details vs. the reply flow :
 *
 *  - GET targets `message.php?…&numreponse={N}` (reply form omits `numreponse`).
 *  - POST targets `bdd.php?config=hfr.inc` (reply POSTs to `bddpost.php`).
 *  - POST body fills `numreponse` with the edited post and keeps `numrep` empty.
 *  - The `delete=1` checkbox on HFR's edit form is **never** transmitted.
 *  - Edit success classifies through the same `ReplySubmitResult.Success` shape.
 */
class DefaultEditPostRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HfrClient
    private lateinit var diagnostics: DiagnosticsLog
    private lateinit var repository: DefaultEditPostRepository

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
        // Keep a handle on the DiagnosticsLog so individual tests can inspect
        // the buffer (anti-leak assertions in particular need to read every
        // record after a submit, see `success log does not leak…`).
        diagnostics = DiagnosticsLog()
        repository = DefaultEditPostRepository(
            hfrClient = client,
            replyFormParser = ReplyFormParser(),
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
    fun `GET edit form carries numreponse query param and hits message_php`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_edit_form_test_post.html")))
        val context = EditPostContext(
            cat = 23,
            subcat = 550,
            topicId = 35_395,
            page = 20,
            numreponse = 2_784_595,
        )
        val form = repository.fetchEditPostForm(context)
        // initialContent must reflect the post's existing BBCode.
        assertTrue(
            "initialContent must include the test post body — got ${form.initialContent.take(80)}",
            form.initialContent.contains("Test technique Redface2"),
        )
        // Parser-side : `delete` is unchecked in the fixture, so it never lands in hiddenFields.
        assertFalse(form.hiddenFields.containsKey("delete"))

        val recorded = server.takeRequest()
        val url = recorded.requestUrl
        assertNotNull(url)
        requireNotNull(url)
        assertEquals("message.php", url.pathSegments.first())
        assertEquals("23", url.queryParameter("cat"))
        assertEquals("550", url.queryParameter("subcat"))
        assertEquals("35395", url.queryParameter("post"))
        assertEquals("20", url.queryParameter("page"))
        assertEquals("2784595", url.queryParameter("numreponse"))
    }

    @Test
    fun `POST edit hits bdd_php with numreponse set and numrep empty`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_edit_form_test_post.html")))
        server.enqueue(MockResponse().setBody(fixture("write_edit_success_response.html")))
        val context = EditPostContext(
            cat = 23,
            subcat = 550,
            topicId = 35_395,
            page = 20,
            numreponse = 2_784_595,
        )
        val form = repository.fetchEditPostForm(context)
        val result = repository.submitEditPost(
            context = context,
            form = form,
            bbcodeContent = "Edited body",
            options = ReplyFormOptions(),
        )
        assertTrue("Edit success expected — got $result", result is ReplySubmitResult.Success)

        // Drop the GET, inspect POST.
        server.takeRequest()
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("bdd.php", recorded.requestUrl?.pathSegments?.first())

        val body = parseFormBody(recorded.body.readUtf8())
        assertEquals("Edited body", body["content_form"])
        assertEquals("Edit must echo the post via numreponse", "2784595", body["numreponse"])
        assertEquals("numrep stays empty (only quote uses it)", "", body["numrep"])
        assertEquals("23", body["cat"])
        assertEquals("550", body["subcat"])
        assertEquals("35395", body["post"])
        assertEquals("20", body["page"])
        assertFalse("password must never be transmitted", body.containsKey("password"))
        assertFalse(
            "delete=1 must never be transmitted on the edit MVP — destructive flow is out of scope",
            body.containsKey("delete"),
        )
    }

    @Test
    fun `POST emits option fields only when the matching toggle is on`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_edit_form_test_post.html")))
        server.enqueue(MockResponse().setBody(fixture("write_edit_success_response.html")))
        val context = EditPostContext(
            cat = 23,
            subcat = 550,
            topicId = 35_395,
            page = 20,
            numreponse = 2_784_595,
        )
        val form = repository.fetchEditPostForm(context)
        repository.submitEditPost(
            context = context,
            form = form,
            bbcodeContent = "Edited body",
            options = ReplyFormOptions(signatureEnabled = true, smileyDisabled = true),
        )
        server.takeRequest()
        val recorded = server.takeRequest()
        val body = parseFormBody(recorded.body.readUtf8())
        assertEquals("1", body["signature"])
        assertEquals("1", body["smiley"])
        assertFalse("emaill absent when toggle off", body.containsKey("emaill"))
    }

    @Test
    fun `success log does not leak numreponse via refreshUrl or t-anchor`() = runTest {
        // The HFR edit success refresh URL embeds the edited post via
        // `#t{numreponse}` (cf. write_edit_success_response.html which
        // anchors `#t2784595`). The diagnostics buffer is user-visible in
        // the alpha panel — leaking `numreponse` there would defeat the
        // « numreponse jamais en clair » contract from the round 1 review.
        // We assert structurally : after a real submit-success against the
        // fixture, no record carries the literal id nor the anchor / path.
        server.enqueue(MockResponse().setBody(fixture("write_edit_form_test_post.html")))
        server.enqueue(MockResponse().setBody(fixture("write_edit_success_response.html")))
        val context = EditPostContext(
            cat = 23,
            subcat = 550,
            topicId = 35_395,
            page = 20,
            numreponse = 2_784_595,
        )
        val form = repository.fetchEditPostForm(context)
        repository.submitEditPost(
            context = context,
            form = form,
            bbcodeContent = "Edited body",
            options = ReplyFormOptions(),
        )

        val records = diagnostics.entries.value
        // Each forbidden token gets its own dedicated assertion so a regression
        // points to the exact failure mode (raw id vs anchor vs URL path).
        listOf(
            "2784595",
            "#t2784595",
            "refreshUrl=/hfr/",
        ).forEach { forbidden ->
            assertFalse(
                "Diagnostics buffer must not contain '$forbidden' — records: ${records.map { it.message }}",
                records.any { it.message.contains(forbidden) },
            )
        }
        // Sanity : the new INFO line must still tell us the submit succeeded
        // (otherwise we'd be silently hiding all post-submit signal).
        assertTrue(
            "At least one record must surface the edit success",
            records.any { it.message.contains("POST edit Success") },
        )
    }

    private fun fixture(name: String): String {
        val stream = requireNotNull(
            DefaultEditPostRepositoryTest::class.java.classLoader?.getResourceAsStream("fixtures/$name"),
        ) { "Fixture not found: fixtures/$name" }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun parseFormBody(body: String): Map<String, String> =
        body.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val (key, value) = pair.split('=', limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
                URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            }
}
