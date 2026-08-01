package fr.forumhfr.redface2.feature.editor
import fr.forumhfr.redface2.core.ui.editor.UploadError
import fr.forumhfr.redface2.core.ui.editor.UploadProgress

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

    /**
     * #312 — user confirmed the « Confirmation avant publication » dialog. Executes the real
     * submission directly, BYPASSING the preference re-check (otherwise the dialog would loop).
     */
    data object SubmitConfirmed : PostEditorIntent

    /** #312 — user dismissed the confirmation dialog. No submission happens; the draft stays. */
    data object SubmitConfirmationDismissed : PostEditorIntent

    /** User dismissed an in-flight error banner. Clears [PostEditorState.submitError]. */
    data object ErrorDismissed : PostEditorIntent

    /** Toggle « Activer votre signature » (HFR `signature=1`). */
    data class ToggleSignature(val enabled: Boolean) : PostEditorIntent

    /** Toggle « Désactiver les smilies » (HFR `smiley=1` ; when checked, HFR renders BBCode smileys as plain text). */
    data class ToggleSmileyDisabled(val disabled: Boolean) : PostEditorIntent

    /** Toggle « Activer la notification par email du sujet » (HFR `emaill=1`). */
    data class ToggleEmailNotification(val enabled: Boolean) : PostEditorIntent

    /**
     * Phase 2F-B (#11) — the user tapped a smiley in the picker. The ViewModel inserts the
     * token at the current caret position via the formatter helper and closes the sheet.
     * #441 — open / dismiss / query-change are no longer intents : the sheet talks directly
     * to the shared `SmileyPickerController` exposed as `PostEditorViewModel.smileyPicker` ;
     * only the insertion stays MVI because it mutates the draft.
     */
    data class SmileySelected(val token: String) : PostEditorIntent

    /** Phase 2F-E (#189) — insert `[img]url[/img]` for a validated remote image URL. */
    data class ImageUrlInserted(val url: String) : PostEditorIntent

    /**
     * #459 PR2 — the user picked a local image from the system photo picker. [uri] is the
     * picker's `Uri.toString()`. The ViewModel reads the bytes (off the platform layer), uploads
     * to the selected host, and inserts the resulting `[img]url[/img]` at the caret on success.
     */
    data class ImagePicked(val uri: String) : PostEditorIntent

    /**
     * Multi-image upload — the user picked several local images at once from the system photo
     * picker. [uris] holds each `Uri.toString()`, in pick order. The ViewModel uploads them
     * sequentially and inserts an `[img]url[/img]` at the caret for each success (order preserved);
     * the batch stops at the first failure, leaving the already-inserted images in place.
     */
    data class ImagesPicked(val uris: List<String>) : PostEditorIntent

    /** #459 PR2 — user dismissed the upload-error banner. Clears [PostEditorState.uploadError]. */
    data object UploadErrorDismissed : PostEditorIntent

    /** #405 — user tapped « Restaurer » on the draft banner: pre-fill the editor from the cached draft. */
    data object DraftRestoreRequested : PostEditorIntent

    /** #405 — user tapped « Ignorer » on the draft banner: delete the cached draft and clear the banner. */
    data object DraftDiscardRequested : PostEditorIntent

    /** #604 lot 3 — remove one quote card (✕). Identified by numreponse, like the sheet. */
    data class QuoteRemoved(val numreponse: Int) : PostEditorIntent

    /** #604 lot 3 — move a quote card one slot up ([delta] = -1) or down (+1) ; out-of-range = no-op. */
    data class QuoteMoved(val numreponse: Int, val delta: Int) : PostEditorIntent

    /** #436 (#604 lot 3) — « Tout vider » : drop every quote card. The typed body is untouched. */
    data object QuotesCleared : PostEditorIntent

    /**
     * #604 lot 4a — the user is leaving the editor (system back). The ViewModel flushes the
     * pending debounced autosave FIRST, then emits [PostEditorEffect.CloseCommitted] — closing
     * through the ViewModel is what guarantees the last < 750 ms of typing reach the #405 row
     * (a plain pop would cancel the debounce with the ViewModel).
     */
    data object CloseRequested : PostEditorIntent
}

/**
 * One-shot effects emitted by [PostEditorViewModel]. These bypass [PostEditorState]
 * so they are never replayed across recompositions / process death.
 */
sealed interface PostEditorEffect {
    /**
     * The reply / quote / edit was accepted by HFR. The receiver navigates back
     * to the topic and refreshes the page if [targetPage] is known. [scrollTo] tells
     * the topic screen which `numreponse` to scroll to after the refresh: HFR's success
     * URL anchors `#t{numreponse}` for **quote and edit** (the parser exposes it as
     * `result.numreponse`), so those carry a non-null [scrollTo]; a **plain reply** anchors
     * `#bas` (no numreponse), so [scrollTo] stays null and `TopicScreen` scrolls to the
     * bottom by default. The #226 overflow guard relies on this: only the null-[scrollTo]
     * (plain-reply) path can re-route to a freshly created last page.
     */
    data class SubmitSucceeded(
        val targetPage: Int?,
        val scrollTo: Int? = null,
    ) : PostEditorEffect

    /**
     * #604 lot 4a — the draft is persisted, the editor may now actually pop (twin of the
     * quick-reply escalation contract : the save is AWAITED before the effect, so navigation
     * can never cancel it).
     */
    data object CloseCommitted : PostEditorEffect
}
