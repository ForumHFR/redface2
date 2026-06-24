package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType

/**
 * Pure presentation derivations for the #603 Drapeaux refonte (ADR-017). They turn a raw [Flag]
 * into the values the list row renders, so the computation lives OFF the composition (ViewModel /
 * mapper) and the LazyColumn never recomputes it. No Android / Compose types here — colors are
 * resolved at render time from [effectiveColor] via `FlagPalette`.
 */

/**
 * Number of pages the user has left to read on a topic: `max(totalPages - lastReadPage, 0)`.
 *
 * Clamped at 0 (a stale cache can carry a [Flag.lastReadPage] past a shrunk [Flag.totalPages]).
 *
 * This is a DISPLAY counter, not an unread oracle: it can be `0` while [Flag.hasUnread] is `true`
 * (unread posts on the last-read page). [Flag.hasUnread] stays the source of truth for read state.
 */
fun Flag.pagesToRead(): Int = (totalPages - lastReadPage).coerceAtLeast(0)

/**
 * The flag color actually shown for this row. The favori/étoile decoration WINS over the bucket
 * color (#384 / dev v118 web parity: a favorited topic listed under « Mes sujets » keeps its yellow
 * marker), so a favorited row reads [FlagType.FAVORITE] regardless of which bucket [Flag.type] it
 * was fetched from. Mirrors the legacy `FlagDot` logic as a single pure source of truth.
 */
fun Flag.effectiveFlagColor(): FlagType = if (isFavorite) FlagType.FAVORITE else type

/**
 * Precomputed presentation bundle for one Drapeaux list row — the "FlagUiModel" of the refonte.
 * Built once per flag in the data/VM layer ([toFlagRowUiModel]); the composition reads these values
 * directly instead of deriving them on each recomposition.
 *
 * @property flag the source domain row (title, authors, ids… consumed as-is by the row).
 * @property pagesToRead see [Flag.pagesToRead] — a display counter, not an unread oracle.
 * @property effectiveColor see [Flag.effectiveFlagColor] — the bucket color, favori-overridden.
 * @property dimmed `true` for a fully-read flag (`!hasUnread`): the marker is rendered desaturated.
 * @property markerStyle the configured marker shape for this view ([MarkerStyle], default STRIPE).
 */
data class FlagRowUiModel(
    val flag: Flag,
    val pagesToRead: Int,
    val effectiveColor: FlagType,
    val dimmed: Boolean,
    val markerStyle: MarkerStyle,
)

/** Builds the [FlagRowUiModel] for this flag under the given [markerStyle]. Pure. */
fun Flag.toFlagRowUiModel(markerStyle: MarkerStyle): FlagRowUiModel = FlagRowUiModel(
    flag = this,
    pagesToRead = pagesToRead(),
    effectiveColor = effectiveFlagColor(),
    dimmed = !hasUnread,
    markerStyle = markerStyle,
)
