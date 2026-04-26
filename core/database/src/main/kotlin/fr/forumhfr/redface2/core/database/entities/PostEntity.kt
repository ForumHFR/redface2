package fr.forumhfr.redface2.core.database.entities

import androidx.room.Entity
import androidx.room.Index
import fr.forumhfr.redface2.core.model.PostContent
import java.time.Instant

@Entity(
    tableName = "posts",
    primaryKeys = ["cat", "numreponse"],
    indices = [Index(value = ["cat", "post"])],
)
data class PostEntity(
    val cat: Int,
    val numreponse: Int,
    val post: Int,
    val author: String,
    val date: Instant,
    val content: PostContent,
    val avatarUrl: String?,
    val isEditable: Boolean,
    val isOwnPost: Boolean,
    val quotedAuthors: List<String>,
    val postIndex: Int?,
    val fetchedAt: Instant,
)
