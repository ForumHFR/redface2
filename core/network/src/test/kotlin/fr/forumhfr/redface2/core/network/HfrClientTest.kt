package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import kotlinx.coroutines.Dispatchers
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
}
