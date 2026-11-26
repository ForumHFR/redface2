package fr.forumhfr.redface2.core.data.flags

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.forumhfr.redface2.core.data.forum.DefaultForumRepository
import fr.forumhfr.redface2.core.data.forum.RestCategory
import fr.forumhfr.redface2.core.data.forum.RestListEnvelope
import fr.forumhfr.redface2.core.data.forum.RestTopic
import fr.forumhfr.redface2.core.data.preferences.DataStoreUserPreferencesRepository
import fr.forumhfr.redface2.core.database.dao.FlagCacheSnapshot
import fr.forumhfr.redface2.core.database.dao.FlagDao
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshDiagnostics
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.domain.flags.FlagLookup
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshStatus
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagFailureKind
import fr.forumhfr.redface2.core.model.FlagSource
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.network.FlagReadClock
import fr.forumhfr.redface2.core.network.FlagReadExecutor
import fr.forumhfr.redface2.core.network.FlagReadJitter
import fr.forumhfr.redface2.core.network.FlagReadLimiter
import fr.forumhfr.redface2.core.network.HfrApiClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.Closeable
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * REST requests at OkHttp entry with 20 categories. Peak counts concurrent requests in the interceptor.
 *
 * | Operation | Requests | Peak |
 * | --- | ---: | ---: |
 * | CYAN refresh, cold / warm catalogue | 41 / 40 | 15 / 15 |
 * | CYAN initial observe, then landing refresh, then warm observe | 41 + 40 + 0 | 15 |
 * | CYAN cold refresh admitted before observation | 41 shared | 15 |
 * | First RED / FAVORITE observation after a completed sweep, then landing refresh | 20 + 40 each | 15 |
 * | Super pull, warm catalogue | 120 | 15 |
 * | Apache 403, warm / no catalogue | 20 / 1 | 15 / 1 |
 * | HTTP 429, warm / no catalogue, two consecutive pulls | 20 / 1 each | 15 / 1 |
 * | findFlag, cold catalogue / cached hit | 5 / 0 | 3 / 0 |
 * | resolveFavorite, sticky hit / memo hit | 2 / 0 | 1 / 0 |
 * | Subcategory names, cold / cached subscription | 1 / 0 | 1 / 0 |
 * | CYAN refresh, persisted LEGACY, cold catalogue | 41 | 20 |
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FlagRequestBudgetTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val catalogue = json.decodeFromString<RestListEnvelope<RestCategory>>(fixture("rest_categories.json"))
    private val categories = catalogue.resource.resources + RestCategory(id = IA, name = "IA")
    private val categoryIds = categories.map { it.id }.toSet()

    @Test
    fun `CYAN refresh pays for the catalogue once and sweeps each category on both pulls`() = runTest {
        repositories {
            val cold = request { flags.refresh(FlagType.CYAN) }
            assertComplete(cold, FlagType.CYAN)
            assertSweep("participated", catalogueCalls = 1, peak = 15)

            val warm = request { flags.refresh(FlagType.CYAN) }
            assertEquals(cold, warm)
            assertSweep("participated", catalogueCalls = 0, peak = 15)
        }
    }

    @Test
    fun `initial observe that settles before landing pays for a second sweep on refresh`() = runTest {
        repositories {
            val cold = request { flags.observe(FlagType.CYAN).first { it != FlagsResult.Loading } }
            assertComplete(cold, FlagType.CYAN)
            assertSweep("participated", catalogueCalls = 1, peak = 15)

            val refreshed = request { flags.refresh(FlagType.CYAN) }
            assertComplete(refreshed, FlagType.CYAN)
            assertSweep("participated", catalogueCalls = 0, peak = 15)

            assertEquals(refreshed, request { flags.observe(FlagType.CYAN).first() })
            assertBudget(RequestCounts(), peak = 0)
        }
    }

    @Test
    fun `cold observation joins a refresh that opened its sweep first`() = runTest {
        repositories {
            request {
                coroutineScope {
                    val refresh = async(start = CoroutineStart.UNDISPATCHED) { flags.refresh(FlagType.CYAN) }
                    val observed = flags.observe(FlagType.CYAN).first { it != FlagsResult.Loading }
                    val refreshed = refresh.await()
                    assertComplete(refreshed, FlagType.CYAN)
                    assertEquals(refreshed, observed)
                }
            }
            assertSweep("participated", catalogueCalls = 1, peak = 15)
        }
    }

    @Test
    fun `first RED and FAVORITE observations reuse the preceding tab's sticky sweep`() = runTest {
        repositories {
            primeCyan()

            for ((type, bucket) in listOf(FlagType.RED to "read", FlagType.FAVORITE to "favorites")) {
                val observed = request { flags.observe(type).first { it != FlagsResult.Loading } }
                assertComplete(observed, type)
                assertBucketsOnly(bucket)

                assertComplete(request { flags.refresh(type) }, type)
                assertSweep(bucket, catalogueCalls = 0, peak = 15)
            }
        }
    }

    @Test
    fun `Super pull refreshes CYAN then RED then FAVORITE and repeats the sticky sweep`() = runTest {
        repositories {
            warmCatalogue()

            // FlagsViewModel.fetchRefreshBatch follows SUPER_FAVORITE_BACKING_TYPES in this order.
            val batch = listOf(
                FlagType.CYAN to "participated",
                FlagType.RED to "read",
                FlagType.FAVORITE to "favorites",
            )
            for ((type, bucket) in batch) {
                assertComplete(request { flags.refresh(type) }, type)
                assertSweep(bucket, catalogueCalls = 0, peak = 15)
            }
        }
    }

    @Test
    fun `Apache 403 with a warm catalogue still sends all buckets and retains pinned rows`() = runTest {
        repositories {
            val cached = primeCyan()
            http.failureCode = 403
            assertBlockedWarm(cached)
        }
    }

    @Test
    fun `Apache 403 with no catalogue sends one call per pull and fails`() = runTest {
        repositories {
            http.failureCode = 403
            repeat(2) {
                val cause = assertBlockedCold(code = 403)
                assertTrue(cause.message.orEmpty().contains("You don't have permission to access"))
            }
        }
    }

    @Test
    fun `429 Retry-After does not delay or suppress the next pull`() = runTest {
        repositories {
            val cached = primeCyan()
            http.failureCode = 429
            val startedAt = currentTime
            repeat(2) { assertBlockedWarm(cached) }
            assertEquals(startedAt, currentTime)
        }
    }

    @Test
    fun `429 Retry-After with no catalogue still sends one call on each pull`() = runTest {
        repositories {
            http.failureCode = 429
            val startedAt = currentTime
            repeat(2) { assertBlockedCold(code = 429) }
            assertEquals(startedAt, currentTime)
        }
    }

    @Test
    fun `findFlag reads three buckets and a shared sticky page for only the requested category`() = runTest {
        repositories {
            val lookup = request { flags.findFlag(DISCUSSIONS, FAVORITE_PIN) } as FlagLookup.Found
            assertPinned(lookup.flag, FlagType.FAVORITE)
            val calls = assertBudget(RequestCounts(catalogue = 1, bucket = 3, sticky = 1), peak = 3)
            assertEquals(
                setOf(
                    RecordedRequest(Kind.BUCKET, DISCUSSIONS, "participated", 1),
                    RecordedRequest(Kind.BUCKET, DISCUSSIONS, "read", 1),
                    RecordedRequest(Kind.BUCKET, DISCUSSIONS, "favorites", 1),
                    RecordedRequest(Kind.STICKY, DISCUSSIONS, page = 1),
                ),
                calls.filter { it.kind != Kind.CATALOGUE }.toSet(),
            )

            assertEquals(lookup, request { flags.findFlag(DISCUSSIONS, FAVORITE_PIN) })
            assertBudget(RequestCounts(), peak = 0)
        }
    }

    @Test
    fun `resolveFavorite checks the sticky page when the bucket is empty then memoizes the hit`() = runTest {
        repositories {
            assertTrue(request { flags.resolveFavorite(DISCUSSIONS, FAVORITE_PIN) }.getOrThrow())
            val calls = assertBudget(RequestCounts(bucket = 1, sticky = 1), peak = 1)
            assertEquals(
                listOf(
                    RecordedRequest(Kind.BUCKET, DISCUSSIONS, "favorites", 1),
                    RecordedRequest(Kind.STICKY, DISCUSSIONS, page = 1),
                ),
                calls,
            )

            assertTrue(request { flags.resolveFavorite(DISCUSSIONS, FAVORITE_PIN) }.getOrThrow())
            assertBudget(RequestCounts(), peak = 0)
        }
    }

    @Test
    fun `subcategory names fetch once and then replay from the forum cache`() = runTest {
        repositories {
            assertNull(request { forum.observeCachedSubcategories(DISCUSSIONS).first() })
            assertBudget(RequestCounts(), peak = 0)
            request { forum.refreshSubcategories(DISCUSSIONS) }
            val calls = assertBudget(RequestCounts(subcategories = 1), peak = 1)
            assertEquals(listOf(RecordedRequest(Kind.SUBCATEGORIES, DISCUSSIONS)), calls)

            val names = request { forum.observeCachedSubcategories(DISCUSSIONS).first() } as ForumResult.Success
            assertEquals(15, names.value.size)
            assertEquals("Actualité", names.value.single { it.id == 422 }.name)
            assertBudget(RequestCounts(), peak = 0)
        }
    }

    @Test
    fun `persisted LEGACY bypasses the limiter even with a stored concurrency of five`() = runTest {
        repositories(persistLegacy = true) {
            assertComplete(request { flags.refresh(FlagType.CYAN) }, FlagType.CYAN)
            assertSweep("participated", catalogueCalls = 1, peak = 20)
        }
    }

    private fun assertComplete(result: FlagsResult, type: FlagType) {
        val success = result as FlagsResult.Success
        assertEquals(FlagRefreshStatus.COMPLETE, success.refreshStatus)
        assertTrue(success.coverage.isComplete)
        assertCoverage(success)
        val pinned = success.flags.single { it.cat == pinCategory(type) && it.topicId == pinId(type) }
        assertPinned(pinned, type)
        if (type == FlagType.CYAN) {
            val bucketRow = success.flags.single { it.cat == 23 && it.topicId == 35395 }
            assertTrue(bucketRow.hasUnread)
            assertEquals(12, bucketRow.lastReadPage)
            assertEquals(2783256L, bucketRow.lastPostReadId)
        }
    }

    private fun assertCoverage(result: FlagsResult.Success, failure: FlagFailureKind? = null) {
        val expected = categoryIds.flatMap { cat ->
            listOf(Triple(cat, FlagSource.BUCKET, failure), Triple(cat, FlagSource.STICKY, failure))
        }.toSet()
        val coverage = result.coverage.categories
        assertEquals(expected.size, coverage.size)
        assertEquals(expected, coverage.map { Triple(it.cat, it.source, it.failure) }.toSet())
    }

    private fun pinId(type: FlagType): Int = when (type) {
        FlagType.CYAN -> CYAN_PIN
        FlagType.RED -> RED_PIN
        FlagType.FAVORITE -> FAVORITE_PIN
    }

    private fun assertPinned(flag: Flag, type: FlagType) {
        assertEquals(pinId(type), flag.topicId)
        assertEquals(type, flag.type)
        assertEquals(pinCategory(type), flag.cat)
        assertEquals(type == FlagType.FAVORITE, flag.isFavorite)
        assertEquals(type != FlagType.FAVORITE, flag.hasUnread)
        assertEquals(if (type == FlagType.CYAN) 5 else 1, flag.lastReadPage)
        assertEquals(
            when (type) {
                FlagType.CYAN -> 40563L
                FlagType.RED -> 0L
                FlagType.FAVORITE -> 28173029L
            },
            flag.lastPostReadId,
        )
    }

    private fun pinCategory(type: FlagType): Int = if (type == FlagType.CYAN) IA else DISCUSSIONS

    private suspend fun TestScope.repositories(
        persistLegacy: Boolean = false,
        block: suspend Repositories.() -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settingsFile = temporaryFolder.root.resolve("user.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { settingsFile }
        if (persistLegacy) {
            store.edit {
                it[stringPreferencesKey("flags_refresh_strategy")] = "legacy"
                it[intPreferencesKey("flags_refresh_concurrency")] = 5
            }
        }
        // Prime only DataStore, so the wave driver below only waits on REST calls.
        store.data.first()
        val preferences = DataStoreUserPreferencesRepository(
            dataStore = store,
            themeBootstrapStore = mockk(),
            startScreenBootstrapStore = mockk(),
            navBarLabelsBootstrapStore = mockk(),
            ioDispatcher = dispatcher,
            externalScope = backgroundScope,
        )
        val clock = Clock.fixed(Instant.parse("2026-11-26T12:00:00Z"), ZoneOffset.UTC)
        val monotonicClock = mockk<FlagReadClock>()
        every { monotonicClock.nowNanos() } answers { testScheduler.currentTime * 1_000_000L }
        val limiter = FlagReadLimiter()
        val diagnostics = DiagnosticsLog()
        val auth = mockk<AuthRepository> {
            every { observeAuthState() } returns flowOf(AuthState.Authenticated("XaT"))
        }
        CountingHttp(responseBodies()).use { http ->
            val client = OkHttpClient.Builder()
                .dns(Dns { error("Unexpected DNS lookup for $it") })
                .addInterceptor(http)
                .build()
            val api = HfrApiClient(
                authenticated = client,
                anonymous = client,
                baseUrl = "https://hfr.test/".toHttpUrl(),
                ioDispatcher = http,
                flagReadExecutor = FlagReadExecutor(limiter, FlagReadJitter()),
            )
            val forum = DefaultForumRepository(api, json, dispatcher, clock)
            val flags = DefaultFlagRepository(
                apiClient = api,
                hfrClient = mockk(),
                flagAddResponseParser = mockk(),
                flagDeleteResponseParser = mockk(),
                forumRepository = forum,
                authRepository = auth,
                flagCacheStore = FlagCacheStore(snapshotDao(), clock, dispatcher),
                json = json,
                ioDispatcher = dispatcher,
                clock = clock,
                diagnostics = diagnostics,
                refreshDiagnostics = FlagRefreshDiagnostics(diagnostics),
                flagReadClock = monotonicClock,
                preferences = preferences,
                readLimiter = limiter,
            )
            Repositories(this, flags, forum, http).block()
        }
    }

    private fun snapshotDao(): FlagDao {
        val snapshots = mutableMapOf<FlagType, FlagCacheSnapshot>()
        return mockk {
            coEvery { getSnapshot("xat", any()) } coAnswers {
                snapshots[secondArg<FlagType>()] ?: FlagCacheSnapshot(emptyList(), emptyList())
            }
            coEvery { replaceSnapshot("xat", any(), any()) } coAnswers {
                snapshots[secondArg<FlagType>()] = thirdArg<FlagCacheSnapshot>()
            }
        }
    }

    private inner class Repositories(
        private val scope: TestScope,
        val flags: DefaultFlagRepository,
        val forum: DefaultForumRepository,
        val http: CountingHttp,
    ) {
        suspend fun <T> request(operation: suspend () -> T): T {
            val result = scope.async { operation() }
            scope.runCurrent()
            while (!result.isCompleted) {
                check(http.executeAdmittedCalls()) { "Repository suspended with no admitted HTTP calls" }
                scope.runCurrent()
            }
            return result.await()
        }

        suspend fun warmCatalogue() {
            val result = request { forum.getCategories() } as ForumResult.Success
            assertEquals(20, result.value.size)
            assertBudget(RequestCounts(catalogue = 1), peak = 1)
        }

        suspend fun primeCyan(): FlagsResult.Success {
            val cached = request { flags.refresh(FlagType.CYAN) } as FlagsResult.Success
            assertComplete(cached, FlagType.CYAN)
            assertSweep("participated", catalogueCalls = 1, peak = 15)
            return cached
        }

        suspend fun assertBlockedWarm(cached: FlagsResult.Success) {
            val result = request { flags.refresh(FlagType.CYAN) } as FlagsResult.Success
            assertEquals(FlagRefreshStatus.FAILED, result.refreshStatus)
            assertEquals(cached.flags, result.flags)
            assertEquals(cached.cachedAt, result.cachedAt)
            assertCoverage(result, failure = FlagFailureKind.SERVER)
            assertBucketsOnly("participated")
        }

        suspend fun assertBlockedCold(code: Int): HfrServerException {
            val result = request { flags.refresh(FlagType.CYAN) } as FlagsResult.Failure
            val cause = result.cause as HfrServerException
            assertEquals(code, cause.code)
            assertBudget(RequestCounts(catalogue = 1), peak = 1)
            return cause
        }

        fun assertSweep(bucket: String, catalogueCalls: Int, peak: Int) {
            val calls = assertBudget(RequestCounts(catalogue = catalogueCalls, bucket = 20, sticky = 20), peak)
            assertCategoryPages(calls, Kind.BUCKET, bucket)
            assertCategoryPages(calls, Kind.STICKY)
        }

        fun assertBucketsOnly(bucket: String) {
            val calls = assertBudget(RequestCounts(bucket = 20), peak = 15)
            assertCategoryPages(calls, Kind.BUCKET, bucket)
        }

        private fun assertCategoryPages(calls: List<RecordedRequest>, kind: Kind, bucket: String? = null) {
            val expected = categoryIds.map { cat -> RecordedRequest(kind, cat, bucket, page = 1) }.toSet()
            assertEquals(expected, calls.filter { it.kind == kind }.toSet())
        }

        fun assertBudget(expected: RequestCounts, peak: Int): List<RecordedRequest> {
            val calls = http.takeRequests()
            val counts = calls.groupingBy { it.kind }.eachCount()
            assertEquals(expected, RequestCounts(
                catalogue = counts[Kind.CATALOGUE] ?: 0,
                bucket = counts[Kind.BUCKET] ?: 0,
                sticky = counts[Kind.STICKY] ?: 0,
                global = counts[Kind.GLOBAL] ?: 0,
                subcategories = counts[Kind.SUBCATEGORIES] ?: 0,
                other = counts[Kind.OTHER] ?: 0,
            ))
            assertEquals("peak in-flight requests", peak, http.takePeak())
            return calls
        }
    }

    /**
     * The test scheduler runs admission first. Every Call that reached the client's IO dispatcher
     * then enters OkHttp on a worker and waits for the other admitted Calls. The barrier size
     * comes from the scheduler's queue, so a different limiter policy changes the measured peak.
     */
    private class CountingHttp(private val bodies: Map<RecordedRequest, String>) :
        CoroutineDispatcher(), Interceptor, Closeable {
        private val pending = ConcurrentLinkedQueue<Runnable>()
        private val requests = ConcurrentLinkedQueue<RecordedRequest>()
        private val workers = Executors.newCachedThreadPool()
        private val inFlight = AtomicInteger()
        private val peak = AtomicInteger()
        private lateinit var wave: CountDownLatch
        var failureCode: Int? = null

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            pending.add(block)
        }

        fun executeAdmittedCalls(): Boolean {
            val admitted = generateSequence(pending::poll).toList()
            if (admitted.isEmpty()) return false
            wave = CountDownLatch(admitted.size)
            val work = admitted.map { workers.submit(it) }
            work.forEach { it.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            return true
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val entry = request.record()
            requests.add(entry)
            peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            try {
                wave.countDown()
                check(wave.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "HTTP wave did not reach OkHttp" }
                val code = failureCode ?: if (entry.kind == Kind.OTHER) 404 else 200
                val contentType = if (code == 403) "text/html; charset=iso-8859-1" else "application/json"
                val body = when (code) {
                    403 -> APACHE_FORBIDDEN
                    200 -> bodies[entry] ?: EMPTY_LIST
                    else -> "{}"
                }
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("Test HTTP $code")
                    .header("Content-Type", contentType)
                    .apply { if (code == 429) header("Retry-After", "120") }
                    .body(body.toResponseBody(contentType.toMediaType()))
                    .build()
            } finally {
                inFlight.decrementAndGet()
            }
        }

        fun takeRequests(): List<RecordedRequest> = generateSequence(requests::poll).toList()

        fun takePeak(): Int = peak.getAndSet(0)

        override fun close() {
            workers.shutdownNow()
        }
    }

    private enum class Kind { CATALOGUE, BUCKET, STICKY, GLOBAL, SUBCATEGORIES, OTHER }

    private data class RecordedRequest(
        val kind: Kind,
        val cat: Int? = null,
        val bucket: String? = null,
        val page: Int? = null,
        val subcat: Int? = null,
    )

    private data class RequestCounts(
        val catalogue: Int = 0,
        val bucket: Int = 0,
        val sticky: Int = 0,
        val global: Int = 0,
        val subcategories: Int = 0,
        val other: Int = 0,
    )

    private fun responseBodies(): Map<RecordedRequest, String> {
        val discussionTopics = json.decodeFromString<RestListEnvelope<RestTopic>>(
            fixture("rest_cat13_topics_last_sticky_favorite.json"),
        )
        // The capture has an unflagged sticky alongside the captured favourite. Give it a RED flag
        // in this scenario so each type has a pinned row that can only come from topics/last.
        val discussions = discussionTopics.resource.resources.map { topic ->
            if (topic.id == RED_PIN) topic.copy(flagOwntopic = 2) else topic
        }
        return mapOf(
            RecordedRequest(Kind.CATALOGUE) to json.encodeToString(catalogue.copy(
                resource = catalogue.resource.copy(
                    resources = categories,
                    resultsCount = categories.size,
                    resultsPerPage = categories.size,
                ),
            )),
            RecordedRequest(Kind.BUCKET, 23, "participated", 1) to fixture("rest_cat23_participated.json"),
            RecordedRequest(Kind.BUCKET, DISCUSSIONS, "favorites", 1) to
                fixture("rest_cat13_favorites_empty_despite_sticky.json"),
            RecordedRequest(Kind.STICKY, DISCUSSIONS, page = 1) to json.encodeToString(
                discussionTopics.copy(resource = discussionTopics.resource.copy(resources = discussions)),
            ),
            RecordedRequest(Kind.STICKY, IA, page = 1) to fixture("rest_cat32_topics_last_sticky.json"),
            RecordedRequest(Kind.SUBCATEGORIES, DISCUSSIONS) to fixture("rest_subcategories_cat13.json"),
            RecordedRequest(Kind.GLOBAL, bucket = "participated") to fixture("rest_global_participated.json"),
            RecordedRequest(Kind.GLOBAL, bucket = "read") to fixture("rest_global_read.json"),
            RecordedRequest(Kind.GLOBAL, bucket = "favorites") to fixture("rest_global_favorites.json"),
        )
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "Missing fixture $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private companion object {
        const val DISCUSSIONS = 13
        const val IA = 32
        const val CYAN_PIN = 1
        const val RED_PIN = 73768
        const val FAVORITE_PIN = 100217
        const val WORKER_TIMEOUT_SECONDS = 5L
        const val EMPTY_LIST = """{"resource":{"resources":[],"results_count":0}}"""
        const val APACHE_FORBIDDEN = """
            <html><head><title>403 Forbidden</title></head><body><h1>Forbidden</h1>
            <p>You don't have permission to access /webservices/rest_api.php on this server.</p></body></html>
        """

        val CATEGORY_TOPICS = Regex("forums/hardwarefr/categories/(\\d+)/(?:subcategories/(\\d+)/)?topics/(\\w+)/")
        val GLOBAL_TOPICS = Regex("forums/hardwarefr/topics/(participated|read|favorites)/")
        val SUBCATEGORY_NAMES = Regex("forums/hardwarefr/categories/(\\d+)/subcategories/")
        val BUCKETS = setOf("participated", "read", "favorites")

        fun Request.record(): RecordedRequest {
            val uri = url.queryParameter("uri").orEmpty()
            val subcategoryNames = SUBCATEGORY_NAMES.matchEntire(uri)
            val globalTopics = GLOBAL_TOPICS.matchEntire(uri)
            val categoryTopics = CATEGORY_TOPICS.matchEntire(uri)
            return when {
                url.encodedPath != "/webservices/rest_api.php" -> RecordedRequest(Kind.OTHER)
                uri == "forums/hardwarefr/categories/" -> RecordedRequest(Kind.CATALOGUE)
                subcategoryNames != null ->
                    RecordedRequest(Kind.SUBCATEGORIES, cat = subcategoryNames.groupValues[1].toInt())
                globalTopics != null -> RecordedRequest(Kind.GLOBAL, bucket = globalTopics.groupValues[1])
                categoryTopics != null -> categoryTopics.toCategoryRequest(url.queryParameter("page")?.toIntOrNull())
                else -> RecordedRequest(Kind.OTHER)
            }
        }

        fun MatchResult.toCategoryRequest(page: Int?): RecordedRequest {
            val (cat, subcat, endpoint) = destructured
            return when (endpoint) {
                "last" -> RecordedRequest(Kind.STICKY, cat.toInt(), page = page, subcat = subcat.toIntOrNull())
                in BUCKETS -> RecordedRequest(Kind.BUCKET, cat.toInt(), endpoint, page, subcat.toIntOrNull())
                else -> RecordedRequest(Kind.OTHER)
            }
        }
    }
}
