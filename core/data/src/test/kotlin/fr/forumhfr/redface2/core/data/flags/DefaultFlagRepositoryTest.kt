package fr.forumhfr.redface2.core.data.flags

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.network.HfrApiClient
import fr.forumhfr.redface2.core.network.HfrRestFlagBucket
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DefaultFlagRepositoryTest {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `observe emits Loading then Success on a happy fetch`() = runTest {
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getFlagTopics(bucket = HfrRestFlagBucket.PARTICIPATED, useAuth = true)
        } returns FIXTURE_PARTICIPATED
        val repo = buildRepository(apiClient)

        repo.observe(FlagType.CYAN).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val success = awaitItem() as FlagsResult.Success
            assertEquals(1, success.flags.size)
            assertEquals(35395, success.flags.single().topicId)
            assertEquals(FlagType.CYAN, success.flags.single().type)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observe emits Failure when the network throws`() = runTest {
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getFlagTopics(bucket = HfrRestFlagBucket.PARTICIPATED, useAuth = true)
        } throws IOException("offline")
        val repo = buildRepository(apiClient)

        repo.observe(FlagType.CYAN).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val result = awaitItem()
            assertTrue("expected Failure, got $result", result is FlagsResult.Failure)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh broadcasts a fresh result to active observers`() = runTest {
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getFlagTopics(bucket = HfrRestFlagBucket.PARTICIPATED, useAuth = true)
        } returnsMany listOf(FIXTURE_PARTICIPATED, FIXTURE_PARTICIPATED_REFRESHED)
        val repo = buildRepository(apiClient)

        repo.observe(FlagType.CYAN).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val initial = awaitItem() as FlagsResult.Success
            assertEquals(35395, initial.flags.single().topicId)

            repo.refresh(FlagType.CYAN)
            assertEquals(FlagsResult.Loading, awaitItem())
            val refreshed = awaitItem() as FlagsResult.Success
            assertEquals(2, refreshed.flags.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observe reuses cached success instead of refetching the same tab`() = runTest {
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getFlagTopics(bucket = HfrRestFlagBucket.FAVORITES, useAuth = true)
        } returns FIXTURE_PARTICIPATED
        val repo = buildRepository(apiClient)

        repo.observe(FlagType.FAVORITE).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val first = awaitItem() as FlagsResult.Success
            assertEquals(35395, first.flags.single().topicId)
            cancelAndIgnoreRemainingEvents()
        }

        repo.observe(FlagType.FAVORITE).test {
            val cached = awaitItem() as FlagsResult.Success
            assertEquals(35395, cached.flags.single().topicId)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) {
            apiClient.getFlagTopics(bucket = HfrRestFlagBucket.FAVORITES, useAuth = true)
        }
    }

    @Test
    fun `clearSessionCache drops cached flags so the next observe fetches again`() = runTest {
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getFlagTopics(bucket = HfrRestFlagBucket.FAVORITES, useAuth = true)
        } returnsMany listOf(FIXTURE_PARTICIPATED, FIXTURE_PARTICIPATED_REFRESHED)
        val repo = buildRepository(apiClient)

        repo.observe(FlagType.FAVORITE).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val first = awaitItem() as FlagsResult.Success
            assertEquals(1, first.flags.size)
            cancelAndIgnoreRemainingEvents()
        }

        repo.clearSessionCache()

        repo.observe(FlagType.FAVORITE).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val refetched = awaitItem() as FlagsResult.Success
            assertEquals(2, refetched.flags.size)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 2) {
            apiClient.getFlagTopics(bucket = HfrRestFlagBucket.FAVORITES, useAuth = true)
        }
    }

    @Test
    fun `each FlagType maps to its own REST bucket`() = runTest {
        val apiClient = mockk<HfrApiClient>()
        coEvery {
            apiClient.getFlagTopics(bucket = HfrRestFlagBucket.PARTICIPATED, useAuth = true)
        } returns FIXTURE_PARTICIPATED
        coEvery {
            apiClient.getFlagTopics(bucket = HfrRestFlagBucket.READ, useAuth = true)
        } returns FIXTURE_PARTICIPATED
        coEvery {
            apiClient.getFlagTopics(bucket = HfrRestFlagBucket.FAVORITES, useAuth = true)
        } returns FIXTURE_PARTICIPATED
        val repo = buildRepository(apiClient)

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

        coVerify(exactly = 1) {
            apiClient.getFlagTopics(bucket = HfrRestFlagBucket.PARTICIPATED, useAuth = true)
        }
        coVerify(exactly = 1) {
            apiClient.getFlagTopics(bucket = HfrRestFlagBucket.READ, useAuth = true)
        }
        coVerify(exactly = 1) {
            apiClient.getFlagTopics(bucket = HfrRestFlagBucket.FAVORITES, useAuth = true)
        }
    }

    private fun buildRepository(apiClient: HfrApiClient): DefaultFlagRepository =
        DefaultFlagRepository(
            apiClient = apiClient,
            json = json,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    private companion object {
        // Real REST shape (single-resource per-cat fixture promoted to a global-style
        // body — same envelope, just with a category-link the mapper can read). The
        // payload comes verbatim from `core/data/src/test/resources/fixtures/
        // rest_cat23_participated.json` so a server-side change to the field set is
        // caught by the mapper tests as well as this one.
        const val FIXTURE_PARTICIPATED = """
            {
              "resource": {
                "page": 1,
                "results_count": 1,
                "results_per_page": 1,
                "resources": [
                  {
                    "id": 35395,
                    "title": "Redface 2",
                    "last_post_date": "2026-05-01 17:07",
                    "is_closed": false,
                    "is_sticky": false,
                    "links": {
                      "category": {
                        "linked_type": "forum_category",
                        "type": "link",
                        "href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/23/"
                      },
                      "subcategory": {
                        "linked_type": "forum_subcategory",
                        "type": "link",
                        "href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/23/subcategories/550/"
                      },
                      "posts": {
                        "linked_type": "list",
                        "type": "link",
                        "href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/23/subcategories/550/topics/35395/posts/?page=12&results_per_page=40",
                        "count": 541
                      },
                      "last_author": {"title": "qwazer"},
                      "author": {"title": "XaTriX"}
                    },
                    "is_read": false,
                    "flag_owntopic": 1,
                    "last_position": 479,
                    "last_post_read_id": 2783256
                  }
                ]
              }
            }
        """

        const val FIXTURE_PARTICIPATED_REFRESHED = """
            {
              "resource": {
                "page": 1,
                "results_count": 2,
                "results_per_page": 50,
                "resources": [
                  {
                    "id": 35395,
                    "title": "Redface 2",
                    "last_post_date": "2026-05-01 17:07",
                    "is_closed": false,
                    "is_sticky": false,
                    "links": {
                      "category": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/23/"},
                      "posts": {
                        "href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/23/topics/35395/posts/?page=12&results_per_page=40",
                        "count": 541
                      }
                    },
                    "is_read": false,
                    "flag_owntopic": 1,
                    "last_post_read_id": 2783256
                  },
                  {
                    "id": 9999,
                    "title": "Other topic",
                    "last_post_date": "2026-05-02 09:00",
                    "is_closed": false,
                    "is_sticky": false,
                    "links": {
                      "category": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/"},
                      "posts": {
                        "href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/topics/9999/posts/?page=3&results_per_page=40",
                        "count": 100
                      }
                    },
                    "is_read": true,
                    "flag_owntopic": 1,
                    "last_post_read_id": 12345
                  }
                ]
              }
            }
        """
    }
}
