package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.maxTextLength
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
    Column {
        OutlinedTextField(
            state = input.buffer,
            inputTransformation = input.transformation,
            lineLimits = TextFieldLineLimits.SingleLine,
            enabled = enabled,
            label = { Text(stringResource(R.string.editor_subject_label)) },
            supportingText = {
                Text(stringResource(R.string.editor_subject_counter, value.length, input.maxLength))
            },
            modifier = modifier.semantics { maxTextLength = input.maxLength },
        )
        SubjectTruncationNotice(input.truncated, input.maxLength)
    }
}

/** Keep the live node outside M3's merging field so typing never announces the counter. */
@Composable
private fun SubjectTruncationNotice(truncated: Boolean, maxLength: Int) {
    val notice = if (truncated) stringResource(R.string.editor_subject_truncated, maxLength) else ""
    Box(
        modifier = (if (truncated) Modifier else Modifier.size(1.dp)).clearAndSetSemantics {
            liveRegion = LiveRegionMode.Polite
            if (notice.isNotEmpty()) contentDescription = notice
        },
    ) {
        if (truncated) Text(
            notice, Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
