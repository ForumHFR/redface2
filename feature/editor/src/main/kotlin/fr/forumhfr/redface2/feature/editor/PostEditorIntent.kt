package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.text.input.TextFieldValue
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction

/**
 * MVI intents emitted by `PostEditorScreen`. Phase 2C (#145) adds the reply submit
 * surface ; quote / edit / create-topic surface in #146 / #147 / #148 / #149.
 */
sealed interface PostEditorIntent {
    data class ContentChanged(val value: TextFieldValue) : PostEditorIntent
    data class ToolbarActionClicked(val action: BbcodeAction) : PostEditorIntent
    data object TogglePreview : PostEditorIntent

    /** User asked to send the reply to HFR (Phase 2C, [PostEditorMode.Reply] only). */
    data object SubmitClicked : PostEditorIntent

    /** User dismissed an in-flight error banner. Clears [PostEditorState.submitError]. */
    data object ErrorDismissed : PostEditorIntent
}

/**
 * One-shot effects emitted by [PostEditorViewModel]. These bypass [PostEditorState]
 * so they are never replayed across recompositions / process death.
 */
sealed interface PostEditorEffect {
    /**
     * The reply was accepted by HFR. The receiver navigates back to the topic and
     * refreshes the page if [targetPage] is known.
     */
    data class SubmitSucceeded(val targetPage: Int?) : PostEditorEffect
}
