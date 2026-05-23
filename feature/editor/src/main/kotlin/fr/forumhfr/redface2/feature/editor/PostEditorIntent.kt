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

    /** Toggle « Activer votre signature » (HFR `signature=1`). */
    data class ToggleSignature(val enabled: Boolean) : PostEditorIntent

    /** Toggle « Désactiver les smilies » (HFR `smiley=1` ; when checked, HFR renders BBCode smileys as plain text). */
    data class ToggleSmileyDisabled(val disabled: Boolean) : PostEditorIntent

    /** Toggle « Activer la notification par email du sujet » (HFR `emaill=1`). */
    data class ToggleEmailNotification(val enabled: Boolean) : PostEditorIntent

    /** Phase 2F-B (#11) — opens the smiley picker bottom-sheet on the Standard tab. */
    data object SmileyPickerOpened : PostEditorIntent

    /** Phase 2F-B (#11) — dismisses the smiley picker (sheet swipe-down or back press). */
    data object SmileyPickerDismissed : PostEditorIntent

    /**
     * Phase 2F-B (#11) — the user typed in the wiki search field. The ViewModel debounces
     * and gates on `query.length > 2` before hitting the network, matching HFR's web
     * composer behaviour (`find_smilies_timer` 300 ms debounce).
     */
    data class SmileySearchQueryChanged(val query: String) : PostEditorIntent

    /**
     * Phase 2F-B (#11) — the user tapped a smiley in the picker. The ViewModel inserts the
     * token at the current caret position via the formatter helper and closes the sheet.
     */
    data class SmileySelected(val token: String) : PostEditorIntent

    /** Phase 2F-E (#189) — insert `[img]url[/img]` for a validated remote image URL. */
    data class ImageUrlInserted(val url: String) : PostEditorIntent
}

/**
 * One-shot effects emitted by [PostEditorViewModel]. These bypass [PostEditorState]
 * so they are never replayed across recompositions / process death.
 */
sealed interface PostEditorEffect {
    /**
     * The reply / quote / edit was accepted by HFR. The receiver navigates back
     * to the topic and refreshes the page if [targetPage] is known. For Phase 2D
     * edit, [scrollTo] additionally tells the topic screen which `numreponse` to
     * scroll to after the refresh — reply / quote always leave it null (HFR's
     * refresh URL anchors `#bas`, scrolling to the bottom of the page, which is
     * already what `TopicScreen` does by default for an unanchored navigation).
     */
    data class SubmitSucceeded(
        val targetPage: Int?,
        val scrollTo: Int? = null,
    ) : PostEditorEffect
}
