package fr.forumhfr.redface2.core.database.entities

import androidx.room.Entity
import java.time.Instant

@Entity(
    tableName = "topic_pages",
    primaryKeys = ["cat", "post", "page"],
)
data class TopicEntity(
    val cat: Int,
    val post: Int,
    val page: Int,
    val title: String,
    val totalPages: Int,
    val isFirstPostOwner: Boolean,
    val pollJson: String?,
    val numreponses: List<Int>,
    val fetchedAt: Instant,
)
