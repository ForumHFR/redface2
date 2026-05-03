package fr.forumhfr.redface2.core.data.flags

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.network.HfrApiClient
import fr.forumhfr.redface2.core.network.HfrRestFlagBucket
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
    fun `observe emits Loading then Success on a happy fetch`() = runTest {
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.PARTICIPATED, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.PARTICIPATED, capturedParticipatedFixture)
        }
        val repo = buildRepository(apiClient, forumRepository)

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
    fun `observe emits Failure when categories cannot be loaded`() = runTest {
        val apiClient = mockk<HfrApiClient>(relaxed = true)
        val forumRepository = mockk<ForumRepository>()
        coEvery { forumRepository.observeCategories() } returns flowOf(
            ForumResult.Failure(IOException("HFR down")),
        )
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
    fun `clearSessionCache drops cached flags so the next observe fetches again`() = runTest {
        val (apiClient, forumRepository) = wireDeps {
            stubFlagsCall(13, HfrRestFlagBucket.FAVORITES, EMPTY_PAGE)
            stubFlagsCall(23, HfrRestFlagBucket.FAVORITES, capturedParticipatedFixture)
        }
        val repo = buildRepository(apiClient, forumRepository)

        repo.observe(FlagType.FAVORITE).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        repo.clearSessionCache()

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
                      "posts": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/topics/100/posts/?page=1&results_per_page=40", "count": 1}
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
                      "posts": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/topics/200/posts/?page=1&results_per_page=40", "count": 1}
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
                      "posts": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/23/topics/300/posts/?page=1&results_per_page=40", "count": 1}
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
                      "posts": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/topics/300/posts/?page=1&results_per_page=40", "count": 1}
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
                      "posts": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/topics/400/posts/?page=1&results_per_page=40", "count": 1}
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
                      "posts": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/topics/100/posts/?page=1&results_per_page=40", "count": 1}
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
                      "posts": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/topics/200/posts/?page=2&results_per_page=40", "count": 50}
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

    private fun stubForumRepository(categories: List<Category>): ForumRepository {
        val repo = mockk<ForumRepository>()
        coEvery { repo.observeCategories() } returns flowOf(ForumResult.Success(categories))
        return repo
    }

    private fun wireDeps(
        configure: ApiStubScope.() -> Unit,
    ): Pair<HfrApiClient, ForumRepository> {
        val apiClient = mockk<HfrApiClient>()
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

    private fun buildRepository(
        apiClient: HfrApiClient,
        forumRepository: ForumRepository,
    ): DefaultFlagRepository = DefaultFlagRepository(
        apiClient = apiClient,
        forumRepository = forumRepository,
        json = json,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun fixture(name: String): String {
        val resource = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "fixture missing: $name"
        }
        return resource.use { it.bufferedReader(Charsets.UTF_8).readText() }
    }

    private companion object {
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
    }
}
