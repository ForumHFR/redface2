package fr.forumhfr.redface2.core.network

import java.io.IOException
import java.net.URLDecoder
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HfrClientModerationAlertTest {
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
        val mutation = authenticated.newBuilder().retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-RF2-Client", "mutation").build())
            }.build()
        client = HfrClient(authenticated, OkHttpClient(), mutation, server.url("/"), Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `GET builds the full authenticated moderation URL`() = runTest {
        server.enqueue(MockResponse().setBody("response"))
        assertEquals("response", client.fetchModerationAlertPage(23, 35421, 2800456, 76))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(
            "/user/modo.php?config=hfr.inc&cat=23&post=35421&numreponse=2800456&page=76&ref=1",
            request.path,
        )
        assertEquals("hfr_session=test-session", request.getHeader("Cookie"))
    }

    @Test
    fun `relative action resolves under user and reason is form encoded with session cookies`() = runTest {
        server.enqueue(MockResponse().setBody("response"))
        val reason = "Abus & insultes + répétées\nMerci"
        assertEquals("response", client.submitModerationAlert(ACTION, TOKEN, REFERER, reason))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("mutation", request.getHeader("X-RF2-Client"))
        assertEquals("/user/$ACTION", request.path)
        assertEquals("application/x-www-form-urlencoded", request.getHeader("Content-Type"))
        assertEquals("hfr_session=test-session", request.getHeader("Cookie"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("%26"))
        assertTrue(body.contains("%2B"))
        assertTrue(body.contains("%C3%A9"))
        assertEquals(
            mapOf(
                "hash_check" to TOKEN, "referer_page" to REFERER,
                "raison" to reason, "Submit" to "Valider votre message",
            ),
            fields(body),
        )
    }

    @Test
    fun `absolute join action preserves query and sends confirmation without a reason`() = runTest {
        server.enqueue(MockResponse().setBody("response"))
        client.joinModerationAlert(server.url("/user/$ACTION").toString(), TOKEN, REFERER)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("mutation", request.getHeader("X-RF2-Client"))
        assertEquals("/user/$ACTION", request.path)
        assertEquals("hfr_session=test-session", request.getHeader("Cookie"))
        assertEquals(
            mapOf("hash_check" to TOKEN, "referer_page" to REFERER, "cfmodoalert" to "1", "Submit" to "Confirmer"),
            fields(request.body.readUtf8()),
        )
    }

    @Test
    fun `absent optional referer is omitted`() = runTest {
        server.enqueue(MockResponse().setBody("response"))
        client.joinModerationAlert(ACTION, TOKEN, null)
        assertFalse(fields(server.takeRequest().body.readUtf8()).containsKey("referer_page"))
    }

    @Test
    fun `foreign origin wrong path and missing token never send a POST`() = runTest {
        val invalidActions = listOf("https://example.org/user/modo.php", "../bdd.php", "modo.php#fragment")
        for (action in invalidActions) {
            val error = runCatching { client.joinModerationAlert(action, TOKEN, null) }.exceptionOrNull()
            assertTrue(error is IOException)
        }
        val error = runCatching { client.joinModerationAlert(ACTION, "", null) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `server failures propagate for both GET and POST`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(runCatching { client.fetchModerationAlertPage(23, 35421, 2800456, 76) }.isFailure)
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(runCatching { client.joinModerationAlert(ACTION, TOKEN, null) }.isFailure)
        assertEquals(2, server.requestCount)
    }

    private fun fields(body: String): Map<String, String> = body.split('&').associate { field ->
        val parts = field.split('=', limit = 2)
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
    }

    private companion object {
        private const val TOKEN = "00000000000000000000000000000000"
        private const val ACTION = "modo.php?cat=23&ref=18&post=35421&numreponse=2800456&page=76&config=hfr.inc"
        private const val REFERER = "https://forum.hardware.fr/forum2.php?cat=23&post=35421&page=76"
    }
}
