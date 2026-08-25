package fr.forumhfr.redface2.core.ui.pager

import androidx.annotation.DrawableRes
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon

/**
 * Shared previous/next page FAB primitive. It owns the cross-surface interaction contract only:
 * the 40.dp footprint, TalkBack role and labels, long-press haptics supplied by
 * [combinedClickable], and the hand-rolled Surface required by #820. A real M3 small FAB installs
 * its own click handler and swallows a `combinedClickable` placed on its modifier, silently losing
 * the first/last-page gesture.
 *
 * Features remain responsible for whether and where this primitive is shown, for reserving its
 * cluster slot, and for mapping the short/long callbacks to page targets.
 */
@Composable
@Suppress("LongParameterList") // Labels, icon, gate and the two gestures are independent contracts.
fun PageFab(
    description: String,
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onLongClickLabel: String,
    enabled: Boolean = true,
) {
    Surface(
        modifier = Modifier
            .semantics { contentDescription = description }
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel,
                role = Role.Button,
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
    ) {
        Box(
            modifier = Modifier.sizeIn(minWidth = PAGE_FAB_SIZE, minHeight = PAGE_FAB_SIZE),
            contentAlignment = Alignment.Center,
        ) {
            RedfaceVectorIcon(resId = iconRes)
        }
    }
}

private val PAGE_FAB_SIZE = 40.dp
