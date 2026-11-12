package fr.forumhfr.redface2.core.ui.editor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import fr.forumhfr.redface2.core.ui.R
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon
import kotlinx.coroutines.launch

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
    compactHeight: Boolean = false,
    showDot: Boolean = false,
) {
    require(badgeCount == null || badgeCount > 0)
    val dotState = stringResource(R.string.editor_status_draft_pending)
    val style = MaterialTheme.typography.labelSmall
    val badgeSize = measureEditorStatusBadge(badgeCount)
    // Stack while both fit; at large font scales overlap the badge instead of clipping either layer.
    val badgeWidth = badgeSize.width
    val height = (24.dp + if (badgeCount != null) badgeSize.height else if (showDot) 6.dp else 0.dp)
        .coerceAtMost(EditorChromeMetrics(compactHeight).controlVisibleHeight)
    // Replace only the tooltip state on masking: its popup/job die, the live anchor keeps its ID.
    val tooltip = key(visible) { rememberEditorTooltipState() }
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } }, state = tooltip,
        // UI 1.12.1: a non-merging leaf with only liveRegion is silent; clear M3's merging anchor/children.
        modifier = modifier.clearAndSetSemantics {
            liveRegion = LiveRegionMode.Polite
            if (visible) {
                contentDescription = label
                if (showDot) stateDescription = dotState
                role = Role.Button
                if (!enabled) disabled()
                onClick {
                    if (enabled) onClick()
                    enabled
                }
                onLongClick { scope.launch { tooltip.show() }; true }
            }
        }, focusable = false, enableUserInput = visible,
    ) {
        // Masking reattaches this movable node: one stable chain keeps its 40 dp layer's bounds exact.
        Box(Modifier.editorCompactTargetSlot(visible && compactHeight), contentAlignment = Alignment.Center) {
            // The stacked badge reaches the bottom edge; a round button clips its lower corners.
            IconButton(onClick = onClick, enabled = enabled && visible, shape = RectangleShape,
                modifier = editorStatusButtonModifier(visible, compactHeight, badgeWidth),
            ) {
                if (visible) Box(Modifier.size(maxOf(24.dp, badgeWidth), height)) {
                    RedfaceVectorIcon(iconRes, Modifier.align(Alignment.TopCenter).testTag(EDITOR_STATUS_GLYPH_TAG))
                    val badgeAlpha = if (enabled) 1f else DISABLED_BADGE_ALPHA
                    if (showDot && badgeCount == null) Box(Modifier.align(Alignment.BottomCenter).size(6.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                        .testTag(EDITOR_STATUS_DOT_TAG).clearAndSetSemantics {})
                    if (badgeCount != null) Badge(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = badgeAlpha),
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = badgeAlpha),
                        modifier = Modifier.align(Alignment.BottomCenter).testTag(EDITOR_STATUS_BADGE_TAG)
                            .clearAndSetSemantics {},
                    ) { Text(badgeCount.toString(), style = style, maxLines = 1,
                        modifier = Modifier.testTag(EDITOR_STATUS_BADGE_TEXT_TAG)) }
                }
            }
        }
    }
}

private fun editorStatusButtonModifier(visible: Boolean, compactHeight: Boolean, badgeWidth: Dp): Modifier = when {
    !visible -> Modifier.size(1.dp).clearAndSetSemantics {}
    // Keep native click semantics under the clearing live anchor; touch expansion is still 48 dp.
    compactHeight -> Modifier.size(maxOf(EditorChromeMetrics(true).controlVisibleHeight, badgeWidth),
        EditorChromeMetrics(true).controlVisibleHeight)
    else -> Modifier.sizeIn(
        minWidth = maxOf(EditorChromeMetrics.minimumTargetSize, badgeWidth),
        minHeight = EditorChromeMetrics.minimumTargetSize,
    ).clearAndSetSemantics {}
}

@Composable
internal fun measureEditorStatusBadge(count: Int?): DpSize {
    val size = rememberTextMeasurer().measure(count?.toString().orEmpty(), MaterialTheme.typography.labelSmall).size
    return with(LocalDensity.current) { DpSize(size.width.toDp() + 8.dp, maxOf(16.dp, size.height.toDp())) }
}

internal const val EDITOR_STATUS_DOT_TAG = "editor-status-dot"
internal const val EDITOR_STATUS_GLYPH_TAG = "editor-status-glyph"
internal const val EDITOR_STATUS_BADGE_TAG = "editor-status-badge"
internal const val EDITOR_STATUS_BADGE_TEXT_TAG = "editor-status-badge-text"

private const val DISABLED_BADGE_ALPHA = 0.38f
