package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.error.HfrRestBlockedException
import fr.forumhfr.redface2.core.domain.error.HfrRestRecoveryPendingException
import fr.forumhfr.redface2.core.network.cookie.CookieStore
import fr.forumhfr.redface2.core.network.cookie.PersistentCookieJar
import io.mockk.every
import io.mockk.mockk
import java.io.File
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        chain.proceed(chain.request().newBuilder().header("Cookie", SESSION_COOKIES).build())
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
            assertEquals(SESSION_COOKIES, sent.getHeader("Cookie"))
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
        val store = MemoryStore(saved)
        val breaker = breaker(store)
        val api = api(breaker)
        val catalogue = sharedRestFixture("rest_categories_auth.json")
        server.enqueue(MockResponse().setBody(catalogue))

        assertEquals(catalogue, withContext(FlagReadSession()) { api.getCategories() })
        breaker.checkProbeAllowed()
        assertNull(store.saved)
        assertEquals(1, server.requestCount)
        val sent = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals(SESSION_COOKIES, sent.getHeader("Cookie"))
        assertEquals(USER_AGENT, sent.getHeader("User-Agent"))
    }

    @Test
    fun `a public catalogue received after local cookie expiry keeps an authenticated pause`() = runBlocking {
        val saved = HfrRestCooldown(clock.instant(), 0, HfrRestContext.AUTHENTICATED)
        val store = MemoryStore(saved)
        val breaker = breaker(store)
        val host = server.url("/").host
        val expiredCookies = listOf(
            Cookie.Builder().name("md_user").value("tester").domain(host).expiresAt(1).build(),
            Cookie.Builder().name("md_passs").value("test-session").domain(host).expiresAt(1).build(),
        )
        val cookieStore = object : CookieStore {
            override fun observe() = flowOf(expiredCookies)
            override suspend fun save(cookies: List<Cookie>) = Unit
            override suspend fun clear() = Unit
        }
        server.enqueue(MockResponse().setBody(sharedRestFixture("rest_categories.json")))

        PersistentCookieJar(cookieStore, Dispatchers.IO).use { jar ->
            val api = api(breaker, client.newBuilder().cookieJar(jar).build())
            val error = runCatching { withContext(FlagReadSession()) { api.getCategories() } }.exceptionOrNull()

            assertEquals(1, server.requestCount)
            assertNull(requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).getHeader("Cookie"))
            assertTrue(error is HfrRestRecoveryPendingException)
            assertEquals(saved, store.saved)
            assertTrue(runCatching { breaker.checkProbeAllowed() }.exceptionOrNull() is HfrRestBlockedException)
        }
    }

    @Test
    fun `a public catalogue received with session cookies keeps the authenticated pause`() = runBlocking {
        val saved = HfrRestCooldown(clock.instant(), 0, HfrRestContext.AUTHENTICATED)
        val store = MemoryStore(saved)
        val breaker = breaker(store)
        val api = api(breaker)
        server.enqueue(MockResponse().setBody(sharedRestFixture("rest_categories.json")))

        val error = runCatching { withContext(FlagReadSession()) { api.getCategories() } }.exceptionOrNull()

        assertTrue(error is HfrRestRecoveryPendingException)
        assertEquals(1, server.requestCount)
        val sent = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("GET", sent.method)
        assertEquals("forums/hardwarefr/categories/", sent.requestUrl?.queryParameter("uri"))
        assertEquals(SESSION_COOKIES, sent.getHeader("Cookie"))
        assertEquals(saved, store.saved)
        assertTrue(breaker.probeBlocked.first())
        assertTrue(runCatching { breaker.checkProbeAllowed() }.exceptionOrNull() is HfrRestBlockedException)
    }

    @Test
    fun `a public catalogue cannot supply read_topics through text`() =
        assertPublicCatalogueRejectsLinkText("read_topics")

    @Test
    fun `a public catalogue cannot supply participated_topics through text`() =
        assertPublicCatalogueRejectsLinkText("participated_topics")

    @Test
    fun `a public catalogue cannot supply favorite_topics through text`() =
        assertPublicCatalogueRejectsLinkText("favorite_topics")

    @Test
    fun `a public list of topics cannot confirm an authenticated bucket recovery`() = runBlocking {
        val saved = HfrRestCooldown(clock.instant(), 0, HfrRestContext.AUTHENTICATED)
        val store = MemoryStore(saved)
        val breaker = breaker(store)
        val api = api(breaker)
        server.enqueue(MockResponse().setBody(sharedRestFixture("rest_topics_cat23_subcat550_p1.json")))

        val error = runCatching { api.getCategoryFlagTopics(23, HfrRestFlagBucket.PARTICIPATED) }.exceptionOrNull()

        assertTrue(error is HfrRestRecoveryPendingException)
        assertEquals(1, server.requestCount)
        assertEquals(SESSION_COOKIES, requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).getHeader("Cookie"))
        assertEquals(saved, store.saved)
        assertTrue(breaker.probeBlocked.first())

        val bucket = sharedRestFixture("rest_cat23_participated.json")
        server.enqueue(MockResponse().setBody(bucket))
        assertEquals(bucket, api.getCategoryFlagTopics(23, HfrRestFlagBucket.PARTICIPATED))
        assertEquals(2, server.requestCount)
        assertNull(store.saved)
        breaker.checkProbeAllowed()
    }

    @Test
    fun `a public topic title cannot supply the bucket read state`() = runBlocking {
        val saved = HfrRestCooldown(clock.instant(), 0, HfrRestContext.AUTHENTICATED)
        val store = MemoryStore(saved)
        val api = api(breaker(store))
        val body = sharedRestFixture("rest_topics_cat23_subcat550_p1.json").replaceFirst(
            """"title": """",
            """"title": "\"is_read\": true, """,
        )
        server.enqueue(MockResponse().setBody(body))

        val error = runCatching { api.getCategoryFlagTopics(23, HfrRestFlagBucket.PARTICIPATED) }.exceptionOrNull()

        assertTrue(error is HfrRestRecoveryPendingException)
        assertEquals(saved, store.saved)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a grouped bucket confirms authenticated recovery using its nested topics`() = runBlocking {
        val store = MemoryStore(HfrRestCooldown(clock.instant(), 0, HfrRestContext.AUTHENTICATED))
        val breaker = breaker(store)
        val api = api(breaker)
        val bucket = sharedRestFixture("rest_global_participated.json")
        server.enqueue(MockResponse().setBody(bucket))

        assertEquals(bucket, api.getGlobalFlagTopics(HfrRestFlagBucket.PARTICIPATED))
        assertEquals(1, server.requestCount)
        assertNull(store.saved)
        breaker.checkProbeAllowed()
    }

    @Test
    fun `each nonempty group needs its own read state to confirm recovery`() = runBlocking {
        val saved = HfrRestCooldown(clock.instant(), 0, HfrRestContext.AUTHENTICATED)
        val store = MemoryStore(saved)
        val api = api(breaker(store))
        val bucket = sharedRestFixture("rest_global_participated.json")
        val body = bucket.replaceFirst(Regex(""""is_read": (?:true|false),"""), "")
        assertTrue(body != bucket)
        server.enqueue(MockResponse().setBody(body))

        val error = runCatching { api.getGlobalFlagTopics(HfrRestFlagBucket.PARTICIPATED) }.exceptionOrNull()

        assertTrue(error is HfrRestRecoveryPendingException)
        assertEquals(saved, store.saved)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a login page during authenticated recovery surfaces the expired session`() = runBlocking {
        val saved = HfrRestCooldown(clock.instant(), 0, HfrRestContext.AUTHENTICATED)
        val store = MemoryStore(saved)
        val breaker = breaker(store)
        val api = api(breaker)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(
            """<form action="login_validation.php"><input name="pseudo"><input name="password"></form>""",
        ))

        val error = runCatching { withContext(FlagReadSession()) { api.getCategories() } }.exceptionOrNull()

        assertEquals(1, server.requestCount)
        val sent = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals(SESSION_COOKIES, sent.getHeader("Cookie"))
        assertEquals(sent.requestUrl.toString(), (error as? SessionExpiredException)?.finalUrl)
        assertEquals(saved, store.saved)
        assertTrue(runCatching { breaker.checkProbeAllowed() }.exceptionOrNull() is HfrRestBlockedException)
    }

    @Test
    fun `a login redirect during authenticated recovery surfaces the expired session in one exchange`() = runBlocking {
        val saved = HfrRestCooldown(clock.instant(), 0, HfrRestContext.AUTHENTICATED)
        val store = MemoryStore(saved)
        val breaker = breaker(store)
        val api = api(breaker)
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/login.php"))
        server.enqueue(MockResponse().setBody("login form"))

        val error = runCatching {
            withContext(FlagReadSession()) { api.getCategoryFlagTopics(13, HfrRestFlagBucket.READ) }
        }.exceptionOrNull()

        assertEquals(server.url("/login.php").toString(), (error as? SessionExpiredException)?.finalUrl)
        assertEquals(1, server.requestCount)
        val sent = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("GET", sent.method)
        assertEquals("forums/hardwarefr/categories/13/topics/read/", sent.requestUrl?.queryParameter("uri"))
        assertEquals(SESSION_COOKIES, sent.getHeader("Cookie"))
        assertEquals(saved, store.saved)
        assertTrue(breaker.probeBlocked.first())
        assertTrue(runCatching { breaker.checkProbeAllowed() }.exceptionOrNull() is HfrRestBlockedException)
    }

    @Test
    fun `an anonymous catalogue cannot clear the authenticated pause or unlock Diagnostics`() = runBlocking {
        val breaker = breaker(MemoryStore(HfrRestCooldown(clock.instant(), 0, HfrRestContext.AUTHENTICATED)))
        val api = api(breaker)
        assertTrue(runCatching { api.getCategories() }.exceptionOrNull() is HfrRestRecoveryPendingException)
        assertTrue(runCatching { breaker.checkProbeAllowed() }.isFailure)
        assertEquals(0, server.requestCount)
        val emptyBucket = sharedRestFixture("rest_cat13_favorites_empty_despite_sticky.json")
        server.enqueue(MockResponse().setBody(emptyBucket))
        assertEquals(emptyBucket, api.getCategoryFlagTopics(13, HfrRestFlagBucket.FAVORITES))
        breaker.checkProbeAllowed()
        assertEquals(1, server.requestCount)
        val sent = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals(USER_AGENT, sent.getHeader("User-Agent"))
        assertEquals(SESSION_COOKIES, sent.getHeader("Cookie"))
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

    private fun api(breaker: HfrRestCircuitBreaker, authenticatedClient: OkHttpClient = authenticated) =
        HfrApiClient(authenticatedClient, client, server.url("/"), Dispatchers.IO, breaker = breaker)

    private fun assertPublicCatalogueRejectsLinkText(missing: String) = runBlocking {
        val saved = HfrRestCooldown(clock.instant(), 0, HfrRestContext.AUTHENTICATED)
        val store = MemoryStore(saved)
        val api = api(breaker(store))
        val accountLinks = listOf("read_topics", "participated_topics", "favorite_topics")
        val otherLinks = (accountLinks - missing).joinToString("") { """"$it":{},""" }
        val body = sharedRestFixture("rest_categories.json").replaceFirst(
            """"links": {""",
            """"links": {$otherLinks"label":"\"$missing\": {}", """,
        )
        server.enqueue(MockResponse().setBody(body))

        val error = runCatching { withContext(FlagReadSession()) { api.getCategories() } }.exceptionOrNull()

        assertTrue(error is HfrRestRecoveryPendingException)
        assertEquals(saved, store.saved)
        assertEquals(1, server.requestCount)
    }

    private fun sharedRestFixture(name: String): String {
        val candidates = listOf(
            File("../data/src/test/resources/fixtures", name),
            File("core/data/src/test/resources/fixtures", name),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Shared REST fixture not found: $name (tried $candidates)")
        return file.readText()
    }

    private class MemoryStore(var saved: HfrRestCooldown? = null) : HfrRestCooldownStore {
        override suspend fun read(): HfrRestCooldown? = saved
        override suspend fun write(cooldown: HfrRestCooldown?) { saved = cooldown }
    }

    private companion object {
        const val USER_AGENT = "Redface2/test (Android; dev)"
        const val SESSION_COOKIES = "md_id=1; md_user=tester; md_passs=test-session"
        fun refused() = MockResponse().setResponseCode(429)
    }
}
