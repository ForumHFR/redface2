package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.diagnostics.AppForegroundDiagnostics
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCallIntent
import fr.forumhfr.redface2.core.domain.diagnostics.HfrConnectErrno
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsContext
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsContextProvider
import fr.forumhfr.redface2.core.domain.stats.NetworkRequestCounts
import fr.forumhfr.redface2.core.domain.stats.NetworkRole
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsRecorder
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsStart
import fr.forumhfr.redface2.core.domain.stats.NetworkStatus
import fr.forumhfr.redface2.core.domain.stats.NetworkTrigger
import fr.forumhfr.redface2.core.domain.stats.NoRequestReason
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.UnknownHostException
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.net.ssl.SSLException
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

class NetworkStatsListenerFactory @Inject constructor(
    private val recorder: NetworkStatsRecorder,
    private val foreground: AppForegroundDiagnostics,
    private val clock: Clock,
    private val networkContext: NetworkDiagnosticsContextProvider =
        NetworkDiagnosticsContextProvider { NetworkDiagnosticsContext() },
) {
    fun forRole(role: NetworkRole, delegate: EventListener.Factory = NO_LISTENER): EventListener.Factory =
        EventListener.Factory { call -> create(role) + delegate.create(call) }

    internal fun create(role: NetworkRole): EventListener = runCatching {
        if (recorder.isEnabled) {
            NetworkStatsEventListener(recorder, foreground, clock, role, networkContext)
        } else {
            EventListener.NONE
        }
    }.getOrDefault(EventListener.NONE)

    private companion object {
        val NO_LISTENER = EventListener.Factory { EventListener.NONE }
    }
}

/** Records individual HTTP exchanges. Proxy CONNECT does not reach these callbacks. */
internal class NetworkStatsEventListener(
    private val recorder: NetworkStatsRecorder,
    private val foreground: AppForegroundDiagnostics,
    private val clock: Clock,
    private val role: NetworkRole,
    private val networkContext: NetworkDiagnosticsContextProvider,
) : EventListener() {
    private var initial: NetworkStatsRecorder.CallRecording? = null
    private var headersStart: NetworkStatsRecorder.CallRecording? = null
    private var exchange: Exchange? = null
    private var sent = false
    // Fast fallback can race TCP attempts on other threads while handshaking on the call thread.
    private val connectTimeouts = ConcurrentHashMap<IOException, NoRequestReason>()
    private val handshaking = ThreadLocal<Boolean>()

    override fun secureConnectStart(call: Call) = record {
        handshaking.set(true)
    }

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) =
        record { handshaking.remove() }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) = record {
        if (handshaking.get() != true && classifyConnectErrno(ioe) == HfrConnectErrno.TIMEOUT) {
            // For HTTPS via an HTTP proxy, connectFailed before TLS covers both TCP and CONNECT.
            val mayBeTunnel = proxy.type() == Proxy.Type.HTTP && call.request().url.isHttps
            connectTimeouts[ioe] = if (mayBeTunnel) NoRequestReason.PROXY_TIMEOUT else NoRequestReason.TCP_TIMEOUT
        }
        handshaking.remove()
    }

    override fun callStart(call: Call) = record {
        initial = recorder.started(snapshot())
    }

    override fun requestHeadersStart(call: Call) = record {
        finishExchange()
        headersStart = recorder.started(snapshot())
    }

    override fun requestHeadersEnd(call: Call, request: Request) = record {
        sent = true
        val recording = headersStart
        headersStart = null
        if (recording != null) {
            val url = request.url
            val duplex = request.body?.isDuplex() == true
            val wire = recording.sent(
                NetworkStatsClassifier.target(url, role), NetworkStatsClassifier.peakGroup(url, role), trigger(request),
            )
            exchange = Exchange(wire, duplex)
        }
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) = record {
        exchange?.bodyOut = byteCount
    }

    override fun responseHeadersEnd(call: Call, response: Response) = record {
        exchange?.apply {
            status = responseStatus(response.code)
        }
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) = record {
        exchange?.let {
            it.bodyIn = byteCount
            if (!it.duplex) finishExchange()
        }
    }

    override fun responseFailed(call: Call, ioe: IOException) = record {
        exchange?.let {
            it.bodyFailed = true
            if (!it.duplex && it.status != NetworkStatus.NO_RESPONSE) finishExchange()
        }
    }

    override fun retryDecision(call: Call, exception: IOException, retry: Boolean) = record {
        if (retry) finishExchange()
    }

    override fun callEnd(call: Call) = record {
        finishExchange()
    }

    override fun callFailed(call: Call, ioe: IOException) = record {
        finishExchange()
        if (!sent) {
            val request = call.request()
            initial?.noRequest(NetworkStatsClassifier.target(request.url, role), noRequestReason(call, ioe),
                trigger(request))
        }
    }

    private fun finishExchange() {
        val last = exchange
        exchange = null
        if (last != null) {
            // OkHttp supplies no partial byte count on a failed body callback.
            val bytesRead = last.bodyIn ?: 0
            val incomplete = last.bodyFailed && last.status != NetworkStatus.NO_RESPONSE
            last.wire.finish(last.status, NetworkRequestCounts(1, bytesRead, last.bodyOut, if (incomplete) 1 else 0))
        }
    }

    private fun snapshot() =
        NetworkStatsStart(
            clock.instant().epochSecond, System.nanoTime(), foreground.currentVisibility(), role,
            networkContext.currentNetworkKind(),
        )

    private fun trigger(request: Request): NetworkTrigger {
        val tag = request.tag(HfrCallTag::class.java)
        val refresh = tag?.flagTrigger
        return when {
            refresh != null -> NetworkTrigger.fromFlags(refresh)
            tag?.intent == HfrCallIntent.Kind.MP_BADGE -> NetworkTrigger.MP_BADGE
            else -> NetworkTrigger.NONE
        }
    }

    private fun noRequestReason(call: Call, error: IOException): NoRequestReason {
        if (call.isCanceled()) return NoRequestReason.CANCELLED
        return connectTimeouts[error] ?: when (error) {
            is UnknownHostException -> NoRequestReason.DNS
            is ConnectException, is NoRouteToHostException -> NoRequestReason.CONNECT
            is SSLException -> NoRequestReason.TLS
            else -> NoRequestReason.OTHER_IO
        }
    }

    private fun responseStatus(code: Int): NetworkStatus = when (code) {
        in 200..299 -> NetworkStatus.SUCCESS
        in 300..399 -> NetworkStatus.REDIRECT
        403 -> NetworkStatus.FORBIDDEN
        429 -> NetworkStatus.RATE_LIMITED
        in 400..499 -> NetworkStatus.OTHER_CLIENT_ERROR
        in 500..599 -> NetworkStatus.SERVER_ERROR
        else -> NetworkStatus.NO_RESPONSE
    }

    private inline fun record(block: () -> Unit) {
        // A statistics failure must never escape into OkHttp or print request details.
        runCatching(block)
    }

    private class Exchange(
        val wire: NetworkStatsRecorder.WireRecording,
        val duplex: Boolean,
    ) {
        var status = NetworkStatus.NO_RESPONSE
        var bodyIn: Long? = null
        var bodyOut = 0L
        var bodyFailed = false
    }
}
