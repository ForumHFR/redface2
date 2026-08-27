package fr.forumhfr.redface2.core.data.messages

import fr.forumhfr.redface2.core.database.dao.PrivateMessageContentDao
import fr.forumhfr.redface2.core.database.dao.StoredPrivateMessageThreadPage
import fr.forumhfr.redface2.core.database.entities.PrivateMessageEntity
import fr.forumhfr.redface2.core.database.entities.PrivateMessageThreadPageEntity
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomPrivateMessageThreadDiskCacheTest {
    @Test
    fun `every operation uses the same lowercase-only Room account key`() = runTest {
        val dao = mockk<PrivateMessageContentDao>(relaxed = true)
        val pageSlot = slot<PrivateMessageThreadPageEntity>()
        val messagesSlot = slot<List<PrivateMessageEntity>>()
        val scrubber = mockk<PrivateContentDatabaseScrubber>(relaxed = true)
        coEvery { dao.replacePage(capture(pageSlot), capture(messagesSlot), 5) } returns Unit
        val cache = RoomPrivateMessageThreadDiskCache(dao, scrubber)
        val rawPseudo = " Alice  \u200bX "

        cache.replace(rawPseudo, thread(), FETCHED_AT)
        cache.read(rawPseudo, threadId = 42, page = 1)
        cache.clearForUser(rawPseudo)

        val expected = " alice  \u200bx "
        assertEquals(expected, pageSlot.captured.userId)
        assertEquals(listOf(expected), messagesSlot.captured.map { it.userId }.distinct())
        coVerify(exactly = 1) { dao.getPage(expected, 42, 1) }
        coVerify(exactly = 1) { dao.clearForUser(expected) }
        coVerify(exactly = 1) { scrubber.scrub() }
    }

    @Test
    fun `global purge deletes both tables before scrubbing SQLite`() = runTest {
        val dao = mockk<PrivateMessageContentDao>(relaxed = true)
        val scrubber = mockk<PrivateContentDatabaseScrubber>(relaxed = true)
        val cache = RoomPrivateMessageThreadDiskCache(dao, scrubber)

        cache.clearAll()

        coVerifyOrder {
            dao.clearAll()
            scrubber.scrub()
        }
    }

    @Test
    fun `moderation marker survives both private message cache mapper directions`() = runTest {
        val dao = mockk<PrivateMessageContentDao>(relaxed = true)
        val pageSlot = slot<PrivateMessageThreadPageEntity>()
        val messagesSlot = slot<List<PrivateMessageEntity>>()
        val scrubber = mockk<PrivateContentDatabaseScrubber>(relaxed = true)
        coEvery { dao.replacePage(capture(pageSlot), capture(messagesSlot), 5) } returns Unit
        val cache = RoomPrivateMessageThreadDiskCache(dao, scrubber)

        cache.replace("Alice", thread(isModerationPost = true), FETCHED_AT)

        assertTrue(messagesSlot.captured.single().isModerationPost)
        coEvery { dao.getPage("alice", 42, 1) } returns StoredPrivateMessageThreadPage(
            page = pageSlot.captured,
            messages = messagesSlot.captured,
        )

        assertTrue(requireNotNull(cache.read("Alice", 42, 1)).messages.single().isModerationPost)
    }

    private fun thread(isModerationPost: Boolean = false) = PrivateMessageThread(
        threadId = 42,
        subject = "subject",
        correspondent = "correspondent",
        messages = listOf(
            Post(
                numreponse = 7,
                author = "author",
                date = FETCHED_AT,
                content = PostContent(emptyList()),
                avatarUrl = null,
                isEditable = false,
                isOwnPost = false,
                quotedAuthors = emptyList(),
                postIndex = null,
                isModerationPost = isModerationPost,
            ),
        ),
        page = 1,
        totalPages = 1,
    )

    private companion object {
        val FETCHED_AT: Instant = Instant.parse("2026-08-24T12:00:00Z")
    }
}
