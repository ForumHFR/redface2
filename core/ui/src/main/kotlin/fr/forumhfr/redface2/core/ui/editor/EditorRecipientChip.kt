package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon

/** One removal target, including the decorative cross; no nested clickable trailing icon. */
@Composable
fun EditorRecipientChip(
    recipient: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val label = stringResource(R.string.editor_recipient_remove, recipient)
    InputChip(
        selected = false,
        onClick = onRemove,
        enabled = enabled,
        // InputChip's Checkbox/Selected semantics do not describe a removal action.
        // M3 reserves the minimum interactive height around its compact visual container.
        modifier = modifier.sizeIn(minWidth = 48.dp).clearAndSetSemantics {
            role = Role.Button
            contentDescription = label
            onClick(label = label) {
                if (enabled) onRemove()
                enabled
            }
            if (!enabled) disabled()
        },
        label = { Text(recipient, Modifier.clearAndSetSemantics {}, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = { RedfaceVectorIcon(R.drawable.ic_close) },
    )
}
