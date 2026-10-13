package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.FlagFetchTrace
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCallOutcome
import fr.forumhfr.redface2.core.domain.diagnostics.HfrClientRole
import fr.forumhfr.redface2.core.model.FlagType
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
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
        assertEquals(1, log.entries.value.size)

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

        assertEquals(4, log.entries.value.size)
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
        assertEquals(1, log.entries.value.size)
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

    private fun newCall(): Pair<Call, EventListener> {
        val tag = HfrCallTag(now, fetch)
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

    private fun connection(): Connection = object : Connection {
        override fun route(): Route = error("Not used by diagnostics")
        override fun socket(): Socket = error("Not used by diagnostics")
        override fun handshake(): Handshake? = null
        override fun protocol(): Protocol = Protocol.HTTP_2
    }

    private fun at(ms: Long, block: () -> Unit) { now = ms * 1_000_000; block() }
}
