package fr.forumhfr.redface2.core.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/** #690 — width of the optional dark outline drawn around the marker ([LocalFlagMarkerBorder]). */
private val MARKER_BORDER_WIDTH = 0.5.dp

/** #690 — opacity of the marker outline; dimmed by [READ_ALPHA] on read rows to match the fill. */
private const val MARKER_BORDER_ALPHA = 0.5f

/**
 * The left-hand marker of a Drapeaux row (#603, ADR-017). Encodes the flag color (cyan / red / favori,
 * the favori decoration winning over the bucket — legacy parity) and the read state (desaturated when
 * read); only the SHAPE varies with [style]:
 *
 * - [MarkerStyle.STRIPE] — a thin vertical color bar (the default, frees the most title width).
 *   IMPORTANT: STRIPE uses `fillMaxHeight` to span the row, so it MUST sit in a bounded-height parent
 *   (e.g. [ForumListRow]'s `IntrinsicSize.Min` Row). In an unbounded-height context it would stretch
 *   to fill all available height — wrap it in `Modifier.height(IntrinsicSize.Min)` or give it a height.
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
    // #690 — optional thin dark outline (GLOBAL pref read from the CompositionLocal, no threading). The
    // border is dimmed on read rows so it tracks the desaturated fill instead of drawing a crisp ring
    // around a faded marker.
    val drawBorder = LocalFlagMarkerBorder.current
    val borderColor = Color.Black.copy(
        alpha = if (hasUnread) MARKER_BORDER_ALPHA else MARKER_BORDER_ALPHA * READ_ALPHA,
    )
    when (style) {
        MarkerStyle.STRIPE -> Box(
            // #603 — the bar spans the row's content height (fillMaxHeight within ForumListRow's
            // IntrinsicSize.Min) instead of a fixed 32 dp that fell short on 2-line titles.
            modifier = modifier
                .fillMaxHeight()
                .width(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
                .markerOutline(drawBorder, borderColor, RoundedCornerShape(2.dp)),
        )

        MarkerStyle.DOT -> Box(
            modifier = modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
                .markerOutline(drawBorder, borderColor, CircleShape),
        )

        MarkerStyle.PASTILLE -> Box(
            modifier = modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = if (hasUnread) PASTILLE_BG_ALPHA else PASTILLE_BG_ALPHA / 2))
                .markerOutline(drawBorder, borderColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            RedfaceVectorIcon(resId = categoryIconRes, tint = color, size = 18.dp)
        }
    }
}

/**
 * #690 — applies the optional [MARKER_BORDER_WIDTH] outline ([color]) clipped to [shape], or returns the
 * receiver unchanged when [enabled] is false. Kept as a private extension so each marker shape stays a
 * single readable modifier chain.
 */
private fun Modifier.markerOutline(enabled: Boolean, color: Color, shape: Shape): Modifier =
    if (enabled) border(MARKER_BORDER_WIDTH, color, shape) else this

/**
 * Trailing « pages à lire » pill of a Drapeaux row (#603) — shown only when the topic is unread and
 * has at least one page left. Reads `+N` in the flag's accent color over a tonal background.
 */
@Composable
fun PagesToReadPill(count: Int, accent: Color, modifier: Modifier = Modifier) {
    // Audit #3 — the "+N" glyph alone reads as « plus 3 » with no context in TalkBack. Carry a clear,
    // localized, PLURAL-AWARE label on the container and mark the inner Text decorative so the pill
    // announces a single meaningful sentence (« 1 page à lire » vs « N pages à lire »).
    val pagesToReadDescription = pluralStringResource(R.plurals.flag_pages_to_read_a11y, count, count)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(accent.copy(alpha = 0.20f))
            .padding(horizontal = 7.dp, vertical = 1.dp)
            .semantics { contentDescription = pagesToReadDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+$count",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            // Decorative — the container's contentDescription owns the announcement.
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}
