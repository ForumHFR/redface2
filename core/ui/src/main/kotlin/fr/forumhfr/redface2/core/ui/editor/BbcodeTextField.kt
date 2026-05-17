package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Controlled Material 3 BBCode text field used by the Phase 2B editor.
 *
 * The field is intentionally minimal — it exposes the `TextFieldValue` (text + selection)
 * directly so the toolbar above can apply formatting via [applyBbcodeAction] without
 * losing the caret position.
 */
@Composable
fun BbcodeTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { hint -> { Text(hint) } },
        minLines = 5,
    )
}
