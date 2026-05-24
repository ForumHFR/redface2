package fr.forumhfr.redface2.feature.editor

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import fr.forumhfr.redface2.core.ui.editor.imageBbcodeTokenOrNull

@Composable
internal fun ImageUrlDialog(
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var value by remember { mutableStateOf("") }
    val isValid = imageBbcodeTokenOrNull(value) != null

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_image_url_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.editor_image_url_label)) },
                supportingText = {
                    Text(
                        stringResource(
                            if (value.isBlank() || isValid) {
                                R.string.editor_image_url_help
                            } else {
                                R.string.editor_image_url_error
                            },
                        ),
                    )
                },
            )
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = {
                    onInsert(value.trim())
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.editor_image_url_insert))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.editor_image_url_cancel))
            }
        },
    )
}
