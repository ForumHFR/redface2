package fr.forumhfr.redface2.core.data.flags

import app.cash.turbine.test
import fr.forumhfr.redface2.core.database.dao.FlagDao
import fr.forumhfr.redface2.core.database.entities.FetchMode
import fr.forumhfr.redface2.core.database.entities.FlagTopicEntity
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.network.HfrApiClient
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.network.HfrRestFlagBucket
import fr.forumhfr.redface2.core.parser.write.FlagDeleteResponseParser
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
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
            flagDao.replaceForType(
                userId = "xat",
                type = FlagType.CYAN,
                rows = match { rows -> rows.size == 1 && rows.single().topicId == 35395 },
            )
        }
    }

    @Test
    fun `observe and refresh fired together share a single REST fan-out - 501`() = runTest {
        // #501 (Codex review) — observe()'s initial fetch and a concurrent refresh() for the SAME tab
        // must collapse into ONE per-category fan-out, not two. The gate keeps the first fetch in
        // flight while the second caller arrives, so a missing in-flight dedup would call cat 23 twice.
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

        // Sequence the two callers so they genuinely overlap: observe() first reaches the gated fetch
        // (registering the in-flight Deferred), THEN refresh() runs and must find + await that same
        // Deferred rather than starting a second fan-out. Only then release the gate.
        val observeJob = launch { repo.observe(FlagType.CYAN).first { it is FlagsResult.Success } }
        runCurrent()
        val refreshJob = launch { repo.refresh(FlagType.CYAN) }
        runCurrent()
        gate.complete(Unit)
        observeJob.join()
        refreshJob.join()

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
            flagDao.replaceForType(
                userId = "xat",
                type = FlagType.CYAN,
                rows = match { rows ->
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
        val repo = buildRepository(apiClient, stubForumRepository(catalogue))

        repo.observe(FlagType.CYAN).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val success = awaitItem() as FlagsResult.Success
            assertTrue(
                "the bucket flag from cat 23 survives the supplement failure",
                success.flags.any { it.cat == 23 },
            )
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
        coEvery { flagDao.getLastFetchedAt("xat", FlagType.CYAN) } returns now
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
    ): DefaultFlagRepository = DefaultFlagRepository(
        apiClient = apiClient,
        hfrClient = hfrClient,
        flagDeleteResponseParser = FlagDeleteResponseParser(),
        forumRepository = forumRepository,
        authRepository = authRepository,
        flagCacheStore = FlagCacheStore(
            flagDao = flagDao,
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(),
        ),
        json = json,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun stubAuthRepository(pseudo: String = "XaT"): AuthRepository {
        val repo = mockk<AuthRepository>()
        every { repo.observeAuthState() } returns flowOf(AuthState.Authenticated(pseudo))
        return repo
    }

    private fun stubFlagDao(): FlagDao {
        val dao = mockk<FlagDao>(relaxed = true)
        coEvery { dao.getFlags(any(), any()) } returns emptyList()
        coEvery { dao.getLastFetchedAt(any(), any()) } returns null
        return dao
    }

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
    }
}
