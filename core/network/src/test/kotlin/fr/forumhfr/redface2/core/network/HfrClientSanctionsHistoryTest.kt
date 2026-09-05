package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.error.HfrServerException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HfrClientSanctionsHistoryTest {
    private val server = MockWebServer()
    private lateinit var client: HfrClient

    @Before
    fun setUp() {
        server.start()
        val cookieJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl): List<Cookie> = listOf(
                Cookie.Builder().name("hfr_session").value("test-session").hostOnlyDomain(url.host).build(),
            )
        }
        val authenticated = OkHttpClient.Builder().cookieJar(cookieJar).build()
        client = HfrClient(authenticated, OkHttpClient(), OkHttpClient(), server.url("/"), Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `history GET uses the exact endpoint and authenticated client`() = runTest {
        server.enqueue(MockResponse().setBody("response"))
        assertEquals("response", client.fetchSanctionsHistoryPage())
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/modo/historique.php?config=hfr.inc", request.path)
        assertEquals("hfr_session=test-session", request.getHeader("Cookie"))
    }

    @Test
    fun `empty HTTP 200 body reaches the parser unchanged`() = runTest {
        server.enqueue(MockResponse().setBody(""))
        assertEquals("", client.fetchSanctionsHistoryPage())
    }

    @Test
    fun `server failures stay typed for the error screen`() = runTest {
        server.enqueue(MockResponse().setResponseCode(SERVER_ERROR))
        val error = runCatching { client.fetchSanctionsHistoryPage() }.exceptionOrNull()
        assertTrue(error is HfrServerException)
        assertEquals(SERVER_ERROR, (error as HfrServerException).code)
    }

    private companion object {
        const val SERVER_ERROR = 503
    }
}
