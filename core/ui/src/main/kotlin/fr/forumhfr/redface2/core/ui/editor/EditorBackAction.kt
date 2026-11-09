package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.editor.ArmToken

enum class EditorSheet { Smileys, Options }

/** Snapshot of visible layers; the host keeps dispatchers, focus, and saving responsibilities. */
data class EditorBackState(
    val smileyDetailOpen: Boolean = false,
    val sheet: EditorSheet? = null,
    val tooltipOpen: Boolean = false,
    val bar: EditorBarState = EditorBarState(),
    val eventDetailKey: EventKey? = null,
    val panelOpen: Boolean = false,
    val sharedPreviewOpen: Boolean = false,
    val submissionSucceeded: Boolean = false,
)

sealed interface EditorBackAction {
    data object ReturnToSmileyGrid : EditorBackAction
    data class CloseSheet(val sheet: EditorSheet) : EditorBackAction
    data object CloseTooltip : EditorBackAction
    data object CloseSublevel : EditorBackAction
    data object CloseFormat : EditorBackAction
    data class Disarm(val token: ArmToken) : EditorBackAction
    data class CloseEventDetails(val key: EventKey) : EditorBackAction
    data object ClosePanel : EditorBackAction
    data object CloseSharedPreview : EditorBackAction
    data class Leave(val saveDraft: Boolean) : EditorBackAction
}

/**
 * ED-85: Back acts on visible layers. XaTriX decisions (2026-11-09, 18:12 and 21:42, #1513):
 * smiley details, sheets and tooltips stay first, then event details, then the arm, then retained
 * Format/Color/Image modes masked by the event (ED-82) or arm. Closing details preserves the arm
 * and saved mode; the next Back disarms if needed before closing the mode.
 */
fun resolveEditorBack(state: EditorBackState): EditorBackAction = when {
    state.sheet == EditorSheet.Smileys && state.smileyDetailOpen -> EditorBackAction.ReturnToSmileyGrid
    state.sheet != null -> EditorBackAction.CloseSheet(state.sheet)
    state.tooltipOpen -> EditorBackAction.CloseTooltip
    state.eventDetailKey != null -> EditorBackAction.CloseEventDetails(state.eventDetailKey)
    state.bar.arm != null -> EditorBackAction.Disarm(state.bar.arm)
    state.bar.visibleMode in setOf(EditorBarMode.Color, EditorBarMode.Image) -> EditorBackAction.CloseSublevel
    state.bar.visibleMode == EditorBarMode.Format -> EditorBackAction.CloseFormat
    state.panelOpen -> EditorBackAction.ClosePanel
    state.sharedPreviewOpen -> EditorBackAction.CloseSharedPreview
    else -> EditorBackAction.Leave(saveDraft = !state.submissionSucceeded)
}
