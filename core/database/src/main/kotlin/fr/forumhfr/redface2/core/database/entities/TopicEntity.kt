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
    /**
     * Sub-category id captured from the topic page HTML (Phase 2C, #145). Required by
     * HFR's write endpoints (`message.php` / `bddpost.php`). Defaults to `-1` for rows
     * persisted before the v3 → v4 migration; the value `-1` is a sentinel meaning
     * "unknown, refresh required" and is **never** sent to HFR — write flows check
     * `subcat > 0` before posting. The `> 0` (instead of `>= 0`) is intentional :
     * HFR's `cat=0` / `cat=prive` moderator-space wire shape emits `subcat=0` and
     * Phase 2C has no fixture proving it would be accepted, so write paths treat
     * `subcat=0` exactly like the missing-subcat sentinel.
     */
    val subcat: Int = -1,
)
