package fr.forumhfr.redface2.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType

/**
 * Footer line of a [FlagItem] row, split in two segments (#325 follow-up). [start] is the
 * only segment allowed to truncate (ellipsis); [end] — typically the last-reply
 * timestamp — keeps its intrinsic width, pinned to the row's end. Either side may be
 * empty. Bundled as one value so callers stay under the detekt parameter-count threshold.
 */
data class FlagMetadata(val start: String = "", val end: String = "")

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
 * [trailingAction] is an optional slot at the end of the row for an inline affordance (e.g.
 * an overflow / quick action). When present, the title column takes the remaining width via
 * `weight(1f)` so the action stays pinned to the right and the text ellipsises instead of
 * overlapping it. When absent (default), the column fills the row as before.
 *
 * [FlagMetadata.end] is an optional end-aligned segment of the footer line (#325
 * follow-up: the last-reply timestamp). It is NEVER truncated — the start segment takes
 * the remaining width and ellipsises instead, so on narrow screens the date survives and
 * the author/pagination clip first (dogfooding feedback on v102: the timestamp, placed
 * last in a single string, was the part being cut off).
 *
 * Note (#99): the « Retirer le drapeau » affordance is no longer an inline `trailingAction`
 * button — `:feature:flags` now wraps this row in a Material 3 `SwipeToDismissBox`
 * (swipe-to-remove + confirmation dialog). The slot is kept for future inline actions.
 */
@Composable
fun FlagItem(
    flag: Flag,
    metadata: FlagMetadata,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingAction: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FlagDot(type = flag.type, hasUnread = flag.hasUnread)
        Column(
            modifier = if (trailingAction != null) Modifier.weight(1f) else Modifier.fillMaxWidth(),
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
        trailingAction?.invoke(this)
    }
}

/**
 * Bottom divider mirroring the visual rhythm of HFR topic listings. Exposed separately
 * from [FlagItem] so callers can choose whether to draw it (e.g. last item of a page).
 */
@Composable
fun FlagItemDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun FlagDot(type: FlagType, hasUnread: Boolean) {
    val color = when (type) {
        FlagType.CYAN -> Color(0xFF00BCD4)
        FlagType.RED -> Color(0xFFE53935)
        FlagType.FAVORITE -> Color(0xFFFFB300)
    }
    val finalColor = if (hasUnread) color else color.copy(alpha = 0.35f)
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(finalColor),
    )
}
