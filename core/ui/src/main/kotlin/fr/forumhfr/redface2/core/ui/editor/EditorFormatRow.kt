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
internal fun EditorFormatRow(onAction: (BbcodeAction) -> Unit, onColor: () -> Unit, enabled: Boolean) {
    val formats = listOf(
        Triple(BbcodeAction.Bold, R.drawable.ic_editor_bold, R.string.bbcode_action_bold),
        Triple(BbcodeAction.Italic, R.drawable.ic_editor_italic, R.string.bbcode_action_italic),
        Triple(BbcodeAction.Underline, R.drawable.ic_editor_underline, R.string.bbcode_action_underline),
        Triple(BbcodeAction.Strike, R.drawable.ic_editor_strike, R.string.bbcode_action_strike),
        Triple(BbcodeAction.Quote, R.drawable.ic_editor_quote, R.string.bbcode_action_quote),
        Triple(BbcodeAction.Cpp, R.drawable.ic_editor_code, R.string.bbcode_action_cpp),
        Triple(BbcodeAction.Fixed, R.drawable.ic_editor_fixed, R.string.bbcode_action_fixed),
        Triple(BbcodeAction.Spoiler, R.drawable.ic_editor_spoiler, R.string.bbcode_action_spoiler),
        Triple(BbcodeAction.Url, R.drawable.ic_editor_link, R.string.bbcode_action_url),
    )
    formats.forEach { (action, icon, label) ->
        EditorBarIcon(icon, stringResource(label), { onAction(action) }, enabled = enabled)
    }
    EditorBarIcon(R.drawable.ic_editor_color, stringResource(R.string.bbcode_action_color), onColor,
        enabled = enabled)
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
) {
    EditorActionIcon(icon, label, onClick, modifier.editorBarActivation(enabled, onClick), enabled, expanded)
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
