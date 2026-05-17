package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.text.input.TextFieldValue
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction

/**
 * MVI intents emitted by `PostEditorScreen`. Phase 2B-A keeps the surface tight: no
 * submit intent yet (no network call), no preview-fetch intent (no `apercu.php`).
 */
sealed interface PostEditorIntent {
    data class ContentChanged(val value: TextFieldValue) : PostEditorIntent
    data class ToolbarActionClicked(val action: BbcodeAction) : PostEditorIntent
    data object TogglePreview : PostEditorIntent
}
