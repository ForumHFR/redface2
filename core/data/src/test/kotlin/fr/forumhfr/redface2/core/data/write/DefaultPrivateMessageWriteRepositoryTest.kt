package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.model.write.PrivateMessageReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #301 — private-message reply repository. The decisive contract is the POST body shape: a private
 * conversation carries `cat=prive` (String), `post={threadId}`, `subcat=0` and a server-prefilled
 * `numrep` inside the form's hidden fields, and [DefaultPrivateMessageWriteRepository] must forward
 * them verbatim — never re-asserting `cat`/`numrep` from a typed context the way the topic reply
 * repository does. Tests use a real [HfrClient] over a [MockWebServer], mirroring
 * `DefaultReplyRepositoryTest`.
 */
class DefaultPrivateMessageWriteRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HfrClient
    private lateinit var repository: DefaultPrivateMessageWriteRepository

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
        repository = DefaultPrivateMessageWriteRepository(
            hfrClient = client,
            replyFormParser = ReplyFormParser(),
            replySubmitResponseParser = ReplySubmitResponseParser(),
            diagnostics = DiagnosticsLog(),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val context = PrivateMessageReplyContext(threadId = 3195237, page = 1)

    @Test
    fun `fetchReplyForm GETs the conversation page on forum2_php with cat=prive`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("private_message_thread.html")))

        val form = repository.fetchReplyForm(context)

        assertFalse(form.isAnonymous)
        assertEquals("prive", form.hiddenFields["cat"])

        val url = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("forum2.php", url.pathSegments.first())
        assertEquals("prive", url.queryParameter("cat"))
        assertEquals("3195237", url.queryParameter("post"))
        assertEquals("1", url.queryParameter("page"))
    }

    @Test
    fun `submitReply forwards cat=prive, post, numrep, subcat verbatim and never overwrites them`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("private_message_thread.html")))
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))

        val form = repository.fetchReplyForm(context)
        val result = repository.submitReply(context, form, bbcodeContent = "Coucou en privé.")

        assertTrue("Expected success, got $result", result is ReplySubmitResult.Success)

        server.takeRequest() // drop the GET
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("bddpost.php", requireNotNull(recorded.requestUrl).pathSegments.first())

        val body = parseFormBody(recorded.body.readUtf8())
        // The three private-message contract fields, carried verbatim from the form.
        assertEquals("prive", body["cat"])
        assertEquals("3195237", body["post"])
        // numrep is the conversation's prefilled last-post id — must NOT be blanked (the topic
        // repository would set it to "" for a simple reply; the MP repository must not).
        assertEquals("1980677227", body["numrep"])
        assertEquals("0", body["subcat"])
        assertEquals("1", body["page"])
        // The fields HFR validates, added by the repository.
        assertEquals("0", body["hash_check"])
        assertEquals("1100", body["verifrequet"])
        assertEquals("Coucou en privé.", body["content_form"])
        // password must never reach HFR.
        assertFalse("password must never be transmitted", body.containsKey("password"))
    }

    @Test
    fun `submitReply adds signature only when the option is enabled`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("private_message_thread.html")))
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))

        val form = repository.fetchReplyForm(context)
        repository.submitReply(
            context,
            form,
            bbcodeContent = "Avec signature.",
            options = ReplyFormOptions(signatureEnabled = true),
        )

        server.takeRequest()
        val body = parseFormBody(server.takeRequest().body.readUtf8())
        assertEquals("1", body["signature"])
    }

    @Test
    fun `submitReply omits signature when the option is disabled`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("private_message_thread.html")))
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))

        val form = repository.fetchReplyForm(context)
        // Default options = all off ; the form's hidden `signature=1` must not leak back as a
        // browser would omit an unchecked field.
        repository.submitReply(context, form, bbcodeContent = "Sans signature.")

        server.takeRequest()
        val body = parseFormBody(server.takeRequest().body.readUtf8())
        assertFalse("signature must be omitted when disabled", body.containsKey("signature"))
    }

    @Test
    fun `submitReply never relays a password hidden field`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))

        val form = ReplyForm(
            hashCheck = "h",
            sujet = "s",
            hiddenFields = mapOf("cat" to "prive", "post" to "3195237", "password" to "secret"),
            isAnonymous = false,
        )
        repository.submitReply(context, form, bbcodeContent = "hi")

        val body = parseFormBody(server.takeRequest().body.readUtf8())
        assertFalse("password must never be transmitted", body.containsKey("password"))
        assertEquals("prive", body["cat"])
    }

    @Test
    fun `submitReply refuses a blank body without any network call`() = runTest {
        val form = ReplyForm(hashCheck = "h", sujet = "s", hiddenFields = emptyMap(), isAnonymous = false)

        val result = repository.submitReply(context, form, bbcodeContent = "   ")

        assertEquals(ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage), result)
        assertEquals("no POST should be sent for a blank body", 0, server.requestCount)
    }

    @Test
    fun `submitReply refuses to POST an anonymous form`() = runTest {
        val form = ReplyForm(hashCheck = "h", sujet = "s", hiddenFields = emptyMap(), isAnonymous = true)

        val result = repository.submitReply(context, form, bbcodeContent = "hi")

        assertEquals(ReplySubmitResult.Failure(ReplyFailureReason.LoginRequired), result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `submitReply classifies the empty-message error response`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_empty_message_error.html")))

        val form = ReplyForm(
            hashCheck = "h",
            sujet = "s",
            hiddenFields = mapOf("cat" to "prive"),
            isAnonymous = false,
        )
        val result = repository.submitReply(context, form, bbcodeContent = "non-blank content")

        assertEquals(ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage), result)
    }

    private fun fixture(name: String): String {
        val stream = requireNotNull(
            DefaultPrivateMessageWriteRepositoryTest::class.java.classLoader?.getResourceAsStream("fixtures/$name"),
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
