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

/**
 * [mediaDiskCachePolicy] is mandatory and comes from [PostEditorChromeState.mediaDiskCachePolicy]: this
 * editor also hosts private-message edits. The shared preview owns the split, fallback and mounted source.
 */
@Composable
@Suppress("LongParameterList") // Shared preview ports plus the target-derived media policy.
internal fun PostEditorPreview(
    visible: Boolean,
    content: PostContent,
    bodyState: TextFieldState,
    bodyFocusRequester: FocusRequester,
    mediaDiskCachePolicy: PostMediaDiskCachePolicy,
    body: @Composable () -> Unit,
) {
    EditorPreview(visible, content, bodyState, bodyFocusRequester, mediaDiskCachePolicy,
        stringResource(R.string.editor_preview_title), Modifier.testTag("post-editor-preview"), body)
}
