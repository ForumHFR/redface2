package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorBackActionTest {
    private val session = EditorSession("session", "alice", "topic:23:42")
    private val key = EventKey(session, OperationRef.Local(EditorLocalOperation.Notice, 1), 1)
    private val arm = ArmToken(1, session, 0, 5_000)

    @Test
    fun `ED85 - first visible layer consumes Back in contract order`() {
        val initial = EditorBackState(
            smileyDetailOpen = true, sheet = EditorSheet.Smileys, tooltipOpen = true,
            bar = EditorBarState(EditorBarMode.Color), eventDetailKey = key,
            panelOpen = true, sharedPreviewOpen = true,
        )
        assertEquals(EditorBackAction.ReturnToSmileyGrid, resolveEditorBack(initial))
        val sheet = initial.copy(smileyDetailOpen = false)
        assertEquals(EditorBackAction.CloseSheet(EditorSheet.Smileys), resolveEditorBack(sheet))
        assertEquals(
            EditorBackAction.CloseSheet(EditorSheet.Options),
            resolveEditorBack(sheet.copy(sheet = EditorSheet.Options)),
        )
        val tooltip = sheet.copy(sheet = null)
        assertEquals(EditorBackAction.CloseTooltip, resolveEditorBack(tooltip))
        val sublevel = tooltip.copy(tooltipOpen = false)
        assertEquals(EditorBackAction.CloseSublevel, resolveEditorBack(sublevel))
        val image = sublevel.copy(bar = EditorBarState(EditorBarMode.Image))
        assertEquals(EditorBackAction.CloseSublevel, resolveEditorBack(image))
        val format = sublevel.copy(bar = EditorBarState(EditorBarMode.Format))
        assertEquals(EditorBackAction.CloseFormat, resolveEditorBack(format))
        val armed = format.copy(bar = EditorBarState(arm = arm))
        assertEquals(EditorBackAction.Disarm(arm), resolveEditorBack(armed))
        val detail = armed.copy(bar = EditorBarState())
        assertEquals(EditorBackAction.CloseEventDetails(key), resolveEditorBack(detail))
        val panel = detail.copy(eventDetailKey = null)
        assertEquals(EditorBackAction.ClosePanel, resolveEditorBack(panel))
        val preview = panel.copy(panelOpen = false)
        assertEquals(EditorBackAction.CloseSharedPreview, resolveEditorBack(preview))
        val leaving = preview.copy(sharedPreviewOpen = false)
        assertEquals(EditorBackAction.Leave(saveDraft = true), resolveEditorBack(leaving))
        assertEquals(
            EditorBackAction.Leave(saveDraft = false), resolveEditorBack(leaving.copy(submissionSucceeded = true)),
        )
    }

    @Test
    fun `ED85 - masked modes disarm before closing and an automatically opened panel closes before exit`() {
        val modes = listOf(
            EditorBarMode.Format to EditorBackAction.CloseFormat,
            EditorBarMode.Color to EditorBackAction.CloseSublevel,
            EditorBarMode.Image to EditorBackAction.CloseSublevel,
        )
        modes.forEach { (mode, closeMode) ->
            val masked = EditorBackState(
                bar = EditorBarState(mode, arm), eventDetailKey = key, panelOpen = true, sharedPreviewOpen = true,
            )
            assertEquals(EditorBackAction.Disarm(arm), resolveEditorBack(masked))
            val disarmed = masked.copy(bar = masked.bar.withSubmission(EditorSubmissionState.Ready))
            assertEquals(mode, disarmed.bar.mode)
            assertEquals(closeMode, resolveEditorBack(disarmed))
        }
        assertEquals(EditorBackAction.ClosePanel, resolveEditorBack(EditorBackState(panelOpen = true)))
    }

    @Test
    fun `ED85 - smiley details sheets and tooltips still consume Back before disarming`() {
        val initial = EditorBackState(
            smileyDetailOpen = true, sheet = EditorSheet.Smileys, tooltipOpen = true,
            bar = EditorBarState(EditorBarMode.Color, arm),
        )
        assertEquals(EditorBackAction.ReturnToSmileyGrid, resolveEditorBack(initial))
        val sheet = initial.copy(smileyDetailOpen = false)
        assertEquals(EditorBackAction.CloseSheet(EditorSheet.Smileys), resolveEditorBack(sheet))
        assertEquals(
            EditorBackAction.CloseSheet(EditorSheet.Options),
            resolveEditorBack(sheet.copy(sheet = EditorSheet.Options)),
        )
        val tooltip = sheet.copy(sheet = null)
        assertEquals(EditorBackAction.CloseTooltip, resolveEditorBack(tooltip))
        assertEquals(EditorBackAction.Disarm(arm), resolveEditorBack(tooltip.copy(tooltipOpen = false)))
    }
}
