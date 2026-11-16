package fr.forumhfr.redface2.core.network.di

import fr.forumhfr.redface2.core.network.HfrConstants
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.FlagFetchTrace
import fr.forumhfr.redface2.core.domain.diagnostics.HfrClientRole
import fr.forumhfr.redface2.core.domain.diagnostics.HfrProbeTraffic
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.network.diagnostics.HfrCallTag
import fr.forumhfr.redface2.core.network.diagnostics.HfrEventListenerFactory
import fr.forumhfr.redface2.core.network.diagnostics.HfrNetworkDiagnostics
import java.io.IOException
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkModuleTest {
    @Test
    fun `derived factories identify roles and images are silent without changing client policies`() {
        val log = DiagnosticsLog()
        val collector = HfrNetworkDiagnostics(log)
        val base = OkHttpClient.Builder().eventListenerFactory(HfrEventListenerFactory(collector) { 0L }).build()
        val fetch = FlagFetchTrace(1, FlagType.CYAN)
        val clients = listOf(
            NetworkModule.provideAuthenticatedClient(base, CookieJar.NO_COOKIES) to HfrClientRole.AUTH,
            NetworkModule.provideAnonymousClient(base) to HfrClientRole.ANON,
            NetworkModule.provideMutationClient(base, CookieJar.NO_COOKIES) to HfrClientRole.MUTATION,
            NetworkModule.provideUploadClient(base) to HfrClientRole.UPLOAD,
        )
        clients.forEach { (client, role) ->
            val request = Request.Builder().url("https://forum.hardware.fr/forum2.php?cat=23&post=35395")
                .tag(HfrCallTag::class.java, HfrCallTag(0, fetch)).build()
            val call = client.newCall(request)
            val listener = client.eventListenerFactory.create(call)
            listener.callStart(call)
            listener.responseHeadersEnd(call, Response.Builder().request(request).protocol(Protocol.HTTP_2)
                .code(200).message("unused").build())
            listener.callEnd(call)
            assertEquals(role, fetch.callSnapshot().last().role)
            assertSame(base.connectionPool, client.connectionPool)
            assertEquals(base.connectTimeoutMillis, client.connectTimeoutMillis)
            assertEquals(base.readTimeoutMillis, client.readTimeoutMillis)
        }
        val beforeImages = log.entries.value
        val image = NetworkModule.provideImageClient(base)
        val call = image.newCall(Request.Builder().url("https://forum.hardware.fr/mesimages/private.jpg").build())
        val listener = image.eventListenerFactory.create(call)
        listener.callStart(call)
        listener.callFailed(call, IOException("Secret image URL"))
        assertEquals(beforeImages, log.entries.value)
    }

    @Test
    fun `HFR images disturb probes through completion and failure without image telemetry`() {
        listOf(false, true).forEach { fail ->
            val log = DiagnosticsLog()
            val traffic = HfrProbeTraffic()
            val collector = HfrNetworkDiagnostics(log, probeTraffic = traffic)
            val base = OkHttpClient.Builder().eventListenerFactory(HfrEventListenerFactory(collector)).build()
            val image = NetworkModule.provideImageClient(base)
            val call = image.newCall(Request.Builder().url("https://forum.hardware.fr/mesimages/Secret.jpg").build())
            val listener = image.eventListenerFactory.create(call)
            val before = traffic.snapshot()
            listener.callStart(call)
            assertTrue(traffic.disturbed(before))
            assertTrue(traffic.snapshot().alreadyActive)
            if (fail) listener.callFailed(call, IOException("Secret")) else listener.callEnd(call)
            listener.callEnd(call)
            assertFalse(traffic.snapshot().alreadyActive)
            assertTrue(traffic.disturbed(before))
            assertFalse(traffic.disturbed(traffic.snapshot()))
            assertTrue(log.entries.value.isEmpty())
        }
    }

    @Test
    fun `external images stay outside HFR interference until redirected to HFR`() {
        val log = DiagnosticsLog()
        val traffic = HfrProbeTraffic()
        val collector = HfrNetworkDiagnostics(log, probeTraffic = traffic)
        val base = OkHttpClient.Builder().eventListenerFactory(HfrEventListenerFactory(collector)).build()
        val image = NetworkModule.provideImageClient(base)
        val call = image.newCall(Request.Builder().url("https://example.invalid/Secret.jpg").build())
        val listener = image.eventListenerFactory.create(call)
        val before = traffic.snapshot()
        listener.callStart(call)
        assertFalse(traffic.disturbed(before))
        val redirected = Request.Builder().url("https://forum.hardware.fr/mesimages/Secret.jpg").build()
        listener.requestHeadersEnd(call, redirected)
        assertTrue(traffic.disturbed(before))
        listener.callEnd(call)
        assertFalse(traffic.snapshot().alreadyActive)
        assertTrue(log.entries.value.isEmpty())
    }

    @Test
    fun `mutation and upload disable retries while read clients preserve them`() {
        val baseClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .build()
        val cookieJar = CookieJar.NO_COOKIES

        val authenticated = NetworkModule.provideAuthenticatedClient(baseClient, cookieJar)
        val anonymous = NetworkModule.provideAnonymousClient(baseClient)
        val image = NetworkModule.provideImageClient(baseClient)
        val mutation = NetworkModule.provideMutationClient(baseClient, cookieJar)
        val upload = NetworkModule.provideUploadClient(baseClient)

        assertTrue(authenticated.retryOnConnectionFailure)
        assertTrue(anonymous.retryOnConnectionFailure)
        assertTrue(image.retryOnConnectionFailure)
        assertFalse(mutation.retryOnConnectionFailure)
        assertFalse(upload.retryOnConnectionFailure)
        assertSame(cookieJar, mutation.cookieJar)
    }

    @Test
    fun `only the image client installs image host interceptors in upgrade then referer order`() {
        val baseClient = OkHttpClient()
        val cookieJar = CookieJar.NO_COOKIES

        val authenticated = NetworkModule.provideAuthenticatedClient(baseClient, cookieJar)
        val anonymous = NetworkModule.provideAnonymousClient(baseClient)
        val image = NetworkModule.provideImageClient(baseClient)
        val mutation = NetworkModule.provideMutationClient(baseClient, cookieJar)
        val upload = NetworkModule.provideUploadClient(baseClient)

        assertEquals(
            listOf(RehostHttpsUpgradeInterceptor::class, RefererInterceptor::class),
            image.interceptors.map { it::class },
        )
        listOf(baseClient, authenticated, anonymous, mutation, upload).forEach { client ->
            assertFalse(client.interceptors.any {
                it is RehostHttpsUpgradeInterceptor || it is RefererInterceptor
            })
        }
    }

    @Test
    fun `four HFR clients refuse SSL redirects while the image client follows them`() {
        val baseClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val cookieJar = CookieJar.NO_COOKIES

        val authenticated = NetworkModule.provideAuthenticatedClient(baseClient, cookieJar)
        val anonymous = NetworkModule.provideAnonymousClient(baseClient)
        val image = NetworkModule.provideImageClient(baseClient)
        val mutation = NetworkModule.provideMutationClient(baseClient, cookieJar)
        val upload = NetworkModule.provideUploadClient(baseClient)

        listOf(authenticated, mutation, upload, anonymous).forEach { client ->
            assertTrue(client.followRedirects)
            assertFalse(client.followSslRedirects)
        }
        assertTrue(image.followRedirects)
        assertTrue(image.followSslRedirects)
    }

    @Test
    fun `anonymous HFR and image clients never carry cookies`() {
        val baseClient = OkHttpClient()

        assertSame(CookieJar.NO_COOKIES, NetworkModule.provideAnonymousClient(baseClient).cookieJar)
        assertSame(CookieJar.NO_COOKIES, NetworkModule.provideImageClient(baseClient).cookieJar)
    }

    @Test
    fun `HFR base URL stays HTTPS`() {
        assertTrue(HfrConstants.BASE_URL.startsWith("https://"))
    }
}
