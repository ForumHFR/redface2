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
 * Note (#99): the « Retirer le drapeau » affordance is no longer an inline `trailingAction`
 * button — `:feature:flags` now wraps this row in a Material 3 `SwipeToDismissBox`
 * (swipe-to-remove + confirmation dialog). The slot is kept for future inline actions.
 */
@Composable
fun FlagItem(
    flag: Flag,
    metadata: String,
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
            if (metadata.isNotEmpty()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    // #325 — the line can exceed narrow screens now that it carries the
                    // last-reply timestamp: signal the truncation instead of hard-clipping.
                    overflow = TextOverflow.Ellipsis,
                )
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
