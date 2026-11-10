@file:Suppress("MatchingDeclarationName") // The host's image capabilities accompany their row.

package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import fr.forumhfr.redface2.core.ui.R

/** Keep the picker launcher at screen scope. Cancellation must not call the event callback (#1573). */
data class EditorBarImageActions(
    val onUpload: () -> Unit,
    val onInsertUrl: (() -> Unit)? = null,
    val canWrapSelection: Boolean = false,
)

@Composable
@Suppress("LongParameterList") // Host capabilities and Back's focus modifier are independent inputs.
internal fun EditorImageRow(
    actions: EditorBarImageActions,
    onExternal: (() -> Unit) -> Unit,
    onWrap: () -> Unit,
    onBack: () -> Unit,
    enabled: Boolean,
    backModifier: Modifier = Modifier,
    compactHeight: Boolean = false,
) {
    EditorBarIcon(R.drawable.ic_arrow_back, stringResource(R.string.editor_return), onBack, backModifier,
        compactHeight = compactHeight)
    EditorBarIcon(R.drawable.ic_image_upload, stringResource(R.string.editor_bar_upload),
        { onExternal(actions.onUpload) }, enabled = enabled, compactHeight = compactHeight)
    actions.onInsertUrl?.let { insert ->
        EditorBarIcon(R.drawable.ic_editor_link, stringResource(R.string.editor_bar_image_url),
            { onExternal(insert) }, enabled = enabled, compactHeight = compactHeight)
    }
    if (actions.canWrapSelection) EditorBarIcon(R.drawable.ic_editor_image,
        stringResource(R.string.editor_bar_image_selection), onWrap, enabled = enabled, compactHeight = compactHeight)
}
