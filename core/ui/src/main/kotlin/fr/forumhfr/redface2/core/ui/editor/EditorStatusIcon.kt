package fr.forumhfr.redface2.core.ui.editor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Badge
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon

/** Keep composed across [visible] changes; [label] includes state/count and destination. See [EditorActionIcon]. */
@Composable
@Suppress("LongParameterList") // Independent icon, count, visibility and enabled state at the host boundary.
fun EditorStatusIcon(
    @DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: Int? = null,
    enabled: Boolean = true,
    visible: Boolean = true,
) {
    require(badgeCount == null || badgeCount > 0)
    val style = MaterialTheme.typography.labelSmall
    val badgeSize = rememberTextMeasurer().measure(badgeCount?.toString().orEmpty(), style = style).size
    val density = LocalDensity.current
    // Stack rather than overlay: even a large counter never covers the 24 dp glyph.
    val badgeWidth = with(density) { badgeSize.width.toDp() } + 8.dp
    val contentHeight = if (badgeCount == null) 24.dp else {
        24.dp + maxOf(16.dp, with(density) { badgeSize.height.toDp() })
    }
    Box(modifier) {
        if (visible) EditorIconTooltip(label) {
            IconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.sizeIn(
                    minWidth = maxOf(48.dp, badgeWidth), minHeight = maxOf(48.dp, contentHeight),
                ).semantics { contentDescription = label },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RedfaceVectorIcon(iconRes, Modifier.testTag(EDITOR_STATUS_GLYPH_TAG))
                    if (badgeCount != null) Badge(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.testTag(EDITOR_STATUS_BADGE_TAG).clearAndSetSemantics {},
                    ) {
                        Text(badgeCount.toString(), style = style, maxLines = 1)
                    }
                }
            }
        }
        EditorLiveAnnouncement(if (visible) label else "")
    }
}

internal const val EDITOR_STATUS_GLYPH_TAG = "editor-status-glyph"
internal const val EDITOR_STATUS_BADGE_TAG = "editor-status-badge"
