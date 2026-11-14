package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorBarStateTest {
    @Test
    fun `ED23 - arming masks format ignores ToggleFormat and disarming restores it`() {
        val session = EditorSession("session", "alice", "topic:23:42")
        val format = reduceEditorBar(EditorBarState(), EditorBarGesture.ToggleFormat)
        val armed = format.withSubmission(EditorSubmissionState.Armed(ArmToken(1, session, 0, 5_000)))
        assertEquals(EditorBarMode.Format, armed.mode)
        assertEquals(EditorBarMode.Normal, armed.visibleMode())
        assertEquals(armed, reduceEditorBar(armed, EditorBarGesture.ToggleFormat))
        val disarmed = armed.withSubmission(EditorSubmissionState.Ready)
        assertEquals(format, disarmed)
        assertEquals(EditorBarMode.Format, disarmed.visibleMode())
        assertEquals(EditorBarMode.Normal, reduceEditorBar(disarmed, EditorBarGesture.ToggleFormat).mode)
    }

    @Test
    fun `ED23 - opening a surface disarms and closes saved modes before the surface can open`() {
        val session = EditorSession("session", "alice", "topic:23:42")
        val arm = ArmToken(1, session, 0, 5_000)
        EditorBarMode.entries.forEach { mode ->
            val closed = reduceEditorBar(EditorBarState(mode, arm), EditorBarGesture.OpenSurface)
            assertEquals(EditorBarState(), closed)
            assertEquals(EditorBarState(), closed.withSubmission(EditorSubmissionState.Ready))
        }
    }

    @Test
    fun `ED85 - a displayed event masks each retained mode until acknowledgement`() {
        val event = controlEvent(EditorEventContent.Problem(EditorErrors.network))
        EditorBarMode.entries.forEach { mode ->
            val state = EditorBarState(mode)
            assertEquals(EditorBarMode.Normal, state.visibleMode(event))
            assertEquals(mode, state.mode)
            assertEquals(mode, state.visibleMode(event = null))
        }
    }

    @Test
    fun `ED85 - retained Back callbacks cannot close a masked mode before disarming`() {
        val session = EditorSession("session", "alice", "topic:23:42")
        val arm = ArmToken(1, session, 0, 5_000)
        listOf(EditorBarMode.Format, EditorBarMode.Color, EditorBarMode.Image).forEach { mode ->
            val armed = EditorBarState(mode, arm)
            assertEquals(armed, reduceEditorBar(armed, EditorBarGesture.Back))
            val disarmed = armed.withSubmission(EditorSubmissionState.Ready)
            val expected = if (mode == EditorBarMode.Color) EditorBarMode.Format else EditorBarMode.Normal
            assertEquals(expected, reduceEditorBar(disarmed, EditorBarGesture.Back).mode)
        }
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
