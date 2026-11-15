package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.diagnostics.HfrProbeTraffic
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeOutcome
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeRunner
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeWave
import fr.forumhfr.redface2.core.network.HfrConstants
import fr.forumhfr.redface2.core.network.qualifiers.HfrBaseUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
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

class OkHttpNetworkProbeRunner @Inject constructor(
    private val baseClient: OkHttpClient,
    @param:HfrBaseUrl private val baseUrl: HttpUrl,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val traffic: HfrProbeTraffic,
) : NetworkProbeRunner {
    override suspend fun run(onWaveStarted: (Int) -> Unit, onWaveFinished: (NetworkProbeWave) -> Unit) {
        val client = anonymousProbeClient(baseClient)
        val observation = traffic.snapshot()
        try {
            for (size in NetworkProbeRunner.WAVE_SIZES) {
                currentCoroutineContext().ensureActive()
                onWaveStarted(size)
                runWave(client, size, observation, onWaveFinished)
            }
        } finally {
            client.connectionPool.evictAll()
        }
    }

    private suspend fun runWave(
        client: OkHttpClient,
        size: Int,
        observation: HfrProbeTraffic.Snapshot,
        onWaveFinished: (NetworkProbeWave) -> Unit,
    ) {
        val timings = List(size) { ProbeTimingListener() }
        var deadline = false
        try {
            val completed = withTimeoutOrNull(NetworkProbeRunner.WAVE_TIMEOUT_MS) {
                coroutineScope {
                    timings.map { timing -> async { execute(client, timing) } }.awaitAll()
                }
                true
            }
            deadline = completed == null
        } finally {
            // Structured children have drained and every response is closed before the next wave.
            val results = timings.map { it.result() }.map { result ->
                if (deadline && result.outcome == NetworkProbeOutcome.CANCELLED) {
                    result.copy(outcome = NetworkProbeOutcome.DEADLINE)
                } else result
            }
            onWaveFinished(NetworkProbeWave(size, results, traffic.disturbed(observation)))
        }
    }

    private suspend fun execute(client: OkHttpClient, timing: ProbeTimingListener) = withContext(ioDispatcher) {
        val url = baseUrl.newBuilder().encodedPath("/webservices/rest_api.php")
            .query(null).addQueryParameter("uri", "forums/hardwarefr/categories/").build()
        val request = Request.Builder().url(url)
            .header("Connection", "close")
            .header("Cache-Control", "no-cache, no-store")
            .header("User-Agent", HfrConstants.USER_AGENT)
            .build()
        // One application Call, no application retry. OkHttp's internal routes remain counted.
        val call = client.newBuilder().eventListener(timing).build().newCall(request)
        // The cancellation hook remains installed while execute() AND the body read are blocked.
        suspendCancellableCoroutine<Unit> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            var outcome = NetworkProbeOutcome.CANCELLED
            var failure: IOException? = null
            try {
                call.execute().use { response ->
                    val source = response.body.source()
                    if (source.request(1)) timing.firstBodyByte()
                    while (!source.exhausted()) source.skip(source.buffer.size)
                    outcome = if (response.isSuccessful) NetworkProbeOutcome.OK else NetworkProbeOutcome.HTTP_ERROR
                }
            } catch (error: IOException) {
                failure = error
                outcome = if (call.isCanceled()) NetworkProbeOutcome.CANCELLED else NetworkProbeOutcome.NETWORK_ERROR
            } finally {
                timing.finish(outcome, failure)
            }
            continuation.resumeWith(Result.success(Unit))
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
    .callTimeout(NetworkProbeRunner.WAVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .build()
