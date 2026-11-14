package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import fr.forumhfr.redface2.core.ui.editor.EditorHeaderPanel
import fr.forumhfr.redface2.core.ui.editor.EditorSubjectField
import fr.forumhfr.redface2.core.ui.editor.EditorSubjectInput

/** The private first-message subject shares the topic editor's header panel and close policy. */
@Composable
internal fun PostEditorSubjectPanel(
    adapter: PostEditorChromeAdapter,
    body: TextFieldState,
    onReturnToBody: () -> Unit,
    content: @Composable () -> Unit,
) {
    val source = adapter.state.source
    val focus = remember { FocusRequester() }
    val input = remember(adapter) {
        EditorSubjectInput(70, { adapter.state.source.subject }, adapter::subject)
    }
    EditorHeaderPanel(adapter.surface == PostEditorChromeSurface.Subject,
        stringResource(R.string.editor_topic_header_title_only), body, adapter::closeSurface,
        { adapter.returnToBody(onReturnToBody) }, initialFocusRequester = focus, body = content) {
        EditorSubjectField(source.subject, input, Modifier.fillMaxWidth().focusRequester(focus),
            enabled = source.isSubjectEditable && !source.isLoadingForm && adapter.state.scaffold.controlsEnabled)
    }
}
