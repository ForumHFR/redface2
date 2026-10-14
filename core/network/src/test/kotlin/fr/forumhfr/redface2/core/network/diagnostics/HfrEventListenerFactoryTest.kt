package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.FlagFetchTrace
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCallOutcome
import fr.forumhfr.redface2.core.domain.diagnostics.HfrClientRole
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshDiagnostics
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshTrigger
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshPhase
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshTrace
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCatalogueTrace
import fr.forumhfr.redface2.core.model.FlagType
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.Executors
import okhttp3.Address
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HfrEventListenerFactoryTest {
    private val log = DiagnosticsLog()
    private val collector = HfrNetworkDiagnostics(log)
    private val fetch = FlagFetchTrace(1, FlagType.CYAN)
    private var now = 0L
    private val factory = HfrEventListenerFactory(collector, HfrClientRole.AUTH) { now }
    private val address = InetSocketAddress.createUnresolved("unused", 443)
    private var activeRefresh: FlagRefreshTrace? = null

    @Test
    fun `new and reused connections retain all timings without consuming buffer entries`() {
        val (call, listener) = newCall()
        at(100) { call.request().tag(HfrCallTag::class.java)!!.executing(now); listener.callStart(call) }
        at(110) { listener.dnsStart(call, "unused") }
        at(120) { listener.dnsEnd(call, "unused", emptyList()) }
        at(125) { listener.connectStart(call, address, Proxy.NO_PROXY) }
        at(130) { listener.secureConnectStart(call) }
        at(160) { listener.secureConnectEnd(call, null) }
        at(180) { listener.connectEnd(call, address, Proxy.NO_PROXY, Protocol.HTTP_2) }
        listener.connectionAcquired(call, connection())
        at(200) { listener.requestHeadersEnd(call, call.request()) }
        at(400) { listener.responseHeadersEnd(call, response(call, 200)); listener.responseBodyStart(call) }
        at(500) { listener.responseBodyEnd(call, 123); listener.callEnd(call) }
        val fresh = fetch.callSnapshot().single()
        assertEquals(100L, fresh.waitMs)
        assertEquals(10L, fresh.dnsMs)
        assertEquals(55L, fresh.connectMs)
        assertEquals(30L, fresh.tlsMs)
        assertEquals(200L, fresh.headersSentToReceivedMs)
        assertEquals(100L, fresh.bodyMs)
        assertEquals(500L, fresh.totalMs)
        assertEquals("h2", fresh.protocol)
        assertEquals(1, fresh.inFlightAtStart)
        assertFalse(fresh.reusedConnection)

        val (second, secondListener) = newCall()
        second.request().tag(HfrCallTag::class.java)!!.executing(now)
        secondListener.callStart(second)
        secondListener.connectionAcquired(second, connection())
        at(600) { secondListener.requestHeadersEnd(second, second.request()) }
        at(700) { secondListener.responseHeadersEnd(second, response(second, 200)) }
        at(800) { secondListener.callEnd(second) }
        val reused = fetch.callSnapshot().last()
        assertTrue(reused.reusedConnection)
        assertEquals(0L, reused.connectMs)
        assertEquals(1, reused.inFlightAtStart)
        assertEquals(HfrCallOutcome.OK, reused.outcome)
        assertTrue(log.entries.value.isEmpty())
    }

    @Test
    fun `slow failed cancelled and HTTP failure calls produce safe lines exactly once`() {
        beginRefresh()
        val (slow, slowListener) = newCall()
        slowListener.callStart(slow)
        at(1_000) { slowListener.responseHeadersEnd(slow, response(slow, 200)); slowListener.callEnd(slow) }
        slowListener.callEnd(slow)
        assertTrue(log.entries.value.none { it.tag == "HfrCall" })

        val (failed, failedListener) = newCall()
        failedListener.callStart(failed)
        failedListener.callFailed(failed, IOException("Cookie=SecretCookie post=35395 hash_check=SecretHash"))
        val (cancelled, cancelledListener) = newCall()
        cancelledListener.callStart(cancelled)
        cancelled.cancel()
        cancelledListener.callFailed(cancelled, IOException("secret proxy host"))
        val (httpFailure, httpListener) = newCall()
        httpListener.callStart(httpFailure)
        httpListener.responseHeadersEnd(httpFailure, response(httpFailure, 503))
        httpListener.callEnd(httpFailure)

        flushRefresh()
        assertEquals(4, log.entries.value.count { it.tag == "HfrCall" })
        assertEquals(
            listOf(HfrCallOutcome.OK, HfrCallOutcome.FAILURE, HfrCallOutcome.CANCELLED, HfrCallOutcome.FAILURE),
            fetch.callSnapshot().map { it.outcome },
        )
        assertEquals("IOException", fetch.callSnapshot()[1].exceptionClass)
        log.entries.value.forEach { entry ->
            listOf("Secret", "Cookie", "35395", "hash_check", "proxy", "https", "?").forEach {
                assertFalse(entry.message, entry.message.contains(it))
            }
        }
    }

    @Test
    fun `waiting before execute alone can make an otherwise fast call slow`() {
        beginRefresh()
        val (call, listener) = newCall()
        at(1_500) { call.request().tag(HfrCallTag::class.java)!!.executing(now); listener.callStart(call) }
        at(1_501) { listener.responseHeadersEnd(call, response(call, 200)); listener.callEnd(call) }
        val summary = fetch.callSnapshot().single()
        assertEquals(1_500L, summary.waitMs)
        assertEquals(1_501L, summary.totalMs)
        flushRefresh()
        assertEquals(1, log.entries.value.count { it.tag == "HfrCall" })
    }

    @Test
    fun `concurrent HFR calls count in-flight calls while images bypass the collector`() {
        val (first, firstListener) = newCall()
        val (second, secondListener) = newCall()
        firstListener.callStart(first)
        secondListener.callStart(second)
        firstListener.callEnd(first)
        secondListener.callEnd(second)
        assertEquals(listOf(1, 2), fetch.callSnapshot().map { it.inFlightAtStart })
        val image = OkHttpClient().newCall(Request.Builder().url("https://forum.hardware.fr/images/test.gif").build())
        assertSame(EventListener.NONE, factory.create(image))
    }

    @Test
    fun `overlapping connections retain B timings when A fails during B TLS`() {
        overlappingConnections(winnerIsA = false)
    }

    @Test
    fun `overlapping connections retain A timings when B fails during A TLS`() {
        overlappingConnections(winnerIsA = true)
    }

    private fun overlappingConnections(winnerIsA: Boolean) {
        val (call, listener) = newCall()
        val secondAddress = InetSocketAddress.createUnresolved("second-unused", 443)
        val workerA = Executors.newSingleThreadExecutor()
        val workerB = Executors.newSingleThreadExecutor()
        try {
            listener.callStart(call)
            at(10) { workerA.submit { listener.connectStart(call, address, Proxy.NO_PROXY) }.get() }
            at(30) { workerB.submit { listener.connectStart(call, secondAddress, Proxy.NO_PROXY) }.get() }
            val winner = if (winnerIsA) workerA else workerB
            val loser = if (winnerIsA) workerB else workerA
            val winningAddress = if (winnerIsA) address else secondAddress
            val losingAddress = if (winnerIsA) secondAddress else address
            // Fast fallback negotiates TLS only for the selected TCP route, serially per call.
            at(40) { winner.submit { listener.secureConnectStart(call) }.get() }
            at(50) {
                loser.submit {
                    listener.connectFailed(call, losingAddress, Proxy.NO_PROXY, null, IOException("Secret"))
                }.get()
            }
            at(70) { winner.submit { listener.secureConnectEnd(call, null) }.get() }
            at(90) {
                winner.submit { listener.connectEnd(call, winningAddress, Proxy.NO_PROXY, Protocol.HTTP_2) }.get()
            }
            listener.connectionAcquired(call, connection(winningAddress))
            at(100) { listener.responseHeadersEnd(call, response(call, 200)); listener.callEnd(call) }

            val summary = fetch.callSnapshot().single()
            assertEquals(if (winnerIsA) 80L else 60L, summary.connectMs)
            assertEquals(30L, summary.tlsMs)
            assertEquals(if (winnerIsA) 20L else 40L, summary.failedConnectMs)
            assertEquals(summary.failedConnectMs, summary.maxFailedConnectMs)
            assertEquals(0L, summary.failedTlsMs)
            assertEquals(2, summary.connectAttempts)
            assertEquals(1, summary.connectFailures)
            assertFalse(summary.reusedConnection)
            assertEquals(HfrCallOutcome.OK, summary.outcome)
        } finally {
            workerA.shutdownNow()
            workerB.shutdownNow()
        }
    }

    @Test
    fun `responseFailed followed by callFailed accounts for the body and call only once`() {
        beginRefresh()
        val (call, listener) = newCall()
        listener.callStart(call)
        at(10) { listener.requestHeadersEnd(call, call.request()) }
        at(20) { listener.responseHeadersEnd(call, response(call, 200)) }
        at(30) { listener.responseBodyStart(call) }
        at(80) { listener.responseFailed(call, IOException("SecretBody")) }
        at(100) { listener.callFailed(call, IOException("SecretMessage")) }
        listener.callFailed(call, IOException("SecretDuplicate"))

        val summary = fetch.callSnapshot().single()
        assertEquals(50L, summary.bodyMs)
        assertEquals(10L, summary.headersSentToReceivedMs)
        assertEquals(100L, summary.totalMs)
        assertEquals(HfrCallOutcome.FAILURE, summary.outcome)
        assertEquals("IOException", summary.exceptionClass)
        val (next, nextListener) = newCall()
        nextListener.callStart(next)
        nextListener.callEnd(next)
        assertEquals(1, fetch.callSnapshot().last().inFlightAtStart)
        flushRefresh()
        assertEquals(1, log.entries.value.count { it.tag == "HfrCall" })
        assertFalse(log.entries.value.any { it.message.contains("Secret") })
    }

    @Test
    fun `redirect with two successive connections to the same address preserves both successful timings`() {
        val (call, listener) = newCall()
        listener.callStart(call)
        at(10) { listener.connectStart(call, address, Proxy.NO_PROXY) }
        at(20) { listener.secureConnectStart(call) }
        at(40) { listener.secureConnectEnd(call, null) }
        at(50) { listener.connectEnd(call, address, Proxy.NO_PROXY, Protocol.HTTP_2) }
        listener.connectionAcquired(call, connection())
        at(60) { listener.requestHeadersEnd(call, call.request()) }
        at(70) { listener.responseHeadersEnd(call, response(call, 302)) }
        at(100) { listener.connectStart(call, address, Proxy.NO_PROXY) }
        at(110) { listener.secureConnectStart(call) }
        at(130) { listener.secureConnectEnd(call, null) }
        at(150) { listener.connectEnd(call, address, Proxy.NO_PROXY, Protocol.HTTP_2) }
        listener.connectionAcquired(call, connection())
        at(160) { listener.requestHeadersEnd(call, call.request()) }
        at(180) { listener.responseHeadersEnd(call, response(call, 200)) }
        at(200) { listener.callEnd(call) }

        val summary = fetch.callSnapshot().single()
        assertEquals(90L, summary.connectMs)
        assertEquals(40L, summary.tlsMs)
        assertEquals(30L, summary.headersSentToReceivedMs)
        assertEquals(2, summary.connectAttempts)
        assertEquals(0, summary.connectFailures)
        assertEquals(200, summary.status)
        assertEquals(HfrCallOutcome.OK, summary.outcome)
        assertFalse(summary.reusedConnection)
    }

    @Test
    fun `retry to the same address excludes the failed attempt from winning connection timings`() {
        val (call, listener) = newCall()
        listener.callStart(call)
        at(10) { listener.connectStart(call, address, Proxy.NO_PROXY) }
        at(20) { listener.secureConnectStart(call) }
        at(40) { listener.connectFailed(call, address, Proxy.NO_PROXY, null, IOException("SecretTLS")) }
        at(50) { listener.connectStart(call, address, Proxy.NO_PROXY) }
        at(60) { listener.secureConnectStart(call) }
        at(80) { listener.secureConnectEnd(call, null) }
        at(100) { listener.connectEnd(call, address, Proxy.NO_PROXY, Protocol.HTTP_2) }
        listener.connectionAcquired(call, connection())
        at(120) { listener.responseHeadersEnd(call, response(call, 200)); listener.callEnd(call) }

        val summary = fetch.callSnapshot().single()
        assertEquals(50L, summary.connectMs)
        assertEquals(20L, summary.tlsMs)
        assertEquals(2, summary.connectAttempts)
        assertEquals(1, summary.connectFailures)
        assertEquals(30L, summary.failedConnectMs)
        assertEquals(30L, summary.maxFailedConnectMs)
        assertEquals(20L, summary.failedTlsMs)
        assertEquals(20L, summary.maxFailedTlsMs)
        assertEquals(HfrCallOutcome.OK, summary.outcome)
    }

    @Test
    fun `blocked TCP and failed TLS attempts retain cumulative and maximum durations in the export`() {
        beginRefresh()
        val (call, listener) = newCall()
        listener.callStart(call)
        at(100) { listener.connectStart(call, address, Proxy.NO_PROXY) }
        at(10_100) { listener.connectFailed(call, address, Proxy.NO_PROXY, null, IOException("SecretTCP")) }
        at(10_200) { listener.connectStart(call, address, Proxy.NO_PROXY) }
        at(10_300) { listener.secureConnectStart(call) }
        at(12_300) { listener.connectFailed(call, address, Proxy.NO_PROXY, null, IOException("SecretTLS")) }
        at(12_400) { listener.connectStart(call, address, Proxy.NO_PROXY) }
        at(12_500) { listener.secureConnectStart(call) }
        at(13_500) { listener.connectFailed(call, address, Proxy.NO_PROXY, null, IOException("SecretRetry")) }
        at(13_600) { listener.callFailed(call, IOException("SecretTerminal")) }

        val summary = fetch.callSnapshot().single()
        assertEquals(0L, summary.connectMs)
        assertEquals(0L, summary.tlsMs)
        assertEquals(13_200L, summary.failedConnectMs)
        assertEquals(10_000L, summary.maxFailedConnectMs)
        assertEquals(3_000L, summary.failedTlsMs)
        assertEquals(2_000L, summary.maxFailedTlsMs)
        assertEquals(3, summary.connectFailures)
        flushRefresh()
        log.entries.value.filter { it.tag == "HfrCall" || it.message.contains("outcome=") }.forEach {
            assertTrue(it.message, it.message.contains("failedConnectMs=13200 maxFailedConnectMs=10000"))
            assertTrue(it.message, it.message.contains("failedTlsMs=3000 maxFailedTlsMs=2000"))
            listOf("Secret", "unused", "443", "https", "Cookie").forEach { secret ->
                assertFalse(it.message, it.message.contains(secret))
            }
        }
    }

    @Test
    fun `callFailed closes every pending connection and handshake exactly once`() {
        val (call, listener) = newCall()
        val secondAddress = InetSocketAddress.createUnresolved("SecretHost", 443)
        listener.callStart(call)
        at(100) { listener.connectStart(call, address, Proxy.NO_PROXY) }
        at(200) { listener.connectStart(call, secondAddress, Proxy.NO_PROXY) }
        at(300) { listener.secureConnectStart(call) }
        at(10_300) { listener.callFailed(call, IOException("SecretTerminal")) }
        at(10_400) {
            listener.connectFailed(call, address, Proxy.NO_PROXY, null, IOException("SecretLate"))
            listener.connectEnd(call, secondAddress, Proxy.NO_PROXY, Protocol.HTTP_2)
            listener.secureConnectEnd(call, null)
            listener.callFailed(call, IOException("SecretDuplicate"))
        }

        val summary = fetch.callSnapshot().single()
        assertEquals(20_300L, summary.failedConnectMs)
        assertEquals(10_200L, summary.maxFailedConnectMs)
        assertEquals(10_000L, summary.failedTlsMs)
        assertEquals(10_000L, summary.maxFailedTlsMs)
        assertEquals(2, summary.connectAbandoned)
        assertEquals(0, summary.connectFailures)
        assertEquals(HfrCallOutcome.FAILURE, summary.outcome)
        assertEquals(1, log.entries.value.count { it.tag == "HfrCall" })
        assertFalse(log.entries.value.single().message.contains("SecretHost"))
    }

    @Test
    fun `cancellation closes a pending TLS attempt before terminal call failure without double counting`() {
        val (call, listener) = newCall()
        listener.callStart(call)
        at(100) { listener.connectStart(call, address, Proxy.NO_PROXY) }
        at(200) { listener.secureConnectStart(call) }
        at(10_200) { call.cancel(); listener.canceled(call) }
        at(10_300) {
            listener.connectFailed(call, address, Proxy.NO_PROXY, null, IOException("SecretLate"))
            listener.callFailed(call, IOException("SecretCancelled"))
        }
        listener.canceled(call)

        val summary = fetch.callSnapshot().single()
        assertEquals(10_100L, summary.failedConnectMs)
        assertEquals(10_100L, summary.maxFailedConnectMs)
        assertEquals(10_000L, summary.failedTlsMs)
        assertEquals(10_000L, summary.maxFailedTlsMs)
        assertEquals(1, summary.connectAbandoned)
        assertEquals(HfrCallOutcome.CANCELLED, summary.outcome)
        val (next, nextListener) = newCall()
        nextListener.callStart(next)
        nextListener.callEnd(next)
        assertEquals(1, fetch.callSnapshot().last().inFlightAtStart)
        assertEquals(1, log.entries.value.count { it.tag == "HfrCall" })
    }

    @Test
    fun `TCP racers and TLS callbacks on different threads retain the acquired handshake`() {
        val (call, listener) = newCall()
        val secondAddress = InetSocketAddress.createUnresolved("SecretOtherHost", 443)
        val tcpWorker = Executors.newSingleThreadExecutor()
        val tlsWorker = Executors.newSingleThreadExecutor()
        try {
            listener.callStart(call)
            at(10) { tcpWorker.submit { listener.connectStart(call, address, Proxy.NO_PROXY) }.get() }
            at(20) { tcpWorker.submit { listener.connectStart(call, secondAddress, Proxy.NO_PROXY) }.get() }
            at(30) { tlsWorker.submit { listener.secureConnectStart(call) }.get() }
            at(40) {
                tcpWorker.submit {
                    listener.connectFailed(call, secondAddress, Proxy.NO_PROXY, null, IOException("SecretTCP"))
                }.get()
            }
            at(70) { listener.secureConnectEnd(call, null) }
            at(90) {
                tlsWorker.submit { listener.connectEnd(call, address, Proxy.NO_PROXY, Protocol.HTTP_2) }.get()
            }
            listener.connectionAcquired(call, connection())
            at(100) { listener.callEnd(call) }

            val summary = fetch.callSnapshot().single()
            assertEquals(80L, summary.connectMs)
            assertEquals(40L, summary.tlsMs)
            assertEquals(20L, summary.failedConnectMs)
            assertEquals(0L, summary.failedTlsMs)
            assertFalse(summary.reusedConnection)
        } finally {
            tcpWorker.shutdownNow()
            tlsWorker.shutdownNow()
        }
    }

    @Test
    fun `TLS failure on a different thread from TCP retains the handshake duration`() {
        val (call, listener) = newCall()
        val tcpWorker = Executors.newSingleThreadExecutor()
        val tlsWorker = Executors.newSingleThreadExecutor()
        try {
            listener.callStart(call)
            at(10) { tcpWorker.submit { listener.connectStart(call, address, Proxy.NO_PROXY) }.get() }
            at(20) { tlsWorker.submit { listener.secureConnectStart(call) }.get() }
            at(10_020) {
                listener.connectFailed(call, address, Proxy.NO_PROXY, null, IOException("SecretTLS"))
            }
            at(10_100) { listener.callFailed(call, IOException("SecretTerminal")) }

            val summary = fetch.callSnapshot().single()
            assertEquals(10_010L, summary.failedConnectMs)
            assertEquals(10_000L, summary.failedTlsMs)
            assertEquals(10_000L, summary.maxFailedTlsMs)
            assertEquals(HfrCallOutcome.FAILURE, summary.outcome)
        } finally {
            tcpWorker.shutdownNow()
            tlsWorker.shutdownNow()
        }
    }

    @Test
    fun `connections to the same address through different proxies keep separate attempt timings`() {
        val (call, listener) = newCall()
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("SecretProxyHost", 8080))
        listener.callStart(call)
        at(10) { listener.connectStart(call, address, Proxy.NO_PROXY) }
        at(20) { listener.connectStart(call, address, proxy) }
        at(30) { listener.secureConnectStart(call) }
        at(60) { listener.secureConnectEnd(call, null) }
        at(70) { listener.connectFailed(call, address, Proxy.NO_PROXY, null, IOException("SecretTCP")) }
        at(90) { listener.connectEnd(call, address, proxy, Protocol.HTTP_2) }
        listener.connectionAcquired(call, connection(routeProxy = proxy))
        at(100) { listener.callEnd(call) }

        val summary = fetch.callSnapshot().single()
        assertEquals(70L, summary.connectMs)
        assertEquals(30L, summary.tlsMs)
        assertEquals(60L, summary.failedConnectMs)
        assertEquals(0L, summary.failedTlsMs)
        listOf("unused", "SecretProxyHost", "8080").forEach {
            assertFalse(summary.line(), summary.line().contains(it))
        }
    }

    @Test
    fun `listener publishes a late failure after its refresh has closed`() {
        val refresh = beginRefresh()
        val (call, listener) = newCall()
        listener.callStart(call)
        at(10) { listener.connectStart(call, address, Proxy.NO_PROXY) }
        refresh.finish()
        assertTrue(log.entries.value.none { it.tag == "HfrCall" })
        at(1_500) { listener.callFailed(call, IOException("SecretLateFailure")) }

        assertEquals(1, log.entries.value.count { it.tag == "HfrCall" })
        assertEquals(fetch.callSnapshot().single().copy(refreshId = null).line(), log.entries.value.last().message)
        assertTrue(log.entries.value.last().message.contains("failedConnectMs=1490"))
    }

    @Test
    fun `two successful connection attempts contribute only the acquired route timings`() {
        val (call, listener) = newCall()
        val secondAddress = InetSocketAddress.createUnresolved("second-unused", 443)
        listener.callStart(call)
        at(10) { listener.connectStart(call, address, Proxy.NO_PROXY) }
        at(20) { listener.secureConnectStart(call) }
        at(30) { listener.secureConnectEnd(call, null) }
        at(40) { listener.connectStart(call, secondAddress, Proxy.NO_PROXY) }
        at(50) { listener.secureConnectStart(call) }
        at(60) { listener.secureConnectEnd(call, null) }
        at(70) { listener.connectEnd(call, address, Proxy.NO_PROXY, Protocol.HTTP_2) }
        at(90) { listener.connectEnd(call, secondAddress, Proxy.NO_PROXY, Protocol.HTTP_2) }
        listener.connectionAcquired(call, connection(secondAddress))
        at(100) { listener.callEnd(call) }

        val summary = fetch.callSnapshot().single()
        assertEquals(50L, summary.connectMs)
        assertEquals(10L, summary.tlsMs)
        assertEquals(2, summary.connectAttempts)
        assertEquals(0, summary.connectFailures)
    }

    @Test
    fun `114 slow correlated calls defer details until the twelve line refresh quota is applied`() {
        val trace = FlagRefreshDiagnostics(log).begin(FlagRefreshTrigger.PULL, listOf(FlagType.CYAN))
        trace.attach(fetch, false)
        repeat(114) {
            val (call, listener) = newCall()
            listener.callStart(call)
            at(now / 1_000_000 + 1_500) {
                listener.responseHeadersEnd(call, response(call, 200))
                listener.callEnd(call)
            }
        }
        assertEquals(1, log.entries.value.size)
        fetch.markFinished()
        trace.result(FlagType.CYAN, fetch)
        trace.published()
        trace.finish()

        assertEquals(14, log.entries.value.size)
        assertEquals(12, log.entries.value.count { it.tag == "HfrCall" })
        assertTrue(log.entries.value.first().message.contains("trigger=pull"))
        assertTrue(log.entries.value.last().message.contains("calls=114 "))
        assertTrue(log.entries.value.last().message.contains("details-omitted=102 "))
    }

    @Test
    fun `uncorrelated slow calls share a bounded global detail window`() {
        repeat(114) {
            val (call, listener) = newCall(correlated = false)
            listener.callStart(call)
            at(now / 1_000_000 + 1_500) { listener.callEnd(call) }
        }
        assertEquals(HfrNetworkDiagnostics.MAX_WINDOW_DETAILS, log.entries.value.size)
        assertTrue(log.entries.value.all { it.tag == "HfrCall" })
        assertTrue(fetch.callSnapshot().isEmpty())
    }

    @Test
    fun `catalogue started without a refresh retains transport timings for a later joiner`() {
        val catalogue = HfrCatalogueTrace()
        val request = Request.Builder()
            .url("https://forum.hardware.fr/webservices/rest_api.php?uri=forums/hardwarefr/categories/")
            .tag(HfrCallTag::class.java, HfrCallTag(now, null, catalogue))
            .build()
        val call = OkHttpClient().newCall(request)
        val listener = factory.create(call)
        listener.callStart(call)
        val trace = FlagRefreshDiagnostics(log).begin(FlagRefreshTrigger.PULL, listOf(FlagType.CYAN))
        fetch.catalogue(catalogue, wasJoined = true)
        trace.attach(fetch, false)
        at(100) { listener.dnsStart(call, "unused") }
        at(200) { listener.dnsEnd(call, "unused", emptyList()) }
        at(300) { listener.requestHeadersEnd(call, request) }
        at(2_000) { listener.responseHeadersEnd(call, response(call, 200)); listener.callEnd(call) }
        assertEquals(1, log.entries.value.size)
        val summary = fetch.callSnapshot().single()
        assertEquals(FlagRefreshPhase.CATALOGUE, summary.phase)
        assertEquals(100L, summary.dnsMs)
        assertEquals(1_700L, summary.headersSentToReceivedMs)
        assertEquals(2_000L, summary.totalMs)
        assertEquals(fetch.refreshId, summary.refreshId)
        trace.result(fetch.type, fetch)
        trace.finish()
        assertTrue(log.entries.value.last().message.contains("catalogue=joined"))
        assertTrue(log.entries.value.last().message.contains("catalogueCallMs=2000"))
    }

    private fun flushRefresh() {
        val trace = activeRefresh ?: beginRefresh()
        trace.result(FlagType.CYAN, fetch)
        trace.finish()
    }

    private fun beginRefresh(): FlagRefreshTrace =
        FlagRefreshDiagnostics(log).begin(FlagRefreshTrigger.PULL, listOf(FlagType.CYAN)).also {
            it.attach(fetch, false)
            activeRefresh = it
        }

    private fun newCall(correlated: Boolean = true): Pair<Call, EventListener> {
        val tag = HfrCallTag(now, if (correlated) fetch else null)
        val request = Request.Builder()
            .url("https://forum.hardware.fr/webservices/rest_api.php?" +
                "uri=forums/hardwarefr/categories/23/topics/participated/&post=35395&hash_check=SecretHash")
            .header("Cookie", "md_user=SecretCookie; md_pass=SecretPassword")
            .header("Proxy-Authorization", "Basic SecretProxy")
            .tag(HfrCallTag::class.java, tag).build()
        val call = OkHttpClient().newCall(request)
        return call to factory.create(call)
    }

    private fun response(call: Call, code: Int): Response = Response.Builder()
        .request(call.request()).protocol(Protocol.HTTP_2).code(code).message("SecretResponse")
        .header("Set-Cookie", "SecretResponseCookie").body("SecretBody".toResponseBody()).build()

    private fun connection(
        socketAddress: InetSocketAddress = address,
        routeProxy: Proxy = Proxy.NO_PROXY,
    ): Connection = object : Connection {
        override fun route(): Route {
            val client = OkHttpClient()
            val address = Address(
                uriHost = "unused", uriPort = 443, dns = client.dns, socketFactory = client.socketFactory,
                sslSocketFactory = client.sslSocketFactory, hostnameVerifier = client.hostnameVerifier,
                certificatePinner = client.certificatePinner, proxyAuthenticator = client.proxyAuthenticator,
                proxy = null, protocols = client.protocols, connectionSpecs = client.connectionSpecs,
                proxySelector = client.proxySelector,
            )
            return Route(address, routeProxy, socketAddress)
        }
        override fun socket(): Socket = error("Not used by diagnostics")
        override fun handshake(): Handshake? = null
        override fun protocol(): Protocol = Protocol.HTTP_2
    }

    private fun at(ms: Long, block: () -> Unit) { now = ms * 1_000_000; block() }
}
