package fr.forumhfr.redface2.core.network.stats

import fr.forumhfr.redface2.core.domain.diagnostics.AppForegroundDiagnostics
import fr.forumhfr.redface2.core.domain.diagnostics.AppVisibility
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshTrigger
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCallIntent
import fr.forumhfr.redface2.core.domain.diagnostics.HfrProbeTraffic
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsContext
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsContextProvider
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeDisturbance
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeRunner
import fr.forumhfr.redface2.core.domain.stats.NetworkPeakGroup
import fr.forumhfr.redface2.core.domain.stats.NetworkRequestCounts
import fr.forumhfr.redface2.core.domain.stats.NetworkRole
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsBuffer
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsProcess
import fr.forumhfr.redface2.core.domain.stats.NetworkStatus
import fr.forumhfr.redface2.core.domain.stats.NetworkTarget
import fr.forumhfr.redface2.core.domain.stats.NetworkTrigger
import fr.forumhfr.redface2.core.domain.stats.NoRequestReason
import fr.forumhfr.redface2.core.network.HfrFavoriteSmileyClient
import fr.forumhfr.redface2.core.network.di.NetworkClientListeners
import fr.forumhfr.redface2.core.network.di.NetworkModule
import fr.forumhfr.redface2.core.network.diagnostics.HfrCallTag
import fr.forumhfr.redface2.core.network.diagnostics.HfrEventListenerFactory
import fr.forumhfr.redface2.core.network.diagnostics.HfrNetworkDiagnostics
import fr.forumhfr.redface2.core.network.diagnostics.NetworkStatsListenerFactory
import fr.forumhfr.redface2.core.network.diagnostics.OkHttpNetworkProbeRunner
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Cache
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NetworkStatsEventListenerTest {
    @get:Rule val server = MockWebServer()
    @get:Rule val temp = TemporaryFolder()

    private val buffer = NetworkStatsBuffer(NetworkStatsProcess(72, isMain = true))
    private val clock = TestClock()
    private val log = DiagnosticsLog()
    private val foreground = AppForegroundDiagnostics(log)
    private val traffic = HfrProbeTraffic()
    private val diagnostics = HfrEventListenerFactory(HfrNetworkDiagnostics(log, probeTraffic = traffic))
    private val stats = NetworkStatsListenerFactory(buffer, foreground, clock)
    private val listeners = NetworkClientListeners(diagnostics, stats)
    private val base = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .dns { listOf(InetAddress.getByName("127.0.0.1")) }
        .callTimeout(Duration.ofSeconds(5))
        .build()

    @Test
    fun `200 counts compressed wire bytes and freezes hour and visibility before the response`() = runTest {
        val payload = "compressible".repeat(100)
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { it.writeUtf8(payload) }
        val bytes = compressed.size
        server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(compressed))
        val client = NetworkModule.provideAuthenticatedClient(base, CookieJar.NO_COOKIES, listeners)
        val tagged = request("/forum2.php").newBuilder().tag(HfrCallTag::class.java,
            HfrCallTag(0, null, flagTrigger = FlagRefreshTrigger.RETURN_FROM_TOPIC)).build()
        client.newCall(tagged).execute().use { response ->
            clock.now = Instant.ofEpochSecond(3600)
            foreground.foregrounded()
            assertEquals(payload, response.body.string())
        }

        val delta = buffer.drain()
        val row = delta.getValue(0).requests.entries.single()
        assertEquals(1, server.requestCount)
        assertEquals(NetworkStatus.SUCCESS, row.key.status)
        assertEquals(NetworkRole.AUTH, row.key.dimensions.role)
        assertEquals(AppVisibility.UNKNOWN, row.key.dimensions.visibility)
        assertEquals(NetworkTrigger.RETURN_FROM_TOPIC, row.key.dimensions.trigger)
        assertEquals(72, row.key.dimensions.versionCode)
        assertEquals(NetworkRequestCounts(1, bytes, 0, 0), row.value)
    }

    @Test
    fun `redirects to and from HFR use each wire URL and its own status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", url("/forum2.php")))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", url("/final", "other.invalid")))
        server.enqueue(MockResponse().setBody("done"))
        val client = NetworkModule.provideAnonymousClient(base, listeners)
        assertEquals("done", execute(client, request("/start", "other.invalid")))

        val rows = buffer.drain().getValue(0).requests
        assertEquals(server.requestCount.toLong(), rows.values.sumOf { it.requests })
        assertEquals(setOf(
            NetworkTarget.OTHER_HOST to NetworkStatus.REDIRECT,
            NetworkTarget.HTML_FORUM2 to NetworkStatus.REDIRECT,
            NetworkTarget.OTHER_HOST to NetworkStatus.SUCCESS,
        ), rows.keys.map { it.dimensions.target to it.status }.toSet())
    }

    @Test
    fun `an OkHttp retry counts the 503 and the next 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("busy"))
        server.enqueue(MockResponse().setBody("ok"))
        val client = NetworkModule.provideAnonymousClient(base, listeners)
        val badge = HfrCallTag(0, null, intent = HfrCallIntent.Kind.MP_BADGE).forCall()
        val req = request("/forum1.php?cat=prive").newBuilder().tag(HfrCallTag::class.java, badge).build()
        assertEquals("ok", execute(client, req))

        val rows = buffer.drain().getValue(0).requests
        assertEquals(2, server.requestCount)
        assertEquals(setOf(NetworkStatus.SERVER_ERROR, NetworkStatus.SUCCESS), rows.keys.map { it.status }.toSet())
        assertTrue(rows.keys.all { it.dimensions.trigger == NetworkTrigger.MP_BADGE })
        assertTrue(rows.values.all { it.requests == 1L })
    }

    @Test
    fun `DNS failure and cancellation before send stay out of requests`() = runTest {
        val client = NetworkModule.provideAnonymousClient(base, listeners).newBuilder()
            .dns { throw UnknownHostException() }.build()
        assertTrue(runCatching { execute(client, request("/forum2.php")) }.exceptionOrNull() is IOException)

        val cancelled = client.newCall(request("/forum1.php"))
        cancelled.cancel()
        assertTrue(runCatching { cancelled.execute().close() }.exceptionOrNull() is IOException)

        val totals = buffer.drain().getValue(0)
        assertEquals(0, server.requestCount)
        assertTrue(totals.requests.isEmpty())
        assertTrue(totals.peaks.isEmpty())
        assertEquals(setOf(NoRequestReason.DNS, NoRequestReason.CANCELLED),
            totals.noRequests.keys.map { it.reason }.toSet())
        assertEquals(2L, totals.noRequests.values.sum())
    }

    @Test
    fun `a wire request without response and a 403 with broken body keep distinct statuses`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setResponseCode(403).setBody("denied".repeat(4000))
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val client = NetworkModule.provideMutationClient(base, CookieJar.NO_COOKIES, listeners)
        repeat(2) {
            assertTrue(runCatching { execute(client, request("/forum2.php")) }.exceptionOrNull() is IOException)
        }

        val totals = buffer.drain().getValue(0)
        assertEquals(2, server.requestCount)
        assertTrue(totals.noRequests.isEmpty())
        assertEquals(setOf(NetworkStatus.NO_RESPONSE, NetworkStatus.FORBIDDEN),
            totals.requests.keys.map { it.status }.toSet())
        assertTrue(totals.requests.values.all { it.requests == 1L && it.incompleteBodies == 1L })
    }

    @Test
    fun `cancellation while reading a body retains its 200 status`() = runTest {
        server.enqueue(MockResponse().setBody("unfinished").throttleBody(1, 1, TimeUnit.SECONDS))
        val client = NetworkModule.provideAnonymousClient(base, listeners)
        val call = client.newCall(request("/forum2.php"))
        call.execute().use { response ->
            val source = response.body.source()
            source.readByte()
            call.cancel()
            assertTrue(runCatching { source.readUtf8() }.exceptionOrNull() is IOException)
        }

        val totals = buffer.drain().getValue(0)
        assertEquals(1, server.requestCount)
        assertTrue(totals.noRequests.isEmpty())
        assertEquals(NetworkStatus.SUCCESS, totals.requests.keys.single().status)
        assertEquals(1L, totals.requests.values.single().incompleteBodies)
    }

    @Test
    fun `the image client counts a download and a cache hit sends nothing`() = runTest {
        server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=600").setBody("image"))
        Cache(temp.newFolder(), 10_000).use { cache ->
            val image = NetworkModule.provideImageClient(base, listeners).newBuilder().cache(cache).build()
            val request = request("/private/image.png", "forum-images.hardware.fr")
            assertEquals("image", execute(image, request))
            assertEquals("image", execute(image, request))
        }

        val rows = buffer.drain().getValue(0)
        assertEquals(1, server.requestCount)
        assertEquals(NetworkTarget.FORUM_IMAGES, rows.requests.keys.single().dimensions.target)
        assertEquals(NetworkRole.IMAGE, rows.requests.keys.single().dimensions.role)
        assertEquals(1L, rows.requests.values.single().requests)
        assertEquals(NetworkPeakGroup.FORUM_IMAGES, rows.peaks.keys.single().group)
        assertTrue(rows.noRequests.isEmpty())
    }

    @Test
    fun `both sensitive smiley clients count without sending details to diagnostics`() = runTest {
        repeat(2) { server.enqueue(MockResponse().setBody("ok")) }
        val auth = NetworkModule.provideAuthenticatedClient(base, CookieJar.NO_COOKIES, listeners)
        val mutation = NetworkModule.provideMutationClient(base, CookieJar.NO_COOKIES, listeners)
        val smileys = HfrFavoriteSmileyClient(auth, mutation, url("/"), Dispatchers.IO, stats)
        assertEquals("ok", smileys.getFavoriteSmileyForm())
        assertEquals("ok", smileys.removeFavoriteSmileys("token", "5ec7e7", listOf("smiley"), 0))

        val rows = buffer.drain().getValue(0).requests
        assertEquals(2, server.requestCount)
        assertEquals(2L, rows.values.single().requests)
        assertEquals(NetworkRole.SMILEY, rows.keys.single().dimensions.role)
        assertEquals(NetworkTarget.FAVORITE_SMILEYS, rows.keys.single().dimensions.target)
        assertTrue(log.entries.value.isEmpty())
    }

    @Test
    fun `probe exchanges count as PROBE and never disturb their own wave`() = runBlocking {
        val count = NetworkProbeRunner.WAVE_SIZES.sum()
        repeat(count) { server.enqueue(MockResponse().setBody("probe")) }
        val probe = OkHttpNetworkProbeRunner(base, url("/"), Dispatchers.IO, traffic, foreground,
            NetworkDiagnosticsContextProvider { NetworkDiagnosticsContext() }, stats)
        probe.run(onWaveStarted = {}, onWaveFinished = {
            assertFalse(NetworkProbeDisturbance.HFR_TRAFFIC in it.disturbedBy)
        })

        val rows = buffer.drain().getValue(0)
        assertEquals(count, server.requestCount)
        assertEquals(count.toLong(), rows.requests.values.single().requests)
        assertEquals(NetworkTarget.PROBE, rows.requests.keys.single().dimensions.target)
        assertEquals(NetworkRole.PROBE, rows.requests.keys.single().dimensions.role)
        assertEquals(NetworkPeakGroup.FORUM, rows.peaks.keys.single().group)
    }

    @Test
    fun `third party upload counts request bytes on the upload client`() = runTest {
        server.enqueue(MockResponse().setBody("ok"))
        val client = NetworkModule.provideUploadClient(base, listeners)
        val upload = request("/upload", "upload.invalid").newBuilder().post("payload".toRequestBody()).build()
        assertEquals("ok", execute(client, upload))

        val row = buffer.drain().getValue(0).requests.entries.single()
        assertEquals(NetworkRole.UPLOAD, row.key.dimensions.role)
        assertEquals(NetworkTarget.THIRD_PARTY_UPLOAD, row.key.dimensions.target)
        assertEquals(7L, row.value.bodyBytesOut)
        assertEquals("payload", server.takeRequest().body.readUtf8())
    }

    private fun execute(client: OkHttpClient, request: Request): String =
        client.newCall(request).execute().use { it.body.string() }

    private fun request(path: String, host: String = "forum.hardware.fr") =
        Request.Builder().url(url(path, host)).build()

    private fun url(path: String, host: String = "forum.hardware.fr") =
        server.url(path).newBuilder().host(host).build()

    private class TestClock : Clock() {
        var now: Instant = Instant.ofEpochSecond(3599)
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(now, zone)
    }
}
