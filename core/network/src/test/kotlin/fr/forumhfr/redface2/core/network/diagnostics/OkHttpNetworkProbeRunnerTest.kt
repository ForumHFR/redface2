package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.diagnostics.HfrProbeTraffic
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.HfrRefreshActivity
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeOutcome
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeReservationStore
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeRunner
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeService
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeStatus
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeWave
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpNetworkProbeRunnerTest {
    private val server = MockWebServer()
    private val traffic = HfrProbeTraffic()
    private val base = OkHttpClient.Builder().build()

    @Before
    fun start() { server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0) }

    @After
    fun stop() { server.shutdown() }

    @Test
    fun `exact waves use simultaneous fresh anonymous calls even with an existing session`() = runBlocking {
        val barrier = AtomicReference(CountDownLatch(1))
        val arrived = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.incrementAndGet()
                val current = barrier.get()
                current.countDown()
                check(current.await(5, TimeUnit.SECONDS)) { "wave was serialized" }
                return MockResponse().setBody("catalogue")
            }
        }
        val cookie = Cookie.Builder().name("md_user").value("SecretSession").domain("localhost").build()
        val session = base.newBuilder().cookieJar(object : CookieJar {
            override fun loadForRequest(url: HttpUrl) = listOf(cookie)
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
        }).addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "SecretAuth").build())
        }.build()
        var previousTotal = 0
        val waves = mutableListOf<NetworkProbeWave>()
        val runner = OkHttpNetworkProbeRunner(session, server.url("/"), Dispatchers.IO, traffic)
        runner.run(onWaveStarted = { size ->
            assertEquals(previousTotal, arrived.get())
            barrier.set(CountDownLatch(size))
        }, onWaveFinished = { wave ->
            assertEquals(wave.size, wave.successes)
            previousTotal += wave.size
            assertEquals(previousTotal, arrived.get())
            waves += wave
        })
        assertEquals(listOf(1, 5, 10, 20), waves.map { it.size })
        assertEquals(36, server.requestCount)
        repeat(36) {
            val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertNull(request.getHeader("Cookie"))
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Proxy-Authorization"))
            assertEquals("close", request.getHeader("Connection"))
            assertTrue(request.requestLine.endsWith("HTTP/1.1"))
            assertEquals(0, request.sequenceNumber)
            assertEquals("forums/hardwarefr/categories/", request.requestUrl?.queryParameter("uri"))
        }
        assertFalse(waves.any { it.disturbed })
    }

    @Test
    fun `closing during a wave cancels pending headers and starts no further wave`() = runBlocking {
        val received = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received.countDown()
                return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
        }
        val waves = mutableListOf<NetworkProbeWave>()
        val job = launch { runner().run({}, waves::add) }
        withContext(Dispatchers.IO) { assertTrue(received.await(5, TimeUnit.SECONDS)) }
        job.cancelAndJoin()
        assertEquals(1, server.requestCount)
        assertEquals(1, waves.size)
        assertEquals(NetworkProbeOutcome.CANCELLED, waves.single().results.single().outcome)
    }

    @Test
    fun `ordinary HFR activity completed during first wave marks all remaining waves disturbed`() = runBlocking {
        repeat(36) { server.enqueue(MockResponse().setBody("catalogue")) }
        val waves = mutableListOf<NetworkProbeWave>()
        runner().run(onWaveStarted = { size ->
            if (size == 1) {
                traffic.started()
                traffic.finished()
            }
        }, onWaveFinished = { wave -> waves += wave })
        assertTrue(waves.all { it.disturbed })
    }

    @Test
    fun `wave deadline cancels unfinished call before advancing to the next wave`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        repeat(35) { server.enqueue(MockResponse().setBody("catalogue")) }
        val waves = mutableListOf<NetworkProbeWave>()
        runner().run({}, { waves += it })
        assertEquals(listOf(1, 5, 10, 20), waves.map { it.size })
        assertEquals(NetworkProbeOutcome.DEADLINE, waves.first().results.single().outcome)
        assertEquals(35, waves.drop(1).sumOf { it.successes })
        assertEquals(36, server.requestCount)
    }

    @Test
    fun `408 followed by 200 never exceeds 36 server requests`() = runBlocking {
        assertSingleExchange(408)
    }

    @Test
    fun `503 Retry-After zero followed by 200 never exceeds 36 server requests`() = runBlocking {
        assertSingleExchange(503)
    }

    @Test(timeout = 10_000)
    fun `cancellation releases refresh lease while DNS still ignores interruption`() = runBlocking {
        val dns = BlockingDns()
        val activity = HfrRefreshActivity()
        val service = service(dns, activity)
        val job = launch { service.run() }
        val refreshed = CompletableDeferred<Unit>()
        val refresh = launch { activity.withRefresh { refreshed.complete(Unit) } }
        try {
            withContext(Dispatchers.IO) { assertTrue(dns.entered.await(5, TimeUnit.SECONDS)) }
            assertFalse(refreshed.isCompleted)
            job.cancel()
            withTimeout(2_000) { job.join(); refreshed.await() }
            assertEquals(NetworkProbeStatus.CANCELLED, service.state.value.status)
            assertEquals(1L, dns.returned.count)
            assertEquals(0, server.requestCount)
            assertTrue(activity.idleGeneration() != null)
        } finally {
            dns.release.countDown()
            job.cancelAndJoin()
            refresh.cancelAndJoin()
        }
    }

    @Test(timeout = 20_000)
    fun `deadline releases refresh lease without waiting for blocked DNS worker`() = runBlocking {
        repeat(35) { server.enqueue(MockResponse().setBody("catalogue")) }
        val dns = BlockingDns()
        val activity = HfrRefreshActivity()
        val service = service(dns, activity)
        val job = launch { service.run() }
        val refreshed = CompletableDeferred<Unit>()
        val refresh = launch { activity.withRefresh { refreshed.complete(Unit) } }
        try {
            withContext(Dispatchers.IO) { assertTrue(dns.entered.await(5, TimeUnit.SECONDS)) }
            assertFalse(refreshed.isCompleted)
            withTimeout(15_000) { job.join(); refreshed.await() }
            assertEquals(NetworkProbeStatus.COMPLETE, service.state.value.status)
            assertEquals(NetworkProbeOutcome.DEADLINE, service.state.value.waves.first().results.single().outcome)
            assertEquals(35, service.state.value.waves.drop(1).sumOf { it.successes })
            assertEquals(1L, dns.returned.count)
            assertEquals(35, server.requestCount)
            assertTrue(activity.idleGeneration() != null)
        } finally {
            job.cancel()
            dns.release.countDown()
            job.join()
            refresh.cancelAndJoin()
        }
    }

    private suspend fun assertSingleExchange(status: Int) {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "0").setBody("retry"))
        repeat(36) { server.enqueue(MockResponse().setBody("catalogue")) }
        val waves = mutableListOf<NetworkProbeWave>()
        runner().run({}, waves::add)
        assertEquals(36, server.requestCount)
        assertEquals(36, waves.sumOf { it.results.size })
        assertEquals(NetworkProbeOutcome.HTTP_ERROR, waves.first().results.single().outcome)
        assertEquals(status, waves.first().results.single().status)
        assertEquals(35, waves.sumOf { it.successes })
    }

    private fun service(dns: Dns, activity: HfrRefreshActivity): NetworkProbeService {
        val client = base.newBuilder().dns(dns).proxy(Proxy.NO_PROXY).build()
        val url = server.url("/").newBuilder().host("probe.invalid").build()
        val runner = OkHttpNetworkProbeRunner(client, url, Dispatchers.IO, traffic)
        val reservations = object : NetworkProbeReservationStore {
            override suspend fun reserve(): Long = 0
            override suspend fun remainingSeconds(): Long = 0
        }
        return NetworkProbeService(runner, reservations, activity, DiagnosticsLog())
    }

    private class BlockingDns : Dns {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        private val first = AtomicBoolean(true)

        override fun lookup(hostname: String): List<InetAddress> {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                var interrupted = false
                while (release.count > 0) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
                returned.countDown()
                if (interrupted) Thread.currentThread().interrupt()
            }
            return listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        }
    }

    private fun runner() = OkHttpNetworkProbeRunner(base, server.url("/"), Dispatchers.IO, traffic)
}
