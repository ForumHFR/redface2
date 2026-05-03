package fr.forumhfr.redface2.core.database.entities

import androidx.room.Entity
import androidx.room.Index
import fr.forumhfr.redface2.core.model.FlagType
import java.time.Instant

/**
 * One row of the user's drapeaux page persisted to disk.
 *
 * Isolation by account is enforced by including [userId] (HFR pseudo, lowercase)
 * in the primary key. A different account opening the app sees a disjoint row
 * set; on logout we wipe the table by [userId] (cf. CacheInvalidator).
 *
 * The shape is deliberately a flat 1:1 of the domain [fr.forumhfr.redface2.core.model.Flag]
 * — no normalization. Drapeaux pages are at most ~150 entries; relational
 * splits (eg. a `topics` join table) would multiply queries for zero data
 * savings.
 */
@Entity(
    tableName = "flag_topics",
    primaryKeys = ["userId", "type", "cat", "topicId"],
    indices = [
        Index(value = ["userId", "type"]),
        Index(value = ["userId", "fetchedAt"]),
    ],
)
data class FlagTopicEntity(
    /** Lowercased HFR pseudo of the account that owns this row. */
    val userId: String,
    val type: FlagType,
    val cat: Int,
    val subcat: Int?,
    val topicId: Int,
    val title: String,
    val totalPages: Int,
    val replyCount: Int,
    val hasUnread: Boolean,
    val lastReadPage: Int,
    /**
     * `numreponse` of the **last post the user read** (REST `last_post_read_id`).
     * Stored as `Long` for forward-compat with the legacy `numreponse` size; current
     * production values fit in `Int`. May be null when REST omits the field on a
     * given row. Narrowing to `Int` happens at the navigation boundary, not at
     * persistence.
     */
    val lastPostReadId: Long?,
    val firstPostAuthor: String,
    val lastReplyAuthor: String,
    /**
     * Raw HFR-printed timestamp (`YYYY-MM-DD HH:mm`, REST format) — see
     * [fr.forumhfr.redface2.core.model.Flag.lastReplyAt]. Lexicographic order matches
     * chronological order, so [FlagDao.getFlags] sorts on this column directly.
     */
    val lastReplyAt: String,
    val fetchedAt: Instant,
    val authMode: FetchMode,
)
