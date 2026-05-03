package fr.forumhfr.redface2.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.database.entities.FetchMode
import fr.forumhfr.redface2.core.database.entities.TopicEntity
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pin the contract of [TopicDao.upsertTopicPageWithPostsUnlessAuthenticated]. The
 * DAO method exists to close a TOCTOU window flagged by the multi-flavor reviews
 * on PR #115 — read-then-write outside a transaction would let a concurrent
 * authenticated fetch slip in between, briefly clobbering the auth row with
 * anonymous data. The transaction guarantees atomicity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TopicDaoTest {

    private lateinit var database: RedfaceDatabase
    private lateinit var dao: TopicDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
            RedfaceDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = database.topicDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `upsertUnlessAuthenticated writes when no row exists`() = runTest {
        val topic = topicEntity(cat = 1, post = 100, page = 1, authMode = FetchMode.ANONYMOUS)

        val written = dao.upsertTopicPageWithPostsUnlessAuthenticated(topic, posts = emptyList())

        assertTrue("expected the write to land on a cold cache", written)
        val row = dao.getTopicPage(1, 100, 1)
        assertNotNull(row)
        assertEquals(FetchMode.ANONYMOUS, row!!.authMode)
    }

    @Test
    fun `upsertUnlessAuthenticated overwrites an existing ANONYMOUS row`() = runTest {
        val initial = topicEntity(cat = 1, post = 100, page = 1, authMode = FetchMode.ANONYMOUS, title = "first")
        dao.upsertTopicPage(initial)

        val replacement = topicEntity(cat = 1, post = 100, page = 1, authMode = FetchMode.ANONYMOUS, title = "second")
        val written = dao.upsertTopicPageWithPostsUnlessAuthenticated(replacement, posts = emptyList())

        assertTrue("anon→anon must succeed", written)
        assertEquals("second", dao.getTopicPage(1, 100, 1)?.title)
    }

    @Test
    fun `upsertUnlessAuthenticated refuses to overwrite an existing AUTHENTICATED row`() = runTest {
        val authed = topicEntity(cat = 1, post = 100, page = 1, authMode = FetchMode.AUTHENTICATED, title = "auth")
        dao.upsertTopicPage(authed)

        val anon = topicEntity(cat = 1, post = 100, page = 1, authMode = FetchMode.ANONYMOUS, title = "anon")
        val written = dao.upsertTopicPageWithPostsUnlessAuthenticated(anon, posts = emptyList())

        assertFalse("anonymous fetch must NOT downgrade an authenticated row", written)
        val preserved = dao.getTopicPage(1, 100, 1)
        assertEquals(FetchMode.AUTHENTICATED, preserved?.authMode)
        assertEquals(
            "AUTHENTICATED row must keep its original payload",
            "auth",
            preserved?.title,
        )
    }

    private fun topicEntity(
        cat: Int,
        post: Int,
        page: Int,
        authMode: FetchMode,
        title: String = "topic $post page $page",
        fetchedAt: Instant = Instant.parse("2026-04-26T18:00:00Z"),
    ): TopicEntity = TopicEntity(
        cat = cat,
        post = post,
        page = page,
        title = title,
        totalPages = 1,
        isFirstPostOwner = false,
        pollJson = null,
        numreponses = emptyList(),
        fetchedAt = fetchedAt,
        authMode = authMode,
    )
}
