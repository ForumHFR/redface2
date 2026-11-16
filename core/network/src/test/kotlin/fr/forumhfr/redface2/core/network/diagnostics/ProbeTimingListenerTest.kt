package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.diagnostics.HfrConnectErrno
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeOutcome
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import okhttp3.Address
import okhttp3.Connection
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Route
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeTimingListenerTest {
    private var now = 0L
    private val timing = ProbeTimingListener { now }
    private val call = OkHttpClient().newCall(Request.Builder().url("https://example.invalid/").build())
    private val address = InetSocketAddress.createUnresolved("example.invalid", 443)

    @Test
    fun `TCP TLS received headers and first body byte remain separate measurements`() {
        timing.callStart(call)
        tick(10)
        timing.connectStart(call, address, Proxy.NO_PROXY)
        tick(20)
        timing.secureConnectStart(call)
        tick(30)
        timing.secureConnectEnd(call, null)
        timing.connectEnd(call, address, Proxy.NO_PROXY, Protocol.HTTP_1_1)
        tick(40)
        timing.responseHeadersEnd(call, response())
        tick(50)
        timing.firstBodyByte()
        tick(60)
        val result = timing.finish(NetworkProbeOutcome.OK)
        assertEquals(20L, result.tcpMs)
        assertEquals(30L, result.tlsMs)
        assertEquals(100L, result.headersReceivedMs)
        assertEquals(150L, result.firstBodyByteMs)
        assertEquals(210L, result.totalMs)
        assertEquals(1, result.connectAttempts)
        assertFalse(result.line().contains("TTFB"))
        assertTrue(result.line().contains("tcpScope=tcp_and_proxy_connect"))
    }

    @Test
    fun `failed connection preserves classified errno and no body measurement or error message`() {
        timing.callStart(call)
        timing.connectStart(call, address, Proxy.NO_PROXY)
        tick(25)
        val failure = SocketTimeoutException("Secret address cookie")
        timing.connectFailed(call, address, Proxy.NO_PROXY, null, failure)
        val result = timing.finish(NetworkProbeOutcome.NETWORK_ERROR, failure)
        assertEquals(25L, result.tcpMs)
        assertNull(result.tlsMs)
        assertNull(result.headersReceivedMs)
        assertNull(result.firstBodyByteMs)
        assertEquals(HfrConnectErrno.TIMEOUT, result.errno)
        assertFalse(result.line().contains("Secret"))
        assertFalse(result.line().contains("example.invalid"))
    }

    @Test
    fun `successful connection uses acquired route instead of adding failed racing routes`() {
        val other = InetSocketAddress.createUnresolved("other.invalid", 443)
        timing.callStart(call)
        timing.connectStart(call, other, Proxy.NO_PROXY)
        tick(2)
        timing.connectStart(call, address, Proxy.NO_PROXY)
        tick(3)
        timing.connectFailed(call, other, Proxy.NO_PROXY, null, SocketTimeoutException("Secret"))
        tick(5)
        timing.secureConnectStart(call)
        tick(20)
        timing.secureConnectEnd(call, null)
        timing.connectEnd(call, address, Proxy.NO_PROXY, Protocol.HTTP_1_1)
        timing.connectionAcquired(call, connection())
        val result = timing.finish(NetworkProbeOutcome.OK)
        assertEquals(8L, result.tcpMs)
        assertEquals(20L, result.tlsMs)
        assertEquals(2, result.connectAttempts)
    }

    private fun connection(): Connection = object : Connection {
        override fun route(): Route {
            val client = OkHttpClient()
            val origin = Address(
                uriHost = "unused", uriPort = 443, dns = client.dns, socketFactory = client.socketFactory,
                sslSocketFactory = client.sslSocketFactory, hostnameVerifier = client.hostnameVerifier,
                certificatePinner = client.certificatePinner, proxyAuthenticator = client.proxyAuthenticator,
                proxy = null, protocols = client.protocols, connectionSpecs = client.connectionSpecs,
                proxySelector = client.proxySelector,
            )
            return Route(origin, Proxy.NO_PROXY, address)
        }
        override fun socket(): Socket = error("Not used by diagnostics")
        override fun handshake(): Handshake? = null
        override fun protocol(): Protocol = Protocol.HTTP_1_1
    }

    private fun tick(ms: Long) { now += ms * 1_000_000 }
    private fun response() = Response.Builder().request(call.request()).protocol(Protocol.HTTP_1_1)
        .code(200).message("OK").build()
}
