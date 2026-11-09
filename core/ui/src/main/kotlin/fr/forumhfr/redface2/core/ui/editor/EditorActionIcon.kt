package fr.forumhfr.redface2.core.ui.editor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon

/**
 * Insertions have no selected state. Only the Format mode trigger supplies [expanded].
 * [containerModifier] styles a background independently of the 48 dp interactive target.
 * In [compactHeight], the button's state layer is 40 dp; its native touch target still expands to 48 dp.
 * The host dispatches NextGesture before [onClick]'s action and stops when gestureConsumed is true.
 */
@Composable
@Suppress("LongParameterList") // Icon + label + click, then target modifier, states and visual content modifier.
fun EditorActionIcon(
    @DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    expanded: Boolean? = null,
    containerModifier: Modifier = Modifier,
    compactHeight: Boolean = false,
) {
    val modeDescription = expanded?.let {
        stringResource(if (it) R.string.editor_mode_expanded else R.string.editor_mode_collapsed)
    }
    EditorIconTooltip(label) {
        Box(
            modifier = if (compactHeight) Modifier.minimumInteractiveComponentSize() else Modifier,
            contentAlignment = Alignment.Center,
        ) {
            Box(containerModifier)
            IconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = (if (compactHeight) modifier.size(EditorChromeMetrics(true).controlVisibleHeight)
                    else modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)).semantics {
                    contentDescription = label
                    modeDescription?.let { stateDescription = it }
                },
            ) {
                RedfaceVectorIcon(iconRes)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorIconTooltip(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        focusable = false,
        content = content,
    )
}
