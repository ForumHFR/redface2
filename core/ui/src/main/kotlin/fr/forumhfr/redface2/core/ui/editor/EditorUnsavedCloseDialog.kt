package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import fr.forumhfr.redface2.core.ui.R

/**
 * ED-10/85: a close normally waits for the draft to be saved. When that save cannot succeed, the
 * editor never pops silently: this dialog announces the loss and asks. Back, an outside tap and
 * [onStay] all keep the editor open with its field intact; only [onLeave] abandons the changes.
 * Mount it above everything the screen can show, including a full-screen load failure.
 */
@Composable
fun EditorUnsavedCloseDialog(
    onStay: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onStay,
        // The title is the dialog's accessible name: a heading, read first when the dialog opens.
        title = { Text(stringResource(R.string.editor_unsaved_close_title), Modifier.semantics { heading() }) },
        text = { Text(stringResource(R.string.editor_unsaved_close_message)) },
        confirmButton = {
            TextButton(onClick = onStay) { Text(stringResource(R.string.editor_unsaved_close_stay)) }
        },
        dismissButton = {
            TextButton(onClick = onLeave) { Text(stringResource(R.string.editor_unsaved_close_leave)) }
        },
    )
}
