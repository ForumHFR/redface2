package fr.forumhfr.redface2.core.network

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Semaphore

/** One process-wide bound on Calls, including their response bodies, across all types and callers. */
@Singleton
class FlagReadLimiter @Inject constructor() {
    private val semaphore = Semaphore(MAX_CALLS)

    internal suspend fun <T> withPermit(session: FlagReadSession, block: suspend () -> T): T {
        val start = session.nowNanos()
        try {
            semaphore.acquire()
        } finally {
            session.waited(session.nowNanos() - start)
        }
        return try {
            session.checkBudget()
            block()
        } finally {
            semaphore.release()
        }
    }

    internal val heldPermits: Int get() = MAX_CALLS - semaphore.availablePermits

    companion object { const val MAX_CALLS = 5 }
}
