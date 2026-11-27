package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.error.HfrRestAvailability
import fr.forumhfr.redface2.core.domain.error.HfrRestBlockedException
import fr.forumhfr.redface2.core.domain.error.HfrRestRecoveryPendingException
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.Response

enum class HfrRestContext { ANONYMOUS, AUTHENTICATED, UNKNOWN }

data class HfrRestCooldown(
    val until: Instant,
    val level: Int,
    val context: HfrRestContext = HfrRestContext.UNKNOWN,
)

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
    private val elapsed: FlagReadClock = FlagReadClock(),
) : HfrRestAvailability {
    private val storage = Mutex()
    private val cooldown = MutableStateFlow<Pause?>(null)
    @Volatile private var loaded = false
    private var generation = 0L
    private var recovery: Admission? = null
    private var firstRefusal: Refusal? = null
    private var storageErrorReported = false

    private val liveCooldown = flow {
        load()
        emitAll(cooldown)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val blockedUntil: Flow<Instant?> = liveCooldown.transformLatest { pause ->
        if (pause != null) {
            var remaining = remainingNanos(pause)
            if (remaining > 0) emit(clock.instant().plusNanos(remaining))
            while (remaining > 0) {
                delay(Duration.ofNanos(remaining).toMillis().coerceAtLeast(1))
                remaining = remainingNanos(pause)
            }
        }
        emit(null)
    }

    override val probeBlocked: Flow<Boolean> = liveCooldown.map { it != null }.distinctUntilChanged()

    override suspend fun cooldownDeadline(): Instant? {
        load()
        return cooldown.value?.let(::remainingNanos)?.takeIf { it > 0 }?.let { clock.instant().plusNanos(it) }
    }

    override suspend fun checkProbeAllowed() {
        load()
        if (cooldown.value != null) throw HfrRestBlockedException()
    }

    /**
     * A cold flags collection needs a catalogue before it can send a bucket. When recovering
     * authenticated REST, the catalogue must use the same authenticated client as the buckets.
     */
    internal suspend fun needsAuthenticatedCatalogue(): Boolean {
        load()
        val pause = cooldown.value ?: return false
        return remainingNanos(pause) == 0L && pause.context == HfrRestContext.AUTHENTICATED
    }

    internal suspend fun admit(
        context: HfrRestContext = HfrRestContext.ANONYMOUS,
        allowRecovery: Boolean = true,
    ): Admission {
        load()
        val (admission, waitForOther) = synchronized(this) {
            val existing = recovery
            val acquired = acquireAdmission(context, allowRecovery)
            acquired to (acquired === existing)
        }
        return if (waitForOther) awaitRecovery(admission, context) else admission
    }

    private fun acquireAdmission(context: HfrRestContext, allowRecovery: Boolean): Admission {
        val pause = cooldown.value ?: return Admission(generation, context)
        if (!allowRecovery || remainingNanos(pause) > 0) throw HfrRestBlockedException()
        val active = recovery
        if (active == null && pause.context != HfrRestContext.UNKNOWN && pause.context != context) {
            throw HfrRestRecoveryPendingException()
        }
        return active ?: Admission(generation, context, CompletableDeferred()).also { recovery = it }
    }

    private suspend fun awaitRecovery(admission: Admission, context: HfrRestContext): Admission {
        val confirmed = withTimeoutOrNull(RECOVERY_WAIT_MS) { checkNotNull(admission.outcome).await() }
        if (confirmed != true) {
            throw if (cooldownDeadline() != null) HfrRestBlockedException() else HfrRestRecoveryPendingException()
        }
        return synchronized(this) {
            if (cooldown.value != null) throw HfrRestBlockedException()
            Admission(generation, context)
        }
    }

    /** Runs immediately before every transport exchange, including OkHttp follow-ups. */
    @Synchronized
    internal fun checkExchange(admission: Admission, singleExchange: Boolean = false) {
        if (admission.generation != generation) throw HfrRestBlockedException()
        if (admission.isRecovery && admission.exchanged) throw HfrRestRecoveryPendingException()
        checkExchangeLimit(singleExchange, admission.exchanged)
        admission.exchanged = true
    }

    private fun checkExchangeLimit(singleExchange: Boolean, alreadySent: Boolean) {
        if (singleExchange && alreadySent) throw IOException("Flags reconciliation exchange limit reached")
    }

    internal suspend fun complete(admission: Admission, reply: RestReply?) = storage.withLock {
        val previous = cooldown.value
        recordReply(admission, reply)
        val current = cooldown.value
        if (current != previous) persist(current)
    }

    @Synchronized
    private fun recordReply(admission: Admission, reply: RestReply?) {
        val wasRecovering = recovery === admission
        if (wasRecovering) recovery = null
        if (admission.generation != generation) {
            if (reply?.qualifying == true) extendPause(admission.context, reply)
            admission.outcome?.complete(false)
            return
        }
        val retryUntil = reply?.takeIf { it.qualifying }?.let(::retryDeadline)
        when {
            reply?.successful == true -> {
                firstRefusal = null
                if (wasRecovering) updateCooldown(null)
            }
            retryUntil != null -> recordRefusal(admission.context, wasRecovering, retryUntil)
        }
        admission.outcome?.complete(reply?.successful == true)
    }

    private fun recordRefusal(context: HfrRestContext, wasRecovering: Boolean, retryUntil: Long) {
        val first = firstRefusal
        if (!wasRecovering && first == null) {
            firstRefusal = Refusal(retryUntil, context)
            return
        }
        val now = elapsed.nowNanos()
        val level = ((cooldown.value?.level ?: -1) + 1).coerceAtMost(COOLDOWN_MINUTES.lastIndex)
        val wait = Duration.ofMinutes(COOLDOWN_MINUTES[level]).toNanos()
        val duration = maxOf(wait, retryUntil - now, first?.deadline?.minus(now) ?: 0).coerceAtMost(MAX_COOLDOWN_NANOS)
        firstRefusal = null
        updateCooldown(Pause(now + duration, level, recoveryContext(context, first?.context)))
    }

    private fun extendPause(context: HfrRestContext, reply: RestReply) {
        val pause = cooldown.value ?: return
        val now = elapsed.nowNanos()
        val extension = (retryDeadline(reply) - now).coerceIn(0, MAX_COOLDOWN_NANOS)
        val recoveryContext = recoveryContext(pause.context, context)
        val remaining = remainingNanos(pause)
        if (extension > remaining || recoveryContext != pause.context) {
            updateCooldown(pause.copy(deadline = now + maxOf(extension, remaining), context = recoveryContext))
        }
    }

    private fun recoveryContext(context: HfrRestContext, other: HfrRestContext?): HfrRestContext =
        if (other == HfrRestContext.AUTHENTICATED) other else context

    private fun retryDeadline(reply: RestReply): Long =
        elapsed.nowNanos() + reply.headers.retryAfterDelay(clock.instant()).toNanos()

    private fun remainingNanos(pause: Pause): Long =
        (pause.deadline - elapsed.nowNanos()).coerceIn(0, MAX_COOLDOWN_NANOS)

    private suspend fun load() {
        if (loaded) return
        storage.withLock {
            if (loaded) return@withLock
            val saved = try {
                store.read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                logStorageError(error)
                null
            }
            if (saved != null) {
                val now = clock.instant()
                val duration = Duration.between(now, saved.until).coerceIn(Duration.ZERO, MAX_COOLDOWN)
                val level = saved.level.coerceIn(0, COOLDOWN_MINUTES.lastIndex)
                val pause = Pause(elapsed.nowNanos() + duration.toNanos(), level, saved.context)
                cooldown.value = pause
                if (saved.until.isAfter(now.plus(MAX_COOLDOWN)) || saved.level != level) persist(pause)
            }
            loaded = true
        }
    }

    private fun updateCooldown(value: Pause?) {
        generation++
        cooldown.value = value
    }

    private suspend fun persist(value: Pause?) {
        try {
            store.write(value?.let {
                HfrRestCooldown(clock.instant().plusNanos(remainingNanos(it)), it.level, it.context)
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            // Keep enforcing the live pause if disk is temporarily unavailable.
            logStorageError(error)
        }
    }

    private fun logStorageError(error: Exception) {
        if (storageErrorReported) return
        storageErrorReported = true
        log.record(DiagnosticsLog.Level.WARN, "HfrRestCircuitBreaker", "storage error ${error.javaClass.simpleName}")
    }

    internal class Admission(
        val generation: Long,
        val context: HfrRestContext,
        val outcome: CompletableDeferred<Boolean>? = null,
    ) {
        val isRecovery: Boolean get() = outcome != null
        var exchanged = false
    }

    private data class Refusal(val deadline: Long, val context: HfrRestContext)
    private data class Pause(val deadline: Long, val level: Int, val context: HfrRestContext)

    companion object {
        private val COOLDOWN_MINUTES = listOf(10L, 20L, 40L, 60L)
        private val MAX_COOLDOWN = Duration.ofHours(2)
        private val MAX_COOLDOWN_NANOS = MAX_COOLDOWN.toNanos()
        private const val RECOVERY_WAIT_MS = 12_000L

        internal fun inMemory() = HfrRestCircuitBreaker(object : HfrRestCooldownStore {
            override suspend fun read(): HfrRestCooldown? = null
            override suspend fun write(cooldown: HfrRestCooldown?) = Unit
        }, Clock.systemUTC(), DiagnosticsLog())

        private fun Headers.retryAfterDelay(now: Instant): Duration {
            val value = get("Retry-After").orEmpty()
            return if (value.isNotEmpty() && value.all { it in '0'..'9' }) {
                val seconds = value.trimStart('0').ifEmpty { "0" }.toLongOrNull() ?: MAX_COOLDOWN.seconds
                Duration.ofSeconds(seconds.coerceAtMost(MAX_COOLDOWN.seconds))
            } else {
                getInstant("Retry-After")?.let { Duration.between(now, it).coerceIn(Duration.ZERO, MAX_COOLDOWN) }
                    ?: Duration.ZERO
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
