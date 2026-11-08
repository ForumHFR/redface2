package fr.forumhfr.redface2.core.ui.editor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon

/** Insertions have no selected state. Only the Format mode trigger supplies [expanded]. */
@Composable
fun EditorActionIcon(
    @DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    expanded: Boolean? = null,
) {
    val modeDescription = expanded?.let {
        stringResource(if (it) R.string.editor_mode_expanded else R.string.editor_mode_collapsed)
    }
    EditorIconTooltip(label) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                contentDescription = label
                modeDescription?.let { stateDescription = it }
            },
        ) {
            RedfaceVectorIcon(iconRes)
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
