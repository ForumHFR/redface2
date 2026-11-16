package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.FlagFetchTrace
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.network.diagnostics.HfrEventListenerFactory
import fr.forumhfr.redface2.core.network.diagnostics.HfrNetworkDiagnostics
import fr.forumhfr.redface2.core.network.diagnostics.HfrCallTag
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No network: failed connects use fake sockets; response/body lifetimes use suspended transports. */
@OptIn(ExperimentalCoroutinesApi::class)
class FlagReadExecutorTest {
    private val limiter = FlagReadLimiter()
    private val jitter = mockk<FlagReadJitter>()
    private val executor = FlagReadExecutor(limiter, jitter)
    private val request = Request.Builder().url("https://forum.hardware.fr/").build()
    private val client = OkHttpClient()

    @Test
    fun `retry diagnostics measure each Call with its own queue and execution times - 984`() = runTest {
        val log = DiagnosticsLog()
        val fetch = FlagFetchTrace(
            1, FlagType.CYAN)
        val factory = HfrEventListenerFactory(
            HfrNetworkDiagnostics(log),
        ) { testScheduler.currentTime * NANOS_PER_MS }
        var calls = 0
        val measuredClient = tcpFailureClient(onConnect = {
            advanceTimeBy(if (++calls == 1) 5_000 else 100)
        }).newBuilder().eventListenerFactory(factory).build()
        val original = HfrCallTag(0, fetch)
        val tagged = request.newBuilder()
            .url("https://forum.hardware.fr/hfr-gm/forums/hardwarefr/categories/23/topics/read/")
            .tag(HfrCallTag::class.java, original).build()
        val session = FlagReadSession { testScheduler.currentTime * NANOS_PER_MS }
        session.succeeded(retry = false)
        coEvery { jitter.pause() } coAnswers { delay(200) }
        val error = runCatching {
            executor.execute(measuredClient, tagged, FlagReadOperation.CATEGORY_BUCKET, session) { call ->
                requireNotNull(call.request().tag(HfrCallTag::class.java))
                    .executing(testScheduler.currentTime * NANOS_PER_MS)
                call.execute().use { }
            }
        }.exceptionOrNull()
        assertTrue(error is ConnectException)
        val summaries = fetch.callSnapshot()
        assertEquals(2, summaries.size)
        assertEquals(listOf(0L, 0L), summaries.map { it.waitMs })
        assertEquals(listOf(5_000L, 100L), summaries.map { it.totalMs })
        assertNull(original.executeNanos)
    }

    @Test
    fun `five Calls across sessions retain their permits through the response lifetime`() = runTest {
        val close = CompletableDeferred<Unit>()
        val sessions = List(3) { FlagReadSession { testScheduler.currentTime * NANOS_PER_MS } }
        var running = 0
        var peak = 0
        val calls = List(14) { index ->
            async {
                executor.execute(client, request, FlagReadOperation.CATEGORY_BUCKET, sessions[index % sessions.size]) {
                    running++
                    peak = maxOf(peak, running)
                    try { close.await() } finally { running-- }
                }
            }
        }
        runCurrent()
        assertEquals(5, running)
        assertEquals(5, limiter.heldPermits)
        advanceTimeBy(250)
        close.complete(Unit)
        calls.awaitAll()
        assertEquals(5, peak)
        assertEquals(0, limiter.heldPermits)
        assertTrue(sessions.sumOf { it.counters().getValue("permitWaitMs") } >= 250)
    }

    @Test
    fun `deadline cancels active transports and permit waiters at twelve virtual seconds`() = runTest {
        val session = FlagReadSession { testScheduler.currentTime * NANOS_PER_MS }
        var cancelled = 0
        val calls = List(7) {
            async {
                runCatching {
                    executor.execute(client, request, FlagReadOperation.CATEGORY_BUCKET, session) {
                        try { awaitCancellation() } finally { cancelled++ }
                    }
                }.exceptionOrNull()
            }
        }
        runCurrent()
        advanceTimeBy(11_999)
        assertEquals(5, limiter.heldPermits)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(calls.awaitAll().all { it is FlagReadDeadlineException })
        assertEquals(5, cancelled)
        assertEquals(0, limiter.heldPermits)
        assertEquals(5L, session.counters().getValue("applicationCalls"))
    }

    @Test
    fun `TCP error cannot retry before a first successful Call`() = runTest {
        coEvery { jitter.pause() } coAnswers { error("circuit must remain closed") }
        val session = FlagReadSession()
        val failure = runCatching {
            executor.execute(tcpFailureClient(), request,
                FlagReadOperation.CATEGORY_BUCKET, session) { it.execute().use { } }
        }.exceptionOrNull()
        assertTrue(failure is ConnectException)
        assertEquals(1L, session.counters().getValue("applicationCalls"))
        assertEquals(0L, session.counters().getValue("retriesAttempted"))
        assertEquals(0, limiter.heldPermits)
    }

    @Test
    fun `one retry only and shared refresh ceiling is ten even for concurrent failures`() = runTest {
        coEvery { jitter.pause() } coAnswers { delay(100) }
        val session = FlagReadSession { testScheduler.currentTime * NANOS_PER_MS }
        executor.execute(client, request, FlagReadOperation.CATEGORY_BUCKET, session) { Unit }
        val failures = List(14) {
            async {
                runCatching {
                    executor.execute(tcpFailureClient(), request,
                        FlagReadOperation.CATEGORY_BUCKET, session) { it.execute().use { } }
                }.exceptionOrNull()
            }
        }
        assertTrue(failures.awaitAll().all { it is ConnectException })
        val counters = session.counters()
        assertEquals(15L, counters.getValue("logicalOperations"))
        assertEquals(25L, counters.getValue("applicationCalls"))
        assertEquals(10L, counters.getValue("retriesAttempted"))
        assertEquals(0L, counters.getValue("retriesSucceeded"))
        assertEquals(0, limiter.heldPermits)
    }

    @Test
    fun `retry releases permit during jitter and reacquires it for the successful Call`() = runTest {
        val session = FlagReadSession { testScheduler.currentTime * NANOS_PER_MS }
        executor.execute(client, request, FlagReadOperation.CATEGORY_BUCKET, session) { Unit }
        coEvery { jitter.pause() } coAnswers {
            assertEquals(0, limiter.heldPermits)
            executor.execute(client, request,
                FlagReadOperation.CATEGORY_BUCKET, session) { assertEquals(1, limiter.heldPermits) }
            delay(200)
        }
        var attempt = 0
        executor.execute(tcpFailureClient(), request, FlagReadOperation.CATEGORY_BUCKET, session) {
            attempt++
            if (attempt == 1) it.execute().use { } else assertEquals(1, limiter.heldPermits)
        }
        assertEquals(2, attempt)
        assertEquals(1L, session.counters().getValue("retriesSucceeded"))
        assertEquals(0, limiter.heldPermits)
    }

    @Test
    fun `cancellation or deadline during jitter never starts another Call`() = runTest {
        listOf(false, true).forEach { cancel ->
            val session = FlagReadSession { testScheduler.currentTime * NANOS_PER_MS }
            executor.execute(client, request, FlagReadOperation.CATEGORY_BUCKET, session) { Unit }
            coEvery { jitter.pause() } coAnswers { delay(20_000) }
            val work = async {
                runCatching {
                    executor.execute(tcpFailureClient(), request,
                        FlagReadOperation.CATEGORY_BUCKET, session) { it.execute().use { } }
                }
            }
            runCurrent()
            if (cancel) work.cancel() else advanceTimeBy(12_000)
            work.join()
            assertEquals(2L, session.counters().getValue("applicationCalls"))
            assertEquals(0L, session.counters().getValue("retriesAttempted"))
            assertEquals(0, limiter.heldPermits)
        }
    }

    @Test
    fun `unknown phase and failures beyond TCP are never eligible`() {
        val eligible = listOf(ConnectException(), NoRouteToHostException(), SocketTimeoutException())
        val excluded = listOf(IOException(), UnknownHostException(), SSLException("tls"), SocketException())
        (eligible + excluded).forEach { error ->
            assertFalse(FlagReadConnectionPhase().canRetry(error))
            val tcp = FlagReadConnectionPhase().apply { connecting(); connectFailed() }
            assertEquals(error in eligible, tcp.canRetry(error))
            tcp.connected() // TLS, reused connection, headers, response or prior route success.
            assertFalse(tcp.canRetry(error))
        }
    }

    @Test
    fun `functional listener rejects TLS and HTTP phases without diagnostics`() {
        val call = client.newCall(request)
        listOf<(EventListener) -> Unit>(
            { it.secureConnectStart(call) },
            { it.connectEnd(call, ADDRESS, Proxy.NO_PROXY, null) },
            { it.connectionAcquired(call, mockk()) },
            { it.requestHeadersStart(call) },
            { it.responseBodyStart(call) },
        ).forEach { event ->
            val phase = FlagReadConnectionPhase()
            val listener = FlagReadEventListener(phase, EventListener.NONE)
            listener.connectStart(call, ADDRESS, Proxy.NO_PROXY)
            event(listener)
            listener.connectFailed(call, ADDRESS, Proxy.NO_PROXY, null, ConnectException())
            assertFalse(phase.canRetry(ConnectException()))
        }
    }

    @Test
    fun `proxy negotiation has no proven pre-HTTP phase and cannot retry`() {
        val phase = FlagReadConnectionPhase()
        val listener = FlagReadEventListener(phase, EventListener.NONE)
        val call = client.newCall(request)
        val proxy = Proxy(Proxy.Type.HTTP, ADDRESS)
        listener.connectStart(call, ADDRESS, proxy)
        listener.connectFailed(call, ADDRESS, proxy, null, SocketTimeoutException())
        assertFalse(phase.canRetry(SocketTimeoutException()))
    }

    @Test
    fun `unknown phase does not retry even after success`() = runTest {
        val session = FlagReadSession()
        executor.execute(client, request, FlagReadOperation.CATEGORY_BUCKET, session) { Unit }
        val error = runCatching {
            executor.execute(client, request,
                FlagReadOperation.CATEGORY_BUCKET, session) { throw SocketTimeoutException() }
        }.exceptionOrNull()
        assertTrue(error is SocketTimeoutException)
        assertEquals(0L, session.counters().getValue("retriesAttempted"))
    }

    @Test
    fun `Call timeout is capped by remaining budget and TCP timeout is five seconds`() = runTest {
        val timeouts = mutableListOf<Int>()
        val session = FlagReadSession { testScheduler.currentTime * NANOS_PER_MS }
        advanceTimeBy(2_000)
        executor.execute(tcpFailureClient(timeouts), request, FlagReadOperation.CATEGORY_BUCKET, session) { call ->
            assertEquals(TimeUnit.SECONDS.toNanos(10), call.timeout().timeoutNanos())
            runCatching { call.execute().use { } }
            currentCoroutineContext().ensureActive()
        }
        assertEquals(listOf(5_000), timeouts)
        assertEquals(10_000L, session.remainingMillis())
    }

    @Test
    fun `POST never enters the read executor`() = runTest {
        val post = request.newBuilder().method("POST", okhttp3.RequestBody.EMPTY).build()
        val error = runCatching {
            executor.execute(client, post,
                FlagReadOperation.CATEGORY_BUCKET, FlagReadSession()) { error("must not execute") }
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0, limiter.heldPermits)
    }

    @Test
    fun `captured global operation uses the same read permit`() = runTest {
        var called = false
        executor.execute(client, request, FlagReadOperation.GLOBAL_BUCKET, FlagReadSession()) {
            assertEquals(1, limiter.heldPermits)
            called = true
        }
        assertTrue(called)
        assertEquals(0, limiter.heldPermits)
    }

    /** Real OkHttp event ordering with a deterministic TCP failure, no OS connect or DNS lookup. */
    private fun tcpFailureClient(
        timeouts: MutableList<Int> = mutableListOf(),
        onConnect: () -> Unit = {},
    ): OkHttpClient =
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .dns { listOf(ADDRESS.address) }
            .socketFactory(object : SocketFactory() {
                override fun createSocket(): Socket = object : Socket() {
                    // OkHttp sets the read timeout before connect; do not allocate an OS socket.
                    override fun setSoTimeout(timeout: Int) = Unit
                    override fun connect(endpoint: SocketAddress?, timeout: Int) {
                        timeouts += timeout
                        onConnect()
                        throw ConnectException("simulated TCP refusal")
                    }
                }
                override fun createSocket(host: String, port: Int): Socket = error("unused")
                override fun createSocket(host: InetAddress, port: Int): Socket = error("unused")
                override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
                    error("unused")
                override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
                    error("unused")
            })
            .build()

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        val ADDRESS = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 443)
    }
}
