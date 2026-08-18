package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.model.write.PrivateMessageQuote
import fr.forumhfr.redface2.core.model.write.PrivateMessageReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.messages.PrivateMessageReplyLinkParser
import fr.forumhfr.redface2.core.parser.write.ReplyFormParser
import fr.forumhfr.redface2.core.parser.write.ReplySubmitResponseParser
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
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
    private lateinit var diagnostics: DiagnosticsLog

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
        repository = DefaultPrivateMessageWriteRepository(
            hfrClient = client,
            replyFormParser = ReplyFormParser(),
            replyLinkParser = PrivateMessageReplyLinkParser(),
            replySubmitResponseParser = ReplySubmitResponseParser(),
            diagnostics = diagnostics,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val context = PrivateMessageReplyContext(threadId = 3195237, page = 1)

    private val quoteContext = PrivateMessageReplyContext(
        threadId = 3_000_001,
        page = 1,
        quote = PrivateMessageQuote(numreponse = 1_980_000_004, ref = 4),
    )

    @Test
    fun `fetchReplyForm GETs the conversation page then follows its message_php reply link`() = runTest {
        // #612 — two GETs: the conversation page (forum2.php) carries the real « Ajouter une
        // réponse » link, which the repository follows to the dedicated message.php reply form
        // (the only one carrying the owner-only `newdest`). Both responses reuse the thread fixture
        // here — its embedded bddpost.php form is enough for the #301 contract assertions.
        server.enqueue(MockResponse().setBody(fixture("private_message_thread.html")))
        server.enqueue(MockResponse().setBody(fixture("private_message_thread.html")))

        val form = repository.fetchReplyForm(context)

        assertFalse(form.isAnonymous)
        assertEquals("prive", form.hiddenFields["cat"])

        val first = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("forum2.php", first.pathSegments.first())
        assertEquals("prive", first.queryParameter("cat"))
        assertEquals("3195237", first.queryParameter("post"))
        assertEquals("1", first.queryParameter("page"))

        // The followed link is a message.php?cat=prive form — never invented, read off the page.
        val second = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("message.php", second.pathSegments.last())
        assertEquals("prive", second.queryParameter("cat"))
        assertEquals("3195237", second.queryParameter("post"))
    }

    @Test
    fun `private quote uses the typed GET and POSTs the measured body without ref`() = runTest {
        // The GET response is the real #1041 quote fixture. The POST response stays local: this test
        // proves the body emitted by the client, never that HFR accepted a live private message.
        server.enqueue(MockResponse().setBody(fixture("private_message_quote_form.html")))
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))

        val form = repository.fetchReplyForm(quoteContext)
        val result = repository.submitReply(
            context = quoteContext,
            form = form,
            bbcodeContent = form.initialContent,
            options = form.options,
        )

        assertTrue("Expected locally parsed success, got $result", result is ReplySubmitResult.Success)

        // Exactly one GET: a quote never fetches forum2.php and never follows its private href.
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        val getUrl = requireNotNull(get.requestUrl)
        assertEquals("message.php", getUrl.pathSegments.first())
        assertEquals("hfr.inc", getUrl.queryParameter("config"))
        assertEquals("prive", getUrl.queryParameter("cat"))
        assertEquals("3000001", getUrl.queryParameter("post"))
        assertEquals("1980000004", getUrl.queryParameter("numrep"))
        assertEquals("4", getUrl.queryParameter("ref"))
        assertEquals("1", getUrl.queryParameter("page"))
        assertEquals("1", getUrl.queryParameter("p"))
        assertEquals("0", getUrl.queryParameter("subcat"))
        assertEquals("0", getUrl.queryParameter("sondage"))
        assertEquals("0", getUrl.queryParameter("owntopic"))
        assertEquals("0", getUrl.queryParameter("new"))

        val posted = server.takeRequest()
        assertEquals("POST", posted.method)
        assertEquals("the quote flow must contain one GET and one POST only", 2, server.requestCount)
        val body = parseFormBody(posted.body.readUtf8())
        assertEquals("prive", body["cat"])
        assertEquals("3000001", body["post"])
        assertEquals("1980000004", body["numrep"])
        assertEquals("", body["numreponse"])
        assertFalse("ref belongs to the GET/BBCode, never the POST body", body.containsKey("ref"))
        assertEquals(form.initialContent, body["content_form"])
        assertEquals("1", body["signature"])

        val log = diagnostics.entries.value.joinToString("\n") { it.message }
        assertFalse("diagnostics must not expose the private URL", log.contains("message.php"))
        assertFalse("diagnostics must not expose threadId", log.contains("3000001"))
        assertFalse("diagnostics must not expose numreponse", log.contains("1980000004"))
        assertFalse("diagnostics must not expose ref", log.contains("ref="))
        assertFalse("diagnostics must not expose quoted content", log.contains("Message prive de test"))
    }

    @Test
    fun `private multi quote POST keeps the first form routing and forwards every block`() = runTest {
        // Client-only proof: the first form comes from the real capture, while the second block is
        // synthetic BBCode. MockWebServer records serialization; it cannot prove HFR accepts it.
        server.enqueue(MockResponse().setBody(fixture("private_message_quote_form.html")))
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))
        val form = repository.fetchReplyForm(quoteContext)
        val secondPrefill = "[quotemsg=1980000005,5,990003]Deuxième citation[/quotemsg]\n"
        val combinedPrefill = form.initialContent + "\n" + secondPrefill

        repository.submitReply(
            context = quoteContext,
            form = form.copy(initialContent = combinedPrefill),
            bbcodeContent = combinedPrefill,
            options = form.options,
        )

        server.takeRequest() // typed quote GET
        val body = parseFormBody(server.takeRequest().body.readUtf8())
        assertEquals("the first quote keeps POST routing", "1980000004", body["numrep"])
        assertEquals(combinedPrefill, body["content_form"])
        assertEquals(2, Regex("\\[quotemsg=").findAll(body.getValue("content_form")).count())
        assertFalse("ref remains GET/BBCode-only", body.containsKey("ref"))
    }

    @Test
    fun `private quote fails closed when HFR serves the simple reply form`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("private_message_reply_form.html")))

        val error = runCatching { repository.fetchReplyForm(quoteContext) }.exceptionOrNull()

        assertTrue("a quote must reject a non-quote form, got $error", error is IllegalArgumentException)
        assertEquals("the quote path must never retry through forum2.php", 1, server.requestCount)
    }

    @Test
    fun `simple reply still forwards its server-provided ref field`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))
        val replyForm = ReplyForm(
            hashCheck = "h",
            sujet = "s",
            hiddenFields = mapOf(
                "cat" to "prive",
                "post" to "4242424",
                "numrep" to "1990000111",
                "numreponse" to "",
                "ref" to "0",
            ),
            isAnonymous = false,
        )

        repository.submitReply(context, replyForm, bbcodeContent = "Réponse simple")

        val body = parseFormBody(server.takeRequest().body.readUtf8())
        assertEquals("the no-ref rule is quote-only", "0", body["ref"])
    }

    @Test
    fun `fetchReplyForm sources the owner DT newdest from the message_php form`() = runTest {
        // #612 — the conversation page (forum2.php) embeds a quick-reply with NO newdest; the
        // owner-only member list lives only on the message.php form the repository follows.
        server.enqueue(MockResponse().setBody(fixture("private_message_dt_owner_thread.html")))
        server.enqueue(MockResponse().setBody(fixture("private_message_dt_owner_reply_form.html")))

        val form = repository.fetchReplyForm(PrivateMessageReplyContext(threadId = 4242424, page = 3))

        assertFalse(form.isAnonymous)
        assertTrue("owner form must expose the member editor", form.canManageRecipients)
        assertEquals("alice, bob, Bébé Yoda, stitch+, Administration", form.manageableRecipients)
        // The message.php form's server-filled routing is carried verbatim.
        assertEquals("prive", form.hiddenFields["cat"])
        assertEquals("4242424", form.hiddenFields["post"])
        assertEquals("1990000111", form.hiddenFields["numrep"])
        assertEquals("3", form.hiddenFields["page"])

        // The second GET targets the message.php link verbatim (server-filled numrep / ref / page).
        server.takeRequest() // drop the forum2.php GET
        val followed = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("message.php", followed.pathSegments.last())
        assertEquals("4242424", followed.queryParameter("post"))
        assertEquals("1990000111", followed.queryParameter("numrep"))
        assertEquals("0", followed.queryParameter("ref"))
        assertEquals("3", followed.queryParameter("page"))
    }

    @Test
    fun `fetchReplyForm falls back to the embedded quick-reply form when no message_php link is present`() = runTest {
        // A conversation page with no « Ajouter une réponse » link (read-only / reshaped) — the
        // repository must still produce a usable reply form from the embedded bddpost.php quick
        // reply, with a single GET. No newdest there, which is correct (no roster / member editor).
        val readOnlyThread = """
            <html><body>
            <form name="hop" action="/bddpost.php" method="post">
              <input type="hidden" name="hash_check" value="ZZ" />
              <input type="hidden" name="cat" value="prive" />
              <input type="hidden" name="post" value="3195237" />
              <input type="hidden" name="numrep" value="42" />
              <input type="hidden" name="pseudo" value="TestUser" />
              <textarea name="content_form"></textarea>
            </form>
            </body></html>
        """.trimIndent()
        server.enqueue(MockResponse().setBody(readOnlyThread))

        val form = repository.fetchReplyForm(context)

        assertFalse(form.isAnonymous)
        assertFalse("no link → no message.php form → no member editor", form.canManageRecipients)
        assertEquals("prive", form.hiddenFields["cat"])
        assertEquals("42", form.hiddenFields["numrep"])
        // Exactly ONE GET: no link to follow.
        assertEquals(1, server.requestCount)
        assertEquals("forum2.php", requireNotNull(server.takeRequest().requestUrl).pathSegments.first())
    }

    @Test
    fun `roster path (no fallback) propagates a message_php follow failure instead of degrading`() = runTest {
        // #612 — the thread page HAS a reply link, but the followed message.php GET fails. With the
        // fallback DISABLED (roster path), the failure must surface (→ Roster.Error + retry), never
        // degrade to the newdest-less quick-reply that would read as « no roster ».
        server.enqueue(MockResponse().setBody(fixture("private_message_dt_owner_thread.html")))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        var threw = false
        try {
            repository.fetchReplyForm(
                PrivateMessageReplyContext(threadId = 4242424, page = 3),
                allowEmbeddedFallback = false,
            )
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            threw = true
        }
        assertTrue("roster fetch must propagate the message.php failure, not fall back", threw)
    }

    // NB: the #612 invariant — the roster path (allowEmbeddedFallback=false) PROPAGATES a follow-GET
    // failure rather than degrading — is the test directly above. The reply path's *fallback on a
    // follow-GET failure* (allowEmbeddedFallback=true) is a distinct branch from the no-link fallback
    // test above; it is exercised in production but not unit-pinned here (an injected mid-follow socket
    // failure proved flaky to assert deterministically over MockWebServer).

    @Test
    fun `submitReply forwards cat=prive, post, numrep, subcat verbatim and never overwrites them`() = runTest {
        // #612 — fetchReplyForm now does two GETs (thread page + followed message.php form).
        server.enqueue(MockResponse().setBody(fixture("private_message_thread.html")))
        server.enqueue(MockResponse().setBody(fixture("private_message_thread.html")))
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))

        val form = repository.fetchReplyForm(context)
        val result = repository.submitReply(context, form, bbcodeContent = "Coucou en privé.")

        assertTrue("Expected success, got $result", result is ReplySubmitResult.Success)

        server.takeRequest() // drop the forum2.php GET
        server.takeRequest() // drop the followed message.php GET
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
        server.enqueue(MockResponse().setBody(fixture("private_message_thread.html")))
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))

        val form = repository.fetchReplyForm(context)
        repository.submitReply(
            context,
            form,
            bbcodeContent = "Avec signature.",
            options = ReplyFormOptions(signatureEnabled = true),
        )

        server.takeRequest() // forum2.php
        server.takeRequest() // followed message.php
        val body = parseFormBody(server.takeRequest().body.readUtf8())
        assertEquals("1", body["signature"])
    }

    @Test
    fun `submitReply omits signature when the option is disabled`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("private_message_thread.html")))
        server.enqueue(MockResponse().setBody(fixture("private_message_thread.html")))
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))

        val form = repository.fetchReplyForm(context)
        // Default options = all off ; the form's hidden `signature=1` must not leak back as a
        // browser would omit an unchecked field.
        repository.submitReply(context, form, bbcodeContent = "Sans signature.")

        server.takeRequest() // forum2.php
        server.takeRequest() // followed message.php
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
