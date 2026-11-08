package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorBarStateTest {
    @Test
    fun `ED23 - format survives body editing disarm and event acknowledgement`() {
        val session = EditorSession("session", "alice", "topic:23:42")
        val format = reduceEditorBar(EditorBarState(), EditorBarGesture.ToggleFormat)
        val armed = format.withSubmission(EditorSubmissionState.Armed(ArmToken(1, session, 0, 5_000)))
        assertEquals(EditorBarMode.Format, armed.mode)
        assertEquals(EditorBarMode.Normal, armed.visibleMode)
        assertEquals(armed, reduceEditorBar(armed, EditorBarGesture.ToggleFormat))
        // The controller reports Ready after editing or timeout; the bar does not own either cause.
        val edited = armed.withSubmission(EditorSubmissionState.Ready)
        assertEquals(format, edited)
        val key = EventKey(session, OperationRef.Local(EditorLocalOperation.Validation, 1), 1)
        val error = EditorEvent(key, EditorEventContent.Problem(EditorErrors.empty), 1)
        val state = EditorEventState(session)
        val observed = reduceEditorEvents(state, EditorEventInput.ObserveOperation(session, key.operation)).state
        val received = reduceEditorEvents(observed, EditorEventInput.Received(error)).state
        val acknowledged = reduceEditorEvents(received, EditorEventInput.Acknowledge(key)).state
        assertEquals(emptyList<EditorEvent>(), acknowledged.events)
        assertEquals(EditorBarMode.Format, edited.visibleMode)
        assertEquals(EditorBarMode.Normal, reduceEditorBar(edited, EditorBarGesture.ToggleFormat).mode)
    }

    @Test
    fun `ED24 - color returns to format on choice and on Back`() {
        val format = EditorBarState(EditorBarMode.Format)
        val color = reduceEditorBar(format, EditorBarGesture.OpenColor)
        assertEquals(EditorBarMode.Color, color.mode)
        assertEquals(format, reduceEditorBar(color, EditorBarGesture.ColorChosen))
        assertEquals(format, reduceEditorBar(color, EditorBarGesture.Back))
        assertEquals(EditorBarState(), reduceEditorBar(format, EditorBarGesture.Back))
        assertEquals(EditorBarState(), reduceEditorBar(EditorBarState(), EditorBarGesture.OpenColor))
    }

    @Test
    fun `ED31 - image action and opening another surface close the mode without reopening it`() {
        val image = reduceEditorBar(EditorBarState(), EditorBarGesture.OpenImage)
        assertEquals(EditorBarMode.Image, image.mode)
        assertEquals(EditorBarState(), reduceEditorBar(image, EditorBarGesture.ImageAction))
        assertEquals(EditorBarState(), reduceEditorBar(image, EditorBarGesture.Back))
        listOf(EditorBarMode.Format, EditorBarMode.Color, EditorBarMode.Image).forEach { mode ->
            val closed = reduceEditorBar(EditorBarState(mode), EditorBarGesture.OpenSurface)
            assertEquals(EditorBarState(), closed.withSubmission(EditorSubmissionState.Ready))
        }
    }
}
