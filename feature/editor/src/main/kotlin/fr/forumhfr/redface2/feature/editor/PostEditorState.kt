package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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

/**
 * Lightweight, non-blocking BBCode validation snapshot. Phase 2B-A intentionally
 * keeps this minimal — the editor never blocks the user, it just hints. A richer
 * `validateBbcode` use case (mismatched closes, length warnings) will live in
 * `:core:domain` once a real submission flow needs the gate.
 */
sealed interface BbcodeValidation {
    data object Idle : BbcodeValidation
    data object EmptyDraft : BbcodeValidation
}

internal fun PostEditorState.withDraft(updated: TextFieldValue): PostEditorState =
    copy(
        draft = updated,
        validation = if (updated.text.isBlank()) BbcodeValidation.EmptyDraft else BbcodeValidation.Idle,
    )

internal fun emptyDraft(): TextFieldValue = TextFieldValue(text = "", selection = TextRange.Zero)
