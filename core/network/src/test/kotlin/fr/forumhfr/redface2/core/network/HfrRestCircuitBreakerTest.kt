package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.error.HfrRestBlockedException
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HfrRestCircuitBreakerTest {
    private val time = TestClock()
    private val store = MemoryStore()
    private val breaker = newBreaker()

    @Test
    fun `403 requires the Apache signature and HTML on the REST endpoint`() {
        val signature = "You don't have permission to access"
        val denial = Response.Builder()
            .request(Request.Builder().url("https://hfr.test/webservices/rest_api.php").build())
            .protocol(Protocol.HTTP_1_1)
            .code(403)
            .message("Forbidden")
            .header("Content-Type", "text/html; charset=iso-8859-1")
            .build()

        assertTrue(denial.restReply(signature)?.qualifying == true)
        assertFalse(denial.restReply("Access to this category is restricted")?.qualifying == true)
        val json = denial.newBuilder().header("Content-Type", "application/json").build()
        assertFalse(json.restReply(signature)?.qualifying == true)
        val html = denial.newBuilder().request(Request.Builder().url("https://hfr.test/forum2.php").build()).build()
        assertNull(html.restReply(signature))
    }

    @Test
    fun `two refusals without a successful REST response open the breaker`() = runTest {
        breaker.reply(refused())
        breaker.reply(RestReply(successful = false, qualifying = false))
        breaker.reply(success)
        breaker.reply(refused())
        assertNull(store.saved)

        breaker.reply(refused())
        assertEquals(HfrRestCooldown(time.now.plusSeconds(600), level = 0, HfrRestContext.ANONYMOUS), store.saved)
        assertBlocked { breaker.admit() }
    }

    @Test
    fun `cooldown survives restart and only one REST request may recover at expiry`() = runTest {
        val late = breaker.admit()
        breaker.reply(refused("1800"))
        breaker.reply(refused())
        breaker.complete(late, success)

        val restarted = newBreaker()
        val deadline = time.now.plusSeconds(1800)
        assertEquals(HfrRestCooldown(deadline, level = 0, HfrRestContext.ANONYMOUS), store.saved)
        assertBlocked { restarted.admit() }
        assertBlocked { restarted.checkProbeAllowed() }

        time.moveTo(deadline)
        val recovery = restarted.admit()
        val sibling = async { runCatching { restarted.admit() } }
        runCurrent()
        assertFalse(sibling.isCompleted)
        assertBlocked { restarted.checkProbeAllowed() }
        restarted.complete(recovery, null)
        assertTrue(sibling.await().isFailure)
        restarted.complete(restarted.admit(), success)
        assertNull(store.saved)
        restarted.checkProbeAllowed()
    }

    @Test
    fun `recovery refusals back off to one hour and success resets the sequence`() = runTest {
        breaker.reply(refused())
        for ((attempt, minutes) in listOf(10L, 20L, 40L, 60L, 60L).withIndex()) {
            breaker.reply(refused())
            val deadline = time.now.plusSeconds(minutes * 60)
            val expected = HfrRestCooldown(deadline, level = attempt.coerceAtMost(3), HfrRestContext.ANONYMOUS)
            assertEquals(expected, store.saved)
            time.moveTo(deadline)
        }
        breaker.reply(success)
        breaker.reply(refused())
        assertNull(store.saved)
        breaker.reply(refused())
        assertEquals(HfrRestCooldown(time.now.plusSeconds(600), level = 0, HfrRestContext.ANONYMOUS), store.saved)
    }

    @Test
    fun `HTTP dates and oversized delay seconds are both capped at two hours`() = runTest {
        breaker.reply(refused("Fri, 27 Nov 2026 13:00:00 GMT"))
        breaker.reply(refused("Fri, 27 Nov 2026 13:30:00 GMT"))
        val expected = HfrRestCooldown(Instant.parse("2026-11-27T13:30:00Z"), 0, HfrRestContext.ANONYMOUS)
        assertEquals(expected, store.saved)

        time.moveTo(checkNotNull(store.saved).until)
        breaker.reply(refused("999999999999999999999999"))
        assertEquals(HfrRestCooldown(time.now.plusSeconds(7200), level = 1, HfrRestContext.ANONYMOUS), store.saved)

        time.moveTo(checkNotNull(store.saved).until)
        breaker.reply(refused("Fri, 27 Nov 2026 18:00:00 GMT"))
        assertEquals(HfrRestCooldown(time.now.plusSeconds(7200), level = 2, HfrRestContext.ANONYMOUS), store.saved)
    }

    @Test
    fun `restored deadline is clamped and the live flow clears it without traffic`() = runTest {
        val observed = mutableListOf<Instant?>()
        val probeBlocked = mutableListOf<Boolean>()
        store.saved = HfrRestCooldown(time.now.plusSeconds(86_400), level = 50)
        val deadline = time.now.plusSeconds(7200)
        val observing = launch { breaker.blockedUntil.take(2).toList(observed) }
        val observingProbe = launch { breaker.probeBlocked.take(2).toList(probeBlocked) }

        runCurrent()
        assertEquals(listOf(deadline), observed)
        assertEquals(listOf(true), probeBlocked)
        assertEquals(HfrRestCooldown(deadline, level = 3), store.saved)
        time.moveTo(deadline)
        advanceTimeBy(7_200_000)
        observing.join()
        assertEquals(listOf(deadline, null), observed)
        assertBlocked { breaker.checkProbeAllowed() }
        assertEquals(listOf(true), probeBlocked)
        breaker.reply(success)
        observingProbe.join()
        assertEquals(listOf(true, false), probeBlocked)
    }

    @Test
    fun `a late refusal extends the pause and wall-clock jumps do not change its remaining duration`() = runTest {
        val late = breaker.admit()
        repeat(2) { breaker.reply(refused()) }
        breaker.complete(late, refused("9000"))
        assertEquals(time.now.plusSeconds(7200), store.saved?.until)

        time.now = time.now.plusSeconds(86_400)
        assertBlocked { breaker.admit() }
        time.now = time.now.minusSeconds(172_800)
        time.nanos = Duration.ofHours(2).toNanos() - 1
        assertBlocked { breaker.admit() }
        time.nanos++
        assertNull(breaker.cooldownDeadline())
        assertNotNull(breaker.admit().outcome)
    }

    private suspend fun HfrRestCircuitBreaker.reply(reply: RestReply) = complete(admit(), reply)

    private suspend fun assertBlocked(action: suspend () -> Unit) {
        assertTrue(runCatching { action() }.exceptionOrNull() is HfrRestBlockedException)
    }

    private fun newBreaker() = HfrRestCircuitBreaker(store, time, DiagnosticsLog(), mockk {
        every { nowNanos() } answers { time.nanos }
    })

    private class MemoryStore : HfrRestCooldownStore {
        var saved: HfrRestCooldown? = null
        override suspend fun read(): HfrRestCooldown? = saved
        override suspend fun write(cooldown: HfrRestCooldown?) { saved = cooldown }
    }

    private class TestClock : Clock() {
        var now: Instant = Instant.parse("2026-11-27T12:00:00Z")
        var nanos: Long = 0
        fun moveTo(instant: Instant) {
            nanos += Duration.between(now, instant).toNanos()
            now = instant
        }
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(now, zone)
    }

    private companion object {
        val success = RestReply(successful = true, qualifying = false)
        fun refused(retryAfter: String? = null) = RestReply(
            successful = false,
            qualifying = true,
            headers = retryAfter?.let { Headers.headersOf("Retry-After", it) } ?: Headers.EMPTY,
        )
    }
}
