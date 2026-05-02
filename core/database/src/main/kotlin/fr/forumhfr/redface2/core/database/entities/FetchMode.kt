package fr.forumhfr.redface2.core.database.entities

/**
 * How a cached row was fetched. Used by the cache layer to prevent an anonymous
 * prefetch (Phase 1D PR 4) from overwriting a richer authenticated row that
 * carries per-user fields like `isOwnPost`, `isEditable`, `hasUnread`,
 * `lastReadPage` or `lastPostReadId`.
 *
 * Stored as the enum name (`AUTHENTICATED` / `ANONYMOUS`) by Room; cf.
 * [fr.forumhfr.redface2.core.database.converters.Converters.fetchModeToString].
 */
enum class FetchMode {
    AUTHENTICATED,
    ANONYMOUS,
}
