package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.text.input.TextFieldValue
import fr.forumhfr.redface2.core.domain.editor.BbcodeValidation
import fr.forumhfr.redface2.core.domain.editor.validateBbcodeDraft
import fr.forumhfr.redface2.core.model.PostContent

/**
 * MVI state of a Phase 2B-A post-level editor session. Holds the current BBCode
 * draft, the parsed preview AST, and the user-visible toggles — but **no** network
 * effect yet. Submission, draft persistence and form fetching arrive later in Phase
 * 2C-D (#145, #146, #147).
 */
data class PostEditorState(
    val mode: PostEditorMode,
    val cat: Int,
    val topicId: Int?,
    val numreponse: Int?,
    val draft: TextFieldValue = TextFieldValue(),
    val preview: PostContent = PostContent(blocks = emptyList()),
    val isPreviewVisible: Boolean = false,
    val validation: BbcodeValidation = BbcodeValidation.Idle,
) {
    val isSubmitEnabled: Boolean get() = draft.text.isNotBlank()
}

internal fun PostEditorState.withDraft(updated: TextFieldValue): PostEditorState =
    copy(
        draft = updated,
        validation = validateBbcodeDraft(updated.text),
    )
