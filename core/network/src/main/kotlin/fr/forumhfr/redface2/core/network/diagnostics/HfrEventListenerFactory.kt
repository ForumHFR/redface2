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
    private val connections = mutableListOf<ConnectionAttempt>()
    private val successfulConnections = mutableListOf<ConnectionAttempt>()
    private var tlsStart: Long? = null
    private var tlsAttempt: ConnectionAttempt? = null
    private var tlsThread: Thread? = null
    private val completedTls = ArrayDeque<Long>()
    private var connectAttempts = 0
    private var connectFailures = 0
    private var connectAbandoned = 0
    private var headersStart: Long? = null
    private var bodyStart: Long? = null
    private var dnsMs = 0L
    private var connectMs = 0L
    private var tlsMs = 0L
    private var failedConnectMs = 0L
    private var maxFailedConnectMs = 0L
    private var failedTlsMs = 0L
    private var maxFailedTlsMs = 0L
    private var headersMs = 0L
    private var receivedHeaders = false
    private var bodyMs = 0L
    private var reused = false
    private var status: Int? = null
    private var protocol: String? = null

    @Synchronized
    override fun callStart(call: Call) {
        started = nanoTime()
        inFlight = diagnostics.started()
    }

    @Synchronized
    override fun dnsStart(call: Call, domainName: String) { dnsStart = nanoTime() }
    @Synchronized
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
        dnsMs += dnsStart?.let { elapsedDiagnosticMs(it, nanoTime()) } ?: 0L
        dnsStart = null
    }

    @Synchronized
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        if (ended.get()) return
        connections += ConnectionAttempt(inetSocketAddress, proxy, nanoTime())
        connectAttempts++
    }
    // OkHttp 5.5 fast fallback races TCP on TaskRunner, then negotiates TLS serially on the
    // caller. TLS has no route in its callbacks: retain it per call and associate completed
    // handshakes with connectEnd in order. TCP and TLS do not need to run on the same thread.
    @Synchronized
    override fun secureConnectStart(call: Call) {
        if (ended.get()) return
        val start = nanoTime()
        tlsStart?.let { recordFailedTls(elapsedDiagnosticMs(it, start)) }
        tlsStart = start
        tlsAttempt = connections.singleOrNull()
        tlsThread = Thread.currentThread()
    }
    @Synchronized
    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        if (ended.get()) return
        tlsStart?.let { completedTls.addLast(elapsedDiagnosticMs(it, nanoTime())) }
        clearPendingTls()
    }
    @Synchronized
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        finishConnect(inetSocketAddress, proxy, successful = true)
    }
    @Synchronized
    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        finishConnect(inetSocketAddress, proxy, successful = false)
    }

    private fun finishConnect(address: InetSocketAddress, proxy: Proxy, successful: Boolean) {
        if (ended.get()) return
        val attempt = connections.firstOrNull { it.address == address && it.proxy == proxy } ?: return
        connections.remove(attempt)
        val end = nanoTime()
        attempt.durationMs = elapsedDiagnosticMs(attempt.started, end)
        if (successful) {
            attempt.tlsMs = completedTls.removeFirstOrNull() ?: 0L
            successfulConnections += attempt
        } else {
            connectFailures++
            recordFailedConnect(attempt.durationMs)
            if (failedNegotiatingRoute(attempt) || connections.isEmpty()) finishFailedTls(end)
        }
    }

    private fun failedNegotiatingRoute(attempt: ConnectionAttempt): Boolean {
        if (tlsAttempt != null) return tlsAttempt === attempt
        // OkHttp 5.5 ConnectPlan.connectTlsEtc invokes secureConnectStart and its failure
        // callback synchronously. TCP racers fail on TaskRunner, independently of that thread.
        // This matches callbacks within TLS, never the thread that started the TCP attempt.
        return tlsStart != null && tlsThread === Thread.currentThread()
    }

    private fun recordFailedConnect(duration: Long) {
        failedConnectMs += duration
        maxFailedConnectMs = maxOf(maxFailedConnectMs, duration)
    }

    private fun finishFailedTls(end: Long) {
        tlsStart?.let { recordFailedTls(elapsedDiagnosticMs(it, end)) }
        clearPendingTls()
        while (completedTls.isNotEmpty()) recordFailedTls(completedTls.removeFirst())
    }

    private fun clearPendingTls() {
        tlsStart = null
        tlsAttempt = null
        tlsThread = null
    }

    private fun recordFailedTls(duration: Long) {
        failedTlsMs += duration
        maxFailedTlsMs = maxOf(maxFailedTlsMs, duration)
    }

    private fun finishPendingConnections(end: Long) {
        connections.forEach { recordFailedConnect(elapsedDiagnosticMs(it.started, end)) }
        connectAbandoned += connections.size
        connections.clear()
        finishFailedTls(end)
    }

    @Synchronized
    override fun connectionAcquired(call: Call, connection: Connection) {
        if (ended.get()) return
        val route = connection.route()
        val winner = successfulConnections.firstOrNull {
            it.address == route.socketAddress && it.proxy == route.proxy
        }
        if (winner == null) {
            reused = true
        } else {
            // Even two successful racers contribute only the connection actually acquired.
            connectMs += winner.durationMs
            tlsMs += winner.tlsMs
        }
        successfulConnections.clear()
        protocol = connection.protocol().toString()
    }
    @Synchronized
    override fun requestHeadersEnd(call: Call, request: Request) { headersStart = nanoTime() }
    @Synchronized
    override fun responseHeadersEnd(call: Call, response: Response) {
        headersMs += headersStart?.let { elapsedDiagnosticMs(it, nanoTime()) } ?: 0L
        headersStart = null
        receivedHeaders = true
        status = response.code
        protocol = response.protocol.toString()
    }
    @Synchronized
    override fun responseBodyStart(call: Call) { bodyStart = nanoTime() }
    @Synchronized
    override fun responseBodyEnd(call: Call, byteCount: Long) {
        bodyMs += bodyStart?.let { elapsedDiagnosticMs(it, nanoTime()) } ?: 0L
        bodyStart = null
    }

    @Synchronized
    override fun requestFailed(call: Call, ioe: IOException) { finishPendingResponse() }
    @Synchronized
    override fun responseFailed(call: Call, ioe: IOException) { finishPendingResponse() }

    private fun finishPendingResponse() {
        bodyMs += bodyStart?.let { elapsedDiagnosticMs(it, nanoTime()) } ?: 0L
        headersStart = null
        bodyStart = null
    }

    override fun callEnd(call: Call) { finish(call, null) }
    override fun callFailed(call: Call, ioe: IOException) { finish(call, ioe) }
    @Synchronized
    override fun canceled(call: Call) {
        // canceled is not terminal in OkHttp. Close pending attempts now, but publish the call
        // only at callEnd/callFailed so cancellation cannot decrement in-flight calls twice.
        if (!ended.get()) finishPendingConnections(nanoTime())
    }

    @Synchronized
    private fun finish(call: Call, failure: IOException?) {
        if (!ended.compareAndSet(false, true)) return
        val end = nanoTime()
        finishPendingConnections(end)
        val wait = tag?.let { elapsedDiagnosticMs(it.queuedNanos, it.executeNanos ?: started) }
        val outcome = when {
            call.isCanceled() -> HfrCallOutcome.CANCELLED
            failure != null || status?.let { it !in 200..299 } == true -> HfrCallOutcome.FAILURE
            else -> HfrCallOutcome.OK
        }
        diagnostics.completed(HfrCallSummary(
            id = id, endpoint = endpoint, role = role, refreshId = tag?.fetch?.refreshId,
            phase = refreshPhase(), waitMs = wait, dnsMs = dnsMs + pending(dnsStart, end),
            connectMs = connectMs, tlsMs = tlsMs,
            headersSentToReceivedMs = if (receivedHeaders) headersMs else null,
            bodyMs = bodyMs + pending(bodyStart, end), totalMs = (wait ?: 0L) + elapsedDiagnosticMs(started, end),
            status = status, protocol = protocol, reusedConnection = reused, inFlightAtStart = inFlight,
            outcome = outcome, exceptionClass = failure?.diagnosticClassName(), flagType = tag?.fetch?.type,
            connectAttempts = connectAttempts, connectFailures = connectFailures,
            connectAbandoned = connectAbandoned, failedConnectMs = failedConnectMs,
            maxFailedConnectMs = maxFailedConnectMs, failedTlsMs = failedTlsMs, maxFailedTlsMs = maxFailedTlsMs,
        ), tag?.fetch, tag?.catalogue)
        connections.clear()
        successfulConnections.clear()
    }

    private fun refreshPhase(): FlagRefreshPhase? {
        if (tag?.fetch == null && tag?.catalogue == null) return null
        return when (endpoint.family) {
            HfrEndpoint.Family.CATEGORIES -> FlagRefreshPhase.CATALOGUE
            HfrEndpoint.Family.PARTICIPATED, HfrEndpoint.Family.READ,
            HfrEndpoint.Family.FAVORITES -> FlagRefreshPhase.BUCKETS
            HfrEndpoint.Family.LAST -> FlagRefreshPhase.STICKIES
            else -> null
        }
    }

    private fun pending(start: Long?, end: Long): Long = start?.let { elapsedDiagnosticMs(it, end) } ?: 0L

    private class ConnectionAttempt(
        val address: InetSocketAddress,
        val proxy: Proxy,
        val started: Long,
    ) {
        var tlsMs: Long = 0L
        var durationMs: Long = 0L
    }
}
