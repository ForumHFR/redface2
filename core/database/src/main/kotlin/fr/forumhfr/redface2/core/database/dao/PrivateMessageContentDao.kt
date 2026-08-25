package fr.forumhfr.redface2.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import fr.forumhfr.redface2.core.database.entities.PrivateMessageEntity
import fr.forumhfr.redface2.core.database.entities.PrivateMessageThreadPageEntity

data class StoredPrivateMessageThreadPage(
    val page: PrivateMessageThreadPageEntity,
    val messages: List<PrivateMessageEntity>,
)

@Dao
interface PrivateMessageContentDao {
    @Query(
        """
        SELECT * FROM mp_thread_pages
        WHERE userId = :userId AND threadId = :threadId AND page = :page
        LIMIT 1
        """,
    )
    suspend fun getPageEntity(
        userId: String,
        threadId: Int,
        page: Int,
    ): PrivateMessageThreadPageEntity?

    @Query(
        """
        SELECT * FROM mp_messages
        WHERE userId = :userId AND threadId = :threadId AND page = :page
        ORDER BY ordinal ASC
        """,
    )
    suspend fun getOrderedMessages(
        userId: String,
        threadId: Int,
        page: Int,
    ): List<PrivateMessageEntity>

    @Transaction
    suspend fun getPage(
        userId: String,
        threadId: Int,
        page: Int,
    ): StoredPrivateMessageThreadPage? {
        val pageEntity = getPageEntity(userId, threadId, page) ?: return null
        return StoredPrivateMessageThreadPage(
            page = pageEntity,
            messages = getOrderedMessages(userId, threadId, page),
        )
    }

    @Query(
        """
        DELETE FROM mp_messages
        WHERE userId = :userId AND threadId = :threadId AND page = :page
        """,
    )
    suspend fun deleteMessagesForPage(userId: String, threadId: Int, page: Int)

    @Upsert
    suspend fun upsertPage(page: PrivateMessageThreadPageEntity)

    @Upsert
    suspend fun upsertMessages(messages: List<PrivateMessageEntity>)

    @Query(
        """
        SELECT * FROM mp_thread_pages
        WHERE userId = :userId
        ORDER BY fetchedAt ASC, threadId ASC, page ASC
        """,
    )
    suspend fun getPagesOldestFirst(userId: String): List<PrivateMessageThreadPageEntity>

    @Delete
    suspend fun deletePages(pages: List<PrivateMessageThreadPageEntity>)

    /** Replaces a complete page and evicts deterministically beyond [maxPages] for this account. */
    @Transaction
    suspend fun replacePage(
        page: PrivateMessageThreadPageEntity,
        messages: List<PrivateMessageEntity>,
        maxPages: Int,
    ) {
        deleteMessagesForPage(page.userId, page.threadId, page.page)
        upsertPage(page)
        if (messages.isNotEmpty()) upsertMessages(messages)
        val pagesToDelete = getPagesOldestFirst(page.userId).dropLast(maxPages)
        if (pagesToDelete.isNotEmpty()) deletePages(pagesToDelete)
    }

    @Query("DELETE FROM mp_messages WHERE userId = :userId")
    suspend fun deleteMessagesForUser(userId: String)

    @Query("DELETE FROM mp_thread_pages WHERE userId = :userId")
    suspend fun deletePagesForUser(userId: String)

    @Transaction
    suspend fun clearForUser(userId: String) {
        deleteMessagesForUser(userId)
        deletePagesForUser(userId)
    }

    @Query("DELETE FROM mp_messages")
    suspend fun deleteAllMessages()

    @Query("DELETE FROM mp_thread_pages")
    suspend fun deleteAllPages()

    /** Global transactional purge used when the application-wide preference is disabled. */
    @Transaction
    suspend fun clearAll() {
        deleteAllMessages()
        deleteAllPages()
    }
}
