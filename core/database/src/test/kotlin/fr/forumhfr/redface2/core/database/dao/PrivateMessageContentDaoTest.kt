package fr.forumhfr.redface2.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.database.entities.PrivateMessageEntity
import fr.forumhfr.redface2.core.database.entities.PrivateMessageThreadPageEntity
import fr.forumhfr.redface2.core.model.PostContent
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PrivateMessageContentDaoTest {
    private lateinit var database: RedfaceDatabase
    private lateinit var dao: PrivateMessageContentDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
            RedfaceDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = database.privateMessageContentDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `replace reads by ordinal and removes messages absent from the replacement`() = runTest {
        val page = page(userId = "alice", threadId = 42, page = 1)
        dao.replacePage(
            page,
            messages = listOf(
                message(page, numreponse = 900, ordinal = 1),
                message(page, numreponse = 100, ordinal = 0),
            ),
            maxPages = 5,
        )

        assertEquals(listOf(100, 900), dao.getPage("alice", 42, 1)?.messages?.map { it.numreponse })

        dao.replacePage(
            page.copy(subject = "updated"),
            messages = listOf(message(page, numreponse = 900, ordinal = 0)),
            maxPages = 5,
        )

        val replaced = dao.getPage("alice", 42, 1)
        assertEquals("updated", replaced?.page?.subject)
        assertEquals(listOf(900), replaced?.messages?.map { it.numreponse })
    }

    @Test
    fun `failed replacement rolls back page metadata and every previous message`() = runTest {
        val oldPage = page(userId = "alice", threadId = 42, page = 1).copy(subject = "old")
        dao.replacePage(
            oldPage,
            messages = listOf(
                message(oldPage, numreponse = 100, ordinal = 0),
                message(oldPage, numreponse = 200, ordinal = 1),
            ),
            maxPages = 5,
        )
        val missingParent = page(userId = "bob", threadId = 99, page = 1)

        val failure = runCatching {
            dao.replacePage(
                oldPage.copy(subject = "new"),
                messages = listOf(
                    message(oldPage, numreponse = 300, ordinal = 0),
                    message(missingParent, numreponse = 999, ordinal = 1),
                ),
                maxPages = 5,
            )
        }.exceptionOrNull()

        assertTrue("the missing parent must violate the composite foreign key", failure != null)
        val restored = dao.getPage("alice", 42, 1)
        assertEquals("old", restored?.page?.subject)
        assertEquals(listOf(100, 200), restored?.messages?.map { it.numreponse })
        assertNull(dao.getPage("bob", 99, 1))
    }

    @Test
    fun `six tied pages evict the deterministic lowest parent key and its children`() = runTest {
        (6 downTo 1).forEach { threadId ->
            val page = page(userId = "alice", threadId = threadId, page = 1)
            dao.replacePage(
                page,
                messages = listOf(message(page, numreponse = threadId, ordinal = 0)),
                maxPages = 5,
            )
        }

        assertNull(dao.getPage("alice", 1, 1))
        assertEquals(emptyList<PrivateMessageEntity>(), dao.getOrderedMessages("alice", 1, 1))
        assertEquals(5, dao.getPagesOldestFirst("alice").size)
        assertEquals(listOf(2, 3, 4, 5, 6), dao.getPagesOldestFirst("alice").map { it.threadId })
    }

    @Test
    fun `clearForUser removes only that account from both tables`() = runTest {
        insertPageWithMessage(userId = "alice", threadId = 1)
        insertPageWithMessage(userId = "bob", threadId = 2)

        dao.clearForUser("alice")

        assertNull(dao.getPage("alice", 1, 1))
        assertEquals(emptyList<PrivateMessageEntity>(), dao.getOrderedMessages("alice", 1, 1))
        assertEquals(2, dao.getPage("bob", 2, 1)?.page?.threadId)
        assertEquals(1, dao.getOrderedMessages("bob", 2, 1).size)
    }

    @Test
    fun `clearAll transaction removes pages and messages for every account`() = runTest {
        insertPageWithMessage(userId = "alice", threadId = 1)
        insertPageWithMessage(userId = "bob", threadId = 2)

        dao.clearAll()

        listOf("alice" to 1, "bob" to 2).forEach { (userId, threadId) ->
            assertNull(dao.getPage(userId, threadId, 1))
            assertEquals(emptyList<PrivateMessageEntity>(), dao.getOrderedMessages(userId, threadId, 1))
        }
    }

    private suspend fun insertPageWithMessage(userId: String, threadId: Int) {
        val page = page(userId, threadId, page = 1)
        dao.replacePage(
            page,
            messages = listOf(message(page, numreponse = threadId, ordinal = 0)),
            maxPages = 5,
        )
    }

    private fun page(
        userId: String,
        threadId: Int,
        page: Int,
    ): PrivateMessageThreadPageEntity = PrivateMessageThreadPageEntity(
        userId = userId,
        threadId = threadId,
        page = page,
        subject = "subject",
        correspondent = "correspondent",
        totalPages = 1,
        canReply = true,
        isMultiRecipient = false,
        fetchedAt = FETCHED_AT,
    )

    private fun message(
        parent: PrivateMessageThreadPageEntity,
        numreponse: Int,
        ordinal: Int,
    ): PrivateMessageEntity = PrivateMessageEntity(
        userId = parent.userId,
        threadId = parent.threadId,
        page = parent.page,
        numreponse = numreponse,
        ordinal = ordinal,
        author = "author",
        date = FETCHED_AT,
        content = PostContent(emptyList()),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
        quoteRef = null,
        profileId = null,
        editedAt = null,
        citedCount = null,
        signature = null,
    )

    private companion object {
        val FETCHED_AT: Instant = Instant.parse("2026-08-24T12:00:00Z")
    }
}
