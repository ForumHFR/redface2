package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val detail = tooltip.copy(tooltipOpen = false)
        assertEquals(EditorBackAction.CloseEventDetails(key), resolveEditorBack(detail))
        val sublevel = detail.copy(eventDetailKey = null)
        assertEquals(EditorBackAction.CloseSublevel, resolveEditorBack(sublevel))
        val image = sublevel.copy(bar = EditorBarState(EditorBarMode.Image))
        assertEquals(EditorBackAction.CloseSublevel, resolveEditorBack(image))
        val format = sublevel.copy(bar = EditorBarState(EditorBarMode.Format))
        assertEquals(EditorBackAction.CloseFormat, resolveEditorBack(format))
        val armed = format.copy(bar = EditorBarState(arm = arm))
        assertEquals(EditorBackAction.Disarm(arm), resolveEditorBack(armed))
        val panel = armed.copy(bar = EditorBarState())
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
                bar = EditorBarState(mode, arm), panelOpen = true, sharedPreviewOpen = true,
            )
            assertEquals(EditorBackAction.Disarm(arm), resolveEditorBack(masked))
            val disarmed = masked.copy(bar = masked.bar.withSubmission(EditorSubmissionState.Ready))
            assertEquals(mode, disarmed.bar.mode)
            assertEquals(closeMode, resolveEditorBack(disarmed))
        }
        assertEquals(EditorBackAction.ClosePanel, resolveEditorBack(EditorBackState(panelOpen = true)))
    }

    @Test
    fun `ED85 - event detail closes before a masked mode and the next Back closes that mode`() {
        val error = EditorEvent(key, EditorEventContent.Problem(EditorErrors.network), arrivalOrder = 1)
        val received = reduceEditorEvents(EditorEventState(session), EditorEventInput.Received(error)).state
        val opened = reduceEditorEvents(received, EditorEventInput.Activate(key)).state
        listOf(EditorBarMode.Format, EditorBarMode.Color, EditorBarMode.Image).forEach { mode ->
            val masked = EditorBackState(bar = EditorBarState(mode), eventDetailKey = opened.detailKey)
            assertEquals(EditorBackAction.CloseEventDetails(key), resolveEditorBack(masked))
            val closed = reduceEditorEvents(opened, EditorEventInput.CloseDetails(key))
            assertNull(closed.state.detailKey)
            assertTrue(closed.state.events.isEmpty())
            assertEquals(listOf(EditorEventCommand.ReturnToBody), closed.commands)
            val revealed = masked.copy(eventDetailKey = closed.state.detailKey)
            assertEquals(mode, revealed.bar.mode)
            val expected = if (mode == EditorBarMode.Format) {
                EditorBackAction.CloseFormat
            } else {
                EditorBackAction.CloseSublevel
            }
            assertEquals(expected, resolveEditorBack(revealed))
            val remaining = if (mode == EditorBarMode.Color) EditorBarMode.Format else EditorBarMode.Normal
            assertEquals(remaining, reduceEditorBar(revealed.bar, EditorBarGesture.Back).mode)
        }
    }

    @Test
    fun `ED85 - armed event detail closes first then disarms then closes the preserved mode`() {
        listOf(EditorBarMode.Format, EditorBarMode.Color, EditorBarMode.Image).forEach { mode ->
            val masked = EditorBackState(bar = EditorBarState(mode, arm), eventDetailKey = key)
            assertEquals(EditorBackAction.CloseEventDetails(key), resolveEditorBack(masked))
            val closed = masked.copy(eventDetailKey = null)
            assertEquals(masked.bar, closed.bar)
            assertEquals(EditorBackAction.Disarm(arm), resolveEditorBack(closed))
            val disarmed = closed.copy(bar = closed.bar.withSubmission(EditorSubmissionState.Ready))
            assertEquals(mode, disarmed.bar.mode)
            val expected = if (mode == EditorBarMode.Format) {
                EditorBackAction.CloseFormat
            } else {
                EditorBackAction.CloseSublevel
            }
            assertEquals(expected, resolveEditorBack(disarmed))
        }
    }

    @Test
    fun `ED85 - smiley details sheets and tooltips consume Back before event details and disarming`() {
        val initial = EditorBackState(
            smileyDetailOpen = true, sheet = EditorSheet.Smileys, tooltipOpen = true,
            bar = EditorBarState(EditorBarMode.Color, arm), eventDetailKey = key,
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
        val detail = tooltip.copy(tooltipOpen = false)
        assertEquals(EditorBackAction.CloseEventDetails(key), resolveEditorBack(detail))
        assertEquals(EditorBackAction.Disarm(arm), resolveEditorBack(detail.copy(eventDetailKey = null)))
    }
}
