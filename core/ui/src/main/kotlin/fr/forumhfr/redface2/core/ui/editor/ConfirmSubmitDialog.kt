package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fr.forumhfr.redface2.core.ui.R

/**
 * #312 — confirmation dialog shown before a publish action when the « Confirmation avant
 * publication » preference is on. Stateless on purpose: visibility is owned by the calling
 * ViewModel (`showSubmitConfirmation` in its state) and both buttons route back through
 * intents / callbacks, so the dialog can be shared by the three editors (`:feature:editor`
 * post + topic forms, `:feature:messages` private reply) without dragging any ViewModel
 * dependency into `:core:ui`. Same M3 AlertDialog shape as `DeletePostConfirmDialog`.
 */
@Composable
fun ConfirmSubmitDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.confirm_submit_title)) },
        text = { Text(text = stringResource(R.string.confirm_submit_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.confirm_submit_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.confirm_submit_cancel))
            }
        },
    )
}
