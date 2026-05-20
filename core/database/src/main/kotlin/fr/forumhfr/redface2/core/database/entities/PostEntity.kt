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
    /**
     * Phase 2D (#147) — true when HFR's topic toolbar exposed an edit link for
     * this post (`<a href="…message.php?…&numreponse=…">`), meaning the current
     * authenticated session owns the post. The column has existed on this
     * entity since v1 — Room ships it on every schema version — only the
     * parser-side semantics flipped from « always false » to « actually detect
     * the edit link » in Phase 2D. A cache hit therefore keeps the « Modifier »
     * UI button without a network round-trip.
     */
    val isEditable: Boolean,
    /**
     * Companion of [isEditable] : true when the post belongs to the
     * authenticated user. Phase 2D currently treats it as equivalent to
     * [isEditable] (HFR exposes the edit link only for the user's own posts on
     * unlocked topics), but the two fields are kept separate to leave room for
     * future authorisation refinements (e.g. moderator-can-edit, locked-but-
     * own-post). Same persistence story as [isEditable] : the column is part
     * of the v1 schema, only the parser now actually populates it.
     */
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
