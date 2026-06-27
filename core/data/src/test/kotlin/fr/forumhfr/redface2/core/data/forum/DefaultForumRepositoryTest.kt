package fr.forumhfr.redface2.core.data.forum

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.forum.FlagFilterBucket
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.network.HfrApiClient
import fr.forumhfr.redface2.core.network.HfrRestFlagBucket
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultForumRepositoryTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `observeCategories emits Loading then Success on the first subscription`() = runTest {
        val apiClient = mockk<HfrApiClient> {
            coEvery { getCategories(any()) } returns fixture("rest_categories.json")
        }
        val repo = repository(apiClient)

        repo.observeCategories().test {
            assertEquals(ForumResult.Loading, awaitItem())
            val success = awaitItem() as ForumResult.Success
            assertEquals(19, success.value.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `categories cache replays Success without re-fetching`() = runTest {
        val apiClient = mockk<HfrApiClient> {
            coEvery { getCategories(any()) } returns fixture("rest_categories.json")
        }
        val repo = repository(apiClient)

        repo.observeCategories().test {
            awaitItem() // Loading
            awaitItem() // Success
            cancelAndIgnoreRemainingEvents()
        }
        // Second observer must reuse the cached payload — no second network call.
        repo.observeCategories().test {
            val cached = awaitItem() as ForumResult.Success
            assertEquals(19, cached.value.size)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { apiClient.getCategories(any()) }
    }

    @Test
    fun `concurrent cold observers coalesce into a single categories fetch`() = runTest {
        // Two collectors subscribe before either fetch completes (the gate keeps the first
        // getCategories suspended). Without the single-flight mutex both would see a cold
        // cache and each fire getCategories — the classic #179 cold-start double-fetch
        // (FlagsViewModel grouping + DefaultFlagRepository.loadCategories racing).
        val gate = CompletableDeferred<Unit>()
        val apiClient = mockk<HfrApiClient> {
            coEvery { getCategories(any()) } coAnswers {
                gate.await()
                fixture("rest_categories.json")
            }
        }
        val repo = repository(apiClient)

        val first = async { repo.observeCategories().first { it is ForumResult.Success } }
        val second = async { repo.observeCategories().first { it is ForumResult.Success } }
        runCurrent() // let both collectors reach the (cold) fetch branch and the mutex

        gate.complete(Unit)
        val firstResult = first.await() as ForumResult.Success
        val secondResult = second.await() as ForumResult.Success

        assertEquals(19, firstResult.value.size)
        assertEquals(19, secondResult.value.size)
        coVerify(exactly = 1) { apiClient.getCategories(any()) }
    }

    @Test
    fun `network failure propagates as Failure with the original cause`() = runTest {
        val boom = IllegalStateException("HFR explosé")
        val apiClient = mockk<HfrApiClient> {
            coEvery { getCategories(any()) } throws boom
        }
        val repo = repository(apiClient)

        repo.observeCategories().test {
            awaitItem() // Loading
            val failure = awaitItem() as ForumResult.Failure
            assertEquals(boom, failure.cause)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshCategories rebroadcasts Loading then a fresh Success`() = runTest {
        val apiClient = mockk<HfrApiClient> {
            coEvery { getCategories(any()) } returns fixture("rest_categories.json")
        }
        val repo = repository(apiClient)

        repo.observeCategories().test {
            awaitItem() // initial Loading
            awaitItem() // initial Success
            repo.refreshCategories()
            assertEquals(ForumResult.Loading, awaitItem())
            assertTrue(awaitItem() is ForumResult.Success)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `prefetchTopicList issues an unauthenticated REST request`() = runTest {
        val apiClient = mockk<HfrApiClient> {
            coEvery {
                getTopicList(cat = 23, subcat = 550, page = 2, resultsPerPage = 50, useAuth = false)
            } returns fixture("rest_topics_cat23_subcat550_p1.json")
        }
        val repo = repository(apiClient)

        repo.prefetchTopicList(cat = 23, subcat = 550, page = 2)

        // Cookie-bearing path must NOT be invoked. ADR-003 § Prefetch makes this
        // the inviolable rule: a prefetch with `useAuth = true` would silently
        // mark drapeaux as read on HFR.
        coVerify(exactly = 1) {
            apiClient.getTopicList(cat = 23, subcat = 550, page = 2, resultsPerPage = 50, useAuth = false)
        }
        coVerify(exactly = 0) {
            apiClient.getTopicList(any(), any(), any(), any(), useAuth = true)
        }
    }

    @Test
    fun `prefetchTopicList swallows network failures`() = runTest {
        val apiClient = mockk<HfrApiClient> {
            coEvery {
                getTopicList(any(), any(), any(), any(), useAuth = false)
            } throws IllegalStateException("HFR éteint")
        }
        val repo = repository(apiClient)

        // Best-effort prefetch — must not bubble up. The screen's authenticated
        // observeTopicList will fail visibly later if HFR is really down.
        repo.prefetchTopicList(cat = 23, subcat = 550, page = 2)
    }

    @Test
    fun `categories cache replays past CachePolicy categories TTL when stale`() = runTest {
        val apiClient = mockk<HfrApiClient> {
            coEvery { getCategories(any()) } returns fixture("rest_categories.json")
        }
        val mutableClock = MutableClock(Instant.parse("2026-04-26T18:00:00Z"))
        val repo = repository(apiClient, clock = mutableClock)

        // Warm the cache.
        repo.observeCategories().test {
            awaitItem() // Loading
            awaitItem() // Success
            cancelAndIgnoreRemainingEvents()
        }
        // Advance past the 24h categories TTL — the next observer should re-fetch.
        mutableClock.advance(java.time.Duration.ofHours(25))
        repo.observeCategories().test {
            // First emission is the still-cached (stale) Success — UX-preserving so
            // the screen does not flash through Loading. Then Loading + a fresh Success.
            awaitItem() as ForumResult.Success
            assertEquals(ForumResult.Loading, awaitItem())
            assertTrue(awaitItem() is ForumResult.Success)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { apiClient.getCategories(any()) }
    }

    @Test
    fun `getCategories returns the fresh cache without a network fetch`() = runTest {
        val apiClient = mockk<HfrApiClient> {
            coEvery { getCategories(any()) } returns fixture("rest_categories.json")
        }
        val repo = repository(apiClient)

        // Warm the cache (one network fetch).
        repo.observeCategories().test {
            awaitItem() // Loading
            awaitItem() // Success
            cancelAndIgnoreRemainingEvents()
        }

        val forced = repo.getCategories(forceRefreshIfStale = true)
        val unforced = repo.getCategories(forceRefreshIfStale = false)

        // A fresh cache is served from memory regardless of the flag — same cached value, single fetch.
        val cachedValue = (forced as ForumResult.Success).value
        assertTrue("the cached categories must be non-empty", cachedValue.isNotEmpty())
        assertEquals(cachedValue, (unforced as ForumResult.Success).value)
        coVerify(exactly = 1) { apiClient.getCategories(any()) }
    }

    @Test
    fun `getCategories degrades to the stale cache when the forced refresh fails`() = runTest {
        // #251 follow-up (code review) — when the forced refresh hits a transient categories-endpoint
        // failure but a stale cache exists, getCategories must DEGRADE to last-known-good rather than
        // failing the caller (the flags fan-out would otherwise blank the whole Drapeaux screen on a
        // momentary outage). A cold cache, having nothing to fall back to, still surfaces the Failure.
        val apiClient = mockk<HfrApiClient> {
            coEvery { getCategories(any()) } returns fixture("rest_categories.json") andThenThrows
                java.io.IOException("offline")
        }
        val mutableClock = MutableClock(Instant.parse("2026-04-26T18:00:00Z"))
        val repo = repository(apiClient, clock = mutableClock)

        // Warm the cache (1st network call succeeds), then age it past the 24h TTL.
        repo.observeCategories().test {
            awaitItem() // Loading
            awaitItem() // Success
            cancelAndIgnoreRemainingEvents()
        }
        mutableClock.advance(java.time.Duration.ofHours(25))

        // Forced refresh fires the 2nd network call which throws → degrade to the stale cache.
        val result = repo.getCategories(forceRefreshIfStale = true)

        assertTrue("expected degraded Success (last-known-good), got $result", result is ForumResult.Success)
        assertTrue((result as ForumResult.Success).value.isNotEmpty())
        coVerify(exactly = 2) { apiClient.getCategories(any()) }
    }

    @Test
    fun `getCategories forces a network re-fetch when the cache is stale - 251`() = runTest {
        val apiClient = mockk<HfrApiClient> {
            coEvery { getCategories(any()) } returns fixture("rest_categories.json")
        }
        val mutableClock = MutableClock(Instant.parse("2026-04-26T18:00:00Z"))
        val repo = repository(apiClient, clock = mutableClock)

        // Warm the cache, then age it past the 24h categories TTL.
        repo.observeCategories().test {
            awaitItem() // Loading
            awaitItem() // Success
            cancelAndIgnoreRemainingEvents()
        }
        mutableClock.advance(java.time.Duration.ofHours(25))

        val result = repo.getCategories(forceRefreshIfStale = true)

        assertTrue("expected Success, got $result", result is ForumResult.Success)
        // #251 — a stale catalogue must be re-fetched so a category added to HFR after the cache
        // was warmed is enumerated. Two fetches total: the warm + this forced refresh.
        coVerify(exactly = 2) { apiClient.getCategories(any()) }
    }

    @Test
    fun `getCategories returns the stale cache without fetch when force is false`() = runTest {
        val apiClient = mockk<HfrApiClient> {
            coEvery { getCategories(any()) } returns fixture("rest_categories.json")
        }
        val mutableClock = MutableClock(Instant.parse("2026-04-26T18:00:00Z"))
        val repo = repository(apiClient, clock = mutableClock)

        repo.observeCategories().test {
            awaitItem() // Loading
            awaitItem() // Success
            cancelAndIgnoreRemainingEvents()
        }
        mutableClock.advance(java.time.Duration.ofHours(25))

        val result = repo.getCategories(forceRefreshIfStale = false)

        assertTrue("expected Success, got $result", result is ForumResult.Success)
        // Caller tolerated staleness — no extra round-trip beyond the warm.
        coVerify(exactly = 1) { apiClient.getCategories(any()) }
    }

    @Test
    fun `getCategories fetches on a cold cache`() = runTest {
        val apiClient = mockk<HfrApiClient> {
            coEvery { getCategories(any()) } returns fixture("rest_categories.json")
        }
        val repo = repository(apiClient)

        val result = repo.getCategories(forceRefreshIfStale = false)

        assertTrue("expected Success, got $result", result is ForumResult.Success)
        coVerify(exactly = 1) { apiClient.getCategories(any()) }
    }

    @Test
    fun `observeTopicList wires the page to the apiClient call`() = runTest {
        val apiClient = mockk<HfrApiClient> {
            coEvery {
                getTopicList(cat = 23, subcat = 550, page = 1, resultsPerPage = 50, useAuth = true)
            } returns fixture("rest_topics_cat23_subcat550_p1.json")
        }
        val repo = repository(apiClient)

        repo.observeTopicList(cat = 23, subcat = 550, page = 1).test {
            assertEquals(ForumResult.Loading, awaitItem())
            val success = awaitItem() as ForumResult.Success
            assertTrue(success.value.topics.isNotEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) {
            apiClient.getTopicList(cat = 23, subcat = 550, page = 1, resultsPerPage = 50, useAuth = true)
        }
    }

    @Test
    fun `getFlagFilteredTopics maps the participated bucket to a TopicListPage`() = runTest {
        val apiClient = mockk<HfrApiClient> {
            coEvery {
                getCategoryFlagTopics(
                    cat = 23,
                    bucket = HfrRestFlagBucket.PARTICIPATED,
                    subcat = null,
                    page = any(),
                    resultsPerPage = any(),
                    useAuth = true,
                )
            } returns fixture("rest_cat23_participated.json")
        }
        val repo = repository(apiClient)

        val result = repo.getFlagFilteredTopics(cat = 23, subcat = null, bucket = FlagFilterBucket.PARTICIPATED)

        assertTrue("expected Success, got $result", result is ForumResult.Success)
        val page = (result as ForumResult.Success).value
        assertEquals(23, page.cat)
        assertTrue("the bucket fixture should map at least one topic", page.topics.isNotEmpty())
    }

    @Test
    fun `getFlagFilteredTopics forwards the subcat and bucket to the api client`() = runTest {
        val apiClient = mockk<HfrApiClient> {
            coEvery {
                getCategoryFlagTopics(
                    cat = 23,
                    bucket = HfrRestFlagBucket.FAVORITES,
                    subcat = 550,
                    page = any(),
                    resultsPerPage = any(),
                    useAuth = true,
                )
            } returns fixture("rest_cat23_participated.json")
        }
        val repo = repository(apiClient)

        repo.getFlagFilteredTopics(cat = 23, subcat = 550, bucket = FlagFilterBucket.FAVORITES)

        coVerify(exactly = 1) {
            apiClient.getCategoryFlagTopics(
                cat = 23,
                bucket = HfrRestFlagBucket.FAVORITES,
                subcat = 550,
                page = any(),
                resultsPerPage = any(),
                useAuth = true,
            )
        }
    }

    @Test
    fun `getFlagFilteredTopics returns Failure on a network error`() = runTest {
        val apiClient = mockk<HfrApiClient> {
            coEvery {
                getCategoryFlagTopics(any(), any(), any(), any(), any(), any())
            } throws RuntimeException("boom")
        }
        val repo = repository(apiClient)

        val result = repo.getFlagFilteredTopics(cat = 23, subcat = null, bucket = FlagFilterBucket.READ)

        assertTrue("expected Failure, got $result", result is ForumResult.Failure)
    }

    private fun repository(
        apiClient: HfrApiClient,
        clock: Clock = Clock.fixed(Instant.parse("2026-04-26T18:00:00Z"), ZoneOffset.UTC),
    ): DefaultForumRepository =
        DefaultForumRepository(
            apiClient = apiClient,
            json = json,
            ioDispatcher = UnconfinedTestDispatcher(),
            clock = clock,
        )

    /**
     * A minimal mutable [Clock] whose [Clock.instant] result can be advanced at
     * test time. Avoids depending on the deprecated `Clock.offset` chain or
     * MockK shadowing for a one-off helper.
     */
    private class MutableClock(private var current: Instant) : Clock() {
        override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
        override fun instant(): Instant = current
        fun advance(duration: java.time.Duration) {
            current = current.plus(duration)
        }
    }

    private fun fixture(name: String): String {
        val resource = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "fixture missing: $name"
        }
        return resource.bufferedReader().use { it.readText() }
    }
}
