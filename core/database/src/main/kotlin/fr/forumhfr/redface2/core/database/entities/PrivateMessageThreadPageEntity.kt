package fr.forumhfr.redface2.core.database.entities

import androidx.room.Entity
import java.time.Instant

/** Metadata for one terminally fetched private-message page, scoped to one account. */
@Entity(
    tableName = "mp_thread_pages",
    primaryKeys = ["userId", "threadId", "page"],
)
data class PrivateMessageThreadPageEntity(
    val userId: String,
    val threadId: Int,
    val page: Int,
    val subject: String,
    val correspondent: String,
    val totalPages: Int,
    val canReply: Boolean,
    val isMultiRecipient: Boolean,
    val fetchedAt: Instant,
)
