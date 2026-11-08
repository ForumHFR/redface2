package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon

/**
 * Always compose this control, including when [event] is null; never key it by event identity.
 * The host allocates [width] and the gap to Submit. No window or bar policy lives here.
 * [onAction] receives the exact displayed key; acknowledgement remains the host reducer's job.
 */
@Composable
@Suppress("LongParameterList") // Event + host width + action, then modifier and two defaulted test seams.
fun EditorEventControl(
    event: EditorEvent?,
    width: Dp,
    onAction: (EventKey, EditorEventAction) -> Unit,
    modifier: Modifier = Modifier,
    summaryTextWidth: Dp = EDITOR_SUMMARY_TEXT_WIDTH_DP.dp,
    onSummaryTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    require(width >= 48.dp && summaryTextWidth > 0.dp)
    val summary = event?.summary?.resolve().orEmpty()
    val action = event?.action
    val actionName = action?.controlLabel()
    val name = if (actionName == null) summary else "$summary, $actionName"
    // Keep intermediate upload counts consultable, but announce only the operation's start.
    // Its Finished/Problem replacement supplies the terminal announcement, even with the same key.
    val announcement = if (event?.content is EditorEventContent.InProgress) {
        remember(event.key) { name }
    } else name
    val isError = event?.rank == EditorEventRank.Error
    val tint = when {
        isError -> MaterialTheme.colorScheme.error
        action != null -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(modifier.width(width).heightIn(min = 48.dp)) {
        EditorLiveAnnouncement(announcement, isError)
        if (event != null) Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.small)
                .background(if (action != null) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .then(if (action == null) Modifier else Modifier.clickable(
                    role = Role.Button, onClickLabel = actionName, onClick = { onAction(event.key, action) },
                ))
                .semantics(mergeDescendants = true) { contentDescription = name },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RedfaceVectorIcon(event.controlIcon(), tint = tint, size = 16.dp)
            Text(
                text = summary,
                modifier = Modifier.weight(1f, fill = false)
                    .widthIn(max = minOf(summaryTextWidth, EDITOR_SUMMARY_TEXT_WIDTH_DP.dp)).clearAndSetSemantics {},
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { onSummaryTextLayout?.invoke(it) },
            )
        }
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

/** Separate from the consultable control so progress updates cannot cause repeated speech. */
@Composable
internal fun EditorLiveAnnouncement(text: String, assertive: Boolean = false) {
    Box(Modifier.size(1.dp).semantics {
        liveRegion = if (assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite
        contentDescription = text
    })
}
