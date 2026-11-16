package fr.forumhfr.redface2.core.network

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Functional monotonic clock; neither deadlines nor retry eligibility depend on diagnostics. */
@Singleton
class FlagReadClock @Inject constructor() {
    fun nowNanos(): Long = System.nanoTime()
}

/** Closed intent list. GLOBAL_BUCKET is reserved until its response contract is implemented. */
enum class FlagReadOperation { CATALOGUE, CATEGORY_BUCKET, STICKY_LAST, GLOBAL_BUCKET }

class FlagReadDeadlineException : IOException("Flags collection deadline reached")

/** One admitted type refresh. Joiners inherit this instance and can never extend its deadline. */
class FlagReadSession(
    private val nanoTime: () -> Long = System::nanoTime,
) : AbstractCoroutineContextElement(Key) {
    private val deadline = nanoTime() + COLLECTION_TIMEOUT_MS * NANOS_PER_MS
    private var successfulCalls = 0
    private var logicalOperations = 0
    private var applicationCalls = 0
    private var retriesAttempted = 0
    private var retriesSucceeded = 0
    private var reservedRetries = 0
    private var permitWaitNanos = 0L
    private var deadlineCategories = 0

    fun remainingNanos(): Long = (deadline - nanoTime()).coerceAtLeast(0)
    fun remainingMillis(): Long = remainingNanos() / NANOS_PER_MS
    fun checkBudget() { if (remainingNanos() == 0L) throw FlagReadDeadlineException() }
    fun nowNanos(): Long = nanoTime()

    @Synchronized fun logicalOperation() { logicalOperations++ }
    @Synchronized fun applicationCall(retry: Boolean) {
        applicationCalls++
        if (retry) retriesAttempted++
    }
    @Synchronized fun succeeded(retry: Boolean) {
        successfulCalls++
        if (retry) retriesSucceeded++
    }
    @Synchronized fun reserveRetry(): Boolean {
        val allowed = successfulCalls > 0 && reservedRetries < MAX_RETRIES
        if (allowed) reservedRetries++
        return allowed
    }

    @Synchronized fun waited(nanos: Long) { permitWaitNanos += nanos.coerceAtLeast(0) }
    @Synchronized fun expiredCategories(count: Int) { deadlineCategories = count }
    @Synchronized fun counters(): Map<String, Long> = mapOf(
        "logicalOperations" to logicalOperations.toLong(),
        "applicationCalls" to applicationCalls.toLong(),
        "retriesAttempted" to retriesAttempted.toLong(),
        "retriesSucceeded" to retriesSucceeded.toLong(),
        "permitWaitMs" to permitWaitNanos / NANOS_PER_MS,
        "deadlineCategories" to deadlineCategories.toLong(),
    )

    companion object Key : CoroutineContext.Key<FlagReadSession> {
        const val COLLECTION_TIMEOUT_MS = 12_000L
        const val MAX_RETRIES = 10
        private const val NANOS_PER_MS = 1_000_000L
    }
}
