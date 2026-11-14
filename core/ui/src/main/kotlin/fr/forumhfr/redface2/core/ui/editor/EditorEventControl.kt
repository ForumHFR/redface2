package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon

/**
 * Always compose this control, including when [event] is null; never key it by event identity.
 * Without an event its semantic node keeps a 1 dp footprint. [width] includes the 4 dp start-gutter
 * extension only without preceding fixed slots; the host reserves at least 185 dp at 360 dp,
 * respects insets/Submit spacing and centers the 48 dp
 * target in any taller [modifier]. In [compactHeight], its visible shape is 40 dp, centered in that target.
 * [onAction] dispatches Activate with the displayed key; honor gestureConsumed.
 */
@Composable
@Suppress("LongParameterList") // Event contract, modifier and text callback plus the optional compact variant.
fun EditorEventControl(
    event: EditorEvent?,
    width: Dp,
    onAction: (EventKey, EditorEventAction) -> Unit,
    modifier: Modifier = Modifier,
    onSummaryTextLayout: ((TextLayoutResult) -> Unit)? = null,
    compactHeight: Boolean = false,
) {
    require(width >= 48.dp)
    val summary = event?.accessibleSummary?.resolve().orEmpty()
    val action = event?.action
    val actionName = action?.controlLabel()
    val name = if (actionName == null) summary else "$summary, $actionName"
    val announce = rememberEventAnnouncement(event, name)
    val isError = event?.rank == EditorEventRank.Error
    val tint = event.controlTint()
    val interactions = remember { MutableInteractionSource() }
    Box(
        modifier = if (event == null) modifier else modifier.width(width).heightIn(min = 48.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = (if (event == null) Modifier.size(1.dp) else Modifier.fillMaxWidth().heightIn(min = 48.dp))
                .then(if (event == null || action == null) Modifier else Modifier.clickable(
                    interactionSource = interactions, indication = null,
                    role = Role.Button, onClickLabel = actionName, onClick = { onAction(event.key, action) },
                ))
                // Keep clickable's role/action, but hide children so Android names the live node itself.
                .clearAndSetSemantics {
                    if (event != null) {
                        contentDescription = name
                        this[EditorEventIconKey] = event.controlIcon()
                        this[EditorEventIconTintKey] = tint
                    }
                    if (announce) liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            if (event != null) EditorEventSummary(event, compactHeight, interactions, onSummaryTextLayout)
        }
    }
}

@Composable
private fun EditorEventSummary(
    event: EditorEvent,
    compactHeight: Boolean,
    interactions: MutableInteractionSource,
    onTextLayout: ((TextLayoutResult) -> Unit)?,
) {
    val tint = event.controlTint()
    Row(
        Modifier.fillMaxWidth().height(EditorChromeMetrics(compactHeight).controlVisibleHeight)
            .testTag("editor-bar-event-shape").clip(MaterialTheme.shapes.small)
            .background(if (event.action != null) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .indication(interactions, LocalIndication.current)
            .padding(start = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        RedfaceVectorIcon(event.controlIcon(), Modifier.editorEventIconPlacement(), tint = tint, size = 16.dp)
        Text(text = event.summary.resolve(),
            modifier = Modifier.weight(1f, fill = false).testTag(EDITOR_EVENT_SUMMARY_TAG),
            style = MaterialTheme.typography.labelSmall, color = tint,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            onTextLayout = { onTextLayout?.invoke(it) })
    }
}

@Composable
private fun EditorEvent?.controlTint(): Color = when {
    this?.rank == EditorEventRank.Error -> MaterialTheme.colorScheme.error
    this?.action != null -> MaterialTheme.colorScheme.onSecondaryContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun rememberEventAnnouncement(event: EditorEvent?, name: String): Boolean {
    // Track operations across masking/absence; UI 1.12.1 still sends non-live description changes.
    val announced = remember { mutableSetOf<Pair<EditorSession, OperationRef>>() }
    val operation = event?.key?.let { it.session to it.operation }
    val inProgress = event?.content is EditorEventContent.InProgress
    val start = remember(operation, inProgress, name) { inProgress && operation !in announced }
    SideEffect { if (inProgress) announced.add(requireNotNull(operation)) }
    return !inProgress || start
}

/** Internal visual diagnostics on the control itself, ignored by accessibility services. */
internal val EditorEventIconKey = SemanticsPropertyKey<Int>("EditorEventIcon")
internal val EditorEventIconTintKey = SemanticsPropertyKey<Color>("EditorEventIconTint")

internal const val EDITOR_EVENT_SUMMARY_TAG = "editor-event-summary"

/** Actual glyph start, inherited by parent layouts without creating a semantic child. */
internal val EditorEventIconStart = VerticalAlignmentLine(::minOf)

private fun Modifier.editorEventIconPlacement(): Modifier = layout { measurable, constraints ->
    val icon = measurable.measure(constraints)
    layout(icon.width, icon.height, mapOf(EditorEventIconStart to 0)) {
        icon.placeRelative(0, 0)
    }
}

@Composable
private fun EditorEventAction.controlLabel(): String = stringResource(when (this) {
    EditorEventAction.Complete -> R.string.editor_action_complete
    EditorEventAction.Details -> R.string.editor_event_open_details
    EditorEventAction.Dismiss -> R.string.editor_action_dismiss
})

private fun EditorEvent.controlIcon(): Int = when {
    rank == EditorEventRank.Error -> R.drawable.ic_editor_submission_warning
    content is EditorEventContent.InProgress -> R.drawable.ic_editor_progress
    content is EditorEventContent.Finished -> R.drawable.ic_editor_done
    else -> R.drawable.ic_editor_info
}
