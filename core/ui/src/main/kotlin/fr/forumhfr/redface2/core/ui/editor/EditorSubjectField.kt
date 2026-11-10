package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.maxTextLength
import androidx.compose.ui.semantics.semantics
import fr.forumhfr.redface2.core.ui.R

/** ED-110. [value] is the collected owner value backing [input]; no content is saved in a Bundle. */
@Composable
fun EditorSubjectField(
    value: String,
    input: EditorSubjectInput,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SideEffect { input.synchronizeBuffer() }
    OutlinedTextField(
        state = input.buffer,
        inputTransformation = input.transformation,
        lineLimits = TextFieldLineLimits.SingleLine,
        enabled = enabled,
        label = { Text(stringResource(R.string.editor_subject_label)) },
        supportingText = {
            val counter = stringResource(R.string.editor_subject_counter, value.length, input.maxLength)
            val notice = if (input.truncated) {
                stringResource(R.string.editor_subject_truncated, input.maxLength)
            } else ""
            Text("$counter $notice".trimEnd(), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        },
        modifier = modifier.semantics { maxTextLength = input.maxLength },
    )
}
