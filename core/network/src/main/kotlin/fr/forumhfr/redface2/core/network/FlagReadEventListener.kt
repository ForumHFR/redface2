package fr.forumhfr.redface2.core.network

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/** Functional phase signal always runs, even with EventListener.NONE or diagnostics disabled. */
internal class FlagReadEventListener(
    private val phase: FlagReadConnectionPhase,
    private val delegate: EventListener,
) : EventListener() {
    override fun callStart(call: Call) {
        delegate.callStart(call)
    }
    override fun dispatcherQueueStart(call: Call, dispatcher: Dispatcher) {
        delegate.dispatcherQueueStart(call, dispatcher)
    }
    override fun dispatcherQueueEnd(call: Call, dispatcher: Dispatcher) {
        delegate.dispatcherQueueEnd(call, dispatcher)
    }
    override fun proxySelectStart(call: Call, url: HttpUrl) {
        delegate.proxySelectStart(call, url)
    }
    override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) {
        delegate.proxySelectEnd(call, url, proxies)
    }
    override fun dnsStart(call: Call, domainName: String) {
        delegate.dnsStart(call, domainName)
    }
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        delegate.dnsEnd(call, domainName, inetAddressList)
    }
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        // Proxy negotiation (notably HTTP CONNECT) has no requestHeadersStart callback.
        // Without proof that no proxy request was sent, keep application retries disabled.
        if (proxy.type() == Proxy.Type.DIRECT) phase.connecting() else phase.connected()
        delegate.connectStart(call, inetSocketAddress, proxy)
    }
    override fun secureConnectStart(call: Call) {
        phase.connected()
        delegate.secureConnectStart(call)
    }
    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        delegate.secureConnectEnd(call, handshake)
    }
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        phase.connected()
        delegate.connectEnd(call, inetSocketAddress, proxy, protocol)
    }
    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        phase.connectFailed()
        delegate.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
    }
    override fun connectionAcquired(call: Call, connection: Connection) {
        phase.connected()
        delegate.connectionAcquired(call, connection)
    }
    override fun connectionReleased(call: Call, connection: Connection) {
        delegate.connectionReleased(call, connection)
    }
    override fun requestHeadersStart(call: Call) {
        phase.connected()
        delegate.requestHeadersStart(call)
    }
    override fun requestHeadersEnd(call: Call, request: Request) {
        delegate.requestHeadersEnd(call, request)
    }
    override fun requestBodyStart(call: Call) {
        phase.connected()
        delegate.requestBodyStart(call)
    }
    override fun requestBodyEnd(call: Call, byteCount: Long) {
        delegate.requestBodyEnd(call, byteCount)
    }
    override fun requestFailed(call: Call, ioe: IOException) {
        delegate.requestFailed(call, ioe)
    }
    override fun responseHeadersStart(call: Call) {
        phase.connected()
        delegate.responseHeadersStart(call)
    }
    override fun responseHeadersEnd(call: Call, response: Response) {
        delegate.responseHeadersEnd(call, response)
    }
    override fun responseBodyStart(call: Call) {
        phase.connected()
        delegate.responseBodyStart(call)
    }
    override fun responseBodyEnd(call: Call, byteCount: Long) {
        delegate.responseBodyEnd(call, byteCount)
    }
    override fun responseFailed(call: Call, ioe: IOException) {
        delegate.responseFailed(call, ioe)
    }
    override fun callEnd(call: Call) {
        delegate.callEnd(call)
    }
    override fun callFailed(call: Call, ioe: IOException) {
        delegate.callFailed(call, ioe)
    }
    override fun canceled(call: Call) {
        phase.connected()
        delegate.canceled(call)
    }
    override fun satisfactionFailure(call: Call, response: Response) {
        delegate.satisfactionFailure(call, response)
    }
    override fun cacheHit(call: Call, response: Response) {
        delegate.cacheHit(call, response)
    }
    override fun cacheMiss(call: Call) {
        delegate.cacheMiss(call)
    }
    override fun cacheConditionalHit(call: Call, cachedResponse: Response) {
        delegate.cacheConditionalHit(call, cachedResponse)
    }
}
