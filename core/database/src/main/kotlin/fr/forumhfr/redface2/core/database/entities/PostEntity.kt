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
     * 2 fix). Persisted in Room v5 so clear-link cache hits keep HFR's best-effort
     * positional quote ref. Nullable on disk : pre-v5 rows backfill to `NULL`, and
     * posts whose HFR HTML exposed no *clear* quote link (obfuscated
     * `md_*cryptlink` toolbar, locked topic, anonymous read) keep `NULL`. Since
     * #227 « Citer » is gated on `Topic.canReply`, not on this column — a `null`
     * quoteRef no longer hides the button (HFR quotes by `numrep={numreponse}` alone).
     */
    val quoteRef: Int? = null,
    /**
     * HFR user id from the profile link `<a href="/hfr/profil-{N}.htm">` in the
     * post toolbar. Phase 2 finish (#208). Nullable on disk for three reasons:
     *
     * - pre-v6 rows backfill to `NULL` (the next live fetch sets the real value);
     * - « Publicité » rows and anonymous reads legitimately have no profile link;
     * - future HFR changes may stop rendering the link for some post types.
     */
    val profileId: Int? = null,
    /**
     * #362 — last-edit timestamp parsed from the post's `div.edited` trailer
     * (« Message édité par <auteur> le DD-MM-YYYY à HH:MM:SS »). Persisted in
     * Room v8 (`MIGRATION_7_8`) so a cache hit keeps the « Édité le … » line of
     * the post menu without a network round-trip. Nullable on disk:
     *
     * - pre-v8 rows backfill to `NULL` (recovered on the next live fetch);
     * - never-edited posts legitimately carry no edit trailer — including posts
     *   whose `div.edited` only holds the « Message cité N fois » citation link.
     */
    val editedAt: Instant? = null,
    /**
     * #330 — the author signature AST (`<span class="signature">`), persisted as JSON via the
     * nullable [Converters.nullablePostContentToJson] converter so the « Afficher les signatures »
     * reading preference is a pure render-time switch (no refetch when toggled). Persisted in
     * Room v14 (`MIGRATION_13_14`). Nullable on disk:
     *
     * - pre-v14 rows backfill to `NULL` (recovered on the next live fetch);
     * - most posts legitimately carry no signature.
     */
    val signature: PostContent? = null,
)
