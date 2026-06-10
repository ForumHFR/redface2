package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.search.SearchTextScope
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
        // Promotion-review finding : coroutine cancellation cannot interrupt a blocking
        // OkHttp execute(), so the 3 s probe budget MUST be enforced by OkHttp itself
        // (ProbeCallTimeout on the derived no-redirect client). The server stalls the
        // headers for 10 s ; without the dedicated timeout the call would only die at the
        // 30 s default — and the late answer would then be a perfectly valid redirect,
        // so the assertNull below would fail too (double signal).
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

    private fun taggedClient(tag: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("X-RF2-Client", tag)
                    .build()
                chain.proceed(request)
            }
            .build()
}
