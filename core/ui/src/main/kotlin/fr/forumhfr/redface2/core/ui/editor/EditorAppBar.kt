@file:Suppress("MatchingDeclarationName") // Keep the status descriptor beside the component's public API.

package fr.forumhfr.redface2.core.ui.editor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
 */
@Composable
@Suppress("LongParameterList") // Stateless host callbacks and independent availability guards.
fun EditorAppBar(
    title: String,
    context: String,
    onCloseRequested: () -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
    statuses: List<EditorAppBarStatus> = emptyList(),
    onOpenHeader: (() -> Unit)? = null,
    compactHeight: Boolean = false,
    closeEnabled: Boolean = true,
    previewEnabled: Boolean = true,
    headerEnabled: Boolean = true,
) {
    require(statuses.size <= 2)
    if (compactHeight) return
    Surface(modifier.fillMaxWidth().height(48.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            EditorActionIcon(R.drawable.ic_arrow_back, stringResource(R.string.editor_return),
                onCloseRequested, enabled = closeEnabled)
            EditorAppBarTitle(title, context, onOpenHeader, headerEnabled, Modifier.weight(1f))
            statuses.forEach { status ->
                EditorStatusIcon(status.iconRes, status.label, status.onClick,
                    badgeCount = status.badgeCount, enabled = status.enabled)
            }
            EditorActionIcon(R.drawable.ic_editor_preview, stringResource(R.string.editor_preview_show),
                onPreview, enabled = previewEnabled)
        }
    }
}

@Composable
private fun EditorAppBarTitle(
    title: String,
    context: String,
    onOpenHeader: (() -> Unit)?,
    enabled: Boolean,
    modifier: Modifier,
) {
    val fullTitle = listOf(title, context).filter { it.isNotEmpty() }.joinToString(", ")
    val headerLabel = stringResource(R.string.editor_app_bar_open_header, fullTitle)
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
            val contextHeight = measurer.measure(context, contextStyle, overflow = TextOverflow.Ellipsis,
                maxLines = 1, constraints = constraints).size.height
            val showContext = context.isNotEmpty() && titleHeight + contextHeight <= with(density) { 48.dp.roundToPx() }
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
internal const val EDITOR_APP_BAR_LINE_ONE = "editor-app-bar-line-one"
internal const val EDITOR_APP_BAR_LINE_TWO = "editor-app-bar-line-two"
