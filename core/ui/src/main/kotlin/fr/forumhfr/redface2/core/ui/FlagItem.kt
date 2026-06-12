package fr.forumhfr.redface2.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.ui.theme.FlagPalette

/**
 * Renders one row of the user's drapeaux list.
 *
 * Visual hierarchy mirrors what HFR users have spent ~20 years training their eyes on:
 * a colored dot on the left for the flag type (cyan / red / yellow), the topic title
 * in the dominant slot, and a [metadata] footer line. When [Flag.hasUnread] is true,
 * the title is rendered in [FontWeight.SemiBold] so the row visibly pops vs read
 * entries (which the favoris view exposes too).
 *
 * The footer string is passed in pre-formatted from the caller (`:feature:flags`)
 * because `:core:ui` has no localized resources of its own — keeping the i18n boundary
 * clean per the convention recorded in `docs/guides/contributing.md`.
 *
 * [FlagMetadata.end] is an optional end-aligned segment of the footer line (#325
 * follow-up: the last-reply timestamp). It is NEVER truncated — the start segment takes
 * the remaining width and ellipsises instead, so on narrow screens the date survives and
 * the author/pagination clip first (dogfooding feedback on v102: the timestamp, placed
 * last in a single string, was the part being cut off).
 *
 * Note (#99 → #457): the « Retirer le drapeau » affordance went from an inline trailing button
 * to a `SwipeToDismissBox` (#99), then to a **long-press** ([longPress], #457) — the horizontal
 * swipe now changes the flag tab, so a row-level horizontal gesture would steal it. The
 * never-consumed `trailingAction` slot was dropped in the same change (detekt parameter budget).
 *
 * [longPress] is optional so the other consumers of this row keep the plain tap behaviour;
 * when null the row uses [clickable] unchanged (no long-press semantics advertised at all).
 */
@Composable
fun FlagItem(
    flag: Flag,
    metadata: FlagMetadata,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    longPress: FlagItemLongPress? = null,
) {
    val rowInteraction = if (longPress != null) {
        Modifier.combinedClickable(
            onLongClick = longPress.onLongPress,
            onLongClickLabel = longPress.label,
            onClick = onClick,
        )
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(rowInteraction)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FlagDot(type = flag.type, isFavorite = flag.isFavorite, hasUnread = flag.hasUnread)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = flag.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (flag.hasUnread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
            )
            if (metadata.start.isNotEmpty() || metadata.end.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = metadata.start,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        // #325 — only the START segment may truncate; the end-aligned
                        // timestamp keeps its intrinsic width (weight measures this text
                        // in the remaining space).
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (metadata.end.isNotEmpty()) {
                        Text(
                            text = metadata.end,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Bottom divider mirroring the visual rhythm of HFR topic listings. Exposed separately
 * from [FlagItem] so callers can choose whether to draw it (e.g. last item of a page).
 */
/**
 * Optional long-press affordance of a [FlagItem] row (#457). [label] doubles as the
 * accessibility `onLongClickLabel` announced for the long-press action.
 */
data class FlagItemLongPress(
    val label: String,
    val onLongPress: () -> Unit,
)

@Composable
fun FlagItemDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun FlagDot(type: FlagType, isFavorite: Boolean, hasUnread: Boolean) {
    // #384 follow-up (dev v118 feedback) — the favori/étoile decoration WINS over the bucket
    // color: a favorited topic listed under « Mes sujets » keeps its yellow dot, like the site.
    // `type` stays the bucket (routing/filters); only the dot reads the decoration.
    // Colors come from FlagPalette — the same source the Forum tab's topic rows use — instead of
    // the local literals this dot historically duplicated (Codex review: two drifting palettes).
    val color = if (isFavorite) FlagPalette.Favorite else FlagPalette.colorFor(type)
    val finalColor = if (hasUnread) color else color.copy(alpha = 0.35f)
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(finalColor),
    )
}
