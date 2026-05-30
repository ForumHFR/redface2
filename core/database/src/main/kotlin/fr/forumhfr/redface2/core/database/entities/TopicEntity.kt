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
     * Sub-category id of POST, read from the `bddpost` reply form (#213). Required by
     * HFR's write endpoints (`message.php` / `bddpost.php`). Defaults to `-1` for rows
     * persisted before the v3 → v4 migration; the value `-1` (SUBCAT_UNKNOWN) is a
     * sentinel meaning "no reply form was present (logged-out / anon prefetch / locked
     * topic), refresh required" and is **never** sent to HFR — write flows refuse
     * `subcat < 0`.
     *
     * `subcat = 0` is a **valid, postable** value (#213) : HFR emits `subcat=0` in the
     * reply form of a category WITHOUT a sub-category (e.g. cat=32
     * « Intelligence artificielle »), proven by a live capture (see
     * `docs/specs/protocol-hfr.md` § POST `bddpost.php`). Write paths gate on
     * `subcat >= 0`, not `> 0`.
     */
    val subcat: Int = -1,
    /**
     * Whether HFR rendered the `bddpost` reply form on the page this row was written
     * from (#213). Persisted in Room v7 (cf. `MIGRATION_6_7`) so a cache hit keeps the
     * reply / quote / edit buttons enabled without a network round-trip. Defaults to
     * `false` : pre-v7 rows backfill to `false` (recovered on the next live
     * authenticated fetch) and anonymous prefetch rows are read-only by construction —
     * see [fr.forumhfr.redface2.core.model.Topic.canReply].
     */
    val canReply: Boolean = false,
)
