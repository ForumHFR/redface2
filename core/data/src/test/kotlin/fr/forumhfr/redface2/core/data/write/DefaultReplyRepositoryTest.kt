package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DefaultReplyRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HfrClient
    private lateinit var repository: DefaultReplyRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val okHttp = OkHttpClient.Builder().build()
        client = HfrClient(
            authenticated = okHttp,
            anonymous = okHttp,
            mutation = okHttp.newBuilder().retryOnConnectionFailure(false).build(),
            baseUrl = server.url("/"),
            ioDispatcher = Dispatchers.Unconfined,
        )
        repository = DefaultReplyRepository(
            hfrClient = client,
            replyFormParser = ReplyFormParser(),
            replySubmitResponseParser = ReplySubmitResponseParser(),
            diagnostics = fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog(),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetchReplyForm hits message_php with the full HFR contract URL`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_reply_form_open_topic.html")))

        val context = ReplyContext(cat = 23, subcat = 550, topicId = 35395, page = 20)
        val form = repository.fetchReplyForm(context)

        assertFalse(form.isAnonymous)
        assertEquals("REDACTED_HASH_CHECK", form.hashCheck)

        val recorded = server.takeRequest()
        val url = recorded.requestUrl
        assertNotNull(url)
        requireNotNull(url)
        assertEquals("message.php", url.pathSegments.first())
        assertEquals("hfr.inc", url.queryParameter("config"))
        assertEquals("23", url.queryParameter("cat"))
        assertEquals("550", url.queryParameter("subcat"))
        assertEquals("35395", url.queryParameter("post"))
        assertEquals("20", url.queryParameter("page"))
        assertEquals("1", url.queryParameter("p"))
        assertEquals("0", url.queryParameter("sondage"))
        assertEquals("0", url.queryParameter("owntopic"))
        assertEquals("0", url.queryParameter("new"))
    }

    @Test
    fun `submitReply posts the full form body to bddpost_php and parses success`() = runTest {
        // GET form ; then success response.
        server.enqueue(MockResponse().setBody(fixture("write_reply_form_open_topic.html")))
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))

        val context = ReplyContext(cat = 23, subcat = 550, topicId = 35395, page = 20)
        val form = repository.fetchReplyForm(context)
        val result = repository.submitReply(context, form, bbcodeContent = "Hello world.")

        assertTrue("Expected success, got $result", result is ReplySubmitResult.Success)
        val success = result as ReplySubmitResult.Success
        assertEquals(20, success.targetPage)

        // First request was the GET — drop it before inspecting the POST.
        server.takeRequest()
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        val postUrl = recorded.requestUrl
        assertNotNull(postUrl)
        requireNotNull(postUrl)
        assertEquals("bddpost.php", postUrl.pathSegments.first())
        assertEquals("hfr.inc", postUrl.queryParameter("config"))

        val body = parseFormBody(recorded.body.readUtf8())
        assertEquals("REDACTED_HASH_CHECK", body["hash_check"])
        assertEquals("1100", body["verifrequet"])
        assertEquals("Hello world.", body["content_form"])
        assertEquals("23", body["cat"])
        assertEquals("550", body["subcat"])
        assertEquals("35395", body["post"])
        assertEquals("20", body["page"])
        // Simple reply: numreponse / numrep stay empty.
        assertEquals("", body["numreponse"])
        assertEquals("", body["numrep"])
        // password must never reach HFR.
        assertFalse("password must never be transmitted", body.containsKey("password"))
        // sujet is part of the contract.
        assertEquals("Redface 2 — PHASE 2 @ ALPHA", body["sujet"])
    }

    @Test
    fun `submitReply classifies empty message error`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_reply_form_open_topic.html")))
        server.enqueue(MockResponse().setBody(fixture("write_empty_message_error.html")))
        val context = ReplyContext(cat = 23, subcat = 550, topicId = 35395, page = 20)
        val form = repository.fetchReplyForm(context)
        val result = repository.submitReply(context, form, bbcodeContent = "non-blank content from user")
        assertEquals(
            ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage),
            result,
        )
    }

    @Test
    fun `submitReply classifies invalid hash check`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_reply_form_open_topic.html")))
        server.enqueue(MockResponse().setBody(fixture("write_invalid_token_error.html")))
        val context = ReplyContext(cat = 23, subcat = 550, topicId = 35395, page = 20)
        val form = repository.fetchReplyForm(context)
        val result = repository.submitReply(context, form, bbcodeContent = "Hello.")
        assertEquals(
            ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck),
            result,
        )
    }

    @Test
    fun `submitReply classifies anti-flood`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_reply_form_open_topic.html")))
        server.enqueue(MockResponse().setBody(fixture("write_antiflood_error.html")))
        val context = ReplyContext(cat = 23, subcat = 550, topicId = 35395, page = 20)
        val form = repository.fetchReplyForm(context)
        val result = repository.submitReply(context, form, bbcodeContent = "Hello.")
        assertEquals(
            ReplySubmitResult.Failure(ReplyFailureReason.AntiFlood),
            result,
        )
    }

    @Test
    fun `submitReply classifies locked topic`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_reply_form_open_topic.html")))
        server.enqueue(MockResponse().setBody(fixture("write_locked_topic_error.html")))
        val context = ReplyContext(cat = 23, subcat = 550, topicId = 35395, page = 20)
        val form = repository.fetchReplyForm(context)
        val result = repository.submitReply(context, form, bbcodeContent = "Hello.")
        assertEquals(
            ReplySubmitResult.Failure(ReplyFailureReason.TopicLocked),
            result,
        )
    }

    @Test
    fun `submitReply refuses to POST when the form is anonymous`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_reply_anonymous_form.html")))
        val context = ReplyContext(cat = 23, subcat = 550, topicId = 35395, page = 20)
        val form = repository.fetchReplyForm(context)
        assertTrue("Anonymous form must be detected", form.isAnonymous)

        // Only the GET should have been issued so far.
        assertEquals(1, server.requestCount)

        val result = repository.submitReply(context, form, bbcodeContent = "Hello.")
        assertEquals(
            ReplySubmitResult.Failure(ReplyFailureReason.LoginRequired),
            result,
        )
        // No POST should have been issued.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `submitReply refuses to POST a blank body, no network call`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_reply_form_open_topic.html")))
        val context = ReplyContext(cat = 23, subcat = 550, topicId = 35395, page = 20)
        val form = repository.fetchReplyForm(context)
        assertEquals(1, server.requestCount)
        val result = repository.submitReply(context, form, bbcodeContent = "    ")
        assertEquals(
            ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage),
            result,
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `POST omits option fields when toggles are off (default ReplyFormOptions)`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_reply_form_open_topic.html")))
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))
        val context = ReplyContext(cat = 23, subcat = 550, topicId = 35395, page = 20)
        val form = repository.fetchReplyForm(context)
        repository.submitReply(context, form, bbcodeContent = "hi", options = ReplyFormOptions())

        // Drop the GET, inspect POST.
        server.takeRequest()
        val recorded = server.takeRequest()
        val body = parseFormBody(recorded.body.readUtf8())
        // All three option fields must be absent (browser-style "unchecked = not submitted").
        assertFalse("signature must be absent when toggle off", body.containsKey("signature"))
        assertFalse("smiley must be absent when toggle off", body.containsKey("smiley"))
        assertFalse("emaill must be absent when toggle off", body.containsKey("emaill"))
    }

    @Test
    fun `POST emits each option field with value 1 only when its toggle is on`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_reply_form_open_topic.html")))
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))
        val context = ReplyContext(cat = 23, subcat = 550, topicId = 35395, page = 20)
        val form = repository.fetchReplyForm(context)
        repository.submitReply(
            context = context,
            form = form,
            bbcodeContent = "hi",
            options = ReplyFormOptions(
                signatureEnabled = true,
                smileyDisabled = false,
                emailNotificationEnabled = true,
            ),
        )
        server.takeRequest()
        val recorded = server.takeRequest()
        val body = parseFormBody(recorded.body.readUtf8())
        assertEquals("1", body["signature"])
        assertFalse("smiley must stay absent when toggle off", body.containsKey("smiley"))
        assertEquals("1", body["emaill"])
    }

    @Test
    fun `POST emits smiley=1 when smileyDisabled toggle is on`() = runTest {
        // `smileyDisabled` is the only one of the three options where ON = privation
        // (HFR's checkbox label is « Désactiver les smilies »). The wire mapping
        // `smileyDisabled = true` → POST `smiley=1` is the most regression-prone
        // because a future inversion would look semantically opposite to what HFR
        // does. Pin it explicitly here ; the other two « positive » options are
        // covered by `POST emits each option field when on`.
        server.enqueue(MockResponse().setBody(fixture("write_reply_form_open_topic.html")))
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))
        val context = ReplyContext(cat = 23, subcat = 550, topicId = 35395, page = 20)
        val form = repository.fetchReplyForm(context)
        repository.submitReply(
            context = context,
            form = form,
            bbcodeContent = "hi",
            options = ReplyFormOptions(smileyDisabled = true),
        )
        server.takeRequest()
        val recorded = server.takeRequest()
        val body = parseFormBody(recorded.body.readUtf8())
        assertEquals(
            "smileyDisabled=true must POST smiley=1 (HFR « Désactiver » semantics)",
            "1",
            body["smiley"],
        )
        // The two « positive » toggles stay off → must be absent from the POST body.
        assertFalse("signature absent when off", body.containsKey("signature"))
        assertFalse("emaill absent when off", body.containsKey("emaill"))
    }

    @Test
    fun `POST sends MsgIcon equal to HFR checked default and never to a later DOM occurrence`() = runTest {
        // Belt-and-braces : on top of the parser unit test, the repository end-to-
        // end POST must carry `MsgIcon=1` because that is the only `checked` radio
        // in the fixture. Pre-round2 the wire would have shipped `MsgIcon=16`.
        server.enqueue(MockResponse().setBody(fixture("write_reply_form_open_topic.html")))
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))
        val context = ReplyContext(cat = 23, subcat = 550, topicId = 35395, page = 20)
        val form = repository.fetchReplyForm(context)
        repository.submitReply(context, form, bbcodeContent = "hi")
        server.takeRequest()
        val recorded = server.takeRequest()
        val body = parseFormBody(recorded.body.readUtf8())
        assertEquals("1", body["MsgIcon"])
    }

    @Test
    fun `quote GET form carries numrep and ref query params`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_quote_form_test_post.html")))
        val context = ReplyContext(
            cat = 23,
            subcat = 550,
            topicId = 35395,
            page = 20,
            quotedNumreponse = 2784595,
            quoteRef = 0,
        )
        val form = repository.fetchReplyForm(context)
        // ReplyFormParser must have surfaced the prefilled `[quotemsg=…]` block so
        // the editor can hydrate the user's draft. Pin the cited numreponse only ;
        // the second `[quotemsg]` parameter is opaque and may shift across captures.
        assertTrue(
            "initialContent must include the quote header — got ${form.initialContent.take(80)}",
            form.initialContent.startsWith("[quotemsg=2784595,"),
        )

        val recorded = server.takeRequest()
        val url = recorded.requestUrl
        assertNotNull(url)
        requireNotNull(url)
        assertEquals("message.php", url.pathSegments.first())
        assertEquals("23", url.queryParameter("cat"))
        assertEquals("550", url.queryParameter("subcat"))
        assertEquals("35395", url.queryParameter("post"))
        assertEquals("20", url.queryParameter("page"))
        assertEquals("2784595", url.queryParameter("numrep"))
        assertEquals("0", url.queryParameter("ref"))
        assertEquals("1", url.queryParameter("p"))
    }

    @Test
    fun `quote POST sends numrep with cited numreponse and keeps numreponse empty`() = runTest {
        // GET quote form, then enqueue a generic reply-success response (HFR returns
        // the same success page shape for reply and quote — cf. write_quote_success
        // _response.html which mirrors write_reply_success_response.html).
        server.enqueue(MockResponse().setBody(fixture("write_quote_form_test_post.html")))
        server.enqueue(MockResponse().setBody(fixture("write_quote_success_response.html")))
        val context = ReplyContext(
            cat = 23,
            subcat = 550,
            topicId = 35395,
            page = 20,
            quotedNumreponse = 2784595,
            quoteRef = 0,
        )
        val form = repository.fetchReplyForm(context)
        val result = repository.submitReply(
            context = context,
            form = form,
            // Final BBCode after the user edited the prefill — pretend the user
            // appended their own message after the quote block.
            bbcodeContent = form.initialContent + "\n\nMy reply.",
        )
        assertTrue("Quote must succeed like a reply, got $result", result is ReplySubmitResult.Success)

        // Drop the GET, inspect the POST.
        server.takeRequest()
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("bddpost.php", recorded.requestUrl?.pathSegments?.first())

        val body = parseFormBody(recorded.body.readUtf8())
        assertEquals("Quote must echo the cited post via numrep", "2784595", body["numrep"])
        assertEquals("numreponse must stay empty for quote (only edit fills it)", "", body["numreponse"])
        assertEquals("23", body["cat"])
        assertEquals("550", body["subcat"])
        assertEquals("35395", body["post"])
        assertEquals("20", body["page"])
        // password must never reach HFR.
        assertFalse("password must never be transmitted", body.containsKey("password"))
        assertTrue(
            "content_form must carry the user-edited prefill — header preserved",
            body["content_form"]?.startsWith("[quotemsg=2784595,") == true,
        )
    }

    @Test
    fun `simple reply form has empty initialContent and does not GET numrep`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_reply_form_open_topic.html")))
        val context = ReplyContext(cat = 23, subcat = 550, topicId = 35395, page = 20)
        val form = repository.fetchReplyForm(context)
        assertEquals("Reply must not prefill content_form", "", form.initialContent)

        val recorded = server.takeRequest()
        val url = recorded.requestUrl
        requireNotNull(url)
        // The reply contract must NOT carry numrep/ref params (HFR treats their
        // presence as a switch to the quote form).
        assertNull("Reply URL must not carry numrep", url.queryParameter("numrep"))
        assertNull("Reply URL must not carry ref", url.queryParameter("ref"))
    }

    private fun fixture(name: String): String {
        val stream = requireNotNull(
            DefaultReplyRepositoryTest::class.java.classLoader?.getResourceAsStream("fixtures/$name"),
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
