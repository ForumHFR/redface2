package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.search.SearchTextScope
import java.time.LocalDate
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
