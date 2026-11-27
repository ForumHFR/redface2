package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.error.HfrRestBlockedException
import fr.forumhfr.redface2.core.domain.flags.FlagsRefreshPolicy
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Functional monotonic clock; neither deadlines nor retry eligibility depend on diagnostics. */
@Singleton
class FlagReadClock @Inject constructor() {
    fun nowNanos(): Long = System.nanoTime()
}

/** Closed read intent list; mutations never enter the flags executor. */
enum class FlagReadOperation { CATALOGUE, CATEGORY_BUCKET, STICKY_LAST, GLOBAL_BUCKET }

class FlagReadDeadlineException : IOException("Flags collection deadline reached")

/** One admitted type refresh. Joiners inherit this instance and can never extend its deadline. */
class FlagReadSession private constructor(
    private val nanoTime: () -> Long,
    val policy: FlagsRefreshPolicy,
    private val deadline: Long,
    private val progress: Progress,
) : AbstractCoroutineContextElement(Key) {
    constructor(
        nanoTime: () -> Long = System::nanoTime,
        policy: FlagsRefreshPolicy = FlagsRefreshPolicy(),
        deadline: Long = nanoTime() + COLLECTION_TIMEOUT_MS * NANOS_PER_MS,
    ) : this(nanoTime, policy, deadline, Progress())

    constructor(nanoTime: () -> Long) : this(nanoTime, FlagsRefreshPolicy())

    fun withPolicy(policy: FlagsRefreshPolicy): FlagReadSession = FlagReadSession(nanoTime, policy, deadline)

    /** Start the shared sticky sweep with its own deadline and the admission's existing retry quota. */
    fun startStickyPhase(): FlagReadSession =
        FlagReadSession(nanoTime, policy, nanoTime() + STICKY_TIMEOUT_MS * NANOS_PER_MS, progress)

    internal fun abort(error: HfrRestBlockedException) { progress.blocked.compareAndSet(null, error) }
    internal fun checkNotBlocked() { progress.blocked.get()?.let { throw it } }
    val wasBlocked: Boolean get() = progress.blocked.get() != null

    fun remainingNanos(): Long = (deadline - nanoTime()).coerceAtLeast(0)
    fun remainingMillis(): Long = remainingNanos() / NANOS_PER_MS
    fun checkBudget() { if (remainingNanos() == 0L) throw FlagReadDeadlineException() }
    fun nowNanos(): Long = nanoTime()

    fun logicalOperation() { synchronized(progress) { progress.logicalOperations++ } }
    fun applicationCall(retry: Boolean) = synchronized(progress) {
        progress.applicationCalls++
        if (retry) progress.retriesAttempted++
    }
    fun succeeded(retry: Boolean) = synchronized(progress) {
        progress.successfulCalls++
        if (retry) progress.retriesSucceeded++
    }
    fun reserveRetry(): Boolean = synchronized(progress) {
        val allowed = policy.applicationRetry && progress.successfulCalls > 0 && progress.reservedRetries < MAX_RETRIES
        if (allowed) progress.reservedRetries++
        allowed
    }

    fun waited(nanos: Long) = synchronized(progress) { progress.permitWaitNanos += nanos.coerceAtLeast(0) }
    fun expiredCategories(count: Int) = synchronized(progress) { progress.deadlineCategories = count }
    fun counters(): Map<String, Long> = synchronized(progress) {
        mapOf(
            "logicalOperations" to progress.logicalOperations.toLong(),
            "applicationCalls" to progress.applicationCalls.toLong(),
            "retriesAttempted" to progress.retriesAttempted.toLong(),
            "retriesSucceeded" to progress.retriesSucceeded.toLong(),
            "permitWaitMs" to progress.permitWaitNanos / NANOS_PER_MS,
            "deadlineCategories" to progress.deadlineCategories.toLong(),
        )
    }

    private class Progress {
        val blocked = AtomicReference<HfrRestBlockedException?>()
        var successfulCalls = 0
        var logicalOperations = 0
        var applicationCalls = 0
        var retriesAttempted = 0
        var retriesSucceeded = 0
        var reservedRetries = 0
        var permitWaitNanos = 0L
        var deadlineCategories = 0
    }

    companion object Key : CoroutineContext.Key<FlagReadSession> {
        const val COLLECTION_TIMEOUT_MS = 12_000L
        const val MAX_RETRIES = 10
        private const val STICKY_TIMEOUT_MS = 12_000L
        private const val NANOS_PER_MS = 1_000_000L
    }
}
