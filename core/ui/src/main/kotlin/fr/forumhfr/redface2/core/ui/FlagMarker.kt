package fr.forumhfr.redface2.core.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.effectiveFlagColor
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon
import fr.forumhfr.redface2.core.ui.theme.FlagPalette

/** Alpha applied to a marker / pill of a fully-read flag, so unread rows visibly pop (legacy parity). */
private const val READ_ALPHA = 0.35f

/** Background alpha of the tonal PASTILLE behind the category glyph. */
private const val PASTILLE_BG_ALPHA = 0.18f

/**
 * The left-hand marker of a Drapeaux row (#603, ADR-017). Encodes the flag color (cyan / red / favori,
 * the favori decoration winning over the bucket — legacy parity) and the read state (desaturated when
 * read); only the SHAPE varies with [style]:
 *
 * - [MarkerStyle.STRIPE] — a thin vertical color bar (the default, frees the most title width).
 * - [MarkerStyle.PASTILLE] — a tonal circle carrying the category glyph ([categoryIconRes]).
 * - [MarkerStyle.DOT] — the legacy minimal colored dot.
 *
 * The color logic mirrors the former `FlagDot` as the single rendering source of truth.
 */
@Composable
// Marker primitive: shape + the 3 flag fields that drive its color/state + the category glyph +
// modifier — kept as primitives (not a [Flag]) so non-drapeau rows can reuse it.
@Suppress("LongParameterList")
fun FlagMarker(
    style: MarkerStyle,
    type: FlagType,
    isFavorite: Boolean,
    hasUnread: Boolean,
    @DrawableRes categoryIconRes: Int,
    modifier: Modifier = Modifier,
) {
    // Favori-wins resolved by the single source of truth in :core:model (shared with FlagItem and the
    // VM mapper) so the rule can't silently diverge per layer.
    val base = FlagPalette.colorFor(effectiveFlagColor(type, isFavorite))
    val color = if (hasUnread) base else base.copy(alpha = READ_ALPHA)
    when (style) {
        MarkerStyle.STRIPE -> Box(
            modifier = modifier
                .size(width = 4.dp, height = 32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )

        MarkerStyle.DOT -> Box(
            modifier = modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color),
        )

        MarkerStyle.PASTILLE -> Box(
            modifier = modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = if (hasUnread) PASTILLE_BG_ALPHA else PASTILLE_BG_ALPHA / 2)),
            contentAlignment = Alignment.Center,
        ) {
            RedfaceVectorIcon(resId = categoryIconRes, tint = color, size = 18.dp)
        }
    }
}

/**
 * Trailing « pages à lire » pill of a Drapeaux row (#603) — shown only when the topic is unread and
 * has at least one page left. Reads `+N` in the flag's accent color over a tonal background.
 */
@Composable
fun PagesToReadPill(count: Int, accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(accent.copy(alpha = 0.20f))
            .padding(horizontal = 7.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+$count",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
    }
}
