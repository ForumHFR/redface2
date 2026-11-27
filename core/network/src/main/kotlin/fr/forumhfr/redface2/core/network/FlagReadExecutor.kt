package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.error.HfrRestBlockedException
import fr.forumhfr.redface2.core.network.diagnostics.HfrCallTag
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** Injectable jitter runs after the failed Call has closed and released its permit. */
@Singleton
class FlagReadJitter @Inject constructor() {
    suspend fun pause() { delay(Random.nextLong(MIN_MS, MAX_MS + 1)) }
    private companion object {
        const val MIN_MS = 100L
        const val MAX_MS = 300L
    }
}

@Singleton
class FlagReadExecutor @Inject constructor(
    private val limiter: FlagReadLimiter,
    private val jitter: FlagReadJitter,
) {
    /** Only HfrApiClient's explicit read intents enter here; mutations have no entry point. */
    internal suspend fun <T> execute(
        client: OkHttpClient,
        request: Request,
        operation: FlagReadOperation,
        session: FlagReadSession,
        executeCall: suspend (Call) -> T,
    ): T {
        require(request.method == "GET")
        require(operation in ALLOWED_OPERATIONS)
        session.logicalOperation()
        session.checkNotBlocked()
        session.checkBudget()
        val result = withTimeoutOrNull(session.remainingMillis().coerceAtLeast(1)) {
            val phase = FlagReadConnectionPhase()
            try {
                Result.success(session.attempt(client, request, phase, retry = false, executeCall))
            } catch (blocked: HfrRestBlockedException) {
                Result.failure(blocked)
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                session.checkBudget()
                session.checkNotBlocked()
                if (!phase.canRetry(error) || !session.reserveRetry()) throw error
                jitter.pause()
                currentCoroutineContext().ensureActive()
                session.checkBudget()
                // No loop: the second application Call is the final attempt, whatever its outcome.
                Result.success(session.attempt(client, request, FlagReadConnectionPhase(), retry = true, executeCall))
            }
        } ?: throw FlagReadDeadlineException()
        return result.getOrThrow()
    }

    private suspend fun <T> FlagReadSession.attempt(
        client: OkHttpClient,
        request: Request,
        phase: FlagReadConnectionPhase,
        retry: Boolean,
        executeCall: suspend (Call) -> T,
    ): T {
        val tag = request.tag(HfrCallTag::class.java)
        val attemptRequest = if (tag == null) request else request.newBuilder()
            .tag(HfrCallTag::class.java, tag.forCall(if (retry) nowNanos() else tag.queuedNanos))
            .build()
        return limiter.withPermit(this) {
            val session = this
            currentCoroutineContext().ensureActive()
            session.checkNotBlocked()
            val factory = client.eventListenerFactory
            val bounded = client.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .eventListenerFactory { call -> FlagReadEventListener(phase, factory.create(call)) }
                .build()
            val call = bounded.newCall(attemptRequest)
            val configuredNanos = TimeUnit.MILLISECONDS.toNanos(client.callTimeoutMillis.toLong())
            val remaining = session.remainingNanos()
            if (remaining == 0L) throw FlagReadDeadlineException()
            call.timeout().timeout(
                if (configuredNanos == 0L) remaining else minOf(remaining, configuredNanos),
                TimeUnit.NANOSECONDS,
            )
            session.applicationCall(retry)
            executeCall(call).also {
                session.checkBudget()
                session.succeeded(retry)
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 5L
        val ALLOWED_OPERATIONS = setOf(FlagReadOperation.CATALOGUE, FlagReadOperation.CATEGORY_BUCKET,
            FlagReadOperation.STICKY_LAST, FlagReadOperation.GLOBAL_BUCKET)
    }
}
