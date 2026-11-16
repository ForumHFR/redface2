package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.flags.FlagsRefreshPolicy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One process-wide semaphore. A policy change closes admission, cancels and drains old Calls. */
@Singleton
class FlagReadLimiter @Inject constructor() {
    private val admission = Mutex()
    private var policy = FlagsRefreshPolicy()
    private var semaphore: Semaphore? = Semaphore(MAX_CALLS)
    private val calls = mutableSetOf<Job>()

    suspend fun configure(next: FlagsRefreshPolicy) = admission.withLock {
        if (next == policy) return@withLock
        withContext(NonCancellable) {
            val old = synchronized(calls) { calls.toList() }
            old.forEach { it.cancel() }
            old.forEach { it.join() }
            // No Call can retain the old semaphore when its replacement becomes visible.
            semaphore = next.effectiveConcurrency?.let(::Semaphore)
            policy = next
        }
    }

    internal suspend fun <T> withPermit(session: FlagReadSession, block: suspend () -> T): T {
        val job = checkNotNull(currentCoroutineContext()[Job])
        val selected = admission.withLock {
            check(session.policy == policy) { "Obsolete flags read policy" }
            synchronized(calls) { calls.add(job) }
            semaphore
        }
        val start = session.nowNanos()
        var acquired = false
        try {
            try {
                selected?.acquire()
                acquired = true
            } finally {
                session.waited(session.nowNanos() - start)
            }
            session.checkBudget()
            return block()
        } finally {
            if (acquired) selected?.release()
            synchronized(calls) { calls.remove(job) }
        }
    }

    internal val heldPermits: Int get() = semaphore?.let {
        checkNotNull(policy.effectiveConcurrency) - it.availablePermits
    } ?: synchronized(calls) { calls.size }

    companion object { const val MAX_CALLS = FlagsRefreshPolicy.DEFAULT_CONCURRENCY }
}
