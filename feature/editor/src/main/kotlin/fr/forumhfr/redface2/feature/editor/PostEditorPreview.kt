package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.ui.editor.EditorPreview
import fr.forumhfr.redface2.core.ui.post.PostMediaDiskCachePolicy

/** Public-post cache policy and existing semantics; the shared preview owns focus and source retention. */
@Composable
internal fun PostEditorPreview(
    visible: Boolean,
    content: PostContent,
    bodyState: TextFieldState,
    bodyFocusRequester: FocusRequester,
    body: @Composable () -> Unit,
) {
    EditorPreview(visible, content, bodyState, bodyFocusRequester, PostMediaDiskCachePolicy.ENABLED,
        stringResource(R.string.editor_preview_title), Modifier.testTag("post-editor-preview"), body)
}
