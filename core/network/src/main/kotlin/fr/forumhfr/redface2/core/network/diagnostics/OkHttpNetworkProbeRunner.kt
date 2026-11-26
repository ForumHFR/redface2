package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.diagnostics.AppForegroundDiagnostics
import fr.forumhfr.redface2.core.domain.diagnostics.AppVisibility
import fr.forumhfr.redface2.core.domain.diagnostics.HfrProbeTraffic
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsContextProvider
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsEvent
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeDisturbance
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeOutcome
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeRunner
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeWave
import fr.forumhfr.redface2.core.domain.stats.NetworkRole
import fr.forumhfr.redface2.core.network.HfrConstants
import fr.forumhfr.redface2.core.network.qualifiers.HfrBaseUrl
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

class OkHttpNetworkProbeRunner
@Suppress("LongParameterList") // The primary constructor also accepts a shorter timeout for tests.
internal constructor(
    private val baseClient: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val ioDispatcher: CoroutineDispatcher,
    private val traffic: HfrProbeTraffic,
    private val foreground: AppForegroundDiagnostics,
    private val network: NetworkDiagnosticsContextProvider,
    private val stats: NetworkStatsListenerFactory,
    private val waveTimeoutMs: Long,
) : NetworkProbeRunner {
    @Inject
    @Suppress("LongParameterList")
    constructor(
        baseClient: OkHttpClient,
        @HfrBaseUrl baseUrl: HttpUrl,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        traffic: HfrProbeTraffic,
        foreground: AppForegroundDiagnostics,
        network: NetworkDiagnosticsContextProvider,
        stats: NetworkStatsListenerFactory,
    ) : this(baseClient, baseUrl, ioDispatcher, traffic, foreground, network, stats, NetworkProbeRunner.WAVE_TIMEOUT_MS)

    override suspend fun run(onWaveStarted: (Int) -> Unit, onWaveFinished: (NetworkProbeWave) -> Unit) {
        val client = anonymousProbeClient(baseClient)
        val observation = traffic.snapshot()
        val environment = ProbeEnvironment(network)
        val foregroundSubscription = foreground.observe(environment::visibilityChanged)
        val networkSubscription = runCatching { network.observe(environment::networkChanged) }.getOrNull()
        try {
            for (size in NetworkProbeRunner.WAVE_SIZES) {
                currentCoroutineContext().ensureActive()
                environment.startWave()
                onWaveStarted(size)
                runWave(client, size, observation, environment, onWaveFinished)
            }
        } finally {
            // Unsubscribe without holding the environment lock used by the callbacks.
            runCatching { networkSubscription?.close() }
            foregroundSubscription.close()
            client.connectionPool.evictAll()
        }
    }

    private suspend fun runWave(
        client: OkHttpClient,
        size: Int,
        observation: HfrProbeTraffic.Snapshot,
        environment: ProbeEnvironment,
        onWaveFinished: (NetworkProbeWave) -> Unit,
    ) {
        val timings = List(size) { ProbeTimingListener() }
        val dns = ProbeDns(client.dns, size,
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waveTimeoutMs))
        val waveClient = client.newBuilder().dns(dns).build()
        var deadline = false
        try {
            val completed = withTimeoutOrNull(waveTimeoutMs) {
                coroutineScope {
                    timings.map { timing -> async { execute(waveClient, timing, dns) } }.awaitAll()
                }
                true
            }
            deadline = completed == null
        } finally {
            dns.close()
            // Structured children have drained and every response is closed before the next wave.
            val disturbances = environment.disturbances(traffic.disturbed(observation))
            val results = timings.map { it.result() }.map { result ->
                if (deadline && result.outcome == NetworkProbeOutcome.CANCELLED) {
                    result.copy(outcome = NetworkProbeOutcome.DEADLINE)
                } else result
            }
            onWaveFinished(NetworkProbeWave(size, results, disturbances))
        }
    }

    private suspend fun execute(
        client: OkHttpClient,
        timing: ProbeTimingListener,
        dns: ProbeDns,
    ) = withContext(ioDispatcher) {
        val url = baseUrl.newBuilder().encodedPath("/webservices/rest_api.php")
            .query(null).addQueryParameter("uri", "forums/hardwarefr/categories/").build()
        val request = Request.Builder().url(url)
            .header("Connection", "close")
            .header("Cache-Control", "no-cache, no-store")
            .header("User-Agent", HfrConstants.USER_AGENT)
            .build()
        val exchanged = AtomicBoolean()
        // Remove 503 retry permission before OkHttp can open a second TCP/TLS connection.
        // Keep the exchange guard as a fallback, before any unexpected follow-up GET.
        val call = client.newBuilder()
            .eventListener(timing + stats.create(NetworkRole.PROBE))
            .addNetworkInterceptor { chain ->
                if (!exchanged.compareAndSet(false, true)) throw ExchangeLimitExceeded()
                val response = chain.proceed(chain.request())
                if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
            }
            .build().newCall(request)
        // The cancellation hook remains installed while execute() AND the body read are blocked.
        suspendCancellableCoroutine<Unit> { continuation ->
            continuation.invokeOnCancellation {
                call.cancel()
                dns.close()
            }
            var outcome = NetworkProbeOutcome.CANCELLED
            var failure: IOException? = null
            try {
                call.execute().use { response ->
                    val source = response.body.source()
                    if (source.request(1)) timing.firstBodyByte()
                    while (!source.exhausted()) source.skip(source.buffer.size)
                    outcome = if (response.isSuccessful) NetworkProbeOutcome.OK else NetworkProbeOutcome.HTTP_ERROR
                }
            } catch (error: ProbeDns.DeadlineExceeded) {
                failure = error
                outcome = NetworkProbeOutcome.DEADLINE
            } catch (error: ProbeDns.Cancelled) {
                failure = error
                outcome = NetworkProbeOutcome.CANCELLED
            } catch (error: ExchangeLimitExceeded) {
                failure = error
                outcome = NetworkProbeOutcome.EXCHANGE_LIMIT
            } catch (error: UnknownHostException) {
                failure = error
                outcome = NetworkProbeOutcome.DNS_ERROR
            } catch (error: IOException) {
                failure = error
                outcome = if (call.isCanceled()) NetworkProbeOutcome.CANCELLED else NetworkProbeOutcome.NETWORK_ERROR
            } finally {
                timing.finish(outcome, failure)
            }
            continuation.resumeWith(Result.success(Unit))
        }
    }

    private class ExchangeLimitExceeded : IOException("Probe exchange limit")
}

/** Remembers brief transitions until the drain, even when the environment recovers during the wave. */
private class ProbeEnvironment(private val network: NetworkDiagnosticsContextProvider) {
    private var visibility = AppVisibility.UNKNOWN
    private var sawBackground = false
    private var sawNetworkChange = false
    private var defaultNetworkChangeCheck: (() -> Boolean)? = null

    fun startWave() {
        synchronized(this) {
            sawBackground = visibility == AppVisibility.BACKGROUND
            sawNetworkChange = false
        }
        defaultNetworkChangeCheck = runCatching { network.defaultNetworkChangeCheck() }.getOrNull()
    }

    @Synchronized
    fun visibilityChanged(value: AppVisibility) {
        visibility = value
        if (value == AppVisibility.BACKGROUND) sawBackground = true
    }

    @Synchronized
    fun networkChanged(event: NetworkDiagnosticsEvent) {
        if (event.defaultNetworkChanged) sawNetworkChange = true
    }

    fun disturbances(hfrTraffic: Boolean): Set<NetworkProbeDisturbance> {
        val defaultNetworkChanged = runCatching { defaultNetworkChangeCheck?.invoke() }.getOrNull() == true
        return synchronized(this) {
            buildSet {
                if (hfrTraffic) add(NetworkProbeDisturbance.HFR_TRAFFIC)
                if (sawBackground) add(NetworkProbeDisturbance.BACKGROUND)
                // Accept a false positive when delayed A -> B -> A callbacks from between waves arrive here.
                if (defaultNetworkChanged || sawNetworkChange) add(NetworkProbeDisturbance.NETWORK_CHANGE)
            }
        }
    }
}

/** Retains proxy routing, DNS and platform TLS verification, but carries no account-capable hooks. */
internal fun anonymousProbeClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
    .apply {
        interceptors().clear()
        networkInterceptors().clear()
    }
    .cookieJar(CookieJar.NO_COOKIES)
    .authenticator(Authenticator.NONE)
    .proxyAuthenticator(Authenticator.NONE)
    .cache(null)
    .protocols(listOf(Protocol.HTTP_1_1))
    .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    // The wave owns the deadline, including DNS and body reads; avoid competing timeout outcomes.
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .build()
