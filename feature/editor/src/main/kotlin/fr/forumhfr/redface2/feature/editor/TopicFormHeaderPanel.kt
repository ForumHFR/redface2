package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import fr.forumhfr.redface2.core.ui.R as UiR
import fr.forumhfr.redface2.core.ui.editor.EditorErrorAction
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.EditorEventDetails
import fr.forumhfr.redface2.core.ui.editor.EditorHeaderPanel

/** Keep mounted in T3's overlay: the body keeps its viewport and selection underneath both panels. */
@Composable
internal fun TopicFormHeaderPanel(
    adapter: TopicFormChromeAdapter,
    bodyState: TextFieldState,
    onReturnToBody: () -> Unit,
    body: @Composable () -> Unit,
) {
    val source = adapter.state.source
    val titleFocus = remember { FocusRequester() }
    val subcategoryFocus = remember { FocusRequester() }
    val focusSubcategory = adapter.memory.headerField == TopicFormHeaderField.Subcategory &&
        adapter.state.showSubcategory
    LaunchedEffect(adapter.state.scaffold.controlsEnabled, source.isRestoringSession) { adapter.openInitialHeader() }
    val title = if (adapter.state.showSubcategory) UiR.string.editor_header_topic
        else R.string.editor_topic_header_title_only
    EditorHeaderPanel(adapter.memory.headerOpen, stringResource(title),
        bodyState, adapter::closeHeader,
        { adapter.host.returnToBody(onReturnToBody) },
        initialFocusRequester = if (focusSubcategory) subcategoryFocus else titleFocus, body = body) {
        OutlinedTextField(
            value = source.subject, onValueChange = adapter::subject, singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(titleFocus), enabled = adapter.state.headerEnabled,
            readOnly = adapter.state.headerReadOnly,
            label = { Text(stringResource(R.string.editor_topic_subject_label)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        if (adapter.state.showSubcategory) SubcategoryDropdown(
            source.subcategoryChoices, source.selectedSubcat, adapter.state.subcategoryEnabled, adapter::subcategory,
            Modifier.focusRequester(subcategoryFocus),
        )
        if (adapter.state.unsupportedFirstPost) {
            HeaderNote(stringResource(R.string.editor_topic_first_post_unsupported))
        }
        if (source.pollPresent && !source.pollEditable) {
            HeaderNote(stringResource(R.string.editor_topic_poll_readonly_note))
        }
    }
}

/** Secondary note under the header fields, styled like the former form's poll note. */
@Composable
private fun HeaderNote(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The GET remedy remains available, including beside an uncertain attempt with an unknown FP body. */
@Composable
internal fun TopicFormEventDetails(
    adapter: TopicFormChromeAdapter,
    bodyState: TextFieldState,
    onReturnToBody: () -> Unit,
    body: @Composable () -> Unit,
) {
    val source = adapter.state.source
    EditorEventDetails(adapter.host.detail, bodyState, adapter.host::closeDetails, adapter::reload,
        { adapter.host.returnToBody(onReturnToBody) }, body = body) { event ->
        val error = (event.content as? EditorEventContent.Problem)?.error
        if (source.isAwaitingInitialBody && error != null && error.action != EditorErrorAction.Reload) {
            TextButton(onClick = { adapter.reload(event.key) }, enabled = !source.isLoadingForm) {
                Text(stringResource(UiR.string.editor_action_reload))
            }
        }
    }
}
