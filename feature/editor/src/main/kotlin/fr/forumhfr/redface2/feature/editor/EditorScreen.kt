package fr.forumhfr.redface2.feature.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fr.forumhfr.redface2.core.ui.RedfacePlaceholderScreen

@Composable
fun EditorScreen(
    mode: String,
    cat: Int,
    post: Int?,
) {
    RedfacePlaceholderScreen(
        title = stringResource(R.string.editor_title, mode),
        body = if (post == null) {
            stringResource(R.string.editor_body_without_post, cat)
        } else {
            stringResource(R.string.editor_body_with_post, cat, post)
        },
    )
}
