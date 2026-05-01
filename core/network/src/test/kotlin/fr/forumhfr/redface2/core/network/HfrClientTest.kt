package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HfrClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HfrClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val okHttp = OkHttpClient.Builder().build()
        client = HfrClient(
            authenticated = okHttp,
            anonymous = okHttp,
            baseUrl = server.url("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getFlagsPage throws SessionExpired when final URL is login page`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/login.php"),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>login</html>"))

        val error = runCatching { client.getFlagsPage(owntopic = 1) }.exceptionOrNull()

        assertTrue("expected SessionExpiredException, got $error", error is SessionExpiredException)
        assertTrue((error as SessionExpiredException).finalUrl.endsWith("/login.php"))
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
    fun `getFlagsPage keeps a real empty flags page distinct from session expired`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>Aucun sujet</body></html>"))

        val html = client.getFlagsPage(owntopic = 3)

        assertEquals("<html><body>Aucun sujet</body></html>", html)
    }

    @Test
    fun `getTopicPage throws SessionExpired when authenticated and final URL is login`() = runTest {
        // Without this hardening, an expired session would be parsed silently as an empty
        // topic — wrong empty screen, no reconnect CTA. Mirrors the getFlagsPage / getMP
        // protection.
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/login.php"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>login</html>"))

        val error = runCatching {
            client.getTopicPage(cat = 23, post = 35395, page = 1, useAuth = true)
        }.exceptionOrNull()

        assertTrue("expected SessionExpiredException, got $error", error is SessionExpiredException)
    }

    @Test
    fun `getForumHomePage with useAuth=true throws SessionExpired on login redirect`() = runTest {
        // Without this hardening, an expired session would surface as an empty
        // ForumIndex, which is indistinguishable in the UI from a forum that lost all
        // its categories. The session-expiry CTA must fire instead.
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/login.php"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>login</html>"))

        val error = runCatching { client.getForumHomePage(useAuth = true) }.exceptionOrNull()

        assertTrue("expected SessionExpiredException, got $error", error is SessionExpiredException)
        assertTrue((error as SessionExpiredException).finalUrl.endsWith("/login.php"))
    }

    @Test
    fun `getTopicListPage builds the legacy v1 query string`() = runTest {
        // Pin the exact query string against a regression — HFR's v1 client kept this
        // exhaustive list of params for ~10 years and we mirror it defensively. Order
        // is fixed by OkHttp's HttpUrl builder (insertion order).
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>ok</body></html>"))

        val html = client.getTopicListPage(cat = 13, subcat = 0, page = 1)

        assertEquals("<html><body>ok</body></html>", html)
        val recorded = server.takeRequest()
        assertEquals("/forum1.php", recorded.requestUrl?.encodedPath)
        val expectedQuery = listOf(
            "config" to "hfr.inc",
            "cat" to "13",
            "subcat" to "0",
            "page" to "1",
            "sondage" to "0",
            "owntopic" to "0",
            "trash" to "0",
            "trash_post" to "0",
            "moderation" to "0",
            "new" to "0",
            "nojs" to "0",
            "subcatgroup" to "0",
        )
        expectedQuery.forEach { (name, value) ->
            assertEquals("query param $name", value, recorded.requestUrl?.queryParameter(name))
        }
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
}
