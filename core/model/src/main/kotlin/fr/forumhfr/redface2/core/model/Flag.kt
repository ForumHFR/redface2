package fr.forumhfr.redface2.core.model

/**
 * One row of the user's HFR drapeaux page (`forum1f.php?config=hfr.inc&owntopic=N`).
 *
 * HFR exposes three drapeau categories (selected via the `owntopic` query param,
 * confirmed by the onglet titles in the captured fixtures):
 *
 * - `owntopic=1` → [FlagType.CYAN] — « Tous les sujets auxquels j'ai participé »
 * - `owntopic=2` → [FlagType.RED] — « Tous les sujets que j'ai commencé à lire uniquement »
 * - `owntopic=3` → [FlagType.FAVORITE] — « Tous mes favoris »
 *
 * The icon filename in the listing's `td.sujetCase5 img[src]` carries both the
 * type and a `hasUnread` axis — `flag0/flag1/favoris` mean unread, `flagn0/flagn1/favorisn`
 * mean read. Convention extracted from production HFR captures (cf.
 * `core/parser/src/test/resources/fixtures/flags_page_owntopic-{1,2,3}.html`).
 */
data class Flag(
    val cat: Int,
    val subcat: Int?,
    val topicId: Int,
    val title: String,
    /**
     * Total number of pages in the topic — read from `td.sujetCase4` ("Dern. page" column,
     * which links to the topic's last page).
     */
    val totalPages: Int,
    /** Number of replies — read from `td.sujetCase7` (the "Rép." column header). */
    val replyCount: Int,
    /** Number of views — read from `td.sujetCase8` (the "Lues" column header). */
    val views: Int,
    val type: FlagType,
    val hasUnread: Boolean,
    /**
     * Page where the user's last read marker is set. Tapping the flag in HFR jumps to this
     * page and scrolls to [firstUnreadPostId]. When [hasUnread] is false, this typically
     * equals the last page of the topic.
     */
    val lastReadPage: Int,
    /**
     * `numreponse` of the first unread post (suffix `#t{id}` in the listing). Useful for
     * deep-linking the user back to where they stopped reading.
     */
    val firstUnreadPostId: Long,
    val firstPostAuthor: String,
    val lastReplyAuthor: String,
    /**
     * Last reply timestamp as printed by HFR (`DD-MM-YYYY HH:mm`). Kept as a raw string at
     * this layer; date parsing is the upstream concern of the data layer (cf. `HfrDateParser`
     * in :core:parser).
     */
    val lastReplyAt: String,
)

enum class FlagType {
    /** Cyan drapeau — sujets participés (`owntopic=1`, `flag1.gif` / `flagn1.gif`). */
    CYAN,
    /** Red drapeau — lus uniquement (`owntopic=2`, `flag0.gif` / `flagn0.gif`). */
    RED,
    /** Yellow star — favoris (`owntopic=3`, `favoris.gif` / `favorisn.gif`). */
    FAVORITE,
}
