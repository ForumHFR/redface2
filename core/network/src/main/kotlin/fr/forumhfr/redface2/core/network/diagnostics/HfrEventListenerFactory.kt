package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshPhase
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCallOutcome
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCallSummary
import fr.forumhfr.redface2.core.domain.diagnostics.HfrClientRole
import fr.forumhfr.redface2.core.domain.diagnostics.HfrEndpoint
import fr.forumhfr.redface2.core.domain.diagnostics.diagnosticClassName
import fr.forumhfr.redface2.core.domain.diagnostics.elapsedDiagnosticMs
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

class HfrEventListenerFactory(
    private val diagnostics: HfrNetworkDiagnostics,
    private val role: HfrClientRole = HfrClientRole.ANON,
    private val nanoTime: () -> Long = System::nanoTime,
) : EventListener.Factory {
    fun withRole(role: HfrClientRole): HfrEventListenerFactory = HfrEventListenerFactory(diagnostics, role, nanoTime)

    override fun create(call: Call): EventListener {
        val endpoint = classifyHfrEndpoint(call.request().url) ?: return EventListener.NONE
        return HfrTimingListener(diagnostics, role, endpoint, call.request().tag(HfrCallTag::class.java), nanoTime)
    }
}

/** Connection time includes TLS; headersSentToReceived is explicitly NOT time to first byte. */
private class HfrTimingListener(
    private val diagnostics: HfrNetworkDiagnostics,
    private val role: HfrClientRole,
    private val endpoint: HfrEndpoint,
    private val tag: HfrCallTag?,
    private val nanoTime: () -> Long,
) : EventListener() {
    private val id = diagnostics.nextId()
    private val ended = AtomicBoolean()
    private var started = 0L
    private var inFlight = 0
    private var dnsStart: Long? = null
    private var connectStart: Long? = null
    private var tlsStart: Long? = null
    private var headersStart: Long? = null
    private var bodyStart: Long? = null
    private var dnsMs = 0L
    private var connectMs = 0L
    private var tlsMs = 0L
    private var headersMs = 0L
    private var receivedHeaders = false
    private var bodyMs = 0L
    private var connected = false
    private var reused = false
    private var status: Int? = null
    private var protocol: String? = null

    override fun callStart(call: Call) {
        started = nanoTime()
        inFlight = diagnostics.started()
    }

    override fun dnsStart(call: Call, domainName: String) { dnsStart = nanoTime() }
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
        dnsMs += dnsStart?.let { elapsedDiagnosticMs(it, nanoTime()) } ?: 0L
        dnsStart = null
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStart = nanoTime()
    }
    override fun secureConnectStart(call: Call) { tlsStart = nanoTime() }
    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        tlsMs += tlsStart?.let { elapsedDiagnosticMs(it, nanoTime()) } ?: 0L
        tlsStart = null
    }
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        connected = true
        finishConnect()
    }
    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        tlsMs += tlsStart?.let { elapsedDiagnosticMs(it, nanoTime()) } ?: 0L
        tlsStart = null
        finishConnect()
    }

    private fun finishConnect() {
        connectMs += connectStart?.let { elapsedDiagnosticMs(it, nanoTime()) } ?: 0L
        connectStart = null
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        reused = reused || !connected
        connected = false
        protocol = connection.protocol().toString()
    }
    override fun requestHeadersEnd(call: Call, request: Request) { headersStart = nanoTime() }
    override fun responseHeadersEnd(call: Call, response: Response) {
        headersMs += headersStart?.let { elapsedDiagnosticMs(it, nanoTime()) } ?: 0L
        headersStart = null
        receivedHeaders = true
        status = response.code
        protocol = response.protocol.toString()
    }
    override fun responseBodyStart(call: Call) { bodyStart = nanoTime() }
    override fun responseBodyEnd(call: Call, byteCount: Long) {
        bodyMs += bodyStart?.let { elapsedDiagnosticMs(it, nanoTime()) } ?: 0L
        bodyStart = null
    }

    override fun requestFailed(call: Call, ioe: IOException) { finishPendingResponse() }
    override fun responseFailed(call: Call, ioe: IOException) { finishPendingResponse() }

    private fun finishPendingResponse() {
        bodyMs += bodyStart?.let { elapsedDiagnosticMs(it, nanoTime()) } ?: 0L
        headersStart = null
        bodyStart = null
    }

    override fun callEnd(call: Call) { finish(call, null) }
    override fun callFailed(call: Call, ioe: IOException) { finish(call, ioe) }

    private fun finish(call: Call, failure: IOException?) {
        if (!ended.compareAndSet(false, true)) return
        val end = nanoTime()
        val wait = tag?.let { elapsedDiagnosticMs(it.queuedNanos, it.executeNanos ?: started) }
        val outcome = when {
            call.isCanceled() -> HfrCallOutcome.CANCELLED
            failure != null || status?.let { it !in 200..299 } == true -> HfrCallOutcome.FAILURE
            else -> HfrCallOutcome.OK
        }
        diagnostics.completed(HfrCallSummary(
            id = id, endpoint = endpoint, role = role, refreshId = tag?.fetch?.refreshId,
            phase = refreshPhase(), waitMs = wait, dnsMs = dnsMs + pending(dnsStart, end),
            connectMs = connectMs + pending(connectStart, end), tlsMs = tlsMs + pending(tlsStart, end),
            headersSentToReceivedMs = if (receivedHeaders) headersMs else null,
            bodyMs = bodyMs + pending(bodyStart, end), totalMs = (wait ?: 0L) + elapsedDiagnosticMs(started, end),
            status = status, protocol = protocol, reusedConnection = reused, inFlightAtStart = inFlight,
            outcome = outcome, exceptionClass = failure?.diagnosticClassName(), flagType = tag?.fetch?.type,
        ), tag?.fetch)
    }

    private fun refreshPhase(): FlagRefreshPhase? = if (tag?.fetch == null) null else when (endpoint.family) {
        HfrEndpoint.Family.CATEGORIES -> FlagRefreshPhase.CATALOGUE
        HfrEndpoint.Family.PARTICIPATED, HfrEndpoint.Family.READ,
        HfrEndpoint.Family.FAVORITES -> FlagRefreshPhase.BUCKETS
        HfrEndpoint.Family.LAST -> FlagRefreshPhase.STICKIES
        else -> null
    }

    private fun pending(start: Long?, end: Long): Long = start?.let { elapsedDiagnosticMs(it, end) } ?: 0L
}
