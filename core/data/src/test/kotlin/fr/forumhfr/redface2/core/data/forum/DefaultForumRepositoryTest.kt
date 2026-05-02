package fr.forumhfr.redface2.core.data.forum

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.network.HfrApiClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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

    private fun repository(apiClient: HfrApiClient): DefaultForumRepository =
        DefaultForumRepository(
            apiClient = apiClient,
            json = json,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    private fun fixture(name: String): String {
        val resource = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "fixture missing: $name"
        }
        return resource.bufferedReader().use { it.readText() }
    }
}
