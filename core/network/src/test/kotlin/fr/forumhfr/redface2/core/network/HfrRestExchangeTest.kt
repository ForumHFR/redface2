package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.error.HfrRestBlockedException
import fr.forumhfr.redface2.core.domain.error.HfrRestRecoveryPendingException
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HfrRestExchangeTest {
    @get:Rule val server = MockWebServer()
    private val clock = Clock.fixed(Instant.parse("2026-11-27T12:00:00Z"), ZoneOffset.UTC)
    private val nanos = AtomicLong()
    private val log = DiagnosticsLog()
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
    }.build()
    private val authenticated = client.newBuilder().addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("Cookie", "test_session=present").build())
    }.build()

    @Test
    fun `an unreadable cooldown is logged once and admission still reaches HFR`() = runBlocking {
        var attempts = 0
        val storage = object : HfrRestCooldownStore {
            override suspend fun read(): HfrRestCooldown? {
                attempts++
                throw IOException("unreadable preferences")
            }
            override suspend fun write(cooldown: HfrRestCooldown?) = Unit
        }
        val breaker = breaker(storage)
        breaker.checkProbeAllowed()
        val api = api(breaker)
        repeat(2) {
            server.enqueue(MockResponse().setBody("available"))
            assertEquals("available", api.getCategories())
        }
        assertEquals(2, server.requestCount)
        assertEquals(1, attempts)
        assertEquals(1, log.entries.value.count { it.message.contains("storage error") })
    }

    @Test
    fun `follow ups of calls already in flight stop at the network interceptor when a pause opens`() = runBlocking {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val uri = request.requestUrl?.queryParameter("uri").orEmpty()
                if ("subcategories" !in uri) return refused()
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return if ("/13/" in uri) {
                    MockResponse().setResponseCode(503).setHeader("Retry-After", "0")
                } else {
                    MockResponse().setResponseCode(408)
                }
            }
        }
        val api = api(breaker())
        val pending = listOf(13, 23).map { cat ->
            async(Dispatchers.IO) { runCatching { api.getSubcategories(cat) } }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repeat(2) { assertTrue(runCatching { api.getCategories() }.exceptionOrNull() is HfrRestBlockedException) }
        } finally {
            release.countDown()
        }
        pending.forEach { assertTrue(it.await().exceptionOrNull() is HfrRestBlockedException) }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `recovery emits one exchange through 503 408 and a connection lost after send`() = runBlocking {
        val saved = HfrRestCooldown(clock.instant(), 0, HfrRestContext.AUTHENTICATED)
        val followUps = listOf(
            MockResponse().setResponseCode(503).setHeader("Retry-After", "0"),
            MockResponse().setResponseCode(408),
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
        )
        followUps.forEachIndexed { index, response ->
            server.enqueue(response)
            val breaker = breaker(MemoryStore(saved))
            val api = api(breaker)
            val result = runCatching { api.getCategoryFlagTopics(13, HfrRestFlagBucket.READ, useAuth = true) }
            assertTrue(result.exceptionOrNull() is HfrRestRecoveryPendingException)
            assertEquals(index + 1, server.requestCount)
            assertTrue(runCatching { breaker.checkProbeAllowed() }.isFailure)
            val sent = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals(USER_AGENT, sent.getHeader("User-Agent"))
            assertEquals("test_session=present", sent.getHeader("Cookie"))
        }
    }

    @Test
    fun `a flags attempt refused during a pause is counted only as a logical operation`() = runBlocking {
        val breaker = breaker(MemoryStore(HfrRestCooldown(clock.instant().plusSeconds(600), 0)))
        val session = FlagReadSession()
        val api = api(breaker)
        val error = runCatching {
            withContext(session) { api.getCategoryFlagTopics(13, HfrRestFlagBucket.READ) }
        }.exceptionOrNull()

        assertTrue(error is HfrRestBlockedException)
        assertEquals(0, server.requestCount)
        assertEquals(1L, session.counters().getValue("logicalOperations"))
        assertEquals(0L, session.counters().getValue("applicationCalls"))
    }

    @Test
    fun `a cold flags catalogue uses authentication when recovering authenticated REST`() = runBlocking {
        val saved = HfrRestCooldown(clock.instant(), 0, HfrRestContext.AUTHENTICATED)
        val breaker = breaker(MemoryStore(saved))
        val api = api(breaker)
        server.enqueue(MockResponse().setBody("catalogue"))

        assertEquals("catalogue", withContext(FlagReadSession()) { api.getCategories() })
        breaker.checkProbeAllowed()
        assertEquals(1, server.requestCount)
        val sent = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("test_session=present", sent.getHeader("Cookie"))
        assertEquals(USER_AGENT, sent.getHeader("User-Agent"))
    }

    @Test
    fun `an anonymous catalogue cannot clear the authenticated pause or unlock Diagnostics`() = runBlocking {
        val breaker = breaker(MemoryStore(HfrRestCooldown(clock.instant(), 0, HfrRestContext.AUTHENTICATED)))
        val api = api(breaker)
        assertTrue(runCatching { api.getCategories() }.exceptionOrNull() is HfrRestRecoveryPendingException)
        assertTrue(runCatching { breaker.checkProbeAllowed() }.isFailure)
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setBody("account flags"))
        assertEquals("account flags", api.getCategoryFlagTopics(13, HfrRestFlagBucket.READ, useAuth = true))
        breaker.checkProbeAllowed()
        assertEquals(1, server.requestCount)
        val sent = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals(USER_AGENT, sent.getHeader("User-Agent"))
        assertEquals("test_session=present", sent.getHeader("Cookie"))
    }

    @Test
    fun `an in-flight refusal with a longer Retry-After extends the pause to the monotonic cap`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if ("subcategories" !in request.requestUrl?.queryParameter("uri").orEmpty()) return refused()
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return refused().setHeader("Retry-After", "9000")
            }
        }
        val store = MemoryStore()
        val api = api(breaker(store))
        val late = async(Dispatchers.IO) { runCatching { api.getSubcategories(13) } }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repeat(2) { assertTrue(runCatching { api.getCategories() }.isFailure) }
        } finally {
            release.countDown()
        }
        assertTrue(late.await().isFailure)
        assertEquals(clock.instant().plusSeconds(7200), store.saved?.until)
        nanos.set(Duration.ofHours(2).toNanos() - 1)
        assertTrue(runCatching { api.getCategories() }.isFailure)
        assertEquals(3, server.requestCount)
    }

    private fun breaker(store: HfrRestCooldownStore = MemoryStore()) = HfrRestCircuitBreaker(
        store, clock, log, mockk { every { nowNanos() } answers { nanos.get() } },
    )

    private fun api(breaker: HfrRestCircuitBreaker) =
        HfrApiClient(authenticated, client, server.url("/"), Dispatchers.IO, breaker = breaker)

    private class MemoryStore(var saved: HfrRestCooldown? = null) : HfrRestCooldownStore {
        override suspend fun read(): HfrRestCooldown? = saved
        override suspend fun write(cooldown: HfrRestCooldown?) { saved = cooldown }
    }

    private companion object {
        const val USER_AGENT = "Redface2/test (Android; dev)"
        fun refused() = MockResponse().setResponseCode(429)
    }
}
