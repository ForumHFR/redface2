package fr.forumhfr.redface2.core.network.di

import java.net.InetAddress
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RehostHttpsUpgradeInterceptorTest {
    private val secureServer = MockWebServer()
    private val cleartextServer = MockWebServer()
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        val heldCertificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("reho.st")
            .addSubjectAlternativeName("www.reho.st")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(heldCertificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(heldCertificate.certificate)
            .build()
        secureServer.useHttps(serverCertificates.sslSocketFactory(), false)
        secureServer.start()
        cleartextServer.start()
        client = OkHttpClient.Builder()
            .dns { listOf(InetAddress.getLoopbackAddress()) }
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .addInterceptor(RehostHttpsUpgradeInterceptor())
            .addInterceptor(RefererInterceptor())
            .build()
    }

    @After
    fun tearDown() {
        secureServer.shutdown()
        cleartextServer.shutdown()
    }

    @Test
    fun `HTTP reho exact host and strict subdomain are upgraded to HTTPS`() {
        listOf("reho.st", "www.reho.st").forEach { host ->
            val recorded = execute(upgradeUrl(host))

            assertEquals("https", recorded.requestUrl?.scheme)
            assertEquals(host, recorded.requestUrl?.host)
            assertEquals("/a?size=large", recorded.path)
            assertEquals(HFR_REFERER, recorded.getHeader("Referer"))
        }
    }

    @Test
    fun `HTTP lookalikes and unrelated hosts stay unchanged`() {
        listOf("reho.st.evil.com", "notreho.st", "imgur.com").forEach { host ->
            val recorded = execute(cleartextUrl(host))

            assertEquals("http", recorded.requestUrl?.scheme)
            assertEquals(host, recorded.requestUrl?.host)
            assertNull(recorded.getHeader("Referer"))
        }
    }

    @Test
    fun `HTTPS reho URL stays unchanged`() {
        val recorded = execute(secureServer.url("/a").newBuilder().host("reho.st").build())

        assertEquals("https", recorded.requestUrl?.scheme)
        assertEquals("/a", recorded.path)
    }

    @Test
    fun `upgrade preserves existing request headers`() {
        val recorded = execute(upgradeUrl("reho.st"), headerValue = "kept")

        assertEquals("kept", recorded.getHeader(TEST_HEADER))
    }

    private fun upgradeUrl(host: String): HttpUrl = secureServer.url("/a?size=large")
        .newBuilder()
        .scheme("http")
        .host(host)
        .build()

    private fun cleartextUrl(host: String): HttpUrl = cleartextServer.url("/a")
        .newBuilder()
        .host(host)
        .build()

    private fun execute(url: HttpUrl, headerValue: String? = null): RecordedRequest {
        val request = Request.Builder()
            .url(url)
            .apply { headerValue?.let { header(TEST_HEADER, it) } }
            .build()
        val destination = if (url.port == secureServer.port) secureServer else cleartextServer
        destination.enqueue(MockResponse().setBody("image"))
        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
        }
        return destination.takeRequest()
    }

    private companion object {
        const val HFR_REFERER = "https://forum.hardware.fr/"
        const val TEST_HEADER = "X-Existing"
    }
}
