package fr.forumhfr.redface2.core.network.di

import java.net.InetAddress
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RefererInterceptorTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder()
        .dns { listOf(InetAddress.getLoopbackAddress()) }
        .addInterceptor(RefererInterceptor())
        .build()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `reho request carries the HFR referer`() {
        assertEquals(HFR_REFERER, executeRequest("reho.st").getHeader("Referer"))
    }

    @Test
    fun `reho subdomain request carries the HFR referer`() {
        assertEquals(HFR_REFERER, executeRequest("img.reho.st").getHeader("Referer"))
    }

    @Test
    fun `unlisted image host does not carry a referer`() {
        assertNull(executeRequest("i.imgur.com").getHeader("Referer"))
    }

    @Test
    fun `lookalike hosts do not carry a referer`() {
        assertNull(executeRequest("reho.st.evil.com").getHeader("Referer"))
        assertNull(executeRequest("xreho.st").getHeader("Referer"))
    }

    private fun executeRequest(host: String): RecordedRequest {
        server.enqueue(MockResponse().setBody("image"))
        val url = server.url("/x.png").newBuilder().host(host).build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful)
        }
        return server.takeRequest()
    }

    private companion object {
        const val HFR_REFERER = "https://forum.hardware.fr/"
    }
}
