package fr.forumhfr.redface2.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType

/**
 * Renders one row of the user's drapeaux list.
 *
 * Visual hierarchy chosen to mirror what HFR users have spent ~20 years training their
 * eyes on: a colored dot on the left for the flag type (cyan / red / yellow), the topic
 * title in the dominant slot, and a footer line with the last reply author + total
 * replies + last read page. When the topic has unread posts, the title is rendered in
 * `Bold` so the row visibly pops vs read entries (which the favoris view exposes too).
 */
@Composable
fun FlagItem(
    flag: Flag,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
            Text(
                text = buildString {
                    append(flag.lastReplyAuthor)
                    append(" · ")
                    append(flag.totalReplies)
                    append(" réponses · p.")
                    append(flag.lastReadPage)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
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
