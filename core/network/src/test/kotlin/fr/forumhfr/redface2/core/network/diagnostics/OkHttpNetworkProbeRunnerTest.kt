package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.diagnostics.HfrProbeTraffic
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeOutcome
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeRunner
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeWave
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
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
    fun start() { server.start() }

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

    private fun runner() = OkHttpNetworkProbeRunner(base, server.url("/"), Dispatchers.IO, traffic)
}
