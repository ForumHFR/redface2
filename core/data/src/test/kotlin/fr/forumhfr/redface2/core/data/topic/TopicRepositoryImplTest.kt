package fr.forumhfr.redface2.core.data.topic

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.database.dao.TopicDao
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.HfrParser
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
    private lateinit var repository: TopicRepositoryImpl

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-04-26T18:00:00Z"), ZoneOffset.UTC)

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

        repository = TopicRepositoryImpl(
            client = client,
            parser = HfrParser(),
            topicDao = dao,
            clock = fixedClock,
            ioDispatcher = Dispatchers.Unconfined,
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
    fun `observeTopicPage emits cache then fresh on second call`() = runTest {
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))

        repository.observeTopicPage(1, 999_395, 1).test {
            awaitItem()
            awaitComplete()
        }

        repository.observeTopicPage(1, 999_395, 1).test {
            val cached = awaitItem()
            val fresh = awaitItem()
            assertEquals(cached.title, fresh.title)
            assertEquals(cached.posts.size, fresh.posts.size)
            awaitComplete()
        }

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `refreshTopicPage upserts the topic page into Room`() = runTest {
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))

        assertNull(dao.getTopicPage(1, 999_395, 1))
        val refreshed = repository.refreshTopicPage(1, 999_395, 1)

        val cachedTopic = dao.getTopicPage(1, 999_395, 1)
        assertNotNull(cachedTopic)
        cachedTopic!!
        assertEquals(refreshed.title, cachedTopic.title)
        assertEquals(refreshed.posts.map { it.numreponse }, cachedTopic.numreponses)

        val cachedPosts = dao.getPostsByNumreponse(1, cachedTopic.numreponses)
        assertEquals(refreshed.posts.size, cachedPosts.size)
    }

    private fun fixtureHtml(name: String): String {
        return requireNotNull(javaClass.getResource("/fixtures/$name")) {
            "Fixture not found: $name"
        }.readText()
    }
}
