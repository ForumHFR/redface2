package fr.forumhfr.redface2.core.model

/**
 * One row of the user's HFR drapeaux page. Phase 1D-1 reads this from the REST API
 * (`forums/hardwarefr/topics/{participated,read,favorites}/`) per ADR-003 — `forum1f.php`
 * HTML scraping has been retired. HFR exposes three drapeau buckets:
 *
 * - `participated/` → [FlagType.CYAN] — sujets participés
 * - `read/` → [FlagType.RED] — lus uniquement
 * - `favorites/` → [FlagType.FAVORITE] — favoris
 *
 * [type] is the **bucket the row was fetched from**, NOT the REST `flag_owntopic` integer.
 * Live-verified 2026-06-11 (#384, fixture `rest_cat13_participated_favorites.json`): the
 * `participated` bucket returns participated-AND-favorited topics with `flag_owntopic=3`, and
 * the `read` bucket returns `flag_owntopic=0` — the field describes the strongest flag ON the
 * topic, not bucket membership. Mapping it to [type] corrupted the per-type Room cache.
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
    /**
     * True when the topic ALSO carries the favori/étoile flag (REST `flag_owntopic == 3`),
     * regardless of the bucket this row was fetched from. [type] stays the bucket (#384 — mapping
     * `flag_owntopic` to [type] corrupted the per-type cache); this field only carries the
     * decoration: a favorited topic listed under « Mes sujets » keeps its yellow dot (web parity,
     * regression reported on dev v118). `false` when REST omits the field.
     */
    val isFavorite: Boolean = false,
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
    /**
     * #638 — REST `last_position`: the **1-based global index** of the last post the user read
     * within the whole topic (NOT a position inside its page, and NOT a page number). Combined with
     * [postsPerPage] it is the only way to know whether the last-read post sits at the very END of
     * its page, which is what [pageToOpen] needs: `lastReadPage` alone cannot distinguish « stopped
     * mid-page » from « stopped at the bottom of the page », and those need opposite behaviours.
     *
     * The 1-based convention is pinned by the fixtures: `last_position = 40` comes with
     * `page = 1` (0-based would put index 40 on page 2), `last_position = 600000` with
     * `page = 15000` (600000 / 40 exactly), and a fully-read topic carries
     * `last_position == links.posts.count` (595908 / 595908). HFR's « Reprise du message
     * précédent » recap at the top of page N+1 does NOT consume a position.
     *
     * `0` is HFR's « never read anything » sentinel, and the field is absent on anonymous rows —
     * both land as a value [pageToOpen] refuses to act on.
     */
    val lastPosition: Int? = null,
    /**
     * #638 — posts per page for THIS topic, from `results_per_page` on the REST `posts` href
     * (40 in practice). Carried per flag rather than hardcoded: the server normalises the value and
     * the user's own `topicpp` profile option exists, so a constant would silently break
     * [pageToOpen] the day either changes.
     */
    val postsPerPage: Int = DEFAULT_POSTS_PER_PAGE,
    val firstPostAuthor: String,
    val lastReplyAuthor: String,
    /**
     * Last reply timestamp as printed by HFR REST (`YYYY-MM-DD HH:mm`). Kept as a raw
     * string at this layer; date parsing is the upstream concern of the data layer.
     */
    val lastReplyAt: String,
)

/**
 * The REST bucket a flag row belongs to. NOT a mirror of the REST `flag_owntopic` response
 * field — that field describes the strongest flag ON the topic, not bucket membership (see
 * the [Flag] KDoc). The 1/2/3 integers only reappear as the WRITE-side `owntopic` selector
 * of `delflag.php` (cf. `HfrClient.removeFlag`).
 */
enum class FlagType {
    /** Cyan drapeau — bucket `participated/` (sujets participés). */
    CYAN,
    /** Red drapeau — bucket `read/` (lus uniquement). */
    RED,
    /** Yellow star — bucket `favorites/` (favoris). */
    FAVORITE,
}
