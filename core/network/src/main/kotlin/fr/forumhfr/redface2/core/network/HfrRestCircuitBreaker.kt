package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.error.HfrRestAvailability
import fr.forumhfr.redface2.core.domain.error.HfrRestBlockedException
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.Response

data class HfrRestCooldown(val until: Instant, val level: Int)

interface HfrRestCooldownStore {
    suspend fun read(): HfrRestCooldown?
    suspend fun write(cooldown: HfrRestCooldown?)
}

/** Device-wide admission for REST, including anonymous requests and the Diagnostics probe. */
@Singleton
class HfrRestCircuitBreaker @Inject constructor(
    private val store: HfrRestCooldownStore,
    private val clock: Clock,
    private val log: DiagnosticsLog,
) : HfrRestAvailability {
    private val storage = Mutex()
    private val cooldown = MutableStateFlow<HfrRestCooldown?>(null)
    @Volatile private var loaded = false
    private var generation = 0L
    private var recovering = false
    private var firstRefusalRetryUntil: Instant? = null

    private val liveCooldown = flow {
        try {
            load()
        } catch (error: IOException) {
            logStorageError(error)
        }
        emitAll(cooldown)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val blockedUntil: Flow<Instant?> = liveCooldown.transformLatest { pause ->
        if (pause != null) {
            var remaining = Duration.between(clock.instant(), pause.until)
            if (remaining > Duration.ZERO) emit(pause.until)
            while (remaining > Duration.ZERO) {
                delay(remaining.toMillis().coerceAtLeast(1))
                remaining = Duration.between(clock.instant(), pause.until)
            }
        }
        emit(null)
    }

    override val probeBlocked: Flow<Boolean> = liveCooldown.map { it != null }.distinctUntilChanged()

    override suspend fun checkProbeAllowed() {
        load()
        if (cooldown.value != null) throw HfrRestBlockedException()
    }

    /** A generation fences off responses from calls already in flight when the breaker opened. */
    internal suspend fun admit(allowRecovery: Boolean = true): Long {
        load()
        return synchronized(this) {
            val pause = cooldown.value
            if (pause != null) {
                if (!allowRecovery || recovering || clock.instant().isBefore(pause.until)) {
                    throw HfrRestBlockedException()
                }
                recovering = true
            }
            generation
        }
    }

    internal suspend fun complete(admission: Long, reply: RestReply?) = storage.withLock {
        val previous = cooldown.value
        recordReply(admission, reply)
        val current = cooldown.value
        if (current != previous) persist(current)
    }

    @Synchronized
    private fun recordReply(admission: Long, reply: RestReply?) {
        if (admission != generation) return
        val wasRecovering = recovering
        recovering = false
        when {
            reply?.successful == true -> {
                firstRefusalRetryUntil = null
                if (wasRecovering) updateCooldown(null)
            }
            reply?.qualifying == true -> {
                val now = clock.instant()
                val retryUntil = maxOf(firstRefusalRetryUntil ?: now, reply.headers.retryAfterDeadline(now))
                if (wasRecovering || firstRefusalRetryUntil != null) {
                    val level = ((cooldown.value?.level ?: -1) + 1).coerceAtMost(COOLDOWN_MINUTES.lastIndex)
                    val until = maxOf(now.plusSeconds(COOLDOWN_MINUTES[level] * 60), retryUntil)
                        .coerceAtMost(now.plusSeconds(MAX_COOLDOWN_SECONDS))
                    firstRefusalRetryUntil = null
                    updateCooldown(HfrRestCooldown(until, level))
                } else {
                    firstRefusalRetryUntil = retryUntil
                }
            }
        }
    }

    private suspend fun load() {
        if (loaded) return
        storage.withLock {
            if (loaded) return@withLock
            val saved = store.read()
            if (saved != null) {
                val clamped = HfrRestCooldown(
                    minOf(saved.until, clock.instant().plusSeconds(MAX_COOLDOWN_SECONDS)),
                    saved.level.coerceIn(0, COOLDOWN_MINUTES.lastIndex),
                )
                cooldown.value = clamped
                if (saved != clamped) persist(clamped)
            }
            loaded = true
        }
    }

    private fun updateCooldown(value: HfrRestCooldown?) {
        generation++
        cooldown.value = value
    }

    private suspend fun persist(value: HfrRestCooldown?) {
        try {
            store.write(value)
        } catch (error: IOException) {
            // Keep enforcing the live pause if disk is temporarily unavailable.
            logStorageError(error)
        }
    }

    private fun logStorageError(error: IOException) {
        log.record(DiagnosticsLog.Level.WARN, "HfrRestCircuitBreaker", "storage error ${error.javaClass.simpleName}")
    }

    companion object {
        private val COOLDOWN_MINUTES = listOf(10L, 20L, 40L, 60L)
        private const val MAX_COOLDOWN_SECONDS = 2L * 60 * 60

        internal fun inMemory() = HfrRestCircuitBreaker(object : HfrRestCooldownStore {
            override suspend fun read(): HfrRestCooldown? = null
            override suspend fun write(cooldown: HfrRestCooldown?) = Unit
        }, Clock.systemUTC(), DiagnosticsLog())

        private fun Headers.retryAfterDeadline(now: Instant): Instant {
            val value = get("Retry-After").orEmpty()
            return if (value.isNotEmpty() && value.all { it in '0'..'9' }) {
                val seconds = value.trimStart('0').ifEmpty { "0" }.toLongOrNull() ?: MAX_COOLDOWN_SECONDS
                now.plusSeconds(seconds.coerceAtMost(MAX_COOLDOWN_SECONDS))
            } else {
                getInstant("Retry-After")?.coerceIn(now, now.plusSeconds(MAX_COOLDOWN_SECONDS)) ?: now
            }
        }
    }
}

internal data class RestReply(val successful: Boolean, val qualifying: Boolean, val headers: Headers = Headers.EMPTY)

internal val Response.isRestReply: Boolean get() = request.url.encodedPath == "/webservices/rest_api.php"

internal val Response.isApacheForbiddenCandidate: Boolean
    get() = isRestReply && code == 403 &&
        header("Content-Type")?.substringBefore(';')?.trim().equals("text/html", ignoreCase = true)

internal fun Response.restReply(body: String? = null): RestReply? = if (isRestReply) {
    val qualifies = code == 429 ||
        (isApacheForbiddenCandidate && body?.contains("You don't have permission to access") == true)
    RestReply(isSuccessful, qualifies, headers)
} else {
    null
}
