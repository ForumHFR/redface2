package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import fr.forumhfr.redface2.core.ui.R

/** ED-20: insertion commands only; selection and undo remain owned by the field's host. */
@Composable
internal fun EditorFormatRow(
    onAction: (BbcodeAction) -> Unit,
    onColor: () -> Unit,
    enabled: Boolean,
    colorModifier: Modifier = Modifier,
    compactHeight: Boolean = false,
) {
    editorShortcutFormats.forEach { entry ->
        EditorBarIcon(checkNotNull(entry.iconRes), stringResource(entry.labelRes),
            { onAction(checkNotNull(entry.bbcode)) },
            enabled = enabled, compactHeight = compactHeight)
    }
    EditorBarIcon(R.drawable.ic_editor_color, stringResource(R.string.bbcode_action_color), onColor, colorModifier,
        enabled = enabled, compactHeight = compactHeight)
}

@Composable
@Suppress("LongParameterList") // Same icon boundary as EditorActionIcon, with keyboard activation.
internal fun EditorBarIcon(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    expanded: Boolean? = null,
    containerModifier: Modifier = Modifier,
    compactHeight: Boolean = false,
) {
    EditorActionIcon(icon, label, onClick, modifier.editorBarActivation(enabled, onClick), enabled, expanded,
        containerModifier, compactHeight)
}

/** Consume both halves before a callback can remove its control or return focus to the field. */
internal fun Modifier.editorBarActivation(enabled: Boolean, onClick: () -> Unit): Modifier = onPreviewKeyEvent {
    val activation = it.key == Key.Enter || it.key == Key.NumPadEnter ||
        it.key == Key.Spacebar || it.key == Key.DirectionCenter
    if (activation && enabled) {
        if (it.type == KeyEventType.KeyUp) onClick()
        true
    } else false
}
