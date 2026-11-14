package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.editor.ArmToken

enum class EditorSheet { Smileys, Options, DraftOffer }

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
    val event: EditorEvent? = null,
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
 * ED-85, decisions of 2026-11-09 (#1513): event details precede disarming, then visible tool levels,
 * panel, preview and guarded exit. A mode retained behind a displayed event is not an open level:
 * Back neither closes that mode nor acknowledges the event (22:12 decision).
 */
fun resolveEditorBack(state: EditorBackState): EditorBackAction = when {
    state.sheet == EditorSheet.Smileys && state.smileyDetailOpen -> EditorBackAction.ReturnToSmileyGrid
    state.sheet != null -> EditorBackAction.CloseSheet(state.sheet)
    state.tooltipOpen -> EditorBackAction.CloseTooltip
    state.eventDetailKey != null -> EditorBackAction.CloseEventDetails(state.eventDetailKey)
    state.bar.arm != null -> EditorBackAction.Disarm(state.bar.arm)
    state.bar.visibleMode(state.event) in setOf(EditorBarMode.Color, EditorBarMode.Image) ->
        EditorBackAction.CloseSublevel
    state.bar.visibleMode(state.event) == EditorBarMode.Format -> EditorBackAction.CloseFormat
    state.panelOpen -> EditorBackAction.ClosePanel
    state.sharedPreviewOpen -> EditorBackAction.CloseSharedPreview
    else -> EditorBackAction.Leave(saveDraft = !state.submissionSucceeded)
}
