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
    /**
     * Same anti-overwrite guard as [TopicEntity.authMode]. An anonymous prefetch
     * row must not blindly clobber an authenticated row with stale `isOwnPost` /
     * `isEditable` flags.
     */
    val authMode: FetchMode,
    /**
     * `ref` parameter parsed from HFR's quote link href (Phase 2C, #146 — round
     * 2 fix). Persisted in Room v5 so the « Citer » button stays available on a
     * cache hit ; without this column, a fresh cache load would reset all
     * quoteRefs to `null` and the UI would suppress quote until the next live
     * fetch. Nullable on disk : pre-v5 rows backfill to `NULL` (sentinel for
     * « unknown, refresh required »), and posts whose HFR HTML did not expose a
     * quote link (locked topic, anonymous read) keep `NULL` legitimately.
     */
    val quoteRef: Int? = null,
)
