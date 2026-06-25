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
