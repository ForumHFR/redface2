package fr.forumhfr.redface2.core.model

/**
 * Pure derivations of a [Flag] shared by every layer that renders or maps a flag (#603, ADR-017).
 * They live in `:core:model` — the only module visible to BOTH the UI primitives (`:core:ui`
 * `FlagItem`/`FlagMarker`) and the feature mapper (`:feature:flags` `toFlagRowUiModel`) — so the rules
 * have a SINGLE source of truth instead of being recomputed (and silently diverging) per layer.
 * No Android / Compose types here.
 */

/**
 * Number of pages the user has left to read: `max(totalPages - lastReadPage, 0)`.
 *
 * Clamped at 0 (a stale cache can carry a [Flag.lastReadPage] past a shrunk [Flag.totalPages]).
 * A DISPLAY counter, not an unread oracle: it can be `0` while [Flag.hasUnread] is `true` (unread
 * posts on the last-read page). [Flag.hasUnread] stays the source of truth for read state.
 */
fun Flag.pagesToRead(): Int = (totalPages - lastReadPage).coerceAtLeast(0)

/**
 * The flag color actually shown for a row: the favori/étoile decoration WINS over the bucket color
 * (#384 / dev v118 web parity — a favorited topic listed under « Mes sujets » keeps its yellow
 * marker), so a favorited row reads [FlagType.FAVORITE] regardless of which bucket it was fetched
 * from. Plain (`type`, `isFavorite`) overload so the UI marker primitive can call it without a [Flag].
 */
fun effectiveFlagColor(type: FlagType, isFavorite: Boolean): FlagType =
    if (isFavorite) FlagType.FAVORITE else type

/** [Flag] convenience over [effectiveFlagColor]. */
fun Flag.effectiveFlagColor(): FlagType = effectiveFlagColor(type, isFavorite)

/**
 * HFR's default posts-per-page, used when REST omits `results_per_page` on the `posts` href.
 * See [Flag.postsPerPage] for why the real value is carried per flag instead of assumed.
 */
const val DEFAULT_POSTS_PER_PAGE = 40

/**
 * #638 — the page a flag row must OPEN on (thony94 / MisterDams, dev v178-179).
 *
 * The bug: tapping a row opened [Flag.lastReadPage], the page where the read marker sits. When the
 * last-read post is the LAST post of that page and a further page exists, that reopens an
 * already-read page with the read post at the bottom, instead of moving on. RF1 went to the next
 * page. But `lastReadPage + 1` is not the fix either: when the user stopped MID-page, unread posts
 * remain on the current page and skipping it loses them.
 *
 * The discriminator is [Flag.lastPosition] (REST `last_position`, 1-based global index): the
 * last-read post is exactly at a page boundary iff `lastPosition % postsPerPage == 0`. So this
 * advances by one page ONLY in that case, and stays put otherwise. Because the decision is made
 * from data already in hand, it happens BEFORE navigation — deciding after the page is displayed
 * would reproduce the « flash before jump » of #477.
 *
 * Conservative by construction: every uncertainty (no unread, already on the last page, absent or
 * `0` [Flag.lastPosition] — including the « dernier lu supprimé » case of #394 — non-positive
 * [Flag.postsPerPage]) degrades to [Flag.lastReadPage], i.e. today's behaviour. We may fail to
 * advance, we never skip unread posts.
 *
 * When it advances, no client-side « last read » banner is needed: HFR itself opens page N+1 with a
 * « Reprise du message précédent » recap of the last post of page N (verified live 2026-08-04),
 * which IS the marker the issue asks for.
 */
fun Flag.pageToOpen(): Int {
    val current = lastReadPage.coerceAtLeast(1)
    val position = lastPosition ?: 0
    val divides = position > 0 && postsPerPage > 0 && position % postsPerPage == 0
    // A 1-based global index lands on a page boundary exactly when it divides the page size — but
    // only trust it when it also lands on the page the marker claims. Cross-checking the two REST
    // fields against each other guards a stale or inconsistent pair (cross-review Sol: a row
    // carrying lastReadPage = 12 with lastPosition = 40 would otherwise advance on the strength of
    // a position belonging to page 1).
    val positionPage = if (divides) position / postsPerPage else 0
    val stoppedAtPageEnd = divides && positionPage == current
    val advances = hasUnread && lastReadPage < totalPages && stoppedAtPageEnd
    return if (advances) (current + 1).coerceAtMost(totalPages) else current
}
