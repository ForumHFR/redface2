package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.EditorEvent
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.EditorEventInput
import fr.forumhfr.redface2.core.ui.editor.EditorLocalOperation
import fr.forumhfr.redface2.core.ui.editor.EventKey
import fr.forumhfr.redface2.core.ui.editor.OperationRef
import fr.forumhfr.redface2.core.ui.editor.applyBbcodeAction
import fr.forumhfr.redface2.core.ui.editor.editBbcode
import fr.forumhfr.redface2.core.ui.editor.insertBbcodeToken

internal class PostEditorChromeFixture {
    val session = EditorSession("opening", "alice", "reply:23:42")
    val events = PostEditorChromeEvents().apply { open(session) }
    var state by mutableStateOf(PostEditorState(PostEditorMode.Reply, 23, 42, null, 1, 0,
        bodyText = "Bonjour", hasLoadedForm = true, hasAuthenticatedForm = true))
    val body = TextFieldState("Bonjour", TextRange(6, 1))
    val calls = mutableListOf<PostEditorIntent>()
    var launches = 0
    val adapter = PostEditorChromeAdapter({ state }, body, events, ::dispatch, { launches++ }, {})
    fun dispatch(intent: PostEditorIntent) {
        calls += intent
        when (intent) {
            is PostEditorIntent.ToolbarActionClicked -> body.editBbcode { text, start, end ->
                applyBbcodeAction(intent.action, text, start, end)
            }
            is PostEditorIntent.SmileySelected -> body.editBbcode { text, start, end ->
                insertBbcodeToken(intent.token, text, start, end)
            }
            PostEditorIntent.TogglePreview -> state = state.copy(isPreviewVisible = !state.isPreviewVisible)
            PostEditorIntent.SubmitConfirmationDismissed -> state = state.copy(
                submission = fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState.Ready)
            else -> Unit
        }
    }
    fun error(error: EditorError, operation: Long = 1, occurrence: Long = 0): EditorEvent {
        val event = EditorEvent(EventKey(session, OperationRef.Local(EditorLocalOperation.FormLoad, operation),
            occurrence), EditorEventContent.Problem(error), operation + occurrence)
        events.dispatch(EditorEventInput.Received(event))
        return event
    }
    fun transition(next: PostEditorState) { events.observe(state, next); state = next }
}
