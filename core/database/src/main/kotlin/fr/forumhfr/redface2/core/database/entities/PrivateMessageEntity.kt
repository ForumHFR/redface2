package fr.forumhfr.redface2.core.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import fr.forumhfr.redface2.core.model.PostContent
import java.time.Instant

/** One rendered message AST belonging to a persisted private-message page. */
@Entity(
    tableName = "mp_messages",
    primaryKeys = ["userId", "threadId", "page", "numreponse"],
    foreignKeys = [
        ForeignKey(
            entity = PrivateMessageThreadPageEntity::class,
            parentColumns = ["userId", "threadId", "page"],
            childColumns = ["userId", "threadId", "page"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["userId", "threadId", "page", "ordinal"])],
)
data class PrivateMessageEntity(
    val userId: String,
    val threadId: Int,
    val page: Int,
    val numreponse: Int,
    val ordinal: Int,
    val author: String,
    val date: Instant,
    val content: PostContent,
    val avatarUrl: String?,
    val isEditable: Boolean,
    val isOwnPost: Boolean,
    val quotedAuthors: List<String>,
    val postIndex: Int?,
    val quoteRef: Int?,
    val profileId: Int?,
    val editedAt: Instant?,
    val citedCount: Int?,
    val signature: PostContent?,
)
