@file:Suppress("MatchingDeclarationName") // Keep the status descriptor beside the component's public API.

package fr.forumhfr.redface2.core.ui.editor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon

data class EditorAppBarStatus(
    @param:DrawableRes val iconRes: Int,
    val label: String,
    val onClick: () -> Unit,
    val badgeCount: Int? = null,
    val enabled: Boolean = true,
)

/**
 * ED-10/11: no insets here; the future column owns ED-04 safeDrawing, top and bottom insets.
 * [onCloseRequested] must call the host's guarded close/save path, never pop navigation directly.
 * [firstStatus] and [secondStatus] are permanent, independent slots: pass null for an absent state,
 * without moving the other state. Their live nodes stay composed (ED-95).
 * [headerActionLabel] names the host's subject/MP action (ED-101/113), including when context is hidden.
 */
@Composable
@Suppress("LongParameterList") // Stateless host callbacks and independent availability guards.
fun EditorAppBar(
    title: String,
    context: String,
    onCloseRequested: () -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
    firstStatus: EditorAppBarStatus? = null,
    secondStatus: EditorAppBarStatus? = null,
    onOpenHeader: (() -> Unit)? = null,
    headerActionLabel: String? = null,
    compactHeight: Boolean = false,
    closeEnabled: Boolean = true,
    previewEnabled: Boolean = true,
    headerEnabled: Boolean = true,
) {
    require(onOpenHeader == null || !headerActionLabel.isNullOrBlank())
    if (compactHeight) return
    Surface(modifier.fillMaxWidth().height(48.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        BoxWithConstraints {
            // Keep Return + at least 48 dp of title/header + a 48 dp action viewport, with 4 dp gutters.
            // Preview leads the scrolling actions so it stays visible ahead of states (ED-25).
            // Below 152 dp even this minimum row scrolls; no target is shrunk or removed.
            val rowWidth = maxOf(maxWidth, 152.dp)
            Row(Modifier.horizontalScroll(rememberScrollState()).width(rowWidth).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                EditorActionIcon(R.drawable.ic_arrow_back, stringResource(R.string.editor_return),
                    onCloseRequested, enabled = closeEnabled)
                EditorAppBarTitle(title, context, onOpenHeader, headerActionLabel, headerEnabled, Modifier.weight(1f))
                Row(Modifier.widthIn(max = rowWidth - 104.dp).horizontalScroll(rememberScrollState())
                    .testTag(EDITOR_APP_BAR_ACTIONS), verticalAlignment = Alignment.CenterVertically) {
                    EditorActionIcon(R.drawable.ic_editor_preview, stringResource(R.string.editor_preview_show),
                        onPreview, enabled = previewEnabled)
                    EditorAppBarStatusSlot(firstStatus)
                    EditorAppBarStatusSlot(secondStatus)
                }
            }
        }
    }
}

@Composable
private fun EditorAppBarStatusSlot(status: EditorAppBarStatus?) {
    EditorStatusIcon(status?.iconRes ?: R.drawable.ic_editor_restore, status?.label.orEmpty(),
        { status?.onClick?.invoke() }, badgeCount = status?.badgeCount,
        enabled = status?.enabled ?: false, visible = status != null)
}

@Composable
@Suppress("LongParameterList") // Title content, injected header action and layout.
private fun EditorAppBarTitle(
    title: String,
    context: String,
    onOpenHeader: (() -> Unit)?,
    headerActionLabel: String?,
    enabled: Boolean,
    modifier: Modifier,
) {
    val fullTitle = listOf(title, context).filter { it.isNotEmpty() }.joinToString(", ")
    val headerLabel = stringResource(R.string.editor_app_bar_open_header, headerActionLabel.orEmpty(), fullTitle)
    val action = if (onOpenHeader == null) Modifier else Modifier
        .clickable(enabled = enabled, role = Role.Button, onClick = onOpenHeader)
        .semantics { contentDescription = headerLabel }
    Row(modifier.height(48.dp).then(action).testTag(EDITOR_APP_BAR_TITLE),
        verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(Modifier.weight(1f).clipToBounds()) {
            val density = LocalDensity.current
            val constraints = Constraints(maxWidth = with(density) { maxWidth.roundToPx() })
            val measurer = rememberTextMeasurer()
            val titleStyle = MaterialTheme.typography.titleSmall
            val contextStyle = MaterialTheme.typography.labelMedium
            val titleHeight = measurer.measure(title, titleStyle, overflow = TextOverflow.Ellipsis,
                maxLines = 1, constraints = constraints).size.height
            val contextLayout = measurer.measure(context, contextStyle, overflow = TextOverflow.Ellipsis,
                maxLines = 1, constraints = constraints)
            val hasVisibleContext = contextLayout.getLineEnd(0, visibleEnd = true) > 0
            val linesFit = titleHeight + contextLayout.size.height <= with(density) { 48.dp.roundToPx() }
            val showContext = context.isNotEmpty() && hasVisibleContext && linesFit
            Column {
                Text(title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().testTag(EDITOR_APP_BAR_LINE_ONE))
                if (showContext) Text(context, style = contextStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().testTag(EDITOR_APP_BAR_LINE_TWO))
            }
        }
        if (onOpenHeader != null) RedfaceVectorIcon(R.drawable.ic_expand_more)
    }
}

internal const val EDITOR_APP_BAR_TITLE = "editor-app-bar-title"
internal const val EDITOR_APP_BAR_ACTIONS = "editor-app-bar-actions"
internal const val EDITOR_APP_BAR_LINE_ONE = "editor-app-bar-line-one"
internal const val EDITOR_APP_BAR_LINE_TWO = "editor-app-bar-line-two"
