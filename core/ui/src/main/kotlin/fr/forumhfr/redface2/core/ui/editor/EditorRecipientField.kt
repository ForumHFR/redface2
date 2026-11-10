package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R

/**
 * ED-110/D4. [value] is the collected owner state backing [input]. Keep [input] in the owner;
 * neither leaving this field nor [onNext] commits its buffer. Empty-buffer Backspace is a no-op:
 * removal always requires the chip's explicit, labelled action.
 * [modifier] targets the editable field, including the header's initial FocusRequester.
 */
@Composable
fun EditorRecipientField(
    value: EditorRecipientState,
    input: EditorRecipientInput,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SideEffect { input.synchronizeBuffer() }
    Box {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (value.chips.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                value.chips.forEachIndexed { index, recipient ->
                    EditorRecipientChip(recipient, onRemove = { input.remove(index, value) }, enabled = enabled)
                }
            }
            OutlinedTextField(
                state = input.buffer,
                inputTransformation = input.transformation,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
                onKeyboardAction = { onNext() },
                lineLimits = TextFieldLineLimits.SingleLine,
                enabled = enabled,
                label = { Text(stringResource(R.string.editor_recipients_label)) },
                supportingText = { Text(stringResource(R.string.editor_recipients_help)) },
                modifier = modifier.fillMaxWidth(),
            )
        }
        RecipientChangeAnnouncement(input.change)
    }
}

/** Announce accepted gestures only, never hydration, refused input or a duplicate comma. */
@Composable
private fun RecipientChangeAnnouncement(change: EditorRecipientChange?) {
    val label = when {
        change == null -> ""
        !change.added -> stringResource(R.string.editor_recipient_removed, change.recipients.single())
        change.recipients.size == 1 -> stringResource(R.string.editor_recipient_added, change.recipients.single())
        else -> stringResource(R.string.editor_recipients_added, change.recipients.joinToString(", "))
    }
    // Stable node, separate from the editable field and the disappearing removal target.
    Box(Modifier.size(1.dp).clearAndSetSemantics {
        liveRegion = LiveRegionMode.Polite
        if (label.isNotEmpty()) contentDescription = label
    })
}
