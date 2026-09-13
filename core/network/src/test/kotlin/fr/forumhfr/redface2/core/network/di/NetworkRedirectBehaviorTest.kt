package fr.forumhfr.redface2.core.network.di

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class NetworkRedirectBehaviorTest {
    private val secureServer = MockWebServer()
    private val cleartextServer = MockWebServer()
    private lateinit var baseClient: OkHttpClient

    @Before
    fun setUp() {
        val heldCertificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
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
        baseClient = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
    }

    @After
    fun tearDown() {
        secureServer.shutdown()
        cleartextServer.shutdown()
    }

    @Test
    fun `only the image client follows an HTTPS to HTTP redirect`() {
        repeat(CLIENT_COUNT) {
            secureServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", cleartextServer.url("/x")),
            )
        }
        cleartextServer.enqueue(MockResponse().setBody("image"))
        val cookieJar = CookieJar.NO_COOKIES
        val hfrClients = listOf(
            NetworkModule.provideAuthenticatedClient(baseClient, cookieJar),
            NetworkModule.provideMutationClient(baseClient, cookieJar),
            NetworkModule.provideUploadClient(baseClient),
            NetworkModule.provideAnonymousClient(baseClient),
        )

        hfrClients.forEach { client ->
            assertEquals(302, execute(client))
        }
        assertEquals(0, cleartextServer.requestCount)

        assertEquals(200, execute(NetworkModule.provideImageClient(baseClient)))
        assertEquals(1, cleartextServer.requestCount)
    }

    private fun execute(client: OkHttpClient): Int = client.newCall(
        Request.Builder().url(secureServer.url("/redirect")).build(),
    ).execute().use { it.code }

    private companion object {
        const val CLIENT_COUNT = 5
    }
}
