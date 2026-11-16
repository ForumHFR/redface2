package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.diagnostics.HfrConnectErrno
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeOutcome
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeRequestResult
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response

/** Per-call listener: no URL, address, header, exception message or account data escapes it. */
internal class ProbeTimingListener(private val nanoTime: () -> Long = System::nanoTime) : EventListener() {
    private var started: Long? = null
    private val attempts = mutableMapOf<Pair<InetSocketAddress, Proxy>, Long>()
    private var attemptCount = 0
    private val connected = mutableMapOf<Pair<InetSocketAddress, Proxy>, Pair<Long, Long?>>()
    private var acquired = false
    private var tlsStarted: Long? = null
    private var tlsThread: Thread? = null
    private val handshakes = ArrayDeque<Pair<Long, Long>>()
    private var tcpMs: Long? = null
    private var tlsMs: Long? = null
    private var headersMs: Long? = null
    private var firstByteMs: Long? = null
    private var errno: HfrConnectErrno? = null
    private var status: Int? = null
    private var completed: NetworkProbeRequestResult? = null

    @Synchronized
    override fun callStart(call: Call) { started = nanoTime() }

    @Synchronized
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        attemptCount++
        attempts[inetSocketAddress to proxy] = nanoTime()
    }

    @Synchronized
    override fun secureConnectStart(call: Call) {
        tlsStarted = nanoTime()
        tlsThread = Thread.currentThread()
    }

    @Synchronized
    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        tlsStarted?.let { handshakes.addLast(it to nanoTime()) }
        tlsStarted = null
        tlsThread = null
    }

    @Synchronized
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        val start = attempts.remove(inetSocketAddress to proxy) ?: return
        val handshake = handshakes.removeFirstOrNull()
        // OkHttp's secureConnectStart follows proxy CONNECT; exported tcpScope names this boundary.
        val tcp = elapsed(start, handshake?.first ?: nanoTime())
        val tls = handshake?.let { elapsed(it.first, it.second) }
        connected[inetSocketAddress to proxy] = tcp to tls
        addTcp(tcp)
        tls?.let(::addTls)
    }

    @Synchronized
    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        val start = attempts.remove(inetSocketAddress to proxy) ?: return
        val tls = tlsStarted.takeIf { tlsThread === Thread.currentThread() }
        val end = nanoTime()
        addTcp(elapsed(start, tls ?: end))
        if (tls != null) {
            addTls(elapsed(tls, end))
            tlsStarted = null
            tlsThread = null
        }
        errno = classifyConnectErrno(ioe)
    }

    @Synchronized
    override fun connectionAcquired(call: Call, connection: Connection) {
        val route = connection.route()
        connected[route.socketAddress to route.proxy]?.let { (tcp, tls) ->
            // Successful latency describes the acquired route, never the sum of racing routes.
            tcpMs = tcp
            tlsMs = tls
            acquired = true
        }
    }

    @Synchronized
    override fun responseHeadersEnd(call: Call, response: Response) {
        headersMs = elapsed(started ?: nanoTime(), nanoTime())
        status = response.code
    }

    /** Called only after source.request(1) has made a body byte available; empty bodies stay null. */
    @Synchronized
    fun firstBodyByte() { firstByteMs = elapsed(started ?: nanoTime(), nanoTime()) }

    @Synchronized
    fun finish(outcome: NetworkProbeOutcome, failure: IOException? = null): NetworkProbeRequestResult {
        val end = nanoTime()
        // Remaining attempts were cancelled, possibly during DNS/TCP/TLS. Retain known phases only.
        tlsStarted?.let { addTls(elapsed(it, end)) }
        val result = NetworkProbeRequestResult(
            tcpMs = tcpMs, tlsMs = tlsMs, headersReceivedMs = headersMs, firstBodyByteMs = firstByteMs,
            totalMs = elapsed(started ?: nanoTime(), end), outcome = outcome,
            // DNS and control failures have closed outcome categories, not a connection errno.
            errno = errno ?: failure?.takeIf { outcome == NetworkProbeOutcome.NETWORK_ERROR }
                ?.let(::classifyConnectErrno), connectAttempts = attemptCount, status = status,
        )
        completed = result
        attempts.clear()
        connected.clear()
        handshakes.clear()
        return result
    }

    @Synchronized
    fun result(): NetworkProbeRequestResult = completed ?: NetworkProbeRequestResult(
        totalMs = elapsed(started ?: nanoTime(), nanoTime()), connectAttempts = attemptCount,
    )

    private fun addTcp(value: Long) { if (!acquired) tcpMs = (tcpMs ?: 0) + value }
    private fun addTls(value: Long) { if (!acquired) tlsMs = (tlsMs ?: 0) + value }
    private fun elapsed(start: Long, end: Long): Long = ((end - start) / 1_000_000).coerceAtLeast(0)
}
