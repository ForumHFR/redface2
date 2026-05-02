package fr.forumhfr.redface2.core.data.topic

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.database.dao.TopicDao
import fr.forumhfr.redface2.core.database.entities.FetchMode
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.HfrParser
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TopicRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var database: RedfaceDatabase
    private lateinit var dao: TopicDao
    private lateinit var client: HfrClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, RedfaceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.topicDao()

        val okHttp = OkHttpClient.Builder().build()
        client = HfrClient(
            authenticated = okHttp,
            anonymous = okHttp,
            baseUrl = server.url("/"),
        )
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    @Test
    fun `observeTopicPage emits fresh fetch when cache is empty`() = runTest {
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val repository = repository(now = Instant.parse("2026-04-26T18:00:00Z"))

        repository.observeTopicPage(cat = 1, post = 999_395, page = 1).test {
            val fresh = awaitItem()
            assertEquals(1, fresh.cat)
            assertEquals(999_395, fresh.post)
            assertEquals(1, fresh.page)
            assertTrue("expected at least one post", fresh.posts.isNotEmpty())
            awaitComplete()
        }

        val recorded = server.takeRequest()
        val requestUrl = recorded.requestUrl
        assertNotNull(requestUrl)
        requestUrl!!
        assertEquals("forum2.php", requestUrl.pathSegments.first())
        assertEquals("hfr.inc", requestUrl.queryParameter("config"))
        assertEquals("1", requestUrl.queryParameter("cat"))
        assertEquals("999395", requestUrl.queryParameter("post"))
        assertEquals("1", requestUrl.queryParameter("page"))
    }

    @Test
    fun `observeTopicPage on a fresh cache hit skips the network refresh`() = runTest {
        // Warm the cache: only one fixture is enqueued, the second observe call
        // must not consume a second response — that's the whole point of the TTL.
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val repo = repository(now = Instant.parse("2026-04-26T18:00:00Z"))
        repo.refreshTopicPage(1, 999_395, 1)
        assertEquals("warmup should issue exactly one network request", 1, server.requestCount)

        // Same fixed clock → cache fetchedAt equals "now" → fresh → no refresh.
        repo.observeTopicPage(1, 999_395, 1).test {
            val cached = awaitItem()
            awaitComplete()
            assertTrue("cache emission should carry posts", cached.posts.isNotEmpty())
        }
        assertEquals("fresh cache hit must not trigger a refresh", 1, server.requestCount)
    }

    @Test
    fun `observeTopicPage on a stale cache emits cache then refreshes`() = runTest {
        // Warm cache as of T0.
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        repository(now = Instant.parse("2026-04-26T18:00:00Z")).refreshTopicPage(1, 999_395, 1)

        // Move 5 minutes forward — beyond CachePolicy.topicPage (60s).
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val staleRepository = repository(now = Instant.parse("2026-04-26T18:05:00Z"))
        staleRepository.observeTopicPage(1, 999_395, 1).test {
            val cached = awaitItem()
            val fresh = awaitItem()
            assertEquals(cached.title, fresh.title)
            assertEquals(cached.posts.size, fresh.posts.size)
            // Round-trip the PostContent AST through the Room JSON converter:
            // the cached read goes through PostContentSerializer.decode while the
            // fresh read comes straight from HfrParser, so equality proves the
            // converter preserves the polymorphic block/inline hierarchy.
            assertEquals(fresh.posts.first().content, cached.posts.first().content)
            awaitComplete()
        }
        assertEquals("stale cache must trigger one refresh request", 2, server.requestCount)
    }

    @Test
    fun `network failure after cached emission keeps the cache and swallows the error`() = runTest {
        // Warm cache as of T0.
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        repository(now = Instant.parse("2026-04-26T18:00:00Z")).refreshTopicPage(1, 999_395, 1)

        // Stale TTL → refresh path triggers, but the next response is a hard
        // disconnect. The flow must emit the cached page exactly once and complete
        // without rethrowing the network failure.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val staleRepository = repository(now = Instant.parse("2026-04-26T18:05:00Z"))
        staleRepository.observeTopicPage(1, 999_395, 1).test {
            val cached = awaitItem()
            assertTrue("cache emission should carry the warmed posts", cached.posts.isNotEmpty())
            awaitComplete()
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `prefetchAnonymous does not overwrite an existing AUTHENTICATED row`() = runTest {
        // First request lands as authenticated and warms the cache with auth-derived
        // post fields (the fixture has isOwnPost=false but the row is tagged AUTH).
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val authedRepo = repository(now = Instant.parse("2026-04-26T18:00:00Z"))
        authedRepo.refreshTopicPage(1, 999_395, 1)
        val authRow = dao.getTopicPage(1, 999_395, 1)
        assertNotNull(authRow)
        assertEquals(FetchMode.AUTHENTICATED, authRow!!.authMode)

        // Anonymous prefetch arrives later — it must NOT clobber the auth row.
        // We enqueue a second response in case the implementation issues the network
        // call; the assertion is on the persisted row's authMode.
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val anonRepo = repository(now = Instant.parse("2026-04-26T18:00:30Z"))
        anonRepo.prefetchAnonymous(1, 999_395, 1)

        val afterPrefetch = dao.getTopicPage(1, 999_395, 1)
        assertNotNull(afterPrefetch)
        assertEquals(
            "anonymous prefetch must keep AUTHENTICATED authMode on the cached row",
            FetchMode.AUTHENTICATED,
            afterPrefetch!!.authMode,
        )
    }

    @Test
    fun `prefetchAnonymous on a cold cache writes an ANONYMOUS row`() = runTest {
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val anonRepo = repository(now = Instant.parse("2026-04-26T18:00:00Z"))

        anonRepo.prefetchAnonymous(1, 999_395, 1)

        val row = dao.getTopicPage(1, 999_395, 1)
        assertNotNull(row)
        assertEquals(FetchMode.ANONYMOUS, row!!.authMode)
    }

    @Test
    fun `refreshTopicPage upserts the topic page into Room and tags it as authenticated`() = runTest {
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val repo = repository(now = Instant.parse("2026-04-26T18:00:00Z"))

        assertNull(dao.getTopicPage(1, 999_395, 1))
        val refreshed = repo.refreshTopicPage(1, 999_395, 1)

        val cachedTopic = dao.getTopicPage(1, 999_395, 1)
        assertNotNull(cachedTopic)
        cachedTopic!!
        assertEquals(refreshed.title, cachedTopic.title)
        assertEquals(refreshed.posts.map { it.numreponse }, cachedTopic.numreponses)
        assertEquals(FetchMode.AUTHENTICATED, cachedTopic.authMode)

        val cachedPosts = dao.getPostsByNumreponse(1, cachedTopic.numreponses)
        assertEquals(refreshed.posts.size, cachedPosts.size)
        cachedPosts.forEach { post ->
            assertEquals(FetchMode.AUTHENTICATED, post.authMode)
        }
    }

    private fun repository(now: Instant): TopicRepositoryImpl = TopicRepositoryImpl(
        client = client,
        parser = HfrParser(),
        topicDao = dao,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun fixtureHtml(name: String): String {
        return requireNotNull(javaClass.getResource("/fixtures/$name")) {
            "Fixture not found: $name"
        }.readText()
    }
}
