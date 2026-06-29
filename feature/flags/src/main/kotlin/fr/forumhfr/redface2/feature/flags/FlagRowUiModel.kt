package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.effectiveFlagColor
import fr.forumhfr.redface2.core.model.pagesToRead

/**
 * VM-side presentation bundle for the #603 Drapeaux refonte (ADR-017). The pure per-flag derivations
 * ([pagesToRead], [effectiveFlagColor]) live in `:core:model` (single source of truth, also used by
 * the `:core:ui` row primitives); this file only bundles them for the data/VM layer.
 */

/**
 * Precomputed presentation bundle for one Drapeaux list row — the "FlagUiModel" of the refonte.
 * Built once per flag in the data/VM layer ([toFlagRowUiModel]) so a consumer reads these values
 * directly instead of deriving them per recomposition.
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
