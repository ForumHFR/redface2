package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.search.SearchTextScope
import fr.forumhfr.redface2.core.model.write.FlagAddContext
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HfrClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HfrClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = HfrClient(
            authenticated = taggedClient("authenticated"),
            anonymous = taggedClient("anonymous"),
            baseUrl = server.url("/"),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPrivateMessageListPage throws SessionExpired on login form served as HTTP 200`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    <html><body>
                      <form action="/login_validation.php?config=hfr.inc">
                        <input name="pseudo">
                        <input name="password" type="password">
                      </form>
                    </body></html>
                    """.trimIndent(),
                ),
        )

        val error = runCatching { client.getPrivateMessageListPage(page = 1) }.exceptionOrNull()

        assertTrue("expected SessionExpiredException, got $error", error is SessionExpiredException)
    }

    @Test
    fun `getTopicPage throws SessionExpired when authenticated and final URL is login`() = runTest {
        // Without this hardening, an expired session would be parsed silently as an empty
        // topic — wrong empty screen, no reconnect CTA. Mirrors the getMP protection.
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/login.php"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>login</html>"))

        val error = runCatching {
            client.getTopicPage(cat = 23, post = 35395, page = 1, useAuth = true)
        }.exceptionOrNull()

        assertTrue("expected SessionExpiredException, got $error", error is SessionExpiredException)
    }

    @Test
    fun `getTopicPage with useAuth=false skips session expiry detection`() = runTest {
        // The anonymous prefetch path must NOT raise SessionExpired even on a login-like body:
        // there is no session to expire, the caller wants whatever HTML the server returned.
        val loginLikeBody = """
            <html><body>
              <form action="/login_validation.php?config=hfr.inc">
                <input name="pseudo">
                <input name="password" type="password">
              </form>
            </body></html>
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(loginLikeBody))

        val html = client.getTopicPage(cat = 23, post = 35395, page = 1, useAuth = false)

        assertEquals(loginLikeBody, html)
    }

    @Test
    fun `searchTopics builds the all-categories mixed search URL on anonymous client`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>ok</body></html>"))

        val html = client.searchTopics(
            query = "kotlin coroutines",
            cat = null,
            page = 1,
            date = LocalDate.of(2026, 5, 22),
            textScope = SearchTextScope.TitlesAndPosts,
        )

        assertEquals("<html><body>ok</body></html>", html)
        val request = server.takeRequest()
        val url = requireNotNull(request.requestUrl)
        assertEquals("/forum1.php", url.encodedPath)
        assertEquals("anonymous", request.headers["X-RF2-Client"])
        assertEquals("1", url.queryParameter("recherches"))
        assertEquals("", url.queryParameter("cat"))
        assertEquals("0", url.queryParameter("orderSearch"))
        assertEquals("hfr.inc", url.queryParameter("config"))
        assertEquals("", url.queryParameter("pseud"))
        assertEquals("kotlin coroutines", url.queryParameter("search"))
        assertEquals("3", url.queryParameter("titre"))
        assertEquals("22", url.queryParameter("jour"))
        assertEquals("5", url.queryParameter("mois"))
        assertEquals("2026", url.queryParameter("annee"))
        assertEquals("20", url.queryParameter("resSearch"))
        assertEquals("2", url.queryParameter("daterange"))
        assertEquals("0", url.queryParameter("subcat"))
        assertEquals("1", url.queryParameter("searchtype"))
        assertEquals("0", url.queryParameter("trash"))
        assertEquals("0", url.queryParameter("trash_post"))
        assertEquals("0", url.queryParameter("moderation"))
        assertNull("page=1 should use HFR's implicit first page", url.queryParameter("page"))
    }

    @Test
    fun `searchTopics encodes category scope and explicit page`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>ok</body></html>"))

        client.searchTopics(
            query = "android",
            cat = 10,
            page = 3,
            date = LocalDate.of(2026, 5, 22),
            textScope = SearchTextScope.TitlesOnly,
        )

        val url = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("10*hfr.inc", url.queryParameter("cat"))
        assertEquals("3", url.queryParameter("page"))
        assertEquals("1", url.queryParameter("titre"))
        assertEquals("1", url.queryParameter("orderSearch"))
    }

    @Test
    fun `searchTopics encodes the author filter on the anonymous client even with an empty query`() = runTest {
        // #402 — author-only search (« Derniers messages » from the profile) : pseud= carries the
        // pseudo (space and all, OkHttp-encoded), search= rides along empty.
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>ok</body></html>"))

        client.searchTopics(
            query = "",
            cat = null,
            page = 1,
            date = LocalDate.of(2026, 6, 11),
            textScope = SearchTextScope.TitlesAndPosts,
            pseudo = "Lt Ripley",
        )

        val request = server.takeRequest()
        val url = requireNotNull(request.requestUrl)
        assertEquals("anonymous", request.headers["X-RF2-Client"])
        assertEquals("Lt Ripley", url.queryParameter("pseud"))
        assertEquals("", url.queryParameter("search"))
        assertNull("page=1 should use HFR's implicit first page", url.queryParameter("page"))
    }

    @Test
    fun `searchTopics encodes posts-only scope`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>ok</body></html>"))

        client.searchTopics(
            query = "kotlin",
            cat = null,
            page = 1,
            date = LocalDate.of(2026, 5, 22),
            textScope = SearchTextScope.PostsOnly,
        )

        val url = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("0", url.queryParameter("titre"))
        assertEquals("0", url.queryParameter("orderSearch"))
    }

    @Test
    fun `searchInTopic POSTs transsearch on the authenticated client with the form contract`() = runTest {
        // Chantier C (#546) — the response is a topic page ; here we only assert the REQUEST shape
        // (the response round-trip is NOT testable — no live transsearch capture, see the model KDoc).
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>ok</body></html>"))

        val html = client.searchInTopic(
            cat = 23,
            topicId = 35395,
            word = "betatest",
            spseudo = "XaTriX",
            onlyMatches = true,
            hashCheck = "deadbeef",
            firstnum = 2783602,
            owntopic = 0,
        )

        assertEquals("<html><body>ok</body></html>", html)
        val request = server.takeRequest()
        assertEquals("authenticated", request.headers["X-RF2-Client"])
        assertEquals("POST", request.method)
        assertEquals("/transsearch.php", requireNotNull(request.requestUrl).encodedPath)
        val body = formFields(request.body.readUtf8())
        assertEquals("deadbeef", body["hash_check"])
        assertEquals("35395", body["post"])
        assertEquals("23", body["cat"])
        assertEquals("hfr.inc", body["config"])
        assertEquals("1", body["p"])
        assertEquals("0", body["sondage"])
        assertEquals("0", body["owntopic"])
        assertEquals("betatest", body["word"])
        assertEquals("XaTriX", body["spseudo"])
        // onlyMatches=true ⇒ HFR's `filter` checkbox is checked.
        assertEquals("1", body["filter"])
        assertEquals("0", body["dep"])
        assertEquals("2783602", body["firstnum"])
        // Fresh search ⇒ the JS-managed cursor is sent empty (HFR clears it on submit).
        assertEquals("", body["currentnum"])
    }

    @Test
    fun `searchInTopic omits filter when onlyMatches is false and carries the nav cursor`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>ok</body></html>"))

        client.searchInTopic(
            cat = 32,
            topicId = 7,
            word = "",
            spseudo = "someone",
            onlyMatches = false,
            hashCheck = "tok",
            firstnum = 16244,
            owntopic = 1,
            currentnum = "16300",
        )

        val body = formFields(server.takeRequest().body.readUtf8())
        // An unchecked HTML checkbox sends no field at all — `filter` must be absent.
        assertNull("filter must be omitted when onlyMatches=false", body["filter"])
        assertEquals("1", body["owntopic"])
        // Navigation cursor carried verbatim so HFR advances to the next match.
        assertEquals("16300", body["currentnum"])
        assertEquals("someone", body["spseudo"])
        assertEquals("", body["word"])
    }

    @Test
    fun `searchInTopic omits firstnum and dep on a navigation step (firstnum null)`() = runTest {
        // Chantier B (#546) — stepping between results must OMIT firstnum (and dep). Re-sending
        // firstnum re-anchors HFR on the FIRST match so the cursor never advances (the stepping bug).
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>ok</body></html>"))

        client.searchInTopic(
            cat = 23,
            topicId = 35395,
            word = "betatest",
            spseudo = "",
            onlyMatches = false,
            hashCheck = "tok",
            firstnum = null,
            currentnum = "2786594",
        )

        val body = formFields(server.takeRequest().body.readUtf8())
        assertNull("firstnum must be omitted on a navigation step", body["firstnum"])
        assertNull("dep must be omitted on a navigation step", body["dep"])
        // The cursor still rides along so HFR knows the current match to advance from.
        assertEquals("2786594", body["currentnum"])
    }

    @Test
    fun `searchInTopic raises SessionExpired when the authenticated POST lands on login`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/login.php"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>login</html>"))

        val error = runCatching {
            client.searchInTopic(
                cat = 23,
                topicId = 35395,
                word = "x",
                spseudo = "",
                onlyMatches = true,
                hashCheck = "tok",
                firstnum = 1,
            )
        }.exceptionOrNull()

        assertTrue("expected SessionExpiredException, got $error", error is SessionExpiredException)
    }

    @Test
    fun `submitPrivateMessageEdit POSTs bdd_php on the authenticated client and forwards the form body`() = runTest {
        // MPStorage write (#6, ADR-014 §4) — GUARDED, NOT OBSERVED LIVE: the bdd.php cat=prive write
        // contract was never captured. Here we only assert the REQUEST shape (endpoint + that the
        // repository-built body, carrying cat=prive as a String, is forwarded verbatim).
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>ok</body></html>"))

        val body = okhttp3.FormBody.Builder(Charsets.UTF_8)
            .add("hash_check", "deadbeef")
            .add("cat", "prive")
            .add("content_form", """{"data":[]}""")
            .build()

        val html = client.submitPrivateMessageEdit(body)

        assertEquals("<html><body>ok</body></html>", html)
        val request = server.takeRequest()
        assertEquals("authenticated", request.headers["X-RF2-Client"])
        assertEquals("POST", request.method)
        assertEquals("/bdd.php", requireNotNull(request.requestUrl).encodedPath)
        assertEquals("hfr.inc", request.requestUrl!!.queryParameter("config"))
        val fields = formFields(request.body.readUtf8())
        assertEquals("deadbeef", fields["hash_check"])
        assertEquals("prive", fields["cat"])
        assertEquals("""{"data":[]}""", fields["content_form"])
    }

    @Test
    fun `submitPollVote POSTs vote_php authenticated without Referer and forwards body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<div class=\"hop\">ok</div>"))
        val formBody = okhttp3.FormBody.Builder(Charsets.UTF_8)
            .add("hash_check", "00000000000000000000000000000000")
            .add("cat", "13")
            .add("page", "1")
            .add("numeropost", "96127")
            .add("reponse", "2")
            .build()

        val html = client.submitPollVote(formBody)

        assertEquals("<div class=\"hop\">ok</div>", html)
        val request = server.takeRequest()
        val url = requireNotNull(request.requestUrl)
        assertEquals("authenticated", request.headers["X-RF2-Client"])
        assertEquals("POST", request.method)
        assertEquals("/user/vote.php", url.encodedPath)
        assertEquals("hfr.inc", url.queryParameter("config"))
        assertNull("live contract requires no Referer", request.headers["Referer"])
        assertEquals(
            mapOf(
                "hash_check" to "00000000000000000000000000000000",
                "cat" to "13",
                "page" to "1",
                "numeropost" to "96127",
                "reponse" to "2",
            ),
            formFields(request.body.readUtf8()),
        )
    }

    @Test
    fun `addFlag builds the addflag URL on the authenticated client without owntopic mapping`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>Favori positionné</body></html>"))
        val context = FlagAddContext(
            cat = 23,
            subcat = 550,
            topicId = 21748,
            page = 1,
            numreponse = 520054,
            ref = 3,
        )

        val html = client.addFlag(context)

        assertTrue(html.contains("Favori positionné"))
        val request = server.takeRequest()
        val url = requireNotNull(request.requestUrl)
        assertEquals("authenticated", request.headers["X-RF2-Client"])
        assertEquals("GET", request.method)
        assertEquals("/user/addflag.php", url.encodedPath)
        assertEquals(
            "config=hfr.inc&cat=23&post=21748&numreponse=520054&page=1&ref=3" +
                "&p=1&sondage=0&owntopic=0&subcat=550",
            url.encodedQuery,
        )
    }

    @Test
    fun `addFlag emits an empty subcat when the topic has none`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>ok</body></html>"))

        client.addFlag(
            FlagAddContext(cat = 5, subcat = null, topicId = 1000, page = 1, numreponse = 4242, ref = 1),
        )

        val url = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("", url.queryParameter("subcat"))
    }

    @Test
    fun `addFlag raises SessionExpired when HFR serves the login form`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/login.php"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>login</html>"))
        val context = FlagAddContext(cat = 23, subcat = 550, topicId = 35395, page = 1, numreponse = 1, ref = 1)

        val error = runCatching { client.addFlag(context) }.exceptionOrNull()

        assertTrue("expected SessionExpiredException, got $error", error is SessionExpiredException)
    }

    @Test
    fun `removeFlag builds the delflag URL on the authenticated client mapping each type to owntopic`() = runTest {
        // owntopic discriminator: CYAN→1, RED→2, FAVORITE→3 (cf. Flag.kt / protocol-hfr.md).
        listOf(
            FlagType.CYAN to "1",
            FlagType.RED to "2",
            FlagType.FAVORITE to "3",
        ).forEach { (type, expectedOwntopic) ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("<html><body>Drapeau effacé avec succès</body></html>"),
            )

            val html = client.removeFlag(cat = 23, subcat = 550, topicId = 35395, type = type, page = 7)

            assertTrue(html.contains("Drapeau effacé avec succès"))
            val request = server.takeRequest()
            val url = requireNotNull(request.requestUrl)
            assertEquals("authenticated", request.headers["X-RF2-Client"])
            assertEquals("/user/delflag.php", url.encodedPath)
            assertEquals("hfr.inc", url.queryParameter("config"))
            assertEquals("23", url.queryParameter("cat"))
            assertEquals("550", url.queryParameter("subcat"))
            assertEquals("35395", url.queryParameter("post"))
            assertEquals("7", url.queryParameter("page"))
            assertEquals("1", url.queryParameter("p"))
            assertEquals("0", url.queryParameter("sondage"))
            assertEquals(expectedOwntopic, url.queryParameter("owntopic"))
            assertEquals("0", url.queryParameter("new"))
        }
    }

    @Test
    fun `removeFlag emits an empty subcat when the flag has none`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>ok</body></html>"))

        client.removeFlag(cat = 5, subcat = null, topicId = 1000, type = FlagType.FAVORITE, page = 1)

        val url = requireNotNull(server.takeRequest().requestUrl)
        // A null subcat serialises as `subcat=` (empty) rather than being dropped, mirroring
        // how HFR's own listing links serialise a missing sub-category.
        assertEquals("", url.queryParameter("subcat"))
    }

    @Test
    fun `removeFlag raises SessionExpired when HFR serves the login form`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/login.php"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>login</html>"))

        val error = runCatching {
            client.removeFlag(cat = 23, subcat = 550, topicId = 35395, type = FlagType.CYAN, page = 1)
        }.exceptionOrNull()

        assertTrue("expected SessionExpiredException, got $error", error is SessionExpiredException)
    }

    @Test
    fun `resolveTopicPageUrl returns the Location of a 301 without following the redirect`() = runTest {
        // Live-proven shape (#277, 2026-06-10) : HFR answers the page=1 probe with a 301
        // whose Location is the relative pretty URL of the REAL page, fragment included.
        server.enqueue(
            MockResponse()
                .setResponseCode(301)
                .addHeader("Location", "/hfr/gsmgpspda/redface-dev-sujet_35421_3.htm#t2786758"),
        )
        // A second response is enqueued on purpose : if the client followed the redirect,
        // it would consume it and requestCount would be 2.
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>page 3</html>"))

        val location = client.resolveTopicPageUrl(cat = 23, post = 35421, numreponse = 2786758)

        assertEquals("/hfr/gsmgpspda/redface-dev-sujet_35421_3.htm#t2786758", location)
        assertEquals("redirect must NOT be followed", 1, server.requestCount)
        val request = server.takeRequest()
        val url = requireNotNull(request.requestUrl)
        assertEquals("/forum2.php", url.encodedPath)
        assertEquals("anonymous", request.headers["X-RF2-Client"])
        assertEquals("hfr.inc", url.queryParameter("config"))
        assertEquals("23", url.queryParameter("cat"))
        assertEquals("35421", url.queryParameter("post"))
        assertEquals("1", url.queryParameter("page"))
        assertEquals("2786758", url.queryParameter("numreponse"))
    }

    @Test
    fun `resolveTopicPageUrl returns null on a non-redirect response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>not a redirect</html>"))

        assertNull(client.resolveTopicPageUrl(cat = 23, post = 35421, numreponse = 2786758))
    }

    @Test
    fun `resolveTopicPageUrl returns null when the redirect has no Location header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(301))

        assertNull(client.resolveTopicPageUrl(cat = 23, post = 35421, numreponse = 2786758))
    }

    @Test
    fun `resolveTopicPageUrl returns null on a network failure`() = runTest {
        // Shut the server down so the call fails with an IOException — the method must
        // degrade to null (caller falls back to the search-href page), not throw.
        server.shutdown()

        assertNull(client.resolveTopicPageUrl(cat = 23, post = 35421, numreponse = 2786758))
    }

    @Test
    fun `resolveTopicPageUrl aborts a stalled probe after its dedicated call timeout`() = runTest {
        // Coroutine cancellation now interrupts the Call, but this probe has no external
        // cancellation in the nominal path. Its intrinsic 3 s budget therefore remains enforced
        // by OkHttp (ProbeCallTimeout on the derived no-redirect client). The server stalls the
        // headers for 10 s ; without the dedicated timeout the late answer would be a valid
        // redirect, so the assertNull below would fail too (double signal).
        server.enqueue(
            MockResponse()
                .setResponseCode(301)
                .addHeader("Location", "/hfr/cat/sujet_1_3.htm#t42")
                .setHeadersDelay(10, TimeUnit.SECONDS),
        )

        val startedAtMs = System.currentTimeMillis()
        val location = client.resolveTopicPageUrl(cat = 23, post = 35421, numreponse = 2786758)
        val elapsedMs = System.currentTimeMillis() - startedAtMs

        assertNull("a stalled probe must degrade to the page-1 fallback", location)
        assertTrue(
            "probe must be cut by its own call timeout, not the 30 s default (took ${"$"}{elapsedMs}ms)",
            elapsedMs < 9_000,
        )
    }

    @Test
    fun `cancelling authenticated call before response headers cancels the OkHttp call`() = runTest {
        val listener = CallLifecycleListener()
        val cancellableClient = cancellableClient(listener)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setSocketPolicy(SocketPolicy.NO_RESPONSE),
        )

        val inFlight = async(Dispatchers.IO) {
            cancellableClient.getPrivateMessageListPage(page = 1)
        }
        try {
            assertTrue(
                "the real call did not send its request headers before the test deadline",
                listener.requestHeadersSent.await(CALL_EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            assertEquals(
                "response headers must still be pending when cancellation starts",
                1L,
                listener.responseHeadersReceived.count,
            )

            inFlight.assertCancelledAtCallLevel(listener)
        } finally {
            listener.cancelCall()
            inFlight.cancel()
        }
    }

    @Test
    fun `cancelling anonymous call during response body cancels the OkHttp call`() = runTest {
        val listener = CallLifecycleListener()
        val cancellableClient = cancellableClient(listener)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("ab")
                .throttleBody(1, 30, TimeUnit.SECONDS),
        )

        val inFlight = async(Dispatchers.IO) {
            cancellableClient.getTopicPage(cat = 23, post = 35395, page = 2, useAuth = false)
        }
        try {
            assertTrue(
                "the real call did not start consuming the response body before the test deadline",
                listener.responseBodyStarted.await(CALL_EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )

            inFlight.assertCancelledAtCallLevel(listener)
        } finally {
            listener.cancelCall()
            inFlight.cancel()
        }
    }

    @Test
    fun `getTopicPage anonymous surfaces a 500 as HfrServerException carrying the code`() = runTest {
        // #324 — a non-2xx answered by HFR must be typed (HfrServerException) so the read
        // screens can tell « HFR est en panne » (5xx) from a local network cut.
        server.enqueue(MockResponse().setResponseCode(500).setBody("<html>boom</html>"))

        val error = runCatching {
            client.getTopicPage(cat = 23, post = 35395, page = 1, useAuth = false)
        }.exceptionOrNull()

        val typed = error as? HfrServerException
            ?: throw AssertionError("expected HfrServerException, got $error")
        assertEquals(500, typed.code)
        assertTrue("missing status in message: ${typed.message}", "500" in typed.message.orEmpty())
    }

    @Test
    fun `getTopicPage authenticated surfaces a 503 as HfrServerException carrying the code`() = runTest {
        // Covers the shared executeAuthenticatedHtml() path (topic auth, MP list/thread, …).
        server.enqueue(MockResponse().setResponseCode(503).setBody("<html>maintenance</html>"))

        val error = runCatching {
            client.getTopicPage(cat = 23, post = 35395, page = 1, useAuth = true)
        }.exceptionOrNull()

        val typed = error as? HfrServerException
            ?: throw AssertionError("expected HfrServerException, got $error")
        assertEquals(503, typed.code)
    }

    @Test
    fun `getProfile surfaces a 500 as HfrServerException carrying the code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("<html>boom</html>"))

        val error = runCatching { client.getProfile(userId = 12345) }.exceptionOrNull()

        val typed = error as? HfrServerException
            ?: throw AssertionError("expected HfrServerException, got $error")
        assertEquals(500, typed.code)
    }

    @Test
    fun `getStaffResponsables GETs the exact staff endpoint on the anonymous client`() = runTest {
        // #1112 / #221 — l'annuaire staff global est la source primaire du badge. Le GET doit viser
        // message-smi-mp-aj.php avec responsable=1 (sans cat) et partir sur le client ANONYME.
        server.enqueue(MockResponse().setResponseCode(200).setBody("<table class=\"main\"></table>"))

        val html = client.getStaffResponsables()

        assertEquals("<table class=\"main\"></table>", html)
        val request = server.takeRequest()
        val url = requireNotNull(request.requestUrl)
        assertEquals("GET", request.method)
        assertEquals("anonymous", request.headers["X-RF2-Client"])
        assertEquals("/message-smi-mp-aj.php", url.encodedPath)
        assertEquals("hfr.inc", url.queryParameter("config"))
        assertEquals("0", url.queryParameter("user_id"))
        assertEquals("1", url.queryParameter("responsable"))
        assertNull("l'annuaire staff est global, sans paramètre cat", url.queryParameter("cat"))
    }

    @Test
    fun `getStaffResponsables surfaces a 500 as HfrServerException carrying the code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("<html>boom</html>"))

        val error = runCatching { client.getStaffResponsables() }.exceptionOrNull()

        val typed = error as? HfrServerException
            ?: throw AssertionError("expected HfrServerException, got $error")
        assertEquals(500, typed.code)
    }

    @Test
    fun `getProfile GETs the exact profile path on the anonymous client`() = runTest {
        // #1112 / #221 — le rôle HFR est lu sur la page profil publique. Le GET doit viser
        // `/hfr/profil-{id}.htm` exact et partir sur le client ANONYME (lire un profil ne
        // doit jamais marquer de drapeaux comme lus — règle prefetch-non-authentifié).
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>profil</body></html>"))

        val html = client.getProfile(userId = 15461)

        assertEquals("<html><body>profil</body></html>", html)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("anonymous", request.headers["X-RF2-Client"])
        assertEquals("/hfr/profil-15461.htm", requireNotNull(request.requestUrl).encodedPath)
    }

    /**
     * Decodes an `application/x-www-form-urlencoded` POST body into a field map. A field absent from
     * the body (e.g. an unchecked checkbox) is simply not a key — `map["filter"]` is then `null`.
     */
    private fun formFields(body: String): Map<String, String> =
        body.split("&")
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val name = pair.substringBefore("=")
                val value = pair.substringAfter("=", "")
                java.net.URLDecoder.decode(name, "UTF-8") to java.net.URLDecoder.decode(value, "UTF-8")
            }

    private fun cancellableClient(listener: EventListener): HfrClient = HfrClient(
        authenticated = taggedClient("authenticated", listener),
        anonymous = taggedClient("anonymous", listener),
        baseUrl = server.url("/"),
        ioDispatcher = Dispatchers.IO,
    )

    private suspend fun Deferred<String>.assertCancelledAtCallLevel(listener: CallLifecycleListener) {
        val coroutineCompleted = CountDownLatch(1)
        invokeOnCompletion { coroutineCompleted.countDown() }

        cancel()

        assertTrue(
            "coroutine cancellation did not invoke Call.cancel() before the test deadline",
            listener.canceled.await(CALL_EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        assertTrue(
            "the canceled OkHttp call did not reach its terminal callFailed event",
            listener.terminalFailure.await(CALL_EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        assertTrue(
            "the coroutine did not complete promptly after the OkHttp call failed",
            coroutineCompleted.await(CALL_EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        val error = runCatching { await() }.exceptionOrNull()
        assertTrue("CancellationException must propagate, got ${error?.javaClass}", error is CancellationException)
    }

    private fun taggedClient(tag: String, listener: EventListener = EventListener.NONE): OkHttpClient =
        OkHttpClient.Builder()
            .eventListener(listener)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("X-RF2-Client", tag)
                    .build()
                chain.proceed(request)
            }
            .build()

    private class CallLifecycleListener : EventListener() {
        @Volatile
        private var activeCall: Call? = null

        val requestHeadersSent = CountDownLatch(1)
        val responseHeadersReceived = CountDownLatch(1)
        val responseBodyStarted = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val terminalFailure = CountDownLatch(1)

        override fun callStart(call: Call) {
            activeCall = call
        }

        override fun requestHeadersEnd(call: Call, request: Request) {
            requestHeadersSent.countDown()
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            responseHeadersReceived.countDown()
        }

        override fun responseBodyStart(call: Call) {
            responseBodyStarted.countDown()
        }

        override fun canceled(call: Call) {
            canceled.countDown()
        }

        override fun callFailed(call: Call, ioe: IOException) {
            terminalFailure.countDown()
        }

        fun cancelCall() {
            activeCall?.cancel()
        }
    }

    private companion object {
        private const val CALL_EVENT_TIMEOUT_SECONDS = 5L
    }
}
