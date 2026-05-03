package fr.forumhfr.redface2.core.model

/**
 * One row of the user's HFR drapeaux page. Phase 1D-1 reads this from the REST API
 * (`forums/hardwarefr/topics/{participated,read,favorites}/`) per ADR-003 — `forum1f.php`
 * HTML scraping has been retired. HFR exposes three drapeau buckets, mapped 1:1 to the
 * REST `flag_owntopic` integer :
 *
 * - `flag_owntopic=1` → [FlagType.CYAN] — sujets participés
 * - `flag_owntopic=2` → [FlagType.RED] — lus uniquement
 * - `flag_owntopic=3` → [FlagType.FAVORITE] — favoris
 *
 * Differences with the legacy HTML model :
 * - `views` was sourced from a column on `forum1f.php`. The REST flag listings do not
 *   advertise a view count; the field is gone rather than nullable everywhere.
 * - `firstUnreadPostId` was sourced from the `#t{numreponse}` href fragment on the
 *   HTML page (the first post the user had **not** yet read). REST exposes
 *   `last_post_read_id` (the **last** post the user has read), which is a different
 *   quantity. We store it under the more accurate name [lastPostReadId] and let the
 *   navigation layer scroll there — re-anchoring the reader on the last-known-read
 *   post is close enough to the legacy "where I stopped" UX without claiming a
 *   first-unread we cannot prove.
 */
data class Flag(
    val cat: Int,
    val subcat: Int?,
    val topicId: Int,
    val title: String,
    val totalPages: Int,
    val replyCount: Int,
    val type: FlagType,
    val hasUnread: Boolean,
    /**
     * Page where the user's last read marker is set, derived from
     * `links.posts.href?page=N` on the REST payload. Tapping the flag jumps to this
     * page; the scroll anchor is [lastPostReadId] when available. When the topic has
     * been fully read this typically equals the last page of the topic.
     */
    val lastReadPage: Int,
    /**
     * `numreponse` of the **last post the user read** (REST `last_post_read_id`), used
     * as a deep-link scroll anchor. May be null when the REST payload omits the field
     * (anonymous responses, edge cases). Stored as `Long` for forward-compat with the
     * legacy `numreponse` size — current production values fit in `Int` (~10M).
     */
    val lastPostReadId: Long?,
    val firstPostAuthor: String,
    val lastReplyAuthor: String,
    /**
     * Last reply timestamp as printed by HFR REST (`YYYY-MM-DD HH:mm`). Kept as a raw
     * string at this layer; date parsing is the upstream concern of the data layer.
     */
    val lastReplyAt: String,
)

enum class FlagType {
    /** Cyan drapeau — sujets participés (`flag_owntopic=1`). */
    CYAN,
    /** Red drapeau — lus uniquement (`flag_owntopic=2`). */
    RED,
    /** Yellow star — favoris (`flag_owntopic=3`). */
    FAVORITE,
}
