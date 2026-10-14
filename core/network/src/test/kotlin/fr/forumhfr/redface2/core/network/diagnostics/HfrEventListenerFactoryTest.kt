package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.FlagFetchTrace
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCallOutcome
import fr.forumhfr.redface2.core.domain.diagnostics.HfrClientRole
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshDiagnostics
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshTrigger
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshPhase
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
        val (slow, slowListener) = newCall()
        slowListener.callStart(slow)
        at(1_000) { slowListener.responseHeadersEnd(slow, response(slow, 200)); slowListener.callEnd(slow) }
        slowListener.callEnd(slow)
        assertTrue(log.entries.value.isEmpty())

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
            at(20) { workerA.submit { listener.secureConnectStart(call) }.get() }
            at(30) { workerB.submit { listener.connectStart(call, secondAddress, Proxy.NO_PROXY) }.get() }
            at(40) { workerB.submit { listener.secureConnectStart(call) }.get() }
            val winner = if (winnerIsA) workerA else workerB
            val loser = if (winnerIsA) workerB else workerA
            val winningAddress = if (winnerIsA) address else secondAddress
            val losingAddress = if (winnerIsA) secondAddress else address
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
            assertEquals(if (winnerIsA) 50L else 30L, summary.tlsMs)
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
        assertEquals(HfrCallOutcome.OK, summary.outcome)
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
        val trace = FlagRefreshDiagnostics(log).begin(FlagRefreshTrigger.PULL, listOf(FlagType.CYAN))
        trace.attach(fetch, false)
        trace.result(FlagType.CYAN, fetch)
        trace.finish()
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

    private fun connection(socketAddress: InetSocketAddress = address): Connection = object : Connection {
        override fun route(): Route {
            val client = OkHttpClient()
            val address = Address(
                uriHost = "unused", uriPort = 443, dns = client.dns, socketFactory = client.socketFactory,
                sslSocketFactory = client.sslSocketFactory, hostnameVerifier = client.hostnameVerifier,
                certificatePinner = client.certificatePinner, proxyAuthenticator = client.proxyAuthenticator,
                proxy = null, protocols = client.protocols, connectionSpecs = client.connectionSpecs,
                proxySelector = client.proxySelector,
            )
            return Route(address, Proxy.NO_PROXY, socketAddress)
        }
        override fun socket(): Socket = error("Not used by diagnostics")
        override fun handshake(): Handshake? = null
        override fun protocol(): Protocol = Protocol.HTTP_2
    }

    private fun at(ms: Long, block: () -> Unit) { now = ms * 1_000_000; block() }
}
