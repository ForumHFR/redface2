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
    /**
     * Whether this row was written from an authenticated or anonymous request.
     * Anonymous prefetch (Phase 1D PR 4) must not overwrite an authenticated
     * row — see [FetchMode]. Defaulted to [FetchMode.AUTHENTICATED] so existing
     * v1 rows keep their richer auth-derived fields after the v1→v2 migration.
     */
    val authMode: FetchMode,
)
