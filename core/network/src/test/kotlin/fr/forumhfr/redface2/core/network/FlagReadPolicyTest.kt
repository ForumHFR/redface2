package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.flags.FlagsRefreshPolicy
import fr.forumhfr.redface2.core.domain.flags.FlagsRefreshStrategy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FlagReadPolicyTest {
    @Test
    fun `reducing N drains cancelled Calls before admitting any replacement`() = runTest {
        val limiter = FlagReadLimiter()
        val closing = CompletableDeferred<Unit>()
        val old = async {
            limiter.withPermit(FlagReadSession()) {
                try { awaitCancellation() } finally { withContext(NonCancellable) { closing.await() } }
            }
        }
        runCurrent()
        val next = FlagsRefreshPolicy(concurrency = 1)
        val configure = async { limiter.configure(next) }
        runCurrent()
        var admitted = false
        val replacement = async { limiter.withPermit(FlagReadSession(policy = next)) { admitted = true } }
        runCurrent()
        assertFalse(admitted)
        assertFalse(configure.isCompleted)
        closing.complete(Unit)
        configure.await()
        replacement.await()
        assertTrue(old.isCancelled)
        assertTrue(admitted)
        assertEquals(0, limiter.heldPermits)
    }

    @Test
    fun `N bounds Calls across sessions while legacy alone removes the bound`() = runTest {
        for (strategy in listOf(FlagsRefreshStrategy.BASELINE, FlagsRefreshStrategy.LEGACY)) {
            val limiter = FlagReadLimiter()
            val policy = FlagsRefreshPolicy(strategy, concurrency = 1)
            limiter.configure(policy)
            val finish = CompletableDeferred<Unit>()
            var active = 0
            val calls = (1..8).map { async {
                limiter.withPermit(FlagReadSession(policy = policy)) {
                    active++
                    finish.await()
                }
            } }
            runCurrent()
            assertEquals(if (strategy == FlagsRefreshStrategy.LEGACY) 8 else 1, active)
            finish.complete(Unit)
            calls.awaitAll()
            assertEquals(0, limiter.heldPermits)
        }
    }

    @Test
    fun `disabled application retry stays off after a successful Call`() {
        val session = FlagReadSession(policy = FlagsRefreshPolicy(applicationRetry = false))
        session.succeeded(retry = false)
        assertFalse(session.reserveRetry())
    }

    @Test
    fun `policy replacement retains the admitted deadline`() {
        var nanos = 0L
        val session = FlagReadSession { nanos }
        nanos = 10_000_000_000L
        val next = session.withPolicy(FlagsRefreshPolicy(concurrency = 1))
        assertEquals(2_000, next.remainingMillis())
    }
}
