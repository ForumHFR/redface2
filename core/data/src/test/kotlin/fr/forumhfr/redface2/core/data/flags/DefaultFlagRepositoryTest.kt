package fr.forumhfr.redface2.core.data.flags

import fr.forumhfr.redface2.core.domain.flags.FlagsRefreshStrategy
import fr.forumhfr.redface2.core.domain.flags.FlagsRefreshPolicy
import app.cash.turbine.test
import fr.forumhfr.redface2.core.database.dao.FlagCacheSnapshot
import fr.forumhfr.redface2.core.database.dao.FlagDao
import fr.forumhfr.redface2.core.database.entities.FlagCategoryCoverageEntity
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshStatus
import fr.forumhfr.redface2.core.model.FlagFailureKind
import fr.forumhfr.redface2.core.model.FlagSource
import io.mockk.MockKMatcherScope
import fr.forumhfr.redface2.core.database.entities.FetchMode
import fr.forumhfr.redface2.core.database.entities.FlagTopicEntity
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshDiagnostics
import fr.forumhfr.redface2.core.domain.diagnostics.FlagFetchTrace
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshPhase
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCallOutcome
import fr.forumhfr.redface2.core.domain.diagnostics.HfrCallSummary
import fr.forumhfr.redface2.core.domain.diagnostics.HfrClientRole
import fr.forumhfr.redface2.core.domain.diagnostics.HfrEndpoint
import fr.forumhfr.redface2.core.domain.flags.FlagLookup
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshCategory
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.write.FlagAddContext
import fr.forumhfr.redface2.core.network.FlagReadClock
import fr.forumhfr.redface2.core.network.FlagReadSession
import fr.forumhfr.redface2.core.network.HfrApiClient
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.network.HfrRestFlagBucket
import fr.forumhfr.redface2.core.parser.write.FlagAddResponseParser
import fr.forumhfr.redface2.core.parser.write.FlagDeleteResponseParser
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1D-1 contract test for [DefaultFlagRepository]. The repository fans out one
 * REST call per HFR public category (per-cat path, contract proven by
 * `rest_cat23_participated.json`) and concatenates the results. Tests use the captured
 * fixture for the cat that has a flagged topic and an empty payload for the others.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
// One class per repository keeps every REST read + #99 delflag code path co-located with its
// fixtures and helpers ; splitting would scatter the shared MockK wiring across files.
@Suppress("LargeClass")
class DefaultFlagRepositoryTest {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val sampleCategories: List<Category> = listOf(
        Category(id = 13, name = "Discussions", forceSubcat = true, subcategoryCount = 15),
        Category(id = 23, name = "Technologies Mobiles", forceSubcat = true, subcategoryCount = 10),
    )

    private val capturedParticipatedFixture: String = fixture("rest_cat23_participated.json")

    @Test
    fun `cold targeted lookup leaves other catalogue categories unverified in memory and Room - P2c fix`() = runTest {
        val (api, forum) = wireDeps {
            HfrRestFlagBucket.entries.forEach { stubFlagsCall(23, it, EMPTY_PAGE) }
        }
        val dao = stubFlagDao()
        val repo = buildRepository(api, forum, flagDao = dao)
        assertEquals(FlagLookup.Absent, repo.findFlag(23, 999))
        for (type in FlagType.entries) {
            val memory = repo.observe(type).first() as FlagsResult.Success
            val disk = requireNotNull(FlagCacheStore(dao, Clock.systemUTC(), UnconfinedTestDispatcher(testScheduler))
                .load(type, "xat"))
            assertEquals(FlagRefreshStatus.PARTIAL, memory.refreshStatus)
            assertTrue(memory.coverage.covers(23))
            assertFalse(memory.coverage.covers(13))
            assertTrue(memory.coverage.categories.any { it.cat == 13 && it.lastSuccessAt == null })
            assertEquals(memory.coverage, disk.result.coverage)
            assertFalse(disk.isFresh)
        }
        coVerify(exactly = 0) { api.getCategoryFlagTopics(13, any(), page = any(),
            resultsPerPage = any(), useAuth = true) }
    }

    @Test
    fun `successful global empty is a no-op on cold and warm caches without any Room write - P2c fix`() = runTest {
        val (api, forum) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        coEvery { api.getGlobalFlagTopics(any()) } returns EMPTY_PAGE
        val policy = MutableStateFlow(FlagsRefreshPolicy(strategy = FlagsRefreshStrategy.GLOBAL_ONLY))
        val dao = stubFlagDao()
        val repo = buildRepository(api, forum, flagDao = dao, policy = policy)
        assertTrue(repo.refresh(FlagType.CYAN) is FlagsResult.Failure)
        repo.observe(FlagType.CYAN).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            assertTrue(awaitItem() is FlagsResult.Failure)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { dao.replaceSnapshot(any(), any(), any()) }
        policy.value = FlagsRefreshPolicy()
        val baseline = repo.refresh(FlagType.CYAN) as FlagsResult.Success
        policy.value = policy.value.copy(strategy = FlagsRefreshStrategy.GLOBAL_ONLY)
        assertEquals(baseline, repo.refresh(FlagType.CYAN))
        coVerify(exactly = 1) { dao.replaceSnapshot(any(), any(), any()) }
    }

    @Test
    fun `cold empty global before category failure stays visible on reobservation - P2c fix2`() = runTest {
        val (api, forum) = wireDeps {}
        coEvery { api.getGlobalFlagTopics(any()) } returns EMPTY_PAGE
        coEvery { api.getCategoryFlagTopics(any(), any(), page = any(),
            resultsPerPage = any(), useAuth = true) } throws IOException("buckets offline")
        val dao = stubFlagDao()
        val policy = MutableStateFlow(FlagsRefreshPolicy(strategy = FlagsRefreshStrategy.GLOBAL_FIRST))
        val repo = buildRepository(api, forum, flagDao = dao, policy = policy,
            testDispatcher = UnconfinedTestDispatcher(testScheduler))
        assertTrue(repo.refresh(FlagType.CYAN) is FlagsResult.Failure)
        repeat(2) {
            repo.observe(FlagType.CYAN).test {
                assertEquals(FlagsResult.Loading, awaitItem())
                assertTrue(awaitItem() is FlagsResult.Failure)
                runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }
        coVerify(exactly = 0) { dao.replaceSnapshot(any(), any(), any()) }
        assertNull(FlagCacheStore(dao, Clock.systemUTC(), UnconfinedTestDispatcher(testScheduler))
            .load(FlagType.CYAN, "xat"))
    }

    @Test
    fun `targeted lookup keeps global marker until an inventory refresh - P2c fix2`() = runTest {
        val observed = decodeGlobalFlags(json, fixture("rest_global_participated.json"), FlagType.CYAN)
        val categories = sampleCategories + observed.map { Category(it.cat, "Observed category", false, 0) }
        val (api, forum) = wireDeps {
            categories.forEach { cat ->
                HfrRestFlagBucket.entries.forEach { stubFlagsCall(cat.id, it, EMPTY_PAGE) }
            }
        }
        coEvery { forum.getCategories(any()) } returns ForumResult.Success(categories)
        coEvery { api.getGlobalFlagTopics(any()) } returns fixture("rest_global_participated.json")
        val dao = stubFlagDao()
        val at = Instant.parse("2026-05-03T11:00:00Z")
        val coverage = categories.flatMap { cat ->
            listOf(FlagSource.BUCKET, FlagSource.STICKY).map { source ->
                FlagCategoryCoverageEntity("xat", FlagType.CYAN, cat.id, source, at, at, null)
            }
        }
        dao.replaceSnapshot("xat", FlagType.CYAN, FlagCacheSnapshot(emptyList(), coverage))
        val policy = MutableStateFlow(FlagsRefreshPolicy(strategy = FlagsRefreshStrategy.GLOBAL_ONLY))
        val repo = buildRepository(api, forum, flagDao = dao, policy = policy)
        repo.refresh(FlagType.CYAN)
        assertEquals(FlagLookup.Absent, repo.findFlag(23, 999))
        val limited = repo.observe(FlagType.CYAN).first() as FlagsResult.Success
        assertTrue(limited.coverage.isGlobalObservation)
        assertEquals(FlagRefreshStatus.PARTIAL, limited.refreshStatus)
        assertEquals(observed.toSet(), limited.flags.toSet())
        val disk = requireNotNull(FlagCacheStore(dao, Clock.systemUTC(),
            UnconfinedTestDispatcher(testScheduler)).load(FlagType.CYAN, "xat"))
        assertTrue(disk.result.coverage.isGlobalObservation)
        assertFalse(disk.isFresh)
        policy.value = FlagsRefreshPolicy()
        val complete = repo.refresh(FlagType.CYAN) as FlagsResult.Success
        assertFalse(complete.coverage.isGlobalObservation)
        assertEquals(FlagRefreshStatus.COMPLETE, complete.refreshStatus)
        assertTrue(complete.flags.isEmpty())
    }

    @Test
    fun `baseline catalogue failure clears global provenance - P2c fix`() = runTest {
        val (api, forum) = wireDeps {}
        coEvery { api.getGlobalFlagTopics(any()) } returns fixture("rest_global_participated.json")
        val policy = MutableStateFlow(FlagsRefreshPolicy(strategy = FlagsRefreshStrategy.GLOBAL_ONLY))
        val dao = stubFlagDao()
        val repo = buildRepository(api, forum, flagDao = dao, policy = policy)
        val global = repo.refresh(FlagType.CYAN) as FlagsResult.Success
        assertTrue(global.coverage.isGlobalObservation)
        policy.value = FlagsRefreshPolicy()
        coEvery { forum.getCategories(any()) } returns ForumResult.Failure(IOException("catalogue offline"))
        val failed = repo.refresh(FlagType.CYAN) as FlagsResult.Success
        assertEquals(global.flags, failed.flags)
        assertFalse(failed.coverage.isGlobalObservation)
        assertEquals(FlagRefreshStatus.FAILED, failed.refreshStatus)
        assertTrue(failed.coverage.categories.all { it.failure == FlagFailureKind.NETWORK })
        assertEquals(failed.coverage, requireNotNull(FlagCacheStore(dao, Clock.systemUTC(),
            UnconfinedTestDispatcher(testScheduler)).load(FlagType.CYAN, "xat")).result.coverage)
    }

    @Test
    fun `lookup cannot certify absence after a mutation invalidates an already resolved type - P2c`() = runTest {
        val (api, forum) = wireDeps {
            HfrRestFlagBucket.entries.forEach { stubFlagsCall(23, it, EMPTY_PAGE) }
        }
        val release = CompletableDeferred<Unit>()
        coEvery { api.getCategoryFlagTopics(23, HfrRestFlagBucket.READ, page = any(),
            resultsPerPage = any(), useAuth = true) } coAnswers {
            release.await()
            EMPTY_PAGE
        }
        val hfr = mockk<HfrClient>()
        coEvery { hfr.addFlag(any()) } returns ADD_SUCCESS_HTML
        val repo = buildRepository(api, forum, hfrClient = hfr,
            testDispatcher = UnconfinedTestDispatcher(testScheduler))
        val lookup = async { repo.findFlag(23, 999) }
        runCurrent()
        coVerify(exactly = 1) { api.getCategoryFlagTopics(23, HfrRestFlagBucket.FAVORITES,
            page = any(), resultsPerPage = any(), useAuth = true) }
        assertTrue(repo.addFlag(sampleFlagAddContext()).isSuccess)
        release.complete(Unit)
        assertEquals(FlagLookup.Unavailable, lookup.await())
    }

    @Test
    fun `every diagnostic line carries policy for pulls initial observations and joins - P2c`() = runTest {
        val (api, forum) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
        }
        val finish = CompletableDeferred<Unit>()
        coEvery { api.getGlobalFlagTopics(any()) } coAnswers {
            finish.await()
            fixture("rest_global_participated.json")
        }
        val log = DiagnosticsLog()
        val policy = MutableStateFlow(FlagsRefreshPolicy(
            strategy = FlagsRefreshStrategy.GLOBAL_ONLY, concurrency = 2))
        val repo = buildRepository(api, forum, diagnostics = log, policy = policy,
            testDispatcher = UnconfinedTestDispatcher(testScheduler))
        val first = async { repo.observe(FlagType.CYAN).first { it is FlagsResult.Success } }
        val joined = async { repo.observe(FlagType.CYAN).first { it is FlagsResult.Success } }
        runCurrent()
        finish.complete(Unit)
        first.await()
        joined.await()
        repo.refresh(FlagType.CYAN)
        val lines = log.entries.value.filter { it.tag == FlagRefreshDiagnostics.TAG }.map { it.message }
        assertTrue(lines.any { "initial-observe" in it })
        assertTrue(lines.any { "joined=1" in it })
        assertTrue(lines.all { "strategy=global-only" in it && "N=2" in it })
    }

    @Test
    fun `global malformed preserves old positions with a durable visible failure - P2c`() = runTest {
        val (api, forum) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        val policy = MutableStateFlow(FlagsRefreshPolicy())
        val dao = stubFlagDao()
        val repo = buildRepository(api, forum, flagDao = dao, policy = policy)
        val baseline = repo.refresh(FlagType.CYAN) as FlagsResult.Success
        policy.value = policy.value.copy(strategy = FlagsRefreshStrategy.GLOBAL_ONLY)
        for (body in listOf("{\"resource\":{}}")) {
            coEvery { api.getGlobalFlagTopics(HfrRestFlagBucket.PARTICIPATED) } returns body
            val result = repo.refresh(FlagType.CYAN) as FlagsResult.Success
            assertEquals(baseline.flags, result.flags)
            assertTrue(result.coverage.isGlobalObservation)
            assertFalse(result.coverage.isComplete)
            assertEquals(FlagRefreshStatus.FAILED, result.refreshStatus)
            val disk = FlagCacheStore(dao, Clock.systemUTC(), UnconfinedTestDispatcher(testScheduler))
                .load(FlagType.CYAN, "xat")!!
            assertTrue(disk.result.coverage.isGlobalObservation)
            assertFalse(disk.isFresh)
            assertEquals(baseline.flags, disk.result.flags)
        }
        coVerify(exactly = 1) { api.getCategoryFlagTopics(23, HfrRestFlagBucket.PARTICIPATED,
            page = any(), resultsPerPage = any(), useAuth = true) }
    }

    @Test
    fun `global failure still runs categories and global success spends the same deadline - P2c`() = runTest {
        for (fail in listOf(true, false)) {
            val (api, forum) = wireDeps {
                stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
                stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
            }
            val monotonic = mockk<FlagReadClock>()
            every { monotonic.nowNanos() } answers { testScheduler.currentTime * 1_000_000 }
            val start = testScheduler.currentTime
            coEvery { api.getGlobalFlagTopics(any()) } coAnswers {
                kotlinx.coroutines.delay(10_000)
                if (fail) throw IOException("global unavailable") else fixture("rest_global_participated.json")
            }
            coEvery { api.getTopicList(any(), null, 1, any(), true) } coAnswers { awaitCancellation() }
            val policy = MutableStateFlow(FlagsRefreshPolicy(
                strategy = FlagsRefreshStrategy.GLOBAL_FIRST))
            val repo = buildRepository(api, forum, policy = policy, flagReadClock = monotonic,
                testDispatcher = UnconfinedTestDispatcher(testScheduler))
            val result = repo.refresh(FlagType.CYAN) as FlagsResult.Success
            assertEquals(12_000L, testScheduler.currentTime - start)
            assertTrue(result.flags.any { it.topicId == 35395 })
            coVerify(exactly = 1) { api.getCategoryFlagTopics(23, HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true) }
        }
    }

    @Test
    fun `policy change cancels drains and replaces an in-flight global result - P2c`() = runTest {
        val (api, forum) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        val close = CompletableDeferred<Unit>()
        var cancelled = false
        coEvery { api.getGlobalFlagTopics(any()) } coAnswers {
            try { awaitCancellation() } finally {
                cancelled = true
                withContext(NonCancellable) { close.await() }
            }
        }
        val policy = MutableStateFlow(FlagsRefreshPolicy(
            strategy = FlagsRefreshStrategy.GLOBAL_ONLY))
        val repo = buildRepository(api, forum, policy = policy,
            testDispatcher = UnconfinedTestDispatcher(testScheduler))
        val refreshing = async { repo.refresh(FlagType.CYAN) }
        runCurrent()
        policy.value = FlagsRefreshPolicy(concurrency = 1)
        runCurrent()
        assertTrue(cancelled)
        coVerify(exactly = 0) { api.getCategoryFlagTopics(any(), any(), page = any(),
            resultsPerPage = any(), useAuth = true) }
        close.complete(Unit)
        val result = refreshing.await() as FlagsResult.Success
        assertEquals(35395, result.flags.single().topicId)
        assertFalse(result.coverage.isGlobalObservation)
    }

    @Test
    fun `hot global miss resolves only target category and distinguishes unavailable - P2c`() = runTest {
        val (api, forum) = wireDeps {
            HfrRestFlagBucket.entries.forEach { bucket -> stubFlagsCall(23, bucket, EMPTY_PAGE) }
        }
        coEvery { api.getGlobalFlagTopics(any()) } returns EMPTY_PAGE
        val policy = MutableStateFlow(FlagsRefreshPolicy(
            strategy = FlagsRefreshStrategy.GLOBAL_ONLY))
        val repo = buildRepository(api, forum, policy = policy)
        repo.refresh(FlagType.CYAN)
        assertEquals(FlagLookup.Absent, repo.findFlag(23, 999))
        coVerify(exactly = 0) { api.getCategoryFlagTopics(13, any(), page = any(),
            resultsPerPage = any(), useAuth = true) }
        repo.clearSessionCache()
        repo.refresh(FlagType.CYAN)
        coEvery { api.getCategoryFlagTopics(23, HfrRestFlagBucket.READ, page = any(),
            resultsPerPage = any(), useAuth = true) } throws IOException("offline")
        assertEquals(FlagLookup.Unavailable, repo.findFlag(23, 999))
    }

    @Test
    fun `stale or incomplete memory success never refetches on resubscription - 984`() = runTest {
        for (incomplete in listOf(false, true)) {
            var now = Instant.parse("2026-05-03T12:00:00Z")
            val clock = mockk<Clock>()
            every { clock.instant() } answers { now }
            val api = mockk<HfrApiClient>()
            coEvery {
                api.getCategoryFlagTopics(any(), any(), page = any(), resultsPerPage = any(), useAuth = true)
            } returns capturedParticipatedFixture
            coEvery { api.getTopicList(any(), null, 1, any(), true) } coAnswers {
                if (incomplete) throw IOException("sticky offline") else EMPTY_PAGE
            }
            val forum = stubForumRepository(sampleCategories)
            val dao = stubFlagDao()
            val repo = buildRepository(api, forum, flagDao = dao, clock = clock)
            val cached = FlagType.entries.associateWith { repo.refresh(it) as FlagsResult.Success }
            assertTrue(cached.values.all { (it.refreshStatus == FlagRefreshStatus.PARTIAL) == incomplete })
            if (!incomplete) now = now.plusSeconds(120)
            // Keep each subscription alive: first() would cancel before a background fetch starts.
            repeat(2) {
                FlagType.entries.forEach { type ->
                    repo.observe(type).test {
                        assertEquals(cached.getValue(type), awaitItem())
                        runCurrent()
                        expectNoEvents()
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            }
            coVerify(exactly = 6) {
                api.getCategoryFlagTopics(any(), any(), page = any(), resultsPerPage = any(), useAuth = true)
            }
            coVerify(exactly = 3) { forum.getCategories(true) }
            coVerify(exactly = 3) { dao.replaceSnapshot(any(), any(), any()) }
        }
    }

    @Test
    fun `legacy rows are unverified until an actual refresh fails - 984`() = runTest {
        val at = Instant.parse("2026-05-03T11:00:00Z")
        val dao = stubFlagDao()
        dao.replaceSnapshot("xat", FlagType.CYAN, FlagCacheSnapshot(
            listOf(flagEntity(FlagType.CYAN, 35395, "Legacy row", at).copy(source = FlagSource.LEGACY_UNKNOWN)),
            emptyList(),
        ))
        val store = FlagCacheStore(dao, Clock.fixed(at, ZoneOffset.UTC), UnconfinedTestDispatcher())
        val unverified = requireNotNull(store.load(FlagType.CYAN, "xat"))
        assertEquals(FlagRefreshStatus.UNVERIFIED, unverified.result.refreshStatus)
        assertNull(unverified.result.issue)
        assertFalse(unverified.isFresh)
        val (api, forum) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
        }
        coEvery { forum.getCategories(true) } returns ForumResult.Failure(IOException("offline"))
        val failed = buildRepository(api, forum, flagDao = dao).refresh(FlagType.CYAN) as FlagsResult.Success
        assertEquals(FlagRefreshStatus.FAILED, failed.refreshStatus)
        assertEquals(unverified.result.flags, failed.flags)
        assertTrue(failed.coverage.categories.all { it.failure == FlagFailureKind.NETWORK })
    }

    @Test
    fun `empty or unreadable catalogue preserves cached rows and never certifies emptiness - 984`() = runTest {
        val badCatalogues = listOf(
            ForumResult.Success(emptyList()),
            ForumResult.Success(listOf(sampleCategories.first().copy(id = 0))),
            ForumResult.Failure(IOException("catalogue unreadable")),
        )
        for (catalogue in badCatalogues) {
            val (api, forum) = wireDeps {
                stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
                stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
            }
            val dao = stubFlagDao()
            val repo = buildRepository(api, forum, flagDao = dao)
            val before = repo.refresh(FlagType.CYAN) as FlagsResult.Success
            val rows = dao.getSnapshot("xat", FlagType.CYAN).rows
            coEvery { forum.getCategories(true) } returns catalogue
            val failed = repo.refresh(FlagType.CYAN) as FlagsResult.Success
            assertEquals(before.flags, failed.flags)
            assertEquals(rows, dao.getSnapshot("xat", FlagType.CYAN).rows)
            assertEquals(FlagRefreshStatus.FAILED, failed.refreshStatus)
            assertFalse(failed.coverage.isComplete)
            assertEquals(before.coverage.oldestSuccessAt, failed.coverage.oldestSuccessAt)
            assertTrue(failed.coverage.categories.all { it.failure != null })
            coVerify(exactly = 2) {
                api.getCategoryFlagTopics(any(), any(), page = any(), resultsPerPage = any(), useAuth = true)
            }
        }
    }

    @Test
    fun `empty catalogue fails without a snapshot and preserves a certified empty cache - 984`() = runTest {
        val (api, forum) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
        }
        val dao = stubFlagDao()
        val repo = buildRepository(api, forum, flagDao = dao)
        repo.refresh(FlagType.CYAN)
        coEvery { forum.getCategories(true) } returns ForumResult.Success(emptyList())
        val retained = repo.refresh(FlagType.CYAN) as FlagsResult.Success
        assertTrue(retained.flags.isEmpty())
        assertEquals(FlagRefreshStatus.FAILED, retained.refreshStatus)
        assertFalse(retained.coverage.isComplete)
        assertTrue(retained.coverage.categories.all { it.failure == FlagFailureKind.SCHEMA })
        assertTrue(buildRepository(api, forum).refresh(FlagType.CYAN) is FlagsResult.Failure)
    }

    @Test
    fun `failed sticky supplements do not make successful buckets cold for findFlag - 984`() = runTest {
        val api = mockk<HfrApiClient>()
        coEvery {
            api.getCategoryFlagTopics(any(), any(), page = any(), resultsPerPage = any(), useAuth = true)
        } returns EMPTY_PAGE
        coEvery { api.getTopicList(any(), null, 1, any(), true) } throws IOException("sticky offline")
        val repo = buildRepository(api, stubForumRepository(sampleCategories))
        FlagType.entries.forEach { repo.refresh(it) }
        repeat(2) { assertEquals(FlagLookup.Unavailable, repo.findFlag(cat = 23, topicId = 999)) }
        coVerify(exactly = 6) {
            api.getCategoryFlagTopics(any(), any(), page = any(), resultsPerPage = any(), useAuth = true)
        }
    }

    @Test
    fun `delflag during a partial refresh cannot restore a retained failed category row - 984`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val (api, forum) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        val dao = stubFlagDao()
        val hfr = mockk<HfrClient>()
        coEvery { hfr.removeFlag(13, any(), 35395, FlagType.CYAN, any()) } returns DELETE_SUCCESS_HTML
        val repo = buildRepository(api, forum, flagDao = dao, hfrClient = hfr)
        val flag = (repo.refresh(FlagType.CYAN) as FlagsResult.Success).flags.single { it.cat == 13 }
        coEvery {
            api.getCategoryFlagTopics(13, HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true)
        } throws
            IOException("failed category must retain its other rows")
        coEvery {
            api.getCategoryFlagTopics(23, HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true)
        } coAnswers {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            capturedParticipatedFixture
        }
        repo.observe(FlagType.CYAN).test {
            assertEquals(2, (awaitItem() as FlagsResult.Success).flags.size)
            val pull = async { repo.refresh(FlagType.CYAN) }
            try {
                entered.await()
                assertEquals(FlagsResult.Loading, awaitItem())
                assertTrue(repo.removeFlag(flag).isSuccess)
                assertFalse((awaitItem() as FlagsResult.Success).flags.any { it.cat == 13 })
                release.complete(Unit)
                val partial = pull.await() as FlagsResult.Success
                assertEquals(FlagRefreshStatus.PARTIAL, partial.refreshStatus)
                assertEquals(listOf(23), partial.flags.map { it.cat })
                assertEquals(partial, awaitItem())
                assertTrue(partial.coverage.categories.any { it.cat == 13 && it.failure == FlagFailureKind.NETWORK })
                assertEquals(listOf(23), dao.getSnapshot("xat", FlagType.CYAN).rows.map { it.cat })
                assertNull(repo.findCachedFlag(13, 35395))
                expectNoEvents()
            } finally {
                release.complete(Unit)
                pull.cancel()
            }
        }
    }

    @Test
    fun `coverage does not depend on a diagnostic sink or transport call summaries - 984`() = runTest {
        val (api, forum) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        coEvery { api.getTopicList(13, null, 1, any(), true) } throws IOException("offline")
        val logged = buildRepository(api, forum).refresh(FlagType.CYAN) as FlagsResult.Success
        val quiet = buildRepository(api, forum, diagnostics = mockk(relaxed = true))
            .refresh(FlagType.CYAN) as FlagsResult.Success
        assertEquals(logged.flags, quiet.flags)
        assertEquals(logged.coverage, quiet.coverage)
        assertEquals(logged.refreshStatus, quiet.refreshStatus)
        assertEquals(FlagRefreshStatus.PARTIAL, quiet.refreshStatus)
    }

    @Test
    fun `partial buckets keep old rows and actual dates through a disk reload - 984`() = runTest {
        val oldTime = Instant.parse("2026-05-03T12:00:00Z")
        var now = oldTime
        val clock = mockk<Clock>()
        every { clock.instant() } answers { now }
        val (api, forum) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        val dao = stubFlagDao()
        val repo = buildRepository(api, forum, flagDao = dao, clock = clock)
        repo.refresh(FlagType.CYAN)
        now = oldTime.plusSeconds(120)
        coEvery {
            api.getCategoryFlagTopics(13, HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true)
        } throws IOException("private message must never reach coverage")
        coEvery {
            api.getCategoryFlagTopics(23, HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true)
        } returns capturedParticipatedFixture.replace("35395", "35396")

        val partial = repo.refresh(FlagType.CYAN) as FlagsResult.Success
        assertEquals(FlagRefreshStatus.PARTIAL, partial.refreshStatus)
        assertEquals(setOf(13 to 35395, 23 to 35396), partial.flags.map { it.cat to it.topicId }.toSet())
        val rows = dao.getSnapshot("xat", FlagType.CYAN).rows
        assertEquals(oldTime, rows.single { it.cat == 13 }.fetchedAt)
        assertEquals(now, rows.single { it.cat == 23 }.fetchedAt)
        val reloaded = requireNotNull(FlagCacheStore(dao, clock, UnconfinedTestDispatcher())
            .load(FlagType.CYAN, "xat"))
        assertEquals(partial.coverage, reloaded.result.coverage)
        assertEquals(partial.refreshStatus, reloaded.result.refreshStatus)
        assertFalse(reloaded.isFresh)
        coVerify(exactly = 2) { dao.replaceSnapshot("xat", FlagType.CYAN, any()) }
    }

    @Test
    fun `exception after first page discards that category instead of replacing it - 984`() = runTest {
        val (api, forum) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        val repo = buildRepository(api, forum)
        repo.refresh(FlagType.CYAN)
        coEvery {
            api.getCategoryFlagTopics(23, HfrRestFlagBucket.PARTICIPATED,
                page = 1, resultsPerPage = any(), useAuth = true)
        } returns capturedParticipatedFixture.replace("35395", "35396").replace("\"results_count\": 1",
            "\"results_count\": 2")
        coEvery {
            api.getCategoryFlagTopics(23, HfrRestFlagBucket.PARTICIPATED,
                page = 2, resultsPerPage = any(), useAuth = true)
        } throws IOException("second page unavailable")
        val result = repo.refresh(FlagType.CYAN) as FlagsResult.Success
        assertEquals(listOf(35395), result.flags.map { it.topicId })
        assertEquals(FlagRefreshStatus.PARTIAL, result.refreshStatus)
        assertFalse(result.coverage.covers(23))
    }

    @Test
    fun `total failure keeps populated or empty cache but fails without any snapshot - 984`() = runTest {
        listOf(capturedParticipatedFixture, EMPTY_PAGE, null).forEach { initial ->
            val (api, forum) = wireDeps {
                stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
                stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, initial ?: EMPTY_PAGE)
            }
            val dao = stubFlagDao()
            val repo = buildRepository(api, forum, flagDao = dao)
            val cached = if (initial != null) repo.refresh(FlagType.CYAN) as FlagsResult.Success else null
            coEvery {
                api.getCategoryFlagTopics(any(), HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true)
            } throws IOException("offline")
            val result = repo.refresh(FlagType.CYAN)
            if (cached == null) {
                assertTrue(result is FlagsResult.Failure)
            } else {
                result as FlagsResult.Success
                assertEquals(cached.flags, result.flags)
                assertEquals(FlagRefreshStatus.FAILED, result.refreshStatus)
                assertEquals(cached.cachedAt, result.cachedAt)
                val disk = requireNotNull(FlagCacheStore(dao,
                    Clock.fixed(Instant.parse("2026-05-03T12:00:00Z"), ZoneOffset.UTC),
                    UnconfinedTestDispatcher()).load(FlagType.CYAN, "xat"))
                assertEquals(FlagRefreshStatus.FAILED, disk.result.refreshStatus)
                assertFalse(disk.isFresh)
            }
        }
    }

    @Test
    fun `schema failure differs from valid empty and an expired session stops new auth reads - 984`() = runTest {
        val (api, forum) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        val dao = stubFlagDao()
        val repo = buildRepository(api, forum, flagDao = dao)
        repo.refresh(FlagType.CYAN)
        coEvery {
            api.getCategoryFlagTopics(23, HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true)
        } returns "{\"resource\":{}}"
        val invalid = repo.refresh(FlagType.CYAN) as FlagsResult.Success
        assertEquals(35395, invalid.flags.single().topicId)
        assertEquals(FlagFailureKind.SCHEMA,
            invalid.coverage.categories.single { it.cat == 23 && it.source == FlagSource.BUCKET }.failure)
        coEvery {
            api.getCategoryFlagTopics(13, HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true)
        } throws SessionExpiredException("[redacted]")
        val expired = repo.refresh(FlagType.CYAN) as FlagsResult.Success
        assertEquals(FlagRefreshStatus.FAILED, expired.refreshStatus)
        assertTrue(expired.coverage.categories.any { it.failure == FlagFailureKind.SESSION })
        // Only the first two refreshes may have started a supplement.
        coVerify(exactly = 2) { api.getTopicList(13, null, 1, any(), true) }
    }

    @Test
    fun `findFlag rechecks a warm type whose target category was not covered - 984`() = runTest {
        val (api, forum) = wireDeps {
            HfrRestFlagBucket.entries.forEach { bucket ->
                stubFlagsCall(13, bucket, EMPTY_PAGE)
                stubFlagsCall(23, bucket, EMPTY_PAGE)
            }
        }
        coEvery {
            api.getCategoryFlagTopics(23, HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true)
        } throws IOException("offline")
        val repo = buildRepository(api, forum)
        FlagType.entries.forEach { repo.refresh(it) }
        coEvery {
            api.getCategoryFlagTopics(23, HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true)
        } returns capturedParticipatedFixture
        assertEquals(35395, (repo.findFlag(23, 35395) as FlagLookup.Found).flag.topicId)
        coVerify(exactly = 2) {
            api.getCategoryFlagTopics(23, HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true)
        }
        coVerify(exactly = 1) {
            api.getCategoryFlagTopics(23, HfrRestFlagBucket.READ,
                page = any(), resultsPerPage = any(), useAuth = true)
        }
    }

    @Test
    fun `fast refresh records two entries with exact page and transport counters - 984`() = runTest {
        val diagnostics = DiagnosticsLog()
        val apiClient = diagnosticApi()
        val dao = stubFlagDao()
        val repo = buildRepository(apiClient, stubForumRepository(sampleCategories),
            flagDao = dao, diagnostics = diagnostics)
        val result = repo.refresh(FlagType.CYAN) as FlagsResult.Success
        assertNull(result.issue)
        assertTrue(diagnostics.entries.value.size <= 3)
        assertEquals(2, diagnostics.entries.value.size)
        val summary = diagnostics.entries.value.last().message
        listOf("outcome=success ", "categories=2", "bucketPages=2", "stickyPages=2", "calls=4",
            "callFailures=0", "reused=4", "bucketsFailures=0", "stickiesFailures=0",
            "fetches=1 newFetches=1 joined=0").forEach {
            assertTrue(summary, summary.contains(it))
        }
        assertTrue(repo.observe(FlagType.CYAN).first() is FlagsResult.Success)
        coVerify(exactly = 1) { dao.replaceSnapshot(any(), FlagType.CYAN, any()) }
    }

    @Test
    fun `failed sticky supplement publishes fresh rows and its category without freshening Room - 984`() = runTest {
        val diagnostics = DiagnosticsLog()
        val apiClient = diagnosticApi(failSticky = true)
        val dao = stubFlagDao()
        var persistedRows = emptyList<FlagTopicEntity>()
        coEvery { dao.replaceSnapshot("xat", FlagType.CYAN, any()) } coAnswers {
            persistedRows = thirdArg<FlagCacheSnapshot>().rows
        }
        val repo = buildRepository(apiClient, stubForumRepository(sampleCategories),
            flagDao = dao, diagnostics = diagnostics)
        val result = repo.refresh(FlagType.CYAN) as FlagsResult.Success
        assertEquals(35395, result.flags.single().topicId)
        assertEquals(listOf(FlagRefreshCategory(13, "Discussions")), result.issue?.stickyCategories)
        assertEquals(false, result.issue?.paginationTruncated)
        coVerify(exactly = 1) {
            dao.replaceSnapshot("xat", FlagType.CYAN, rowsMatching { rows ->
                rows.single().topicId == 35395 &&
                    rows.single().fetchedAt == Instant.parse("2026-05-03T12:00:00Z")
            })
        }
        coEvery { dao.getFlags("xat", FlagType.CYAN) } returns persistedRows
        coEvery { dao.getCoverage("xat", FlagType.CYAN) } returns result.coverage.categories.map {
            FlagCategoryCoverageEntity("xat", FlagType.CYAN, it.cat, it.source,
                it.lastSuccessAt, it.lastAttemptAt, it.failure)
        }
        val disk = requireNotNull(FlagCacheStore(dao,
            Clock.fixed(Instant.parse("2026-05-03T12:00:00Z"), ZoneOffset.UTC),
            UnconfinedTestDispatcher()).load(FlagType.CYAN, "xat"))
        assertFalse("partial rows cannot delay a later disk retry", disk.isFresh)
        assertEquals(FlagRefreshStatus.PARTIAL, disk.result.refreshStatus)
        assertEquals(listOf(13), disk.result.issue?.stickyCategories?.map { it.id })
        val summary = diagnostics.entries.value.last().message
        assertTrue(summary, summary.contains("outcome=success-incomplete"))
        assertTrue(summary, summary.contains("reasons=sticky_request"))
        assertTrue(summary, summary.contains("stickiesFailures=1"))
        assertTrue(summary, summary.contains("callFailures=1"))
        assertTrue(summary, summary.contains("calls=4"))
        assertTrue(repo.observe(FlagType.CYAN).first() is FlagsResult.Success)
    }

    @Test
    fun `bucket failure preserves independent successes - 984`() = runTest {
        val diagnostics = DiagnosticsLog()
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
        }
        coEvery {
            apiClient.getCategoryFlagTopics(cat = 23, bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true)
        } throws IOException("Cookie=SecretCookie hash_check=SecretHash")
        val repo = buildRepository(apiClient, forumRepository, diagnostics = diagnostics)
        val result = repo.refresh(FlagType.CYAN) as FlagsResult.Success
        assertEquals(FlagRefreshStatus.PARTIAL, result.refreshStatus)
        assertEquals(FlagFailureKind.NETWORK,
            result.coverage.categories.single { it.cat == 23 && it.source == FlagSource.BUCKET }.failure)
        val summary = diagnostics.entries.value.last().message
        assertTrue(summary, summary.contains("strategy=baseline N=15 "))
        assertTrue(summary, summary.contains("bucketsFailures=1"))
        assertTrue(summary, !summary.contains("SecretCookie"))
    }

    @Test
    fun `stale disk refresh publishes an incomplete success after the old rows - 984`() = runTest {
        val dao = stubFlagDao()
        val stale = Instant.parse("2026-05-03T11:00:00Z")
        coEvery { dao.getFlags("xat", FlagType.CYAN) } returns listOf(
            flagEntity(FlagType.CYAN, 999, "Old row", stale),
        )
        coEvery { dao.getLastFetchedAt("xat", FlagType.CYAN) } returns stale
        val repo = buildRepository(diagnosticApi(failSticky = true), stubForumRepository(sampleCategories),
            flagDao = dao)
        repo.observe(FlagType.CYAN).test {
            assertEquals(999, (awaitItem() as FlagsResult.Success).flags.single().topicId)
            val fresh = awaitItem() as FlagsResult.Success
            assertEquals(35395, fresh.flags.single().topicId)
            assertEquals(listOf(FlagRefreshCategory(13, "Discussions")), fresh.issue?.stickyCategories)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { dao.replaceSnapshot("xat", FlagType.CYAN, any()) }
    }

    @Test
    fun `partial refresh retains the failed sticky category - 984`() = runTest {
        val (api, forum) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.FAVORITES, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.FAVORITES, capturedParticipatedFixture)
        }
        coEvery {
            api.getTopicList(13, null, 1, any(), true)
        } returns fixture("rest_cat13_topics_last_sticky_favorite.json")
        val dao = stubFlagDao()
        val repo = buildRepository(api, forum, flagDao = dao)
        val complete = repo.refresh(FlagType.FAVORITE) as FlagsResult.Success
        assertNull(complete.issue)
        assertEquals(setOf(100_217, 35395), complete.flags.map { it.topicId }.toSet())
        coEvery { api.getTopicList(13, null, 1, any(), true) } throws IOException("sticky unavailable")
        coEvery {
            api.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.FAVORITES,
                page = any(), resultsPerPage = any(), useAuth = true,
            )
        } returns capturedParticipatedFixture.replace("35395", "35396")

        val partial = repo.refresh(FlagType.FAVORITE) as FlagsResult.Success
        assertEquals(setOf(100_217, 35396), partial.flags.map { it.topicId }.toSet())
        assertEquals(listOf(FlagRefreshCategory(13, "Discussions")), partial.issue?.stickyCategories)
        coVerify(exactly = 1) {
            dao.replaceSnapshot("xat", FlagType.FAVORITE, rowsMatching { rows ->
                rows.map { it.topicId }.toSet() == setOf(100_217, 35396)
            })
        }
        coVerify(exactly = 2) { dao.replaceSnapshot("xat", FlagType.FAVORITE, any()) }
        // Re-observation serves the incomplete memory snapshot without an implicit refresh.
        assertEquals(partial, repo.observe(FlagType.FAVORITE).first())
    }

    @Test
    fun `sticky decode failure carries its category without failing the fresh bucket - 984`() = runTest {
        val api = diagnosticApi()
        coEvery { api.getTopicList(13, null, 1, any(), true) } returns "invalid JSON"
        val diagnostics = DiagnosticsLog()
        val repo = buildRepository(api, stubForumRepository(sampleCategories), diagnostics = diagnostics)
        val result = repo.refresh(FlagType.CYAN) as FlagsResult.Success
        assertEquals(35395, result.flags.single().topicId)
        assertEquals(listOf(FlagRefreshCategory(13, "Discussions")), result.issue?.stickyCategories)
        val summary = diagnostics.entries.value.last().message
        assertTrue(summary, summary.contains("outcome=success-incomplete"))
        assertTrue(summary, summary.contains("reasons=sticky_decode"))
        assertTrue(summary, summary.contains("stickiesFailures=1"))
    }

    @Test
    fun `a favorite mutation preserves the partial snapshot issue and stale freshness - 984`() = runTest {
        val api = diagnosticApi(failSticky = true)
        val dao = stubFlagDao()
        val hfr = mockk<HfrClient>()
        coEvery { hfr.addFlag(any()) } returns ADD_SUCCESS_HTML
        val repo = buildRepository(api, stubForumRepository(sampleCategories), flagDao = dao, hfrClient = hfr)
        val partial = repo.refresh(FlagType.CYAN) as FlagsResult.Success
        assertTrue(repo.addFlag(sampleFlagAddContext()).isSuccess)
        val updated = repo.observe(FlagType.CYAN).first() as FlagsResult.Success
        assertTrue(updated.flags.single().isFavorite)
        assertTrue("a mutation reuses the warning identity", updated.issue === partial.issue)
        coVerify(exactly = 1) { dao.markFavorite("xat", FlagType.CYAN, 23, 35395) }
        assertEquals(partial.coverage, updated.coverage)
    }

    @Test
    fun `page cap publishes a partial list with a pagination issue - 984`() = runTest {
        val api = diagnosticApi()
        val page = capturedParticipatedFixture.replace("\"results_count\": 1", "\"results_count\": 101")
        coEvery {
            api.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true,
            )
        } returns page
        val diagnostics = DiagnosticsLog()
        val dao = stubFlagDao()
        val repo = buildRepository(api, stubForumRepository(sampleCategories),
            flagDao = dao, diagnostics = diagnostics)
        val result = repo.refresh(FlagType.CYAN) as FlagsResult.Success
        assertTrue("capped category must not replace its cache", result.flags.isEmpty())
        assertEquals(true, result.issue?.paginationTruncated)
        assertEquals(emptyList<FlagRefreshCategory>(), result.issue?.stickyCategories)
        assertTrue(diagnostics.entries.value.last().message.contains("reasons=page_cap"))
        coVerify(exactly = 100) {
            api.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true,
            )
        }
        coVerify(exactly = 0) {
            api.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.PARTICIPATED,
                page = 101, resultsPerPage = any(), useAuth = true,
            )
        }
        coVerify(exactly = 1) { dao.replaceSnapshot("xat", FlagType.CYAN, any()) }
    }

    @Test
    fun `an early empty page only diagnoses a divergent count without a UI warning - 984`() = runTest {
        val api = diagnosticApi()
        coEvery {
            api.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true,
            )
        } returns EMPTY_PAGE.replace("\"results_count\": 0", "\"results_count\": 2")
        val diagnostics = DiagnosticsLog()
        val repo = buildRepository(api, stubForumRepository(sampleCategories), diagnostics = diagnostics)
        val result = repo.refresh(FlagType.CYAN) as FlagsResult.Success
        assertTrue(result.flags.isEmpty())
        assertNull(result.issue)
        assertTrue(diagnostics.entries.value.last().message.contains("reasons=empty_bucket_page"))
        coVerify(exactly = 1) {
            api.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.PARTICIPATED,
                page = 1, resultsPerPage = any(), useAuth = true,
            )
        }
    }

    @Test
    fun `observe emits Loading then Success on a happy fetch`() = runTest {
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        val flagDao = stubFlagDao()
        val repo = buildRepository(apiClient, forumRepository, flagDao = flagDao)

        repo.observe(FlagType.CYAN).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val success = awaitItem() as FlagsResult.Success
            assertEquals(1, success.flags.size)
            val flag = success.flags.single()
            assertEquals(35395, flag.topicId)
            assertEquals(23, flag.cat)
            assertEquals(FlagType.CYAN, flag.type)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            flagDao.replaceSnapshot(
                userId = "xat",
                type = FlagType.CYAN,
                snapshot = rowsMatching { rows -> rows.size == 1 && rows.single().topicId == 35395 },
            )
        }
    }

    @Test
    fun `concurrent explicit refreshes share a fetch in the same generation - 984`() = runTest {
        val diagnostics = DiagnosticsLog()
        // A second pull may join an active pull, but never an initial observation's old generation.
        val gate = CompletableDeferred<Unit>()
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 13,
                bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(),
                resultsPerPage = any(),
                useAuth = true,
            )
        } returns EMPTY_PAGE
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 23,
                bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(),
                resultsPerPage = any(),
                useAuth = true,
            )
        } coAnswers {
            gate.await()
            capturedParticipatedFixture
        }
        coEvery {
            apiClient.getTopicList(cat = any(), subcat = null, page = 1, resultsPerPage = any(), useAuth = true)
        } returns EMPTY_PAGE
        val dao = stubFlagDao()
        val repo = buildRepository(apiClient, stubForumRepository(sampleCategories),
            flagDao = dao, diagnostics = diagnostics)

        val firstRefresh = launch { repo.refresh(FlagType.CYAN) }
        runCurrent()
        val refreshJob = launch { repo.refresh(FlagType.CYAN) }
        runCurrent()
        gate.complete(Unit)
        firstRefresh.join()
        refreshJob.join()
        val joined = diagnostics.entries.value.single { it.message.contains("joined=1") }.message
        assertTrue(joined, joined.contains("fetches=1 newFetches=0 joined=1"))
        assertTrue(joined, joined.contains("fetchRefreshIds=[1]"))
        assertTrue(joined, joined.contains("bucketPages=2"))
        assertTrue(joined, joined.contains("stickyGenerationRefused=0"))

        // A single fan-out: the gated category was hit exactly once despite the two concurrent callers.
        coVerify(exactly = 1) {
            apiClient.getCategoryFlagTopics(
                cat = 23,
                bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(),
                resultsPerPage = any(),
                useAuth = true,
            )
        }
        coVerify(exactly = 1) { dao.replaceSnapshot("xat", FlagType.CYAN, any()) }
        coVerify(exactly = 2) {
            apiClient.getTopicList(cat = any(), subcat = null, page = 1, resultsPerPage = any(), useAuth = true)
        }
    }

    @Test
    fun `pull cancels obsolete buckets and publishes only the new generation - 984`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val replacementRelease = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val sequence = AtomicLong()
        val oldCategories = mutableSetOf<Int>()
        var cancelledCalls = 0
        var calls = 0
        val api = mockk<HfrApiClient>()
        coEvery {
            api.getCategoryFlagTopics(cat = any(), bucket = HfrRestFlagBucket.FAVORITES,
                page = any(), resultsPerPage = any(), useAuth = true)
        } coAnswers {
            val cat = firstArg<Int>()
            calls++
            if (oldCategories.add(cat)) {
                val id = sequence.incrementAndGet()
                if (oldCategories.size == sampleCategories.size) entered.complete(Unit)
                try {
                    release.await()
                } catch (cancellation: CancellationException) {
                    recordDiagnosticCall(id, cat, sticky = false, failed = false, cancelled = true)
                    if (++cancelledCalls == sampleCategories.size) cancelled.complete(Unit)
                    throw cancellation
                }
            } else {
                replacementRelease.await()
            }
            recordDiagnosticCall(sequence.incrementAndGet(), cat, sticky = false, failed = false)
            EMPTY_PAGE
        }
        coEvery {
            api.getTopicList(cat = any(), subcat = null, page = 1, resultsPerPage = any(), useAuth = true)
        } coAnswers {
            val cat = firstArg<Int>()
            recordDiagnosticCall(sequence.incrementAndGet(), cat, sticky = true, failed = false)
            if (cat == 13) fixture("rest_cat13_topics_last_sticky_favorite.json") else EMPTY_PAGE
        }
        val dao = stubFlagDao()
        val diagnostics = DiagnosticsLog()
        val repo = buildRepository(api, stubForumRepository(sampleCategories),
            flagDao = dao, diagnostics = diagnostics)

        repo.observe(FlagType.FAVORITE).test {
            try {
                assertEquals(FlagsResult.Loading, awaitItem())
                entered.await()
                val pull = launch { repo.refresh(FlagType.FAVORITE) }
                runCurrent()
                cancelled.await()
                assertTrue("the obsolete request must be cancelled before its barrier is released",
                    !release.isCompleted)
                assertEquals(FlagsResult.Loading, awaitItem())
                replacementRelease.complete(Unit)
                runCurrent()
                // Before releasing the old fetch, the pull must finish with the new sweep's sticky.
                assertTrue("pull must not await the old generation", pull.isCompleted)
                assertEquals(100_217, (awaitItem() as FlagsResult.Success).flags.single().topicId)
                release.complete(Unit)
                runCurrent()
                // The initial observer may reuse the current success; every late success must be current.
                assertEquals(100_217, (awaitItem() as FlagsResult.Success).flags.single().topicId)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            } finally {
                release.complete(Unit)
                replacementRelease.complete(Unit)
            }
        }
        assertEquals(4, calls)
        assertEquals(2, cancelledCalls)
        coVerify(exactly = 2) {
            api.getTopicList(cat = any(), subcat = null, page = 1, resultsPerPage = any(), useAuth = true)
        }
        coVerify(exactly = 1) { dao.replaceSnapshot("xat", FlagType.FAVORITE, any()) }
        coVerify(exactly = 1) {
            dao.replaceSnapshot("xat", FlagType.FAVORITE, rowsMatching { it.single().topicId == 100_217 })
        }
        val refreshEntries = diagnostics.entries.value.filter { it.tag == FlagRefreshDiagnostics.TAG }
        val pullRefresh = refreshEntries.single {
            it.message.contains("trigger=pull ")
        }.message.substringBefore(' ')
        val pullSummary = refreshEntries.single {
            it.message.startsWith("$pullRefresh ") && it.message.contains("outcome=")
        }.message
        // beginRefresh cancels the old fetch before the pull joins the current generation.
        // Either the observer's retry or the pull can create that same replacement first.
        // Only its creator/joiner counters vary; cancellation and publication stay exact above.
        assertTrue(pullSummary, pullSummary.contains("outcome=success exception=-"))
        assertTrue(pullSummary, pullSummary.contains("fetches=1 "))
        assertTrue(pullSummary, pullSummary.contains("newFetches=1 joined=0") ||
            pullSummary.contains("newFetches=0 joined=1"))
        assertTrue(pullSummary, pullSummary.contains("pendingFetches=0"))
        assertTrue(pullSummary, pullSummary.contains("stickyGenerationRefused=0"))
        val initialSummary = refreshEntries.single {
            it.message.startsWith("refresh=1 ") && it.message.contains("outcome=")
        }.message
        assertTrue(initialSummary, initialSummary.contains("outcome=success exception=-"))
        assertTrue(initialSummary, initialSummary.contains("fetches=2 "))
        assertTrue(initialSummary, initialSummary.contains("pendingFetches=0"))
        assertTrue(initialSummary, initialSummary.contains("callFailures=2"))
        assertTrue("cancelled transport must remain visible despite the successful replacement",
            diagnostics.entries.value.any { it.tag == "HfrCall" && it.message.contains("outcome=cancelled") })
    }

    @Test
    fun `pull cancels an obsolete sticky sweep and publishes the new one - 984`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val replacementRelease = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var stickyCalls = 0
        val api = mockk<HfrApiClient>()
        coEvery {
            api.getCategoryFlagTopics(cat = 13, bucket = any(), page = any(),
                resultsPerPage = any(), useAuth = true)
        } returns EMPTY_PAGE
        coEvery {
            api.getTopicList(cat = 13, subcat = null, page = 1, resultsPerPage = any(), useAuth = true)
        } coAnswers {
            if (++stickyCalls == 1) {
                entered.complete(Unit)
                try {
                    release.await()
                } catch (cancellation: CancellationException) {
                    cancelled.complete(Unit)
                    throw cancellation
                }
            } else {
                replacementRelease.await()
            }
            fixture("rest_cat13_topics_last_sticky_favorite.json")
        }
        val dao = stubFlagDao()
        val repo = buildRepository(api, stubForumRepository(sampleCategories.take(1)), flagDao = dao)

        repo.observe(FlagType.FAVORITE).test {
            try {
                assertEquals(FlagsResult.Loading, awaitItem())
                entered.await()
                val pull = launch { repo.refresh(FlagType.FAVORITE) }
                runCurrent()
                cancelled.await()
                assertEquals(FlagsResult.Loading, awaitItem())
                replacementRelease.complete(Unit)
                pull.join()
                repeat(2) {
                    assertEquals(100_217, (awaitItem() as FlagsResult.Success).flags.single().topicId)
                }
                expectNoEvents()
                assertEquals(2, stickyCalls)
                coVerify(exactly = 1) { dao.replaceSnapshot("xat", FlagType.FAVORITE, any()) }
                cancelAndIgnoreRemainingEvents()
            } finally {
                release.complete(Unit)
                replacementRelease.complete(Unit)
            }
        }
    }

    @Test
    fun `cancelling one pull keeps the shared generation alive for the other callers - 984`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val api = mockk<HfrApiClient>()
        coEvery {
            api.getCategoryFlagTopics(cat = 13, bucket = HfrRestFlagBucket.FAVORITES,
                page = any(), resultsPerPage = any(), useAuth = true)
        } coAnswers {
            calls++
            gate.await()
            EMPTY_PAGE
        }
        coEvery {
            api.getTopicList(cat = 13, subcat = null, page = 1, resultsPerPage = any(), useAuth = true)
        } returns fixture("rest_cat13_topics_last_sticky_favorite.json")
        val dao = stubFlagDao()
        val repo = buildRepository(api, stubForumRepository(sampleCategories.take(1)), flagDao = dao)
        val first = launch { repo.refresh(FlagType.FAVORITE) }
        runCurrent()
        val second = launch { repo.refresh(FlagType.FAVORITE) }
        runCurrent()
        first.cancel()
        first.join()
        // A third caller must reuse the barrier still owned by the second caller.
        val third = launch { repo.refresh(FlagType.FAVORITE) }
        runCurrent()
        gate.complete(Unit)
        second.join()
        third.join()
        assertEquals(1, calls)
        val result = repo.observe(FlagType.FAVORITE).first() as FlagsResult.Success
        assertEquals(100_217, result.flags.single().topicId)
        coVerify(exactly = 1) { dao.replaceSnapshot("xat", FlagType.FAVORITE, any()) }
    }

    @Test
    fun `cancelling the last pull cancels its unconsumed bucket fetch - 984`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val api = mockk<HfrApiClient>()
        coEvery {
            api.getCategoryFlagTopics(cat = 13, bucket = any(), page = any(),
                resultsPerPage = any(), useAuth = true)
        } coAnswers {
            entered.complete(Unit)
            try {
                release.await()
                EMPTY_PAGE
            } catch (cancellation: CancellationException) {
                cancelled.complete(Unit)
                throw cancellation
            }
        }
        val dao = stubFlagDao()
        val diagnostics = DiagnosticsLog()
        val repo = buildRepository(api, stubForumRepository(sampleCategories.take(1)),
            flagDao = dao, diagnostics = diagnostics)
        val pull = launch { repo.refresh(FlagType.CYAN) }
        try {
            entered.await()
            pull.cancel(CancellationException("last pull closed"))
            pull.join()
            cancelled.await()
            coVerify(exactly = 0) { dao.replaceSnapshot(any(), any(), any()) }
            coVerify(exactly = 0) {
                api.getTopicList(cat = any(), subcat = any(), page = any(),
                    resultsPerPage = any(), useAuth = any())
            }
            val summary = diagnostics.entries.value.last().message
            assertTrue(summary, summary.contains("outcome=failure exception=CancellationException"))
        } finally {
            release.complete(Unit)
            pull.cancel()
        }
    }

    @Test
    fun `bucket cancellation propagates with its diagnostic exception and no cache write - 984`() = runTest {
        val cancellation = CancellationException("cancel bucket")
        val api = mockk<HfrApiClient>()
        coEvery {
            api.getCategoryFlagTopics(cat = 13, bucket = any(), page = any(),
                resultsPerPage = any(), useAuth = true)
        } throws cancellation
        val dao = stubFlagDao()
        val diagnostics = DiagnosticsLog()
        val repo = buildRepository(api, stubForumRepository(sampleCategories.take(1)),
            flagDao = dao, diagnostics = diagnostics)

        val error = runCatching { repo.refresh(FlagType.CYAN) }.exceptionOrNull()
        assertCancellation(cancellation, error)
        coVerify(exactly = 0) { dao.replaceSnapshot(any(), any(), any()) }
        val summary = diagnostics.entries.value.last().message
        assertTrue(summary, summary.contains("outcome=failure exception=CancellationException"))
    }

    @Test
    fun `sticky REST cancellation propagates for the active session without a cache write - 984`() = runTest {
        val cancellation = CancellationException("cancel sticky")
        val api = mockk<HfrApiClient>()
        coEvery {
            api.getCategoryFlagTopics(cat = 13, bucket = any(), page = any(),
                resultsPerPage = any(), useAuth = true)
        } returns EMPTY_PAGE
        coEvery {
            api.getTopicList(cat = 13, subcat = null, page = 1, resultsPerPage = any(), useAuth = true)
        } throws cancellation
        val dao = stubFlagDao()
        val repo = buildRepository(api, stubForumRepository(sampleCategories.take(1)), flagDao = dao)
        assertCancellation(cancellation, runCatching { repo.refresh(FlagType.CYAN) }.exceptionOrNull())
        coVerify(exactly = 0) { dao.replaceSnapshot(any(), any(), any()) }
    }

    @Test
    fun `Room cancellation propagates instead of publishing a successful refresh - 984`() = runTest {
        val cancellation = CancellationException("cancel Room")
        val dao = stubFlagDao()
        coEvery { dao.replaceSnapshot(any(), any(), any()) } throws cancellation
        val repo = buildRepository(diagnosticApi(), stubForumRepository(sampleCategories), flagDao = dao)
        assertCancellation(cancellation, runCatching { repo.refresh(FlagType.CYAN) }.exceptionOrNull())
    }

    @Test
    fun `Super refresh order keeps each bucket and re-probes each sweep - 984`() = runTest {
        val buckets = mutableListOf<HfrRestFlagBucket>()
        val api = mockk<HfrApiClient>()
        coEvery {
            api.getCategoryFlagTopics(cat = 13, bucket = any(), page = any(),
                resultsPerPage = any(), useAuth = true)
        } coAnswers {
            buckets += secondArg<HfrRestFlagBucket>()
            EMPTY_PAGE
        }
        coEvery {
            api.getTopicList(cat = 13, subcat = null, page = 1, resultsPerPage = any(), useAuth = true)
        } returns fixture("rest_cat13_topics_last_sticky_favorite.json")
        val dao = stubFlagDao()
        val repo = buildRepository(api, stubForumRepository(sampleCategories.take(1)), flagDao = dao)
        listOf(FlagType.CYAN, FlagType.RED, FlagType.FAVORITE).forEach { repo.refresh(it) }
        assertEquals(listOf(HfrRestFlagBucket.PARTICIPATED, HfrRestFlagBucket.READ,
            HfrRestFlagBucket.FAVORITES), buckets)
        coVerify(exactly = 3) {
            api.getTopicList(cat = 13, subcat = null, page = 1, resultsPerPage = any(), useAuth = true)
        }
        FlagType.entries.forEach { type ->
            assertTrue(repo.observe(type).first() is FlagsResult.Success)
            coVerify(exactly = 1) { dao.replaceSnapshot("xat", type, any()) }
        }
    }

    @Test
    fun `clearSessionCache keeps an in-flight fetch from caching across a session change - 501 P1`() = runTest {
        // #501 (Codex review P1) — a fetch in flight when the account logs out / switches must not
        // repopulate the type-keyed singleton cache, which observe() serves before resolving the
        // current user (else the next account would receive the previous account's flags).
        val gate = CompletableDeferred<Unit>()
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 13,
                bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(),
                resultsPerPage = any(),
                useAuth = true,
            )
        } returns EMPTY_PAGE
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 23,
                bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(),
                resultsPerPage = any(),
                useAuth = true,
            )
        } coAnswers {
            gate.await()
            capturedParticipatedFixture
        }
        val repo = buildRepository(apiClient, stubForumRepository(sampleCategories))

        val inFlight = launch { repo.observe(FlagType.CYAN).first { it is FlagsResult.Success } }
        runCurrent() // the fetch is now in flight, awaiting the gate
        repo.clearSessionCache() // logout / account switch while the fetch is running
        gate.complete(Unit)
        inFlight.join()

        // A fresh observe must RE-FETCH (Loading first) rather than serve the interrupted fetch's
        // success from the singleton cache.
        repo.observe(FlagType.CYAN).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            assertTrue(awaitItem() is FlagsResult.Success)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `account switch cancels the previous owner and only the new account is cached - 984`() = runTest {
        val authState = MutableStateFlow<AuthState>(AuthState.Authenticated("XaT"))
        val auth = mockk<AuthRepository>()
        every { auth.observeAuthState() } returns authState
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var calls = 0
        val api = mockk<HfrApiClient>()
        coEvery {
            api.getCategoryFlagTopics(cat = 23, bucket = any(), page = any(),
                resultsPerPage = any(), useAuth = true)
        } coAnswers {
            if (++calls == 1) {
                entered.complete(Unit)
                try {
                    released.await()
                } catch (cancellation: CancellationException) {
                    cancelled.complete(Unit)
                    throw cancellation
                }
                capturedParticipatedFixture
            } else {
                EMPTY_PAGE
            }
        }
        coEvery {
            api.getTopicList(cat = 23, subcat = null, page = 1, resultsPerPage = any(), useAuth = true)
        } returns EMPTY_PAGE
        val dao = stubFlagDao()
        val repo = buildRepository(api, stubForumRepository(sampleCategories.takeLast(1)),
            authRepository = auth, flagDao = dao)
        val previous = async {
            runCatching { repo.observe(FlagType.CYAN).first { it is FlagsResult.Success } }
        }
        entered.await()
        authState.value = AuthState.Authenticated("SecondUser")
        repo.clearSessionCache()
        cancelled.await()
        repo.refresh(FlagType.CYAN)
        assertTrue(previous.await().exceptionOrNull() is CancellationException)
        assertTrue((repo.observe(FlagType.CYAN).first() as FlagsResult.Success).flags.isEmpty())
        assertEquals(2, calls)
        coVerify(exactly = 0) { dao.replaceSnapshot("xat", any(), any()) }
        coVerify(exactly = 1) { dao.replaceSnapshot("seconduser", FlagType.CYAN, any()) }
    }

    @Test
    fun `a CYAN fetch persists favorited rows under CYAN - regression 384`() = runTest {
        // Live-captured contract (rest_cat13_participated_favorites.json, 2026-06-11): the
        // participated bucket returns participated-AND-favorited topics with flag_owntopic=3.
        // Before the fix they were persisted under FAVORITE, so replaceForType(CYAN) never
        // rewrote them and getFlags(CYAN) lost them on the next Room-served landing — the
        // « liste amputée au retour de l'onglet Messages » of #384.
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, fixture("rest_cat13_participated_favorites.json"))
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
        }
        val flagDao = stubFlagDao()
        val repo = buildRepository(apiClient, forumRepository, flagDao = flagDao)

        repo.observe(FlagType.CYAN).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val success = awaitItem() as FlagsResult.Success
            assertEquals(3, success.flags.size)
            assertTrue(
                "in-memory list types every row with the requested bucket",
                success.flags.all { it.type == FlagType.CYAN },
            )
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            flagDao.replaceSnapshot(
                userId = "xat",
                type = FlagType.CYAN,
                snapshot = rowsMatching { rows ->
                    rows.size == 3 && rows.all { it.type == FlagType.CYAN } &&
                        rows.map { it.topicId }.containsAll(listOf(26595, 55667, 121657))
                },
            )
        }
    }

    @Test
    fun `observe emits Failure when the network throws`() = runTest {
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = any(),
                bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(),
                resultsPerPage = any(),
                useAuth = true,
            )
        } throws IOException("offline")
        val forumRepository = stubForumRepository(sampleCategories)
        val repo = buildRepository(apiClient, forumRepository)

        repo.observe(FlagType.CYAN).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val result = awaitItem()
            assertTrue("expected Failure, got $result", result is FlagsResult.Failure)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadCategories takes a fresh catalogue so a newly added category's flags are fetched - 251`() = runTest {
        // #251 — a category added to HFR after the 24h categories cache was warmed (e.g. cat 32
        // « IA ») must still have its drapeaux fetched. The fan-out previously read
        // observeCategories()'s STALE leading emission and silently skipped the new category, so
        // its cyans were invisible. It now asks for a guaranteed-fresh catalogue.
        // sampleCategories (13, 23) is the stale catalogue without cat 32; the fan-out must instead
        // enumerate the FRESH catalogue returned by getCategories(forceRefreshIfStale = true).
        val freshCatalogue = sampleCategories +
            Category(id = 32, name = "Intelligence Artificielle", forceSubcat = false, subcategoryCount = 1)
        val apiClient = mockk<HfrApiClient>()
        val forumRepository = mockk<ForumRepository>()
        coEvery { forumRepository.getCategories(forceRefreshIfStale = true) } returns
            ForumResult.Success(freshCatalogue)
        // Only the newly-added cat 32 carries a flagged topic; the rest are empty.
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 13, bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true,
            )
        } returns EMPTY_PAGE
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true,
            )
        } returns EMPTY_PAGE
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 32, bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true,
            )
        } returns capturedParticipatedFixture
        val repo = buildRepository(apiClient, forumRepository)

        repo.observe(FlagType.CYAN).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val success = awaitItem() as FlagsResult.Success
            assertEquals("the flag from the newly-added category must be present", 1, success.flags.size)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            apiClient.getCategoryFlagTopics(
                cat = 32, bucket = HfrRestFlagBucket.PARTICIPATED,
                page = 1, resultsPerPage = 50, useAuth = true,
            )
        }
    }

    @Test
    fun `a flagged sticky dropped by the bucket is recovered from topics-last - 251`() = runTest {
        // #251 — cat 32 « IA » has no subcategory; the REST flag bucket OMITS its flagged STICKY
        // topic (the « Règles » sticky carries a cyan flag yet is absent from topics/participated/).
        // The topics/last supplement must recover it (fixture: sticky id=1, flag_owntopic=1, unread).
        val catalogue = listOf(
            Category(id = 32, name = "Intelligence Artificielle", forceSubcat = false, subcategoryCount = 0),
        )
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 32, bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true,
            )
        } returns EMPTY_PAGE
        coEvery {
            apiClient.getTopicList(cat = 32, subcat = null, page = any(), resultsPerPage = any(), useAuth = true)
        } returns fixture("rest_cat32_topics_last_sticky.json")
        val repo = buildRepository(apiClient, stubForumRepository(catalogue))

        repo.observe(FlagType.CYAN).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val success = awaitItem() as FlagsResult.Success
            val sticky = success.flags.single { it.cat == 32 && it.topicId == 1 }
            assertEquals(FlagType.CYAN, sticky.type)
            assertTrue("is_read=false ⇒ the recovered sticky is unread", sticky.hasUnread)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            apiClient.getTopicList(cat = 32, subcat = null, page = 1, resultsPerPage = 50, useAuth = true)
        }
    }

    @Test
    fun `a favorite sticky in a category WITH subcategories is recovered from topics-last - 862`() = runTest {
        // #862 — the drop is category-wide, not a no-subcat quirk : proven live 2026-07-13 on
        // cat 13 « Discussions » (15 subcats). The favorites bucket came back EMPTY while
        // CATEGORY-level topics/last carried the flagged sticky « Rappel droits d'auteurs »
        // (id=100217, flag_owntopic=3, hosted in subcat 422) — both captured as-is. The #251
        // supplement, widened to all cats, must recover it.
        val catalogue = listOf(
            Category(id = 13, name = "Discussions", forceSubcat = true, subcategoryCount = 15),
        )
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 13, bucket = HfrRestFlagBucket.FAVORITES,
                page = any(), resultsPerPage = any(), useAuth = true,
            )
        } returns fixture("rest_cat13_favorites_empty_despite_sticky.json")
        coEvery {
            apiClient.getTopicList(cat = 13, subcat = null, page = any(), resultsPerPage = any(), useAuth = true)
        } returns fixture("rest_cat13_topics_last_sticky_favorite.json")
        val repo = buildRepository(apiClient, stubForumRepository(catalogue))

        repo.observe(FlagType.FAVORITE).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val success = awaitItem() as FlagsResult.Success
            val sticky = success.flags.single { it.cat == 13 && it.topicId == 100_217 }
            assertEquals(FlagType.FAVORITE, sticky.type)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            apiClient.getTopicList(cat = 13, subcat = null, page = 1, resultsPerPage = 50, useAuth = true)
        }
    }

    @Test
    fun `the flag types share ONE topics-last sweep per refresh burst - 862`() = runTest {
        // #862 gate Sol r2 — the sweep is a coalescing device : the types of one burst must share
        // exactly one Deferred (19 GETs per burst, not 57). Two type fetches, one getTopicList.
        val catalogue = listOf(
            Category(id = 13, name = "Discussions", forceSubcat = true, subcategoryCount = 15),
        )
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 13, bucket = any(), page = any(), resultsPerPage = any(), useAuth = true,
            )
        } returns EMPTY_PAGE
        coEvery {
            apiClient.getTopicList(cat = 13, subcat = null, page = any(), resultsPerPage = any(), useAuth = true)
        } returns fixture("rest_cat13_topics_last_sticky_favorite.json")
        val repo = buildRepository(apiClient, stubForumRepository(catalogue))

        repo.observe(FlagType.CYAN).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        repo.observe(FlagType.FAVORITE).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) {
            apiClient.getTopicList(cat = 13, subcat = null, page = any(), resultsPerPage = any(), useAuth = true)
        }
    }

    @Test
    fun `an explicit refresh opens a new sweep generation and re-probes topics-last - 862`() = runTest {
        // #862 gate Sol r2 — a manual pull is a NEW generation, never served by the previous
        // burst's sweep (a user who just flagged a sticky must see it on pull).
        val catalogue = listOf(
            Category(id = 13, name = "Discussions", forceSubcat = true, subcategoryCount = 15),
        )
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 13, bucket = any(), page = any(), resultsPerPage = any(), useAuth = true,
            )
        } returns EMPTY_PAGE
        coEvery {
            apiClient.getTopicList(cat = 13, subcat = null, page = any(), resultsPerPage = any(), useAuth = true)
        } returns fixture("rest_cat13_topics_last_sticky_favorite.json")
        val repo = buildRepository(apiClient, stubForumRepository(catalogue))

        repo.observe(FlagType.FAVORITE).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        repo.refresh(FlagType.FAVORITE)

        coVerify(exactly = 2) {
            apiClient.getTopicList(cat = 13, subcat = null, page = any(), resultsPerPage = any(), useAuth = true)
        }
    }

    @Test
    fun `an old in-flight fetch never joins the sweep of a newer explicit refresh - 862`() = runTest {
        // #984 strengthens #862: discard the old snapshot and retry the entire CYAN fetch
        // under the current generation. Never mix its old bucket with the pull's sweep.
        val catalogue = listOf(
            Category(id = 13, name = "Discussions", forceSubcat = true, subcategoryCount = 15),
        )
        val cyanGate = CompletableDeferred<Unit>()
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 13, bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true,
            )
        } coAnswers {
            cyanGate.await()
            EMPTY_PAGE
        }
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 13, bucket = HfrRestFlagBucket.FAVORITES,
                page = any(), resultsPerPage = any(), useAuth = true,
            )
        } returns EMPTY_PAGE
        coEvery {
            apiClient.getTopicList(cat = 13, subcat = null, page = any(), resultsPerPage = any(), useAuth = true)
        } returns fixture("rest_cat13_topics_last_sticky_favorite.json")
        val dao = stubFlagDao()
        val repo = buildRepository(apiClient, stubForumRepository(catalogue), flagDao = dao)

        // 1. CYAN starts under generation 0, held in its bucket call — no sweep exists yet.
        var cyanResult: FlagsResult? = null
        val cyanJob = launch {
            cyanResult = repo.observe(FlagType.CYAN).first { it is FlagsResult.Success }
        }
        runCurrent()
        // 2. Explicit refresh : new generation, creates THE only sweep of this test.
        repo.refresh(FlagType.FAVORITE)
        // 3. CYAN resumes: the old result is rejected; a new bucket may share the current sweep.
        cyanGate.complete(Unit)
        cyanJob.join()

        val cyanFlags = (cyanResult as FlagsResult.Success).flags
        assertTrue(
            "the pre-pull CYAN fetch must not pick the pull's sticky supplement",
            cyanFlags.none { it.topicId == 100_217 },
        )
        coVerify(exactly = 1) {
            apiClient.getTopicList(cat = 13, subcat = null, page = any(), resultsPerPage = any(), useAuth = true)
        }
        coVerify(exactly = 2) {
            apiClient.getCategoryFlagTopics(cat = 13, bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true)
        }
        coVerify(exactly = 1) { dao.replaceSnapshot("xat", FlagType.CYAN, any()) }
    }

    @Test
    fun `a fetch that started under an older burst never joins a newer sweep - 862`() = runTest {
        // #984: even when the previous sweep exists, the old result cannot be committed.
        // Only a complete retry under N+1 may share N+1's supplement.
        val catalogue = listOf(
            Category(id = 13, name = "Discussions", forceSubcat = true, subcategoryCount = 15),
        )
        val cyanGate = CompletableDeferred<Unit>()
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 13, bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true,
            )
        } coAnswers {
            cyanGate.await()
            EMPTY_PAGE
        }
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 13, bucket = HfrRestFlagBucket.FAVORITES,
                page = any(), resultsPerPage = any(), useAuth = true,
            )
        } returns EMPTY_PAGE
        coEvery {
            apiClient.getTopicList(cat = 13, subcat = null, page = any(), resultsPerPage = any(), useAuth = true)
        } returns fixture("rest_cat13_topics_last_sticky_favorite.json")
        val dao = stubFlagDao()
        val repo = buildRepository(apiClient, stubForumRepository(catalogue), flagDao = dao)

        // 1. FAVORITE completes → burst 0's sweep exists.
        repo.observe(FlagType.FAVORITE).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        // 2. CYAN starts under burst 0, held inside its bucket call.
        var cyanResult: FlagsResult? = null
        val cyanJob = launch {
            cyanResult = repo.observe(FlagType.CYAN).first { it is FlagsResult.Success }
        }
        runCurrent()
        // 3. An explicit refresh opens burst 1 (burst 0's sweep exists → bump) and completes.
        repo.refresh(FlagType.FAVORITE)
        // 4. CYAN resumes: burst 0 is refused; a new bucket fetch runs under burst 1.
        cyanGate.complete(Unit)
        cyanJob.join()

        val cyanFlags = (cyanResult as FlagsResult.Success).flags
        assertTrue(
            "the stale-burst CYAN fetch must not pick the newer sweep's sticky",
            cyanFlags.none { it.topicId == 100_217 },
        )
        // Two sweeps ran; the retried CYAN fetch shares burst 1's sweep.
        coVerify(exactly = 2) {
            apiClient.getTopicList(cat = 13, subcat = null, page = any(), resultsPerPage = any(), useAuth = true)
        }
        coVerify(exactly = 2) {
            apiClient.getCategoryFlagTopics(cat = 13, bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true)
        }
        coVerify(exactly = 1) { dao.replaceSnapshot("xat", FlagType.CYAN, any()) }
    }

    @Test
    fun `a sticky already returned by the bucket is not duplicated by the supplement - 251`() = runTest {
        // #251 — dedup by (cat, topicId): if a no-subcat cat's bucket DID return the sticky, the
        // topics/last supplement must not add a second copy.
        val catalogue = listOf(
            Category(id = 32, name = "Intelligence Artificielle", forceSubcat = false, subcategoryCount = 0),
        )
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 32, bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true,
            )
        } returns CAT32_STICKY_BUCKET_PAGE
        coEvery {
            apiClient.getTopicList(cat = 32, subcat = null, page = any(), resultsPerPage = any(), useAuth = true)
        } returns fixture("rest_cat32_topics_last_sticky.json")
        val repo = buildRepository(apiClient, stubForumRepository(catalogue))

        repo.observe(FlagType.CYAN).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val success = awaitItem() as FlagsResult.Success
            assertEquals(
                "the sticky must appear exactly once (bucket + supplement deduped)",
                1,
                success.flags.count { it.cat == 32 && it.topicId == 1 },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a topics-last failure in the supplement does not fail the whole refresh - 251`() = runTest {
        // #251 — the supplement is best-effort and OUTSIDE the bucket fan-out's fail-all contract:
        // a topics/last failure for a no-subcat cat must NOT turn the screen into a "Réessayer" error;
        // the bucket results still surface.
        val catalogue = listOf(
            Category(id = 23, name = "Technologies Mobiles", forceSubcat = true, subcategoryCount = 10),
            Category(id = 32, name = "Intelligence Artificielle", forceSubcat = false, subcategoryCount = 0),
        )
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true,
            )
        } returns capturedParticipatedFixture
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 32, bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true,
            )
        } returns EMPTY_PAGE
        coEvery {
            apiClient.getTopicList(cat = 32, subcat = null, page = any(), resultsPerPage = any(), useAuth = true)
        } throws RuntimeException("topics/last boom")
        coEvery {
            apiClient.getTopicList(cat = 23, subcat = null, page = any(), resultsPerPage = any(), useAuth = true)
        } returns EMPTY_PAGE
        val repo = buildRepository(apiClient, stubForumRepository(catalogue))

        repo.observe(FlagType.CYAN).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val success = awaitItem() as FlagsResult.Success
            assertTrue(
                "the bucket flag from cat 23 survives the supplement failure",
                success.flags.any { it.cat == 23 },
            )
            // #984 option (b): keep the best-effort list, but stop treating the missing cat as silent.
            assertEquals(listOf(FlagRefreshCategory(32, "Intelligence Artificielle")),
                success.issue?.stickyCategories)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observe emits Failure when categories cannot be loaded`() = runTest {
        val apiClient = mockk<HfrApiClient>(relaxed = true)
        val forumRepository = mockk<ForumRepository>()
        coEvery { forumRepository.getCategories(any()) } returns
            ForumResult.Failure(IOException("HFR down"))
        val repo = buildRepository(apiClient, forumRepository)

        repo.observe(FlagType.CYAN).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val result = awaitItem()
            assertTrue("expected Failure, got $result", result is FlagsResult.Failure)
            assertTrue((result as FlagsResult.Failure).cause is IOException)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh broadcasts a fresh result to active observers`() = runTest {
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        val repo = buildRepository(apiClient, forumRepository)

        repo.observe(FlagType.CYAN).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val initial = awaitItem() as FlagsResult.Success
            assertEquals(1, initial.flags.size)

            repo.refresh(FlagType.CYAN)
            assertEquals(FlagsResult.Loading, awaitItem())
            val refreshed = awaitItem() as FlagsResult.Success
            assertEquals(1, refreshed.flags.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observe reuses cached success instead of refetching the same tab`() = runTest {
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.FAVORITES, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.FAVORITES, capturedParticipatedFixture)
        }
        val repo = buildRepository(apiClient, forumRepository)

        repo.observe(FlagType.FAVORITE).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val first = awaitItem() as FlagsResult.Success
            assertEquals(1, first.flags.size)
            cancelAndIgnoreRemainingEvents()
        }

        repo.observe(FlagType.FAVORITE).test {
            val cached = awaitItem() as FlagsResult.Success
            assertEquals(1, cached.flags.size)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) {
            apiClient.getCategoryFlagTopics(
                cat = 23,
                bucket = HfrRestFlagBucket.FAVORITES,
                page = 1,
                resultsPerPage = 50,
                useAuth = true,
            )
        }
    }

    @Test
    fun `observe emits fresh Room cache without REST fan-out`() = runTest {
        val now = Instant.parse("2026-05-03T12:00:00Z")
        val apiClient = mockk<HfrApiClient>(relaxed = true)
        val flagDao = stubFlagDao()
        coEvery { flagDao.getFlags("xat", FlagType.CYAN) } returns listOf(
            flagEntity(
                type = FlagType.CYAN,
                topicId = 35395,
                title = "Redface 2",
                fetchedAt = now,
            ),
        )
        coEvery { flagDao.getCoverage("xat", FlagType.CYAN) } returns successfulCoverage(FlagType.CYAN, now)
        val repo = buildRepository(
            apiClient = apiClient,
            forumRepository = stubForumRepository(sampleCategories),
            flagDao = flagDao,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        repo.observe(FlagType.CYAN).test {
            val success = awaitItem() as FlagsResult.Success
            assertEquals(1, success.flags.size)
            assertEquals(35395, success.flags.single().topicId)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) {
            apiClient.getCategoryFlagTopics(
                cat = any(),
                bucket = any(),
                page = any(),
                resultsPerPage = any(),
                useAuth = any(),
            )
        }
    }

    @Test
    fun `clearSessionCache plus logout disk purge makes the next observe fetch again`() = runTest {
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.FAVORITES, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.FAVORITES, capturedParticipatedFixture)
        }
        val dao = stubFlagDao()
        val repo = buildRepository(apiClient, forumRepository, flagDao = dao)

        repo.observe(FlagType.FAVORITE).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        repo.clearSessionCache()
        // CacheInvalidator purges this account on logout; the fake now actually persists snapshots.
        dao.deleteForType("xat", FlagType.FAVORITE)

        repo.observe(FlagType.FAVORITE).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 2) {
            apiClient.getCategoryFlagTopics(
                cat = 23,
                bucket = HfrRestFlagBucket.FAVORITES,
                page = 1,
                resultsPerPage = 50,
                useAuth = true,
            )
        }
    }

    @Test
    fun `each FlagType maps to its own REST bucket on the per-cat endpoint`() = runTest {
        val (apiClient, forumRepository) = wireDeps {
            HfrRestFlagBucket.entries.forEach { bucket ->
                stubFlagsCall(13, bucket, EMPTY_PAGE)
                stubFlagsCall(23, bucket, capturedParticipatedFixture)
            }
        }
        val repo = buildRepository(apiClient, forumRepository)

        listOf(
            FlagType.CYAN to HfrRestFlagBucket.PARTICIPATED,
            FlagType.RED to HfrRestFlagBucket.READ,
            FlagType.FAVORITE to HfrRestFlagBucket.FAVORITES,
        ).forEach { (type, _) ->
            repo.observe(type).test {
                awaitItem() // Loading
                val success = awaitItem() as FlagsResult.Success
                assertEquals(1, success.flags.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

        HfrRestFlagBucket.entries.forEach { bucket ->
            coVerify(exactly = 1) {
                apiClient.getCategoryFlagTopics(
                    cat = 23,
                    bucket = bucket,
                    page = 1,
                    resultsPerPage = 50,
                    useAuth = true,
                )
            }
        }
    }

    @Test
    fun `non-positive cat ids are filtered out before fan-out`() = runTest {
        // ForumRepository may surface defensive non-public cats (cat=0 modos space) ; the
        // REST drapeaux endpoint would 403 on them, so the repository must skip them.
        val withModos = listOf(
            Category(id = 0, name = "Espace modos", forceSubcat = false, subcategoryCount = 0),
            Category(id = 23, name = "Technologies Mobiles", forceSubcat = true, subcategoryCount = 10),
        )
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 23,
                bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(),
                resultsPerPage = any(),
                useAuth = true,
            )
        } returns capturedParticipatedFixture
        val forumRepository = stubForumRepository(withModos)
        val repo = buildRepository(apiClient, forumRepository)

        repo.observe(FlagType.CYAN).test {
            awaitItem() // Loading
            val success = awaitItem() as FlagsResult.Success
            assertEquals(1, success.flags.size)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) {
            apiClient.getCategoryFlagTopics(
                cat = 0,
                bucket = any(),
                page = any(),
                resultsPerPage = any(),
                useAuth = any(),
            )
        }
    }

    @Test
    fun `flags from multiple categories are sorted globally by lastReplyAt descending`() = runTest {
        // Per-cat fan-out concatenates in cat-iteration order ; without a global sort the
        // screen would block-by-cat instead of by activity. Two cats, three flags total,
        // older flag in the second-iterated cat must still land last.
        val cat13Page = """
            {
              "resource": {
                "page": 1,
                "results_count": 2,
                "results_per_page": 50,
                "resources": [
                  {
                    "id": 100,
                    "title": "old in cat 13",
                    "links": {
                      "category": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/"},
                      "posts": {"href": "$CATEGORIES_URL/13/topics/100/posts/?page=1&results_per_page=40", "count": 1}
                    },
                    "is_read": false,
                    "flag_owntopic": 1,
                    "last_post_date": "2026-04-01 10:00"
                  },
                  {
                    "id": 200,
                    "title": "newest in cat 13",
                    "links": {
                      "category": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/"},
                      "posts": {"href": "$CATEGORIES_URL/13/topics/200/posts/?page=1&results_per_page=40", "count": 1}
                    },
                    "is_read": false,
                    "flag_owntopic": 1,
                    "last_post_date": "2026-05-03 12:00"
                  }
                ]
              }
            }
        """
        val cat23Page = """
            {
              "resource": {
                "page": 1,
                "results_count": 1,
                "results_per_page": 50,
                "resources": [
                  {
                    "id": 300,
                    "title": "middle in cat 23",
                    "links": {
                      "category": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/23/"},
                      "posts": {"href": "$CATEGORIES_URL/23/topics/300/posts/?page=1&results_per_page=40", "count": 1}
                    },
                    "is_read": false,
                    "flag_owntopic": 1,
                    "last_post_date": "2026-04-15 18:30"
                  }
                ]
              }
            }
        """
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, cat13Page)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, cat23Page)
        }
        val repo = buildRepository(apiClient, forumRepository)

        repo.observe(FlagType.CYAN).test {
            awaitItem() // Loading
            val success = awaitItem() as FlagsResult.Success
            assertEquals(
                "globally sorted by lastReplyAt desc, regardless of cat iteration order",
                listOf(200, 300, 100),
                success.flags.map { it.topicId },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pagination keeps walking when server inflates results_per_page on a partial page`() = runTest {
        // Regression guard: the old `seen = page * pageSize` heuristic stopped after
        // page 1 when the server advertised a results_per_page bigger than the actual
        // payload (e.g. normalised to 10 while only 1 topic landed). The new contract
        // pages on `accumulated.size >= results_count` so the second flag is fetched.
        val page1 = """
            {
              "resource": {
                "page": 1,
                "results_count": 2,
                "results_per_page": 10,
                "resources": [
                  {
                    "id": 300,
                    "title": "Topic A — partial page",
                    "links": {
                      "category": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/"},
                      "posts": {"href": "$CATEGORIES_URL/13/topics/300/posts/?page=1&results_per_page=40", "count": 1}
                    },
                    "is_read": false,
                    "flag_owntopic": 1
                  }
                ]
              }
            }
        """
        val page2 = """
            {
              "resource": {
                "page": 2,
                "results_count": 2,
                "results_per_page": 10,
                "resources": [
                  {
                    "id": 400,
                    "title": "Topic B — second page",
                    "links": {
                      "category": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/"},
                      "posts": {"href": "$CATEGORIES_URL/13/topics/400/posts/?page=1&results_per_page=40", "count": 1}
                    },
                    "is_read": false,
                    "flag_owntopic": 1
                  }
                ]
              }
            }
        """
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 13,
                bucket = HfrRestFlagBucket.PARTICIPATED,
                page = 1,
                resultsPerPage = 50,
                useAuth = true,
            )
        } returns page1
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 13,
                bucket = HfrRestFlagBucket.PARTICIPATED,
                page = 2,
                resultsPerPage = 50,
                useAuth = true,
            )
        } returns page2
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 23,
                bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(),
                resultsPerPage = any(),
                useAuth = true,
            )
        } returns EMPTY_PAGE
        val forumRepository = stubForumRepository(sampleCategories)
        val repo = buildRepository(apiClient, forumRepository)

        repo.observe(FlagType.CYAN).test {
            awaitItem() // Loading
            val success = awaitItem() as FlagsResult.Success
            assertEquals(setOf(300, 400), success.flags.map { it.topicId }.toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pagination walks subsequent pages while results_count exceeds the current page`() = runTest {
        // Two pages on cat 13: results_count=2, results_per_page=1.
        // After page 1 the repository must walk page 2 and concatenate.
        val page1 = """
            {
              "resource": {
                "page": 1,
                "results_count": 2,
                "results_per_page": 1,
                "resources": [
                  {
                    "id": 100,
                    "title": "Topic A",
                    "links": {
                      "category": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/"},
                      "posts": {"href": "$CATEGORIES_URL/13/topics/100/posts/?page=1&results_per_page=40", "count": 1}
                    },
                    "is_read": false,
                    "flag_owntopic": 1
                  }
                ]
              }
            }
        """
        val page2 = """
            {
              "resource": {
                "page": 2,
                "results_count": 2,
                "results_per_page": 1,
                "resources": [
                  {
                    "id": 200,
                    "title": "Topic B",
                    "links": {
                      "category": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/"},
                      "posts": {"href": "$CATEGORIES_URL/13/topics/200/posts/?page=2&results_per_page=40", "count": 50}
                    },
                    "is_read": false,
                    "flag_owntopic": 1
                  }
                ]
              }
            }
        """
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 13,
                bucket = HfrRestFlagBucket.PARTICIPATED,
                page = 1,
                resultsPerPage = 50,
                useAuth = true,
            )
        } returns page1
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 13,
                bucket = HfrRestFlagBucket.PARTICIPATED,
                page = 2,
                resultsPerPage = 50,
                useAuth = true,
            )
        } returns page2
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 23,
                bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(),
                resultsPerPage = any(),
                useAuth = true,
            )
        } returns EMPTY_PAGE
        val forumRepository = stubForumRepository(sampleCategories)
        val repo = buildRepository(apiClient, forumRepository)

        repo.observe(FlagType.CYAN).test {
            awaitItem() // Loading
            val success = awaitItem() as FlagsResult.Success
            assertEquals(setOf(100, 200), success.flags.map { it.topicId }.toSet())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) {
            apiClient.getCategoryFlagTopics(
                cat = 13,
                bucket = HfrRestFlagBucket.PARTICIPATED,
                page = 2,
                resultsPerPage = 50,
                useAuth = true,
            )
        }
    }

    @Test
    fun `addFlag success marks existing cached topic as favorite and Room`() = runTest {
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        val flagDao = stubFlagDao()
        val hfrClient = mockk<HfrClient>()
        val context = sampleFlagAddContext()
        coEvery { hfrClient.addFlag(context) } returns ADD_SUCCESS_HTML
        val repo = buildRepository(apiClient, forumRepository, flagDao = flagDao, hfrClient = hfrClient)

        repo.observe(FlagType.CYAN).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val seeded = awaitItem() as FlagsResult.Success
            assertEquals(false, seeded.flags.single { it.topicId == 35395 }.isFavorite)

            val result = repo.addFlag(context)
            assertTrue("expected success, got $result", result.isSuccess)

            val updated = awaitItem() as FlagsResult.Success
            assertEquals(true, updated.flags.single { it.topicId == 35395 }.isFavorite)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { hfrClient.addFlag(context) }
        coVerify { flagDao.markFavorite("xat", FlagType.CYAN, 23, 35395) }
        coVerify(exactly = 1) {
            flagDao.deleteForType(userId = "xat", type = FlagType.FAVORITE)
        }
    }

    @Test
    fun `resolveFavorite finds an ordinary favorite in the fresh category bucket - 986`() = runTest {
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(23, HfrRestFlagBucket.FAVORITES, capturedParticipatedFixture)
        }
        val repo = buildRepository(apiClient, forumRepository)

        val result = repo.resolveFavorite(cat = 23, topicId = 35395)

        assertTrue("the topic is present in the fresh favorites bucket", result.getOrThrow())
        coVerify(exactly = 0) {
            apiClient.getTopicList(
                cat = any(), subcat = any(), page = any(), resultsPerPage = any(), useAuth = any(),
            )
        }
    }

    @Test
    fun `resolveFavorite recovers a favorite sticky omitted by the bucket - 986`() = runTest {
        val catalogue = listOf(
            Category(id = 13, name = "Discussions", forceSubcat = true, subcategoryCount = 15),
        )
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 13, bucket = HfrRestFlagBucket.FAVORITES,
                page = any(), resultsPerPage = any(), useAuth = true,
            )
        } returns fixture("rest_cat13_favorites_empty_despite_sticky.json")
        coEvery {
            apiClient.getTopicList(
                cat = 13, subcat = null, page = 1, resultsPerPage = 50, useAuth = true,
            )
        } returns fixture("rest_cat13_topics_last_sticky_favorite.json")
        val repo = buildRepository(apiClient, stubForumRepository(catalogue))

        val result = repo.resolveFavorite(cat = 13, topicId = 100_217)

        assertTrue("the topics-last fallback carries the omitted sticky", result.getOrThrow())
    }

    @Test
    fun `resolveFavorite fails closed when the sticky fallback cannot complete - 986`() = runTest {
        val catalogue = listOf(
            Category(id = 13, name = "Discussions", forceSubcat = true, subcategoryCount = 15),
        )
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 13, bucket = HfrRestFlagBucket.FAVORITES,
                page = any(), resultsPerPage = any(), useAuth = true,
            )
        } returns EMPTY_PAGE
        coEvery {
            apiClient.getTopicList(
                cat = 13, subcat = null, page = 1, resultsPerPage = 50, useAuth = true,
            )
        } throws IOException("offline")
        val repo = buildRepository(apiClient, stubForumRepository(catalogue))

        val result = repo.resolveFavorite(cat = 13, topicId = 999_999)

        assertTrue("a fallback failure must not be reported as not-favorite", result.isFailure)
    }

    @Test
    fun `resolveFavorite fails closed when the favorites pagination is incomplete - 986`() = runTest {
        val pageOne = capturedParticipatedFixture.replace(
            oldValue = "\"results_count\": 1",
            newValue = "\"results_count\": 2",
        )
        val incompletePageTwo = EMPTY_PAGE
            .replace(oldValue = "\"page\": 1", newValue = "\"page\": 2")
            .replace(oldValue = "\"results_count\": 0", newValue = "\"results_count\": 2")
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.FAVORITES,
                page = 1, resultsPerPage = 50, useAuth = true,
            )
        } returns pageOne
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.FAVORITES,
                page = 2, resultsPerPage = 50, useAuth = true,
            )
        } returns incompletePageTwo
        val repo = buildRepository(
            apiClient = apiClient,
            forumRepository = stubForumRepository(sampleCategories),
        )

        val result = repo.resolveFavorite(cat = 23, topicId = 999_999)

        assertTrue("a truncated bucket must not become a destructive false", result.isFailure)
        coVerify(exactly = 0) {
            apiClient.getTopicList(
                cat = any(), subcat = any(), page = any(), resultsPerPage = any(), useAuth = any(),
            )
        }
    }

    @Test
    fun `resolveFavorite memoizes success for 30 seconds then refreshes - 986`() = runTest {
        var now = Instant.parse("2026-05-03T12:00:00Z")
        val clock = mockk<Clock>()
        every { clock.instant() } answers { now }
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.FAVORITES,
                page = 1, resultsPerPage = 50, useAuth = true,
            )
        } returns EMPTY_PAGE
        coEvery {
            apiClient.getTopicList(
                cat = 23, subcat = null, page = 1, resultsPerPage = 50, useAuth = true,
            )
        } returns EMPTY_PAGE
        val repo = buildRepository(
            apiClient = apiClient,
            forumRepository = stubForumRepository(sampleCategories),
            clock = clock,
        )

        assertEquals(false, repo.resolveFavorite(cat = 23, topicId = 999_999).getOrThrow())
        assertEquals(false, repo.resolveFavorite(cat = 23, topicId = 999_999).getOrThrow())
        coVerify(exactly = 1) {
            apiClient.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.FAVORITES,
                page = 1, resultsPerPage = 50, useAuth = true,
            )
        }

        now = now.plusSeconds(31)
        assertEquals(false, repo.resolveFavorite(cat = 23, topicId = 999_999).getOrThrow())
        coVerify(exactly = 2) {
            apiClient.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.FAVORITES,
                page = 1, resultsPerPage = 50, useAuth = true,
            )
        }
        coVerify(exactly = 2) {
            apiClient.getTopicList(
                cat = 23, subcat = null, page = 1, resultsPerPage = 50, useAuth = true,
            )
        }
    }

    @Test
    fun `resolveFavorite shares an in-flight lookup when one caller is cancelled - 986`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.FAVORITES,
                page = 1, resultsPerPage = 50, useAuth = true,
            )
        } coAnswers {
            gate.await()
            EMPTY_PAGE
        }
        coEvery {
            apiClient.getTopicList(
                cat = 23, subcat = null, page = 1, resultsPerPage = 50, useAuth = true,
            )
        } returns EMPTY_PAGE
        val repo = buildRepository(apiClient, stubForumRepository(sampleCategories))

        val firstCaller = launch { repo.resolveFavorite(cat = 23, topicId = 999_999) }
        runCurrent()
        val replacementCaller = async { repo.resolveFavorite(cat = 23, topicId = 999_999) }
        runCurrent()
        firstCaller.cancel()
        firstCaller.join()
        gate.complete(Unit)

        assertEquals(false, replacementCaller.await().getOrThrow())
        coVerify(exactly = 1) {
            apiClient.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.FAVORITES,
                page = 1, resultsPerPage = 50, useAuth = true,
            )
        }
        coVerify(exactly = 1) {
            apiClient.getTopicList(
                cat = 23, subcat = null, page = 1, resultsPerPage = 50, useAuth = true,
            )
        }
    }

    @Test
    fun `resolveFavorite finishes and memoizes after its only caller is cancelled - 986`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.FAVORITES,
                page = 1, resultsPerPage = 50, useAuth = true,
            )
        } coAnswers {
            gate.await()
            EMPTY_PAGE
        }
        coEvery {
            apiClient.getTopicList(
                cat = 23, subcat = null, page = 1, resultsPerPage = 50, useAuth = true,
            )
        } returns EMPTY_PAGE
        val repo = buildRepository(apiClient, stubForumRepository(sampleCategories))

        val closedMenuCaller = launch { repo.resolveFavorite(cat = 23, topicId = 999_999) }
        runCurrent()
        closedMenuCaller.cancel()
        closedMenuCaller.join()
        gate.complete(Unit)
        runCurrent()

        assertEquals(false, repo.resolveFavorite(cat = 23, topicId = 999_999).getOrThrow())
        coVerify(exactly = 1) {
            apiClient.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.FAVORITES,
                page = 1, resultsPerPage = 50, useAuth = true,
            )
        }
    }

    @Test
    fun `addFlag invalidates the favorite resolution memo - 986`() = runTest {
        var favoriteCalls = 0
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.FAVORITES,
                page = 1, resultsPerPage = 50, useAuth = true,
            )
        } coAnswers {
            favoriteCalls++
            if (favoriteCalls == 1) EMPTY_PAGE else capturedParticipatedFixture
        }
        coEvery {
            apiClient.getTopicList(
                cat = 23, subcat = null, page = 1, resultsPerPage = 50, useAuth = true,
            )
        } returns EMPTY_PAGE
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.addFlag(any()) } returns ADD_SUCCESS_HTML
        val repo = buildRepository(
            apiClient = apiClient,
            forumRepository = stubForumRepository(sampleCategories),
            hfrClient = hfrClient,
        )

        assertEquals(false, repo.resolveFavorite(cat = 23, topicId = 35395).getOrThrow())
        assertTrue(repo.addFlag(sampleFlagAddContext()).isSuccess)
        assertTrue(repo.resolveFavorite(cat = 23, topicId = 35395).getOrThrow())

        assertEquals(2, favoriteCalls)
    }

    @Test
    fun `addFlag invalidates an empty favorite snapshot so the next observe refetches - 986`() = runTest {
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.FAVORITES, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.FAVORITES, EMPTY_PAGE)
        }
        sampleCategories.forEach { category ->
            coEvery {
                apiClient.getTopicList(
                    cat = category.id, subcat = null, page = 1, resultsPerPage = 50, useAuth = true,
                )
            } returns EMPTY_PAGE
        }
        val flagDao = stubFlagDao()
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.addFlag(any()) } returns ADD_SUCCESS_HTML
        val repo = buildRepository(apiClient, forumRepository, flagDao = flagDao, hfrClient = hfrClient)

        repo.observe(FlagType.FAVORITE).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            assertTrue((awaitItem() as FlagsResult.Success).flags.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(repo.addFlag(sampleFlagAddContext()).isSuccess)
        repo.observe(FlagType.FAVORITE).test {
            assertEquals(
                "the invalidated memory and Room snapshot must not satisfy the next read",
                FlagsResult.Loading,
                awaitItem(),
            )
            assertTrue((awaitItem() as FlagsResult.Success).flags.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) {
            flagDao.deleteForType(userId = "xat", type = FlagType.FAVORITE)
        }
        coVerify(exactly = 2) {
            apiClient.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.FAVORITES,
                page = 1, resultsPerPage = 50, useAuth = true,
            )
        }
    }

    @Test
    fun `addFlag prevents an older favorite fetch from restoring or exposing stale data - 986`() = runTest {
        val diagnostics = DiagnosticsLog()
        val gate = CompletableDeferred<Unit>()
        var favoriteCalls = 0
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.FAVORITES,
                page = 1, resultsPerPage = 50, useAuth = true,
            )
        } coAnswers {
            favoriteCalls++
            if (favoriteCalls == 1) {
                gate.await()
                EMPTY_PAGE
            } else {
                capturedParticipatedFixture
            }
        }
        coEvery {
            apiClient.getTopicList(
                cat = 23, subcat = null, page = 1, resultsPerPage = 50, useAuth = true,
            )
        } returns EMPTY_PAGE
        val flagDao = stubFlagDao()
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.addFlag(any()) } returns ADD_SUCCESS_HTML
        val repo = buildRepository(
            apiClient = apiClient,
            forumRepository = stubForumRepository(
                listOf(Category(id = 23, name = "Technologies Mobiles", forceSubcat = true, subcategoryCount = 10)),
            ),
            flagDao = flagDao,
            hfrClient = hfrClient,
            diagnostics = diagnostics,
        )

        var observed: FlagsResult.Success? = null
        val observeJob = launch {
            observed = repo.observe(FlagType.FAVORITE).first { it is FlagsResult.Success } as FlagsResult.Success
        }
        runCurrent()
        assertTrue(repo.addFlag(sampleFlagAddContext()).isSuccess)
        // The active observer makes addFlag launch its detached replacement before the old
        // request resumes. That fetch is new globally, but reused by the initial refresh.
        assertEquals("addFlag must start the replacement before releasing the old fetch", 2, favoriteCalls)
        gate.complete(Unit)
        observeJob.join()

        assertEquals(listOf(35395), observed?.flags?.map { it.topicId })
        assertEquals("the invalidated generation must run a second REST fetch", 2, favoriteCalls)
        val refreshEntries = diagnostics.entries.value.filter { it.tag == FlagRefreshDiagnostics.TAG }
        // Identity 2 belongs to the detached fetch, not to a second refresh lifecycle.
        assertEquals(2, refreshEntries.size)
        assertTrue(refreshEntries.first().message.startsWith("refresh=1 trigger=initial-observe "))
        val summary = refreshEntries.single { it.message.contains("outcome=") }.message
        assertTrue(summary, summary.startsWith("refresh=1 "))
        assertTrue(summary, summary.contains("outcome=success "))
        assertTrue(summary, summary.contains("fetches=2 newFetches=1 joined=1"))
        assertTrue(summary, summary.contains("fetchRefreshIds=[1, 2]"))
        assertTrue(summary, !summary.contains("cache_generation"))
        coVerify(exactly = 1) {
            flagDao.replaceSnapshot(
                userId = "xat",
                type = FlagType.FAVORITE,
                snapshot = rowsMatching { rows -> rows.singleOrNull()?.topicId == 35395 },
            )
        }
    }

    @Test
    fun `a Room load started during favorite purge waits and sees the purged snapshot - 986`() = runTest {
        val purgeGate = CompletableDeferred<Unit>()
        val restGate = CompletableDeferred<Unit>()
        var roomPurged = false
        val flagDao = stubFlagDao()
        coEvery { flagDao.deleteForType(userId = "xat", type = FlagType.FAVORITE) } coAnswers {
            purgeGate.await()
            roomPurged = true
        }
        coEvery { flagDao.getFlags(userId = "xat", type = FlagType.FAVORITE) } coAnswers {
            if (roomPurged) {
                emptyList()
            } else {
                listOf(
                    flagEntity(
                        type = FlagType.FAVORITE,
                        topicId = 999_999,
                        title = "Stale Room favorite",
                        fetchedAt = Instant.parse("2026-05-03T12:00:00Z"),
                    ),
                )
            }
        }
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.FAVORITES,
                page = 1, resultsPerPage = 50, useAuth = true,
            )
        } coAnswers {
            restGate.await()
            capturedParticipatedFixture
        }
        coEvery {
            apiClient.getTopicList(
                cat = 23, subcat = null, page = 1, resultsPerPage = 50, useAuth = true,
            )
        } returns EMPTY_PAGE
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.addFlag(any()) } returns ADD_SUCCESS_HTML
        val repo = buildRepository(
            apiClient = apiClient,
            forumRepository = stubForumRepository(
                listOf(Category(id = 23, name = "Technologies Mobiles", forceSubcat = true, subcategoryCount = 10)),
            ),
            flagDao = flagDao,
            hfrClient = hfrClient,
        )

        val addJob = launch { assertTrue(repo.addFlag(sampleFlagAddContext()).isSuccess) }
        runCurrent() // addflag succeeded; reconcile holds the FAVORITE lock inside the gated purge.

        var observed: FlagsResult.Success? = null
        val observeJob = launch {
            observed = repo.observe(FlagType.FAVORITE).first { it is FlagsResult.Success } as FlagsResult.Success
        }
        runCurrent() // observe is waiting for the same lock and cannot read the pre-purge Room rows.
        purgeGate.complete(Unit)
        addJob.join()
        runCurrent() // Room read now sees roomPurged=true; both refresh paths share the gated REST fetch.
        coVerify(exactly = 1) {
            flagDao.getFlags(userId = "xat", type = FlagType.FAVORITE)
        }
        restGate.complete(Unit)
        observeJob.join()

        assertEquals(listOf(35395), observed?.flags?.map { it.topicId })
        coVerify(exactly = 1) {
            apiClient.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.FAVORITES,
                page = 1, resultsPerPage = 50, useAuth = true,
            )
        }
    }

    @Test
    fun `addFlag failure touches no cache and returns failure`() = runTest {
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        val flagDao = stubFlagDao()
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.addFlag(any()) } returns ADD_FAILURE_HTML
        val repo = buildRepository(apiClient, forumRepository, flagDao = flagDao, hfrClient = hfrClient)

        repo.observe(FlagType.CYAN).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            awaitItem() as FlagsResult.Success

            val result = repo.addFlag(sampleFlagAddContext())
            assertTrue("expected failure, got $result", result.isFailure)
            assertTrue(result.exceptionOrNull() is FlagAddFailedException)

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) {
            flagDao.replaceSnapshot(
                userId = "xat",
                type = FlagType.CYAN,
                snapshot = rowsMatching { rows -> rows.any { it.topicId == 35395 && it.isFavorite } },
            )
        }
    }

    @Test
    fun `addFlag session expiry touches no cache and returns failure`() = runTest {
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        val flagDao = stubFlagDao()
        val hfrClient = mockk<HfrClient>()
        val expired = SessionExpiredException("https://forum.hardware.fr/login.php")
        coEvery { hfrClient.addFlag(any()) } throws expired
        val repo = buildRepository(apiClient, forumRepository, flagDao = flagDao, hfrClient = hfrClient)

        repo.observe(FlagType.CYAN).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            awaitItem() as FlagsResult.Success

            val result = repo.addFlag(sampleFlagAddContext())
            assertTrue("expected failure, got $result", result.isFailure)
            assertTrue(result.exceptionOrNull() is SessionExpiredException)

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) {
            flagDao.replaceSnapshot(
                userId = "xat",
                type = FlagType.CYAN,
                snapshot = rowsMatching { rows -> rows.any { it.topicId == 35395 && it.isFavorite } },
            )
        }
    }

    @Test
    fun `addFlag fails fast when anonymous without hitting the network`() = runTest {
        val apiClient = mockk<HfrApiClient>(relaxed = true)
        val hfrClient = mockk<HfrClient>(relaxed = true)
        val anonymousAuth = mockk<AuthRepository>()
        every { anonymousAuth.observeAuthState() } returns flowOf(AuthState.Anonymous)
        val repo = buildRepository(
            apiClient = apiClient,
            forumRepository = stubForumRepository(sampleCategories),
            authRepository = anonymousAuth,
            hfrClient = hfrClient,
        )

        val result = repo.addFlag(sampleFlagAddContext())
        assertTrue("anonymous addFlag must fail", result.isFailure)
        coVerify(exactly = 0) {
            hfrClient.addFlag(any())
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // #1144 — cancellation is control flow, not an outcome
    //
    // Both mutations wrap their work in `runCatching`, which catches Throwable — CancellationException
    // included. Folding it into `Result.failure` reported « l'ajout a échoué » for a caller that had
    // merely gone away, AND made the `if (raised is CancellationException) throw raised` guard in
    // TopicViewModel.confirmRemoveTopicFlag unreachable — the exact net that hid the defect #1144 is
    // about. These two tests pin the re-throw.
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `addFlag re-throws a cancellation instead of folding it into a failed Result (#1144)`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.addFlag(any()) } throws CancellationException("caller went away")
        val repo = buildRepository(
            apiClient = mockk(relaxed = true),
            forumRepository = stubForumRepository(sampleCategories),
            hfrClient = hfrClient,
        )

        var propagated: Throwable? = null
        var returned: Result<Unit>? = null
        try {
            returned = repo.addFlag(sampleFlagAddContext())
        } catch (cancellation: CancellationException) {
            propagated = cancellation
        }

        assertNull("a cancellation must NOT come back as a Result", returned)
        assertTrue("expected the CancellationException to propagate", propagated is CancellationException)
        assertEquals("caller went away", propagated?.message)
    }

    @Test
    fun `removeFlag re-throws a cancellation instead of folding it into a failed Result (#1144)`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery {
            hfrClient.removeFlag(cat = any(), subcat = any(), topicId = any(), type = any(), page = any())
        } throws CancellationException("caller went away")
        val repo = buildRepository(
            apiClient = mockk(relaxed = true),
            forumRepository = stubForumRepository(sampleCategories),
            hfrClient = hfrClient,
        )

        var propagated: Throwable? = null
        var returned: Result<Unit>? = null
        try {
            returned = repo.removeFlag(sampleFlag(FlagType.CYAN, topicId = 35395))
        } catch (cancellation: CancellationException) {
            propagated = cancellation
        }

        assertNull("a cancellation must NOT come back as a Result", returned)
        assertTrue("expected the CancellationException to propagate", propagated is CancellationException)
        assertEquals("caller went away", propagated?.message)
    }

    @Test
    fun `addFlag traces both outcomes so a detached mutation is never silent (#1144)`() = runTest {
        // The callers are detached now (`awaitDetached`), so a back press can leave the write running
        // with no ViewModel left to raise a snackbar. DiagnosticsLog — surfaced by the in-app viewer —
        // is what keeps the mutation from succeeding, or failing, without a trace.
        val diagnostics = DiagnosticsLog()
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.addFlag(any()) } throws SessionExpiredException("https://forum.hardware.fr/login.php")
        val repo = buildRepository(
            apiClient = mockk(relaxed = true),
            forumRepository = stubForumRepository(sampleCategories),
            hfrClient = hfrClient,
            diagnostics = diagnostics,
        )

        assertTrue(repo.addFlag(sampleFlagAddContext()).isFailure)

        val traced = diagnostics.entries.value.filter { it.tag == "FlagRepository" }
        assertTrue(
            "a failed favourite must leave a WARN trace, got $traced",
            traced.any { it.level == DiagnosticsLog.Level.WARN && it.message.startsWith("addflag FAILED topic=35395") },
        )
    }

    @Test
    fun `removeFlag success drops the item from the exposed cache and Room`() = runTest {
        // Seed the in-memory cache with the captured cat23 participated flag (topic 35395).
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        val flagDao = stubFlagDao()
        val hfrClient = mockk<HfrClient>()
        // delflag.php success page carries the confirmation sentence. The full captured HTML
        // shape is pinned by FlagDeleteResponseParserTest in :core:parser ; here we only need
        // the success marker so the repository takes its success branch.
        coEvery {
            hfrClient.removeFlag(cat = 23, subcat = any(), topicId = 35395, type = FlagType.CYAN, page = any())
        } returns DELETE_SUCCESS_HTML
        val repo = buildRepository(apiClient, forumRepository, flagDao = flagDao, hfrClient = hfrClient)

        repo.observe(FlagType.CYAN).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val seeded = awaitItem() as FlagsResult.Success
            val flag = seeded.flags.single { it.topicId == 35395 }

            val result = repo.removeFlag(flag)
            assertTrue("expected success, got $result", result.isSuccess)

            // The repository re-broadcasts the trimmed list to active observers.
            val updated = awaitItem() as FlagsResult.Success
            assertTrue("topic 35395 must be gone", updated.flags.none { it.topicId == 35395 })
            cancelAndIgnoreRemainingEvents()
        }

        // Room row evicted with the logical key (userId, type, cat, topicId).
        coVerify {
            flagDao.deleteFlag(userId = "xat", type = FlagType.CYAN, cat = 23, topicId = 35395)
        }
    }

    @Test
    fun `removeFlag fences a captured refresh out of Room memory and emissions - 984`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var deleted = false
        var roomRows = emptyList<FlagTopicEntity>()
        val writesAfterDeletion = mutableListOf<List<FlagTopicEntity>>()
        val api = mockk<HfrApiClient>()
        coEvery {
            api.getCategoryFlagTopics(cat = 23, bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true)
        } coAnswers {
            val snapshot = if (deleted) EMPTY_PAGE else capturedParticipatedFixture
            if (++calls == 2) {
                entered.complete(Unit)
                // Keep the captured response until after deletion, even if F3 cancels its fetch.
                withContext(NonCancellable) { release.await() }
            }
            snapshot
        }
        coEvery {
            api.getTopicList(cat = 23, subcat = null, page = 1, resultsPerPage = any(), useAuth = true)
        } returns EMPTY_PAGE
        val dao = stubFlagDao()
        coEvery { dao.replaceSnapshot("xat", FlagType.CYAN, any()) } coAnswers {
            roomRows = thirdArg<FlagCacheSnapshot>().rows
            if (deleted) writesAfterDeletion += roomRows
        }
        coEvery { dao.deleteFlag("xat", FlagType.CYAN, 23, 35395) } coAnswers {
            roomRows = roomRows.filterNot { it.cat == 23 && it.topicId == 35395 }
        }
        val hfr = mockk<HfrClient>()
        coEvery {
            hfr.removeFlag(cat = 23, subcat = any(), topicId = 35395, type = FlagType.CYAN, page = any())
        } coAnswers {
            deleted = true
            DELETE_SUCCESS_HTML
        }
        val repo = buildRepository(api, stubForumRepository(sampleCategories.takeLast(1)),
            flagDao = dao, hfrClient = hfr)

        repo.observe(FlagType.CYAN).test {
            val pull = launch(start = CoroutineStart.LAZY) { repo.refresh(FlagType.CYAN) }
            try {
                assertEquals(FlagsResult.Loading, awaitItem())
                val flag = (awaitItem() as FlagsResult.Success).flags.single()
                assertEquals(35395, roomRows.single().topicId)
                pull.start()
                entered.await()
                assertEquals(FlagsResult.Loading, awaitItem())
                assertTrue(repo.removeFlag(flag).isSuccess)
                assertTrue((awaitItem() as FlagsResult.Success).flags.isEmpty())
                assertTrue(roomRows.isEmpty())
                assertNull(repo.findCachedFlag(23, 35395))

                release.complete(Unit)
                pull.join()
                assertTrue((awaitItem() as FlagsResult.Success).flags.isEmpty())
                expectNoEvents()
                assertTrue(roomRows.isEmpty())
                assertNull(repo.findCachedFlag(23, 35395))
                assertTrue((repo.observe(FlagType.CYAN).first() as FlagsResult.Success).flags.isEmpty())
                assertEquals(3, calls)
                assertEquals(1, writesAfterDeletion.size)
                assertTrue(writesAfterDeletion.single().isEmpty())
                cancelAndIgnoreRemainingEvents()
            } finally {
                release.complete(Unit)
                pull.cancel()
            }
        }
    }

    @Test
    fun `removeFlag invalidates a captured fetch even before the cache is warm - 984`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val api = mockk<HfrApiClient>()
        coEvery {
            api.getCategoryFlagTopics(cat = 23, bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true)
        } coAnswers {
            if (++calls == 1) {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                capturedParticipatedFixture
            } else {
                EMPTY_PAGE
            }
        }
        coEvery {
            api.getTopicList(cat = 23, subcat = null, page = 1, resultsPerPage = any(), useAuth = true)
        } returns EMPTY_PAGE
        val hfr = mockk<HfrClient>()
        coEvery {
            hfr.removeFlag(cat = 23, subcat = any(), topicId = 35395, type = FlagType.CYAN, page = any())
        } returns DELETE_SUCCESS_HTML
        val dao = stubFlagDao()
        val repo = buildRepository(api, stubForumRepository(sampleCategories.takeLast(1)),
            flagDao = dao, hfrClient = hfr)

        repo.observe(FlagType.CYAN).test {
            try {
                assertEquals(FlagsResult.Loading, awaitItem())
                entered.await()
                assertTrue(repo.removeFlag(sampleFlag()).isSuccess)
                release.complete(Unit)
                assertTrue((awaitItem() as FlagsResult.Success).flags.isEmpty())
                expectNoEvents()
                assertNull(repo.findCachedFlag(23, 35395))
                coVerify(exactly = 1) { dao.replaceSnapshot("xat", FlagType.CYAN, rowsMatching { it.isEmpty() }) }
                coVerify(exactly = 0) { dao.replaceSnapshot("xat", FlagType.CYAN, rowsMatching { it.isNotEmpty() }) }
                assertEquals(2, calls)
                cancelAndIgnoreRemainingEvents()
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun `removeFlag waits for an ongoing Room commit before purging its row - 984`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val accepted = CompletableDeferred<Unit>()
        var deleted = false
        var roomRows = emptyList<FlagTopicEntity>()
        val api = mockk<HfrApiClient>()
        coEvery {
            api.getCategoryFlagTopics(cat = 23, bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true)
        } coAnswers { if (deleted) EMPTY_PAGE else capturedParticipatedFixture }
        coEvery {
            api.getTopicList(cat = 23, subcat = null, page = 1, resultsPerPage = any(), useAuth = true)
        } returns EMPTY_PAGE
        val dao = stubFlagDao()
        coEvery { dao.replaceSnapshot("xat", FlagType.CYAN, any()) } coAnswers {
            entered.complete(Unit)
            // Model a write that cannot be interrupted after it has begun committing.
            withContext(NonCancellable) { release.await() }
            roomRows = thirdArg<FlagCacheSnapshot>().rows
        }
        coEvery { dao.deleteFlag("xat", FlagType.CYAN, 23, 35395) } coAnswers {
            roomRows = roomRows.filterNot { it.topicId == 35395 }
        }
        val hfr = mockk<HfrClient>()
        coEvery {
            hfr.removeFlag(cat = 23, subcat = any(), topicId = 35395, type = FlagType.CYAN, page = any())
        } coAnswers {
            deleted = true
            accepted.complete(Unit)
            DELETE_SUCCESS_HTML
        }
        val repo = buildRepository(api, stubForumRepository(sampleCategories.takeLast(1)),
            flagDao = dao, hfrClient = hfr)
        val initial = async { repo.observe(FlagType.CYAN).first { it is FlagsResult.Success } }
        entered.await()
        val removal = async { repo.removeFlag(sampleFlag()) }
        try {
            accepted.await()
            runCurrent()
            assertTrue("removal must wait for the older Room commit", !removal.isCompleted)
            coVerify(exactly = 0) { dao.deleteFlag(any(), any(), any(), any()) }
            release.complete(Unit)
            assertTrue(removal.await().isSuccess)
            initial.await()
            assertTrue(roomRows.isEmpty())
            assertNull(repo.findCachedFlag(23, 35395))
            coVerify(exactly = 1) { dao.deleteFlag("xat", FlagType.CYAN, 23, 35395) }
        } finally {
            release.complete(Unit)
            initial.cancel()
            removal.cancel()
        }
    }

    @Test
    fun `removeFlag failure touches no cache and returns failure`() = runTest {
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.FAVORITES, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.FAVORITES, capturedParticipatedFixture)
        }
        val flagDao = stubFlagDao()
        val hfrClient = mockk<HfrClient>()
        // delflag.php "already removed" page does NOT carry the success sentence (the real
        // capture shows « Aucun favori n'est repertorié », pinned in :core:parser).
        coEvery {
            hfrClient.removeFlag(cat = any(), subcat = any(), topicId = any(), type = any(), page = any())
        } returns DELETE_FAILURE_HTML
        val repo = buildRepository(apiClient, forumRepository, flagDao = flagDao, hfrClient = hfrClient)

        repo.observe(FlagType.FAVORITE).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val seeded = awaitItem() as FlagsResult.Success
            val flag = seeded.flags.single()

            val result = repo.removeFlag(flag)
            assertTrue("expected failure, got $result", result.isFailure)

            // No re-broadcast: the next awaitItem would time out, so we assert no further
            // emission via expectNoEvents.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        // No Room eviction on failure.
        coVerify(exactly = 0) {
            flagDao.deleteFlag(userId = any(), type = any(), cat = any(), topicId = any())
        }
    }

    @Test
    fun `removeFlag session expiry touches no cache and returns failure`() = runTest {
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        val flagDao = stubFlagDao()
        val hfrClient = mockk<HfrClient>()
        val expired = SessionExpiredException("https://forum.hardware.fr/login.php")
        coEvery {
            hfrClient.removeFlag(cat = any(), subcat = any(), topicId = any(), type = any(), page = any())
        } throws expired
        val repo = buildRepository(apiClient, forumRepository, flagDao = flagDao, hfrClient = hfrClient)

        repo.observe(FlagType.CYAN).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val seeded = awaitItem() as FlagsResult.Success
            val flag = seeded.flags.single()

            val result = repo.removeFlag(flag)
            assertTrue("expected failure, got $result", result.isFailure)
            assertTrue(result.exceptionOrNull() is SessionExpiredException)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) {
            flagDao.deleteFlag(userId = any(), type = any(), cat = any(), topicId = any())
        }
    }

    @Test
    fun `removeFlag fails fast when anonymous without hitting the network`() = runTest {
        val apiClient = mockk<HfrApiClient>(relaxed = true)
        val hfrClient = mockk<HfrClient>(relaxed = true)
        val anonymousAuth = mockk<AuthRepository>()
        every { anonymousAuth.observeAuthState() } returns flowOf(AuthState.Anonymous)
        val repo = buildRepository(
            apiClient = apiClient,
            forumRepository = stubForumRepository(sampleCategories),
            authRepository = anonymousAuth,
            hfrClient = hfrClient,
        )

        val result = repo.removeFlag(sampleFlag())
        assertTrue("anonymous removeFlag must fail", result.isFailure)
        coVerify(exactly = 0) {
            hfrClient.removeFlag(cat = any(), subcat = any(), topicId = any(), type = any(), page = any())
        }
    }

    // ─── findFlag (#809) ──────────────────────────────────────────────────────────

    @Test
    fun `findFlag returns a warm cached flag without any further network - 809`() = runTest {
        // Only the CYAN (participated) bucket is stubbed; warming it seeds topic 35395 (cat 23).
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        val repo = buildRepository(apiClient, forumRepository)
        repo.observe(FlagType.CYAN).first { it is FlagsResult.Success }

        val found = repo.findFlag(cat = 23, topicId = 35395)
        assertEquals(35395, (found as FlagLookup.Found).flag.topicId)
        assertEquals(FlagType.CYAN, (found as FlagLookup.Found).flag.type)

        // A memory hit fires NO fallback fan-out: the RED / FAVORITE buckets were never queried.
        coVerify(exactly = 0) {
            apiClient.getCategoryFlagTopics(
                cat = any(), bucket = HfrRestFlagBucket.READ, page = any(), resultsPerPage = any(), useAuth = any(),
            )
        }
        coVerify(exactly = 0) {
            apiClient.getCategoryFlagTopics(
                cat = any(),
                bucket = HfrRestFlagBucket.FAVORITES,
                page = any(),
                resultsPerPage = any(),
                useAuth = any(),
            )
        }
    }

    @Test
    fun `findCachedFlag scans warm caches without fetching cold buckets - 737`() = runTest {
        // Legacy Super favorites only know topicId. They may be resolved from already-warm memory,
        // but must not fan out RED/FAVORITES buckets just to decorate a local snapshot list.
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        val repo = buildRepository(apiClient, forumRepository)
        repo.observe(FlagType.CYAN).first { it is FlagsResult.Success }

        val found = repo.findCachedFlag(topicId = 35395)
        assertEquals(35395, found?.topicId)
        assertEquals(23, found?.cat)

        coVerify(exactly = 0) {
            apiClient.getCategoryFlagTopics(
                cat = any(), bucket = HfrRestFlagBucket.READ, page = any(), resultsPerPage = any(), useAuth = any(),
            )
        }
        coVerify(exactly = 0) {
            apiClient.getCategoryFlagTopics(
                cat = any(),
                bucket = HfrRestFlagBucket.FAVORITES,
                page = any(),
                resultsPerPage = any(),
                useAuth = any(),
            )
        }
    }

    @Test
    fun `findFlag resolves a cold FAVORITE behind a warm CYAN miss - 809`() = runTest {
        // Review finding #809 — the Drapeaux screen warms ONE tab at a time : a warm-but-missing
        // CYAN bucket says nothing about a never-loaded FAVORITE bucket. The cold buckets — and
        // only those — are fetched, so a FAVORITE-only topic stays removable from the topic screen.
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(13, HfrRestFlagBucket.READ, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.READ, EMPTY_PAGE)
            stubFlagsCall(13, HfrRestFlagBucket.FAVORITES, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.FAVORITES, capturedParticipatedFixture)
        }
        val repo = buildRepository(apiClient, forumRepository)
        repo.observe(FlagType.CYAN).first { it is FlagsResult.Success } // warms CYAN only

        val found = repo.findFlag(cat = 23, topicId = 35395)
        assertEquals(35395, (found as FlagLookup.Found).flag.topicId)
        assertEquals(FlagType.FAVORITE, (found as FlagLookup.Found).flag.type)

        // The warm CYAN bucket was authoritative for its own type : never implicitly re-fetched.
        coVerify(exactly = 1) {
            apiClient.getCategoryFlagTopics(
                cat = 23,
                bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(),
                resultsPerPage = any(),
                useAuth = any(),
            )
        }
    }

    @Test
    fun `findFlag misses with zero network when all three buckets are warm - 809`() = runTest {
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
            stubFlagsCall(13, HfrRestFlagBucket.READ, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.READ, EMPTY_PAGE)
            stubFlagsCall(13, HfrRestFlagBucket.FAVORITES, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.FAVORITES, EMPTY_PAGE)
        }
        val repo = buildRepository(apiClient, forumRepository)
        FlagType.entries.forEach { type ->
            repo.observe(type).first { it is FlagsResult.Success }
        }

        // Every bucket warm + miss → null with ZERO additional network (one observe fetch per
        // bucket per cat, nothing more — the Drapeaux view owns refresh policy).
        val found = repo.findFlag(cat = 23, topicId = 999_999)
        assertEquals(FlagLookup.Absent, found)
        HfrRestFlagBucket.entries.forEach { bucket ->
            coVerify(exactly = 1) {
                apiClient.getCategoryFlagTopics(
                    cat = 23, bucket = bucket, page = any(), resultsPerPage = any(), useAuth = any(),
                )
            }
        }
    }

    @Test
    fun `findFlag fans out the three buckets and hits when no cache is warm - 809`() = runTest {
        // Cold caches: every bucket of every cat must be stubbed since the fallback fans all three out.
        // The target 35395 lives in the FAVORITES bucket, so the re-scan resolves it as a FAVORITE.
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(13, HfrRestFlagBucket.READ, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.READ, EMPTY_PAGE)
            stubFlagsCall(13, HfrRestFlagBucket.FAVORITES, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.FAVORITES, capturedParticipatedFixture)
        }
        val repo = buildRepository(apiClient, forumRepository)

        val found = repo.findFlag(cat = 23, topicId = 35395)
        assertEquals(35395, (found as FlagLookup.Found).flag.topicId)
        assertEquals(FlagType.FAVORITE, (found as FlagLookup.Found).flag.type)

        // The fan-out actually queried the FAVORITES bucket that held the match.
        coVerify {
            apiClient.getCategoryFlagTopics(
                cat = 23, bucket = HfrRestFlagBucket.FAVORITES, page = any(), resultsPerPage = any(), useAuth = true,
            )
        }
    }

    @Test
    fun `findFlag tie-breaks a multi-bucket topic to the earliest FlagType - 809`() = runTest {
        // Gate Codex #809 — topic 35395 warm in BOTH the CYAN and FAVORITE buckets : the EnumMap
        // iteration (CYAN → RED → FAVORITE) must resolve it deterministically to CYAN, the type
        // `removeFlag` will key `delflag.php` on.
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
            stubFlagsCall(13, HfrRestFlagBucket.FAVORITES, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.FAVORITES, capturedParticipatedFixture)
        }
        val repo = buildRepository(apiClient, forumRepository)
        repo.observe(FlagType.CYAN).first { it is FlagsResult.Success }
        repo.observe(FlagType.FAVORITE).first { it is FlagsResult.Success }

        val found = repo.findFlag(cat = 23, topicId = 35395)
        assertEquals(35395, (found as FlagLookup.Found).flag.topicId)
        assertEquals(FlagType.CYAN, (found as FlagLookup.Found).flag.type)
    }

    @Test
    fun `findFlag returns null for an anonymous session without hitting the network - 809`() = runTest {
        val apiClient = mockk<HfrApiClient>(relaxed = true)
        val anonymousAuth = mockk<AuthRepository>()
        every { anonymousAuth.observeAuthState() } returns flowOf(AuthState.Anonymous)
        val repo = buildRepository(
            apiClient = apiClient,
            forumRepository = stubForumRepository(sampleCategories),
            authRepository = anonymousAuth,
        )

        // Cold cache + anonymous → short-circuit to null before any REST round-trip.
        val found = repo.findFlag(cat = 23, topicId = 35395)
        assertEquals(FlagLookup.Absent, found)
        coVerify(exactly = 0) {
            apiClient.getCategoryFlagTopics(
                cat = any(), bucket = any(), page = any(), resultsPerPage = any(), useAuth = any(),
            )
        }
    }

    /** Mocked clients feed the same safe transport boundary as the EventListener, without real IO. */
    private fun diagnosticApi(failSticky: Boolean = false): HfrApiClient {
        val api = mockk<HfrApiClient>()
        val sequence = AtomicLong()
        coEvery {
            api.getCategoryFlagTopics(cat = any(), bucket = HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true)
        } coAnswers {
            val cat = firstArg<Int>()
            recordDiagnosticCall(sequence.incrementAndGet(), cat, sticky = false, failed = false)
            if (cat == 23) capturedParticipatedFixture else EMPTY_PAGE
        }
        coEvery {
            api.getTopicList(cat = any(), subcat = null, page = 1, resultsPerPage = any(), useAuth = true)
        } coAnswers {
            val cat = firstArg<Int>()
            val failed = failSticky && cat == 13
            recordDiagnosticCall(sequence.incrementAndGet(), cat, sticky = true, failed = failed)
            if (failed) throw IOException("secret cookie")
            EMPTY_PAGE
        }
        return api
    }

    private fun assertCancellation(expected: CancellationException, actual: Throwable?) {
        // Coroutine stack recovery may copy the exception across the detached Deferred boundary.
        assertTrue("CancellationException must propagate, got $actual", actual is CancellationException)
        assertEquals(expected.message, actual?.message)
    }

    private suspend fun recordDiagnosticCall(
        id: Long,
        cat: Int,
        sticky: Boolean,
        failed: Boolean,
        cancelled: Boolean = false,
    ) {
        val trace = requireNotNull(currentCoroutineContext()[FlagFetchTrace])
        val family = if (sticky) HfrEndpoint.Family.LAST else when (trace.type) {
            FlagType.CYAN -> HfrEndpoint.Family.PARTICIPATED
            FlagType.RED -> HfrEndpoint.Family.READ
            FlagType.FAVORITE -> HfrEndpoint.Family.FAVORITES
        }
        trace.call(HfrCallSummary(
            id = id,
            endpoint = HfrEndpoint(family, cat, 1),
            role = HfrClientRole.AUTH, refreshId = trace.refreshId,
            phase = if (sticky) FlagRefreshPhase.STICKIES else FlagRefreshPhase.BUCKETS,
            waitMs = 0, dnsMs = 0, connectMs = 0, tlsMs = 0, headersSentToReceivedMs = 10, bodyMs = 0, totalMs = 10,
            status = if (cancelled) null else if (failed) 503 else 200,
            protocol = "h2", reusedConnection = true, inFlightAtStart = 1,
            outcome = when {
                cancelled -> HfrCallOutcome.CANCELLED
                failed -> HfrCallOutcome.FAILURE
                else -> HfrCallOutcome.OK
            },
            exceptionClass = if (cancelled) "CancellationException" else if (failed) "IOException" else null,
        ))
    }

    @Test
    fun `collection publishes complete buckets at twelve seconds and retains unfinished cached categories - 984`() =
        runTest {
            val (api, forum) = wireDeps {
                stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
                stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
            }
            val monotonic = mockk<FlagReadClock>()
            every { monotonic.nowNanos() } answers { testScheduler.currentTime * 1_000_000 }
            val repo = buildRepository(api, forum, flagReadClock = monotonic,
                testDispatcher = UnconfinedTestDispatcher(testScheduler))
            val old = repo.refresh(FlagType.CYAN) as FlagsResult.Success
            coEvery {
                api.getCategoryFlagTopics(23, HfrRestFlagBucket.PARTICIPATED,
                    page = any(), resultsPerPage = any(), useAuth = true)
            } coAnswers { awaitCancellation() }
            val refresh = async { repo.refresh(FlagType.CYAN) }
            advanceTimeBy(11_999)
            assertFalse(refresh.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(refresh.isCompleted)
            val result = refresh.await() as FlagsResult.Success
            assertEquals(12_000L, testScheduler.currentTime)
            assertEquals(old.flags, result.flags)
            assertEquals(FlagRefreshStatus.PARTIAL, result.refreshStatus)
            assertEquals(null, result.coverage.categories.single {
                it.cat == 13 && it.source == FlagSource.BUCKET
            }.failure)
            assertTrue(result.coverage.categories.filter { it.cat == 23 }
                .all { it.failure == FlagFailureKind.DEADLINE })
        }

    @Test
    fun `sticky sweep is cancelled at the deadline while completed sticky partitions survive - 984`() = runTest {
        val (api, forum) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        val monotonic = mockk<FlagReadClock>()
        every { monotonic.nowNanos() } answers { testScheduler.currentTime * 1_000_000 }
        val repo = buildRepository(api, forum, flagReadClock = monotonic,
                testDispatcher = UnconfinedTestDispatcher(testScheduler))
        repo.refresh(FlagType.CYAN)
        var cancelled = false
        coEvery {
            api.getTopicList(23, null, page = 1, resultsPerPage = any(), useAuth = true)
        } coAnswers {
            try { awaitCancellation() } finally { cancelled = true }
        }
        val refresh = async { repo.refresh(FlagType.CYAN) }
        advanceTimeBy(12_000)
        runCurrent()
        assertTrue(refresh.isCompleted)
        val result = refresh.await() as FlagsResult.Success
        assertTrue(cancelled)
        assertEquals(FlagFailureKind.DEADLINE, result.coverage.categories.single {
            it.cat == 23 && it.source == FlagSource.STICKY
        }.failure)
        assertEquals(null, result.coverage.categories.single {
            it.cat == 13 && it.source == FlagSource.STICKY
        }.failure)
        assertEquals(FlagRefreshStatus.PARTIAL, result.refreshStatus)
    }

    @Test
    fun `catalogue deadline retains the whole previous snapshot and marks its categories - 984`() = runTest {
        val (api, forum) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        val monotonic = mockk<FlagReadClock>()
        every { monotonic.nowNanos() } answers { testScheduler.currentTime * 1_000_000 }
        val repo = buildRepository(api, forum, flagReadClock = monotonic,
            testDispatcher = UnconfinedTestDispatcher(testScheduler))
        val old = repo.refresh(FlagType.CYAN) as FlagsResult.Success
        coEvery { forum.getCategories(any()) } coAnswers { awaitCancellation() }
        val refresh = async { repo.refresh(FlagType.CYAN) }
        advanceTimeBy(12_000)
        runCurrent()
        assertTrue(refresh.isCompleted)
        val result = refresh.await() as FlagsResult.Success
        assertEquals(old.flags, result.flags)
        assertTrue(result.coverage.categories.all { it.failure == FlagFailureKind.DEADLINE })
        assertEquals(FlagRefreshStatus.FAILED, result.refreshStatus)
    }

    @Test
    fun `last consumer cancellation stops detached sticky sweep without publishing a failure - 984`() = runTest {
        val (api, forum) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        var started = 0
        var cancelled = 0
        coEvery {
            api.getTopicList(any(), null, page = 1, resultsPerPage = any(), useAuth = true)
        } coAnswers {
            started++
            try { awaitCancellation() } finally { cancelled++ }
        }
        val repo = buildRepository(api, forum, testDispatcher = UnconfinedTestDispatcher(testScheduler))
        val first = async { repo.refresh(FlagType.CYAN) }
        val second = async { repo.refresh(FlagType.CYAN) }
        runCurrent()
        assertEquals(2, started)
        first.cancel()
        runCurrent()
        assertEquals(0, cancelled)
        second.cancel()
        runCurrent()
        assertEquals(2, cancelled)
    }

    @Test
    fun `joined caller cannot extend admitted collection deadline - 984`() = runTest {
        for (joinDelay in listOf(0L, 10_000L)) {
            val (api, forum) = wireDeps {
                stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
                stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
            }
            val monotonic = mockk<FlagReadClock>()
            every { monotonic.nowNanos() } answers { testScheduler.currentTime * 1_000_000 }
            val repo = buildRepository(api, forum, flagReadClock = monotonic,
                    testDispatcher = UnconfinedTestDispatcher(testScheduler))
            coEvery {
                api.getTopicList(any(), null, page = 1, resultsPerPage = any(), useAuth = true)
            } coAnswers { awaitCancellation() }
            val startedAt = testScheduler.currentTime
            val first = async { repo.refresh(FlagType.CYAN) }
            advanceTimeBy(joinDelay)
            val joined = async { repo.refresh(FlagType.CYAN) }
            advanceTimeBy(12_000 - joinDelay)
            runCurrent()
            assertTrue(first.isCompleted)
            assertTrue(joined.isCompleted)
            assertEquals(startedAt + 12_000L, testScheduler.currentTime)
            assertEquals((first.await() as FlagsResult.Success).coverage,
                (joined.await() as FlagsResult.Success).coverage)
        }
    }

    @Test
    fun `joined caller keeps original budget when its predecessor leaves and generation changes - 984`() = runTest {
        val (api, forum) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
            stubFlagsCall(13, HfrRestFlagBucket.READ, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.READ, EMPTY_PAGE)
        }
        val monotonic = mockk<FlagReadClock>()
        every { monotonic.nowNanos() } answers { testScheduler.currentTime * 1_000_000 }
        val repo = buildRepository(api, forum, flagReadClock = monotonic,
            testDispatcher = UnconfinedTestDispatcher(testScheduler))
        repo.refresh(FlagType.CYAN)
        coEvery {
            api.getCategoryFlagTopics(23, HfrRestFlagBucket.PARTICIPATED,
                page = any(), resultsPerPage = any(), useAuth = true)
        } coAnswers { awaitCancellation() }
        val first = async { repo.refresh(FlagType.CYAN) }
        advanceTimeBy(5_000)
        val joined = async { repo.refresh(FlagType.CYAN) }
        runCurrent()
        first.cancel()
        runCurrent()
        advanceTimeBy(5_000)
        repo.refresh(FlagType.RED) // Fences CYAN; its remaining caller must retain the original 12 s.
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(joined.isCompleted)
        assertEquals(12_000L, testScheduler.currentTime)
        assertEquals(FlagRefreshStatus.PARTIAL, (joined.await() as FlagsResult.Success).refreshStatus)
    }

    @Test
    fun `shared sticky deadline preserves a later consumers completed buckets - 984`() = runTest {
        val (api, forum) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
            stubFlagsCall(13, HfrRestFlagBucket.READ, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.READ, capturedParticipatedFixture)
        }
        val monotonic = mockk<FlagReadClock>()
        every { monotonic.nowNanos() } answers { testScheduler.currentTime * 1_000_000 }
        val dao = stubFlagDao()
        val repo = buildRepository(api, forum, flagDao = dao, flagReadClock = monotonic,
            testDispatcher = UnconfinedTestDispatcher(testScheduler))
        var cancelled = false
        coEvery { api.getTopicList(23, null, 1, any(), true) } coAnswers {
            try { awaitCancellation() } finally { cancelled = true }
        }
        // Observations share the generation without a pull fencing the earlier consumer.
        val first = async { repo.observe(FlagType.CYAN).first { it is FlagsResult.Success } }
        runCurrent()
        advanceTimeBy(4_000)
        val later = async { repo.observe(FlagType.RED).first { it is FlagsResult.Success } }
        runCurrent()
        advanceTimeBy(8_000)
        runCurrent()
        assertTrue(first.isCompleted)
        assertTrue("a shared deadline must finish the later refresh without cancelling it", later.isCompleted)
        val result = later.await() as FlagsResult.Success
        assertEquals(12_000L, testScheduler.currentTime)
        assertTrue(cancelled)
        assertEquals(FlagRefreshStatus.PARTIAL, result.refreshStatus)
        assertEquals(35395, result.flags.single().topicId)
        assertTrue(result.coverage.categories.filter { it.source == FlagSource.BUCKET }
            .all { it.failure == null && it.lastSuccessAt != null })
        assertEquals(FlagFailureKind.DEADLINE, result.coverage.categories.single {
            it.cat == 23 && it.source == FlagSource.STICKY
        }.failure)
        assertNull(result.coverage.categories.single { it.cat == 13 && it.source == FlagSource.STICKY }.failure)
        val disk = dao.getSnapshot("xat", FlagType.RED)
        assertEquals(35395, disk.rows.single().topicId)
        assertEquals(result.coverage.categories.map { it.failure }, disk.coverage.map { it.failure })
        coVerify(exactly = 1) { dao.replaceSnapshot("xat", FlagType.RED, any()) }
        coVerify(exactly = 1) { api.getTopicList(23, null, 1, any(), true) }
        first.await()
    }

    @Test
    fun `observation deadline leaves a newer pull to publish without a transient failure - 984`() = runTest {
        val (api, forum) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        val monotonic = mockk<FlagReadClock>()
        every { monotonic.nowNanos() } answers { testScheduler.currentTime * 1_000_000 }
        val dao = stubFlagDao()
        val diagnostics = DiagnosticsLog()
        // A queued dispatcher lets the pull start its replacement before the observer rejoins.
        val repo = buildRepository(api, forum, flagDao = dao, flagReadClock = monotonic, diagnostics = diagnostics,
            testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        coEvery { api.getTopicList(23, null, 1, any(), true) } coAnswers { awaitCancellation() }
        val observation = async { repo.observe(FlagType.CYAN).first { it !is FlagsResult.Loading } }
        runCurrent()
        advanceTimeBy(10_000)
        val pull = async { repo.refresh(FlagType.CYAN) }
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse("the observer must not receive a failure while the replacement is in flight",
            observation.isCompleted)
        val summary = diagnostics.entries.value.single { it.message.startsWith("refresh=1 types=CYAN") }.message
        assertTrue("the original wait must finish at t=12s without failure: $summary", summary.contains("exception=-"))
        assertFalse("the newer pull still owns its independent budget", pull.isCompleted)
        coVerify(exactly = 0) { dao.replaceSnapshot("xat", FlagType.CYAN, any()) }
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(pull.isCompleted)
        val result = pull.await() as FlagsResult.Success
        assertEquals(FlagRefreshStatus.PARTIAL, result.refreshStatus)
        assertEquals(result, observation.await())
        assertEquals(22_000L, testScheduler.currentTime)
        coVerify(exactly = 1) { dao.replaceSnapshot("xat", FlagType.CYAN, any()) }
    }

    @Test
    fun `observation deadline keeps stale cache while a newer pull is in flight - 984`() = runTest {
        val (api, forum) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        val monotonic = mockk<FlagReadClock>()
        every { monotonic.nowNanos() } answers { testScheduler.currentTime * 1_000_000 }
        val dao = stubFlagDao()
        val stale = Instant.parse("2026-05-03T11:00:00Z")
        coEvery { dao.getFlags("xat", FlagType.CYAN) } returns listOf(
            flagEntity(FlagType.CYAN, 999, "Old row", stale),
        )
        val diagnostics = DiagnosticsLog()
        val repo = buildRepository(api, forum, flagDao = dao, flagReadClock = monotonic, diagnostics = diagnostics,
            testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        coEvery { api.getTopicList(23, null, 1, any(), true) } coAnswers { awaitCancellation() }
        repo.observe(FlagType.CYAN).test {
            val cached = awaitItem() as FlagsResult.Success
            assertEquals(999, cached.flags.single().topicId)
            advanceTimeBy(10_000)
            val pull = async { repo.refresh(FlagType.CYAN) }
            runCurrent()
            assertEquals(FlagsResult.Loading, awaitItem())
            advanceTimeBy(2_000)
            runCurrent()
            val summary = diagnostics.entries.value.single { it.message.startsWith("refresh=1 types=CYAN") }.message
            assertTrue("the cache fallback must finish without failure: $summary", summary.contains("exception=-"))
            expectNoEvents()
            assertFalse(pull.isCompleted)
            assertEquals(cached, repo.observe(FlagType.CYAN).first())
            coVerify(exactly = 0) { dao.replaceSnapshot("xat", FlagType.CYAN, any()) }
            advanceTimeBy(10_000)
            runCurrent()
            val result = pull.await() as FlagsResult.Success
            assertEquals(setOf(999, 35395), result.flags.map { it.topicId }.toSet())
            assertEquals(result, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `catalogue owner inherits read session while resolveFavorite stays outside - 984`() = runTest {
        val (api, forum) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
            stubFlagsCall(23, HfrRestFlagBucket.FAVORITES, EMPTY_PAGE)
        }
        coEvery { forum.getCategories(any()) } coAnswers {
            assertTrue(currentCoroutineContext()[FlagReadSession] != null)
            ForumResult.Success(sampleCategories)
        }
        coEvery {
            api.getCategoryFlagTopics(23, HfrRestFlagBucket.FAVORITES,
                page = any(), resultsPerPage = any(), useAuth = true)
        } coAnswers {
            assertNull(currentCoroutineContext()[FlagReadSession])
            EMPTY_PAGE
        }
        val repo = buildRepository(api, forum)
        repo.refresh(FlagType.CYAN)
        coEvery {
            api.getTopicList(23, null, page = 1, resultsPerPage = any(), useAuth = true)
        } coAnswers {
            assertNull(currentCoroutineContext()[FlagReadSession])
            EMPTY_PAGE
        }
        val menuRepo = buildRepository(api, forum)
        assertEquals(false, menuRepo.resolveFavorite(23, 999).getOrThrow())
    }

    private fun sampleFlag(type: FlagType = FlagType.CYAN, topicId: Int = 35395): Flag = Flag(
        cat = 23,
        subcat = 550,
        topicId = topicId,
        title = "Redface 2",
        totalPages = 10,
        replyCount = 42,
        type = type,
        hasUnread = true,
        lastReadPage = 7,
        lastPostReadId = 1234L,
        firstPostAuthor = "XaT",
        lastReplyAuthor = "XaTelitte",
        lastReplyAt = "2026-05-03 12:00",
    )

    private fun stubForumRepository(categories: List<Category>): ForumRepository {
        val repo = mockk<ForumRepository>()
        coEvery { repo.observeCategories() } returns flowOf(ForumResult.Success(categories))
        coEvery { repo.getCategories(any()) } returns ForumResult.Success(categories)
        return repo
    }

    private fun wireDeps(
        configure: ApiStubScope.() -> Unit,
    ): Pair<HfrApiClient, ForumRepository> {
        val apiClient = mockk<HfrApiClient>()
        // A happy bucket fixture also needs a successful (possibly empty) sticky supplement.
        // Otherwise an unstubbed call now correctly adds an incomplete-list issue (#984).
        coEvery {
            apiClient.getTopicList(cat = any(), subcat = null, page = 1, resultsPerPage = any(), useAuth = true)
        } returns EMPTY_PAGE
        ApiStubScope(apiClient).configure()
        return apiClient to stubForumRepository(sampleCategories)
    }

    private class ApiStubScope(val apiClient: HfrApiClient) {
        fun stubFlagsCall(cat: Int, bucket: HfrRestFlagBucket, body: String) {
            coEvery {
                apiClient.getCategoryFlagTopics(
                    cat = cat,
                    bucket = bucket,
                    page = any(),
                    resultsPerPage = any(),
                    useAuth = true,
                )
            } returns body
        }
    }

    @Suppress("LongParameterList") // Test factory : each dep has a default so call-sites stay terse.
    private fun buildRepository(
        apiClient: HfrApiClient,
        forumRepository: ForumRepository,
        authRepository: AuthRepository = stubAuthRepository(),
        flagDao: FlagDao = stubFlagDao(),
        clock: Clock = Clock.fixed(Instant.parse("2026-05-03T12:00:00Z"), ZoneOffset.UTC),
        hfrClient: HfrClient = mockk(relaxed = true),
        diagnostics: DiagnosticsLog = DiagnosticsLog(),
        flagReadClock: FlagReadClock = FlagReadClock(),
        testDispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
        policy: kotlinx.coroutines.flow.StateFlow<FlagsRefreshPolicy> =
            kotlinx.coroutines.flow.MutableStateFlow(FlagsRefreshPolicy()),
    ): DefaultFlagRepository = DefaultFlagRepository(
        apiClient = apiClient,
        hfrClient = hfrClient,
        flagAddResponseParser = FlagAddResponseParser(),
        flagDeleteResponseParser = FlagDeleteResponseParser(),
        forumRepository = forumRepository,
        authRepository = authRepository,
        flagCacheStore = FlagCacheStore(
            flagDao = flagDao,
            clock = clock,
            ioDispatcher = testDispatcher,
        ),
        json = json,
        ioDispatcher = testDispatcher,
        clock = clock,
        diagnostics = diagnostics,
        refreshDiagnostics = FlagRefreshDiagnostics(diagnostics),
        flagReadClock = flagReadClock,
        preferences = mockk<fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository> {
            every { observeFlagsRefreshPolicy() } returns policy
        },
        readLimiter = fr.forumhfr.redface2.core.network.FlagReadLimiter(),
    )

    private fun stubAuthRepository(pseudo: String = "XaT"): AuthRepository {
        val repo = mockk<AuthRepository>()
        every { repo.observeAuthState() } returns flowOf(AuthState.Authenticated(pseudo))
        return repo
    }

    private fun stubFlagDao(): FlagDao {
        val dao = mockk<FlagDao>(relaxed = true)
        val snapshots = mutableMapOf<Pair<String, FlagType>, FlagCacheSnapshot>()
        coEvery { dao.getFlags(any(), any()) } coAnswers {
            snapshots[firstArg<String>() to secondArg<FlagType>()]?.rows.orEmpty()
        }
        coEvery { dao.getCoverage(any(), any()) } coAnswers {
            snapshots[firstArg<String>() to secondArg<FlagType>()]?.coverage.orEmpty()
        }
        coEvery { dao.getSnapshot(any(), any()) } coAnswers {
            FlagCacheSnapshot(dao.getFlags(firstArg(), secondArg()), dao.getCoverage(firstArg(), secondArg()))
        }
        coEvery { dao.replaceSnapshot(any(), any(), any()) } coAnswers {
            val snapshot = thirdArg<FlagCacheSnapshot>()
            // Match Room's rollback-compatible source encoding, exercised by FlagCacheStoreTest.
            snapshots[firstArg<String>() to secondArg<FlagType>()] = snapshot.copy(
                rows = snapshot.rows.map {
                    it.copy(source = if (it.source == FlagSource.GLOBAL) FlagSource.LEGACY_UNKNOWN else it.source)
                },
                coverage = snapshot.coverage.map {
                    it.copy(source = if (it.source == FlagSource.GLOBAL) FlagSource.LEGACY_UNKNOWN else it.source)
                },
            )
        }
        coEvery { dao.deleteForType(any(), any()) } coAnswers {
            snapshots.remove(firstArg<String>() to secondArg<FlagType>())
            Unit
        }
        coEvery { dao.deleteFlag(any(), any(), any(), any()) } coAnswers {
            val key = firstArg<String>() to secondArg<FlagType>()
            snapshots[key]?.let { snapshot ->
                snapshots[key] = snapshot.copy(rows = snapshot.rows.filterNot {
                    it.cat == thirdArg<Int>() && it.topicId == arg<Int>(3)
                })
            }
            Unit
        }
        coEvery { dao.getLastFetchedAt(any(), any()) } returns null
        return dao
    }

    private fun MockKMatcherScope.rowsMatching(predicate: (List<FlagTopicEntity>) -> Boolean): FlagCacheSnapshot =
        match { predicate(it.rows) }

    private fun successfulCoverage(type: FlagType, at: Instant): List<FlagCategoryCoverageEntity> =
        sampleCategories.flatMap { category ->
            listOf(FlagSource.BUCKET, FlagSource.STICKY).map { source ->
                FlagCategoryCoverageEntity("xat", type, category.id, source, at, at, null)
            }
        }

    private fun sampleFlagAddContext(): FlagAddContext = FlagAddContext(
        cat = 23,
        subcat = 550,
        topicId = 35395,
        page = 12,
        numreponse = 2786758,
        ref = 4,
    )

    private fun flagEntity(
        type: FlagType,
        topicId: Int,
        title: String,
        fetchedAt: Instant,
    ): FlagTopicEntity = FlagTopicEntity(
        userId = "xat",
        type = type,
        cat = 23,
        subcat = 550,
        topicId = topicId,
        title = title,
        totalPages = 10,
        replyCount = 42,
        hasUnread = true,
        lastReadPage = 9,
        lastPostReadId = 1234L,
        firstPostAuthor = "XaT",
        lastReplyAuthor = "XaTelitte",
        lastReplyAt = "2026-05-03 12:00",
        fetchedAt = fetchedAt,
        authMode = FetchMode.AUTHENTICATED,
    )

    private fun fixture(name: String): String {
        val resource = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "fixture missing: $name"
        }
        return resource.use { it.bufferedReader(Charsets.UTF_8).readText() }
    }

    private companion object {
        const val CATEGORIES_URL = "https://forum.hardware.fr/api/forums/hardwarefr/categories"

        const val EMPTY_PAGE = """
            {
              "resource": {
                "page": 1,
                "results_count": 0,
                "results_per_page": 50,
                "resources": []
              }
            }
        """

        // #251 dedup test — a participated-bucket page that DOES contain the cat 32 sticky (id=1), so
        // the (cat, topicId) dedup must drop the topics/last supplement's copy. Test stub (same kind
        // as EMPTY_PAGE), not a captured fixture: the real bucket drops this sticky (that's the bug).
        const val CAT32_STICKY_BUCKET_PAGE = """
            {
              "resource": {
                "page": 1,
                "results_count": 1,
                "results_per_page": 50,
                "resources": [
                  {
                    "id": 1,
                    "title": "[Règles de la catégorie IA]",
                    "is_sticky": true,
                    "is_read": false,
                    "flag_owntopic": 1,
                    "links": {}
                  }
                ]
              }
            }
        """

        // Minimal delflag.php response bodies for the repository branch tests. The exhaustive
        // HTML shapes are captured fixtures pinned by FlagDeleteResponseParserTest (:core:parser) ;
        // here we only exercise the repository's success/failure dispatch, so the marker
        // sentence (and its absence) is all that matters.
        const val DELETE_SUCCESS_HTML =
            """<html><body><div class="hop">Drapeau effacé avec succès</div></body></html>"""
        const val DELETE_FAILURE_HTML =
            """<html><body><div class="hop">Aucun favori n'est repertorié</div></body></html>"""

        // Minimal addflag.php response bodies for repository branch tests. They are NOT captured
        // fixtures: the success shape mirrors the `.hop` wrapper used by the delflag fixture, and
        // the marker sentence is the live-verified #986 contract.
        const val ADD_SUCCESS_HTML =
            """<html><body><div class="hop">Favori positionné</div></body></html>"""
        /**
         * Page d'échec : volontairement une page QUELCONQUE sans le marqueur de succès, et non une
         * phrase HFR — le vrai libellé d'échec de `addflag.php` n'a jamais été capturé. Le parser est
         * fail-closed (il ne cherche que « favori positionné » et traite tout le reste comme un
         * échec), donc le contenu exact n'a aucune incidence ; inventer une phrase française
         * laisserait croire à un contrat attesté, ce que la charte fixtures interdit.
         */
        const val ADD_FAILURE_HTML =
            """<html><body><div class="hop">page non reconnue</div></body></html>"""
    }
}
