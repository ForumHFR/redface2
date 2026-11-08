package fr.forumhfr.redface2.core.ui.editor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon

/** Keep composed across [visible] changes; [label] includes state/count and destination. See [EditorActionIcon]. */
@OptIn(ExperimentalMaterial3Api::class)
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
    val height = 24.dp + if (badgeCount == null) 0.dp else maxOf(16.dp, with(density) { badgeSize.height.toDp() })
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState(),
        modifier = modifier, focusable = false, enableUserInput = visible,
    ) {
        IconButton(
            onClick = onClick, enabled = enabled && visible,
            modifier = (if (!visible) Modifier.size(1.dp) else Modifier.sizeIn(
                minWidth = maxOf(48.dp, badgeWidth), minHeight = maxOf(48.dp, height),
            )).semantics {
                liveRegion = LiveRegionMode.Polite
                if (visible) contentDescription = label
            }.then(if (visible) Modifier else Modifier.clearAndSetSemantics {}),
        ) {
            if (visible) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RedfaceVectorIcon(iconRes, Modifier.testTag(EDITOR_STATUS_GLYPH_TAG))
                if (badgeCount != null) Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.testTag(EDITOR_STATUS_BADGE_TAG).clearAndSetSemantics {},
                ) { Text(badgeCount.toString(), style = style, maxLines = 1) }
            }
        }
    }
}

internal const val EDITOR_STATUS_GLYPH_TAG = "editor-status-glyph"
internal const val EDITOR_STATUS_BADGE_TAG = "editor-status-badge"
