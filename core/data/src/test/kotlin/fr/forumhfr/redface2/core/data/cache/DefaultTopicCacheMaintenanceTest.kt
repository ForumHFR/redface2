package fr.forumhfr.redface2.core.data.cache

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.database.dao.FlagDao
import fr.forumhfr.redface2.core.database.dao.TopicDao
import fr.forumhfr.redface2.core.database.entities.FetchMode
import fr.forumhfr.redface2.core.database.entities.FlagTopicEntity
import fr.forumhfr.redface2.core.database.entities.PostEntity
import fr.forumhfr.redface2.core.database.entities.TopicEntity
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.PostContent
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
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

/**
 * Round-trip test for the alpha « Vider le cache des topics » action :
 *
 * 1. Seed Room with a known `TopicEntity` + a couple of `PostEntity` rows (mirroring
 *    what `TopicRepositoryImpl.fetchAndPersist` does on a real fetch — we go through
 *    the DAO directly so we don't have to spin up a MockWebServer just to verify the
 *    delete path).
 * 2. Run `DefaultTopicCacheMaintenance.clearTopicCache()`.
 * 3. Assert both tables are empty afterwards : `getTopicPage(...)` is null and the
 *    posts lookup returns no rows.
 *
 * Robolectric so Room can build its in-memory SQLite — same pattern as
 * `TopicRepositoryImplTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DefaultTopicCacheMaintenanceTest {

    private lateinit var database: RedfaceDatabase
    private lateinit var topicDao: TopicDao
    private lateinit var flagDao: FlagDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, RedfaceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        topicDao = database.topicDao()
        flagDao = database.flagDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `clearTopicCache wipes both topic_pages and posts`() = runTest {
        val maintenance = DefaultTopicCacheMaintenance(topicDao, Dispatchers.Unconfined)

        val topicEntity = TopicEntity(
            cat = 13,
            post = 84_540,
            page = 2,
            title = "Topic test",
            totalPages = 5,
            isFirstPostOwner = false,
            pollJson = null,
            numreponses = listOf(1001, 1002),
            fetchedAt = Instant.parse("2026-05-24T22:00:00Z"),
            authMode = FetchMode.AUTHENTICATED,
            subcat = 432,
        )
        val postEntities = listOf(
            samplePost(cat = 13, post = 84_540, numreponse = 1001),
            samplePost(cat = 13, post = 84_540, numreponse = 1002),
        )
        topicDao.upsertTopicPageWithPosts(topicEntity, postEntities)
        // Sanity check : the seed actually wrote both rows.
        assertNotNull(topicDao.getTopicPage(13, 84_540, 2))
        assertEquals(2, topicDao.getPostsByNumreponse(13, listOf(1001, 1002)).size)

        maintenance.clearTopicCache()

        assertNull(
            "topic_pages must be empty after clearTopicCache",
            topicDao.getTopicPage(13, 84_540, 2),
        )
        assertTrue(
            "posts must be empty after clearTopicCache",
            topicDao.getPostsByNumreponse(13, listOf(1001, 1002)).isEmpty(),
        )
    }

    @Test
    fun `clearTopicCache keeps flag cache untouched`() = runTest {
        val maintenance = DefaultTopicCacheMaintenance(topicDao, Dispatchers.Unconfined)
        val flag = sampleFlag()
        topicDao.upsertTopicPageWithPosts(
            TopicEntity(
                cat = 23,
                post = 35_395,
                page = 24,
                title = "Redface 2",
                totalPages = 24,
                isFirstPostOwner = false,
                pollJson = null,
                numreponses = listOf(2_785_212),
                fetchedAt = Instant.parse("2026-05-24T22:00:00Z"),
                authMode = FetchMode.AUTHENTICATED,
                subcat = 550,
            ),
            listOf(samplePost(cat = 23, post = 35_395, numreponse = 2_785_212)),
        )
        flagDao.upsertAll(listOf(flag))

        maintenance.clearTopicCache()

        assertTrue(topicDao.getPostsByNumreponse(23, listOf(2_785_212)).isEmpty())
        assertEquals(
            "flag_topics must survive the topic-only maintenance wipe",
            listOf(flag),
            flagDao.getFlags("xatelitte", FlagType.CYAN),
        )
    }

    @Test
    fun `clearTopicCache is idempotent — running it on an empty cache does not throw`() = runTest {
        val maintenance = DefaultTopicCacheMaintenance(topicDao, Dispatchers.Unconfined)

        // No seed — straight to clear. Room DELETEs against empty tables are no-ops; we
        // pin the behaviour because the UI button can be clicked even when there is
        // nothing to clear, and a user re-tap after a successful clear must not crash.
        maintenance.clearTopicCache()

        assertNull(topicDao.getTopicPage(13, 84_540, 2))
    }

    private fun samplePost(cat: Int, post: Int, numreponse: Int): PostEntity = PostEntity(
        cat = cat,
        numreponse = numreponse,
        post = post,
        author = "tester",
        date = Instant.parse("2026-05-24T22:00:00Z"),
        content = PostContent(blocks = emptyList()),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
        fetchedAt = Instant.parse("2026-05-24T22:00:00Z"),
        authMode = FetchMode.AUTHENTICATED,
    )

    private fun sampleFlag(): FlagTopicEntity = FlagTopicEntity(
        userId = "xatelitte",
        type = FlagType.CYAN,
        cat = 23,
        subcat = 550,
        topicId = 35_395,
        title = "Redface 2",
        totalPages = 24,
        replyCount = 2_785_212,
        hasUnread = true,
        lastReadPage = 24,
        lastPostReadId = 2_785_212L,
        firstPostAuthor = "XaTriX",
        lastReplyAuthor = "Lt Ripley",
        lastReplyAt = "2026-05-24 22:00",
        fetchedAt = Instant.parse("2026-05-24T22:00:00Z"),
        authMode = FetchMode.AUTHENTICATED,
    )
}
