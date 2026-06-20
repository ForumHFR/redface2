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

    // #606 — owner manages DT/MultiMP members via a `newdest` override on the reply.

    @Test
    fun `submitReply forwards newdest verbatim when the owner passes no override`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))

        val form = ReplyForm(
            hashCheck = "h",
            sujet = "s",
            hiddenFields = mapOf(
                "cat" to "prive",
                "post" to "4242424",
                "newdest" to "alice, bob, Administration",
            ),
            isAnonymous = false,
        )
        repository.submitReply(context, form, bbcodeContent = "hi") // recipientsOverride defaults to null

        val raw = server.takeRequest().body.readUtf8()
        val body = parseFormBody(raw)
        // No edit → HFR's prefilled member list is reposted unchanged, exactly once.
        assertEquals("alice, bob, Administration", body["newdest"])
        assertEquals(1, raw.split('&').count { it.startsWith("newdest=") })
    }

    @Test
    fun `submitReply ignores a recipientsOverride on a form without newdest (participant)`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))

        // A simple participant's form has NO `newdest` key — the owner guard blocks the override.
        val form = ReplyForm(
            hashCheck = "h",
            sujet = "s",
            hiddenFields = mapOf("cat" to "prive", "post" to "4242424"),
            isAnonymous = false,
        )
        repository.submitReply(
            context,
            form,
            bbcodeContent = "hi",
            recipientsOverride = "alice, intruder",
        )

        val body = parseFormBody(server.takeRequest().body.readUtf8())
        assertFalse("a participant override must never introduce newdest", body.containsKey("newdest"))
    }

    @Test
    fun `submitReply applies the owner override as a single modified newdest field`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))

        val form = ReplyForm(
            hashCheck = "h",
            sujet = "s",
            hiddenFields = mapOf(
                "cat" to "prive",
                "post" to "4242424",
                "newdest" to "alice, bob, Administration",
            ),
            isAnonymous = false,
        )
        repository.submitReply(
            context,
            form,
            bbcodeContent = "hi",
            recipientsOverride = "bob, Bébé Yoda",
        )

        val raw = server.takeRequest().body.readUtf8()
        val body = parseFormBody(raw)
        // The override wins over the prefill, and exactly one `newdest` is emitted (no duplicate).
        assertEquals("bob, Bébé Yoda", body["newdest"])
        assertEquals(1, raw.split('&').count { it.startsWith("newdest=") })
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

    // #301 follow-up — composing a NEW conversation (fixture mp_compose_form.html,
    // captured live 2026-06-11 ; the POST response shape is the shared bddpost.php family).

    @Test
    fun `fetchComposeForm GETs the standalone composer on message_php with cat=prive`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("mp_compose_form.html")))

        val form = repository.fetchComposeForm()

        assertFalse(form.isAnonymous)
        assertEquals("prive", form.hiddenFields["cat"])
        assertEquals("", form.hiddenFields["post"])

        val url = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("message.php", url.pathSegments.first())
        assertEquals("prive", url.queryParameter("cat"))
        assertEquals("", url.queryParameter("dest"))
        assertEquals("0", url.queryParameter("subcat"))
    }

    @Test
    fun `fetchComposeForm forwards the prefilled recipient in the URL`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("mp_compose_form.html")))

        repository.fetchComposeForm(prefilledRecipient = "bozoleclown")

        val url = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("bozoleclown", url.queryParameter("dest"))
    }

    @Test
    fun `submitNewMessage overrides dest and sujet and forwards the composer routing verbatim`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("mp_compose_form.html")))
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))

        val form = repository.fetchComposeForm()
        val result = repository.submitNewMessage(
            form = form,
            recipients = "bozoleclown, Lt Ripley",
            subject = "Test depuis Redface 2",
            bbcodeContent = "Bonjour en privé.",
        )

        assertTrue("Expected success, got $result", result is ReplySubmitResult.Success)

        server.takeRequest() // drop the GET
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("bddpost.php", requireNotNull(recorded.requestUrl).pathSegments.first())

        val raw = recorded.body.readUtf8()
        val body = parseFormBody(raw)
        // User-typed routing overrides the form's (empty) text-input values…
        assertEquals("bozoleclown, Lt Ripley", body["dest"])
        assertEquals("Test depuis Redface 2", body["sujet"])
        assertEquals("Bonjour en privé.", body["content_form"])
        // …and exactly once : the parsed empty prefill must not ride along as a duplicate.
        assertEquals(1, raw.split('&').count { it.startsWith("dest=") })
        assertEquals(1, raw.split('&').count { it.startsWith("sujet=") })
        // New-conversation routing forwarded verbatim from the composer's hidden fields.
        assertEquals("prive", body["cat"])
        assertEquals("", body["post"])
        assertEquals("", body["numrep"])
        assertEquals("", body["numreponse"])
        assertEquals("", body["parents"])
        assertEquals("", body["stickold"])
        assertEquals("SCRUBBED_HASH_CHECK", body["hash_check"])
        assertEquals("1100", body["verifrequet"])
        assertEquals("TestUser", body["pseudo"])
        assertFalse("password must never be transmitted", body.containsKey("password"))
    }

    @Test
    fun `submitNewMessage refuses blank recipients without any network call`() = runTest {
        val form = ReplyForm(hashCheck = "h", sujet = "", hiddenFields = emptyMap(), isAnonymous = false)

        val result = repository.submitNewMessage(
            form = form,
            recipients = "   ",
            subject = "Sujet",
            bbcodeContent = "corps",
        )

        assertEquals(ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage), result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `submitNewMessage refuses a blank subject without any network call`() = runTest {
        val form = ReplyForm(hashCheck = "h", sujet = "", hiddenFields = emptyMap(), isAnonymous = false)

        val result = repository.submitNewMessage(
            form = form,
            recipients = "bozoleclown",
            subject = " ",
            bbcodeContent = "corps",
        )

        assertEquals(ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage), result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `submitNewMessage refuses an anonymous composer form`() = runTest {
        val form = ReplyForm(hashCheck = "h", sujet = "", hiddenFields = emptyMap(), isAnonymous = true)

        val result = repository.submitNewMessage(
            form = form,
            recipients = "bozoleclown",
            subject = "Sujet",
            bbcodeContent = "corps",
        )

        assertEquals(ReplySubmitResult.Failure(ReplyFailureReason.LoginRequired), result)
        assertEquals(0, server.requestCount)
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
