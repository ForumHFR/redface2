package fr.forumhfr.redface2.core.data.forum

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.network.HfrApiClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
