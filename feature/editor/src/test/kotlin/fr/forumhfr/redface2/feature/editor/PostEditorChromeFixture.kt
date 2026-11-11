package fr.forumhfr.redface2.feature.editor

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.EditorEvent
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.EditorEventInput
import fr.forumhfr.redface2.core.ui.editor.EditorHostMemory
import fr.forumhfr.redface2.core.ui.editor.EditorLocalOperation
import fr.forumhfr.redface2.core.ui.editor.EditorWindowMetrics
import fr.forumhfr.redface2.core.ui.editor.EventKey
import fr.forumhfr.redface2.core.ui.editor.OperationRef
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.applyBbcodeAction
import fr.forumhfr.redface2.core.ui.editor.editBbcode
import fr.forumhfr.redface2.core.ui.editor.insertBbcodeToken

internal class PostEditorChromeFixture {
    val session = EditorSession("opening", "alice", "reply:23:42")
    val events = PostEditorChromeEvents().apply { open(session) }
    val memory = EditorHostMemory<PostEditorChromeSurface>()
    var state by mutableStateOf(PostEditorState(PostEditorMode.Reply, 23, 42, null, 1, 0,
        bodyText = "Bonjour", hasLoadedForm = true, hasAuthenticatedForm = true))
    val body = TextFieldState("Bonjour", TextRange(6, 1))
    val calls = mutableListOf<PostEditorIntent>()
    var launches = 0
    val adapter = PostEditorChromeAdapter({ state }, body, events, ::dispatch, { launches++ }, {}, memory)
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
            is PostEditorIntent.QuoteRemoved -> state = state.copy(quotes = state.quotes.filterNot {
                if (intent.quote == null) it.numreponse == intent.numreponse else it == intent.quote
            })
            is PostEditorIntent.QuoteMoved -> {
                val cards = state.quotes.toMutableList()
                val index = cards.indexOfFirst { if (intent.quote == null) it.numreponse == intent.numreponse
                    else it == intent.quote }
                cards.add(index + intent.delta, cards.removeAt(index))
                state = state.copy(quotes = cards)
            }
            PostEditorIntent.QuotesCleared -> state = state.copy(quotes = emptyList())
            PostEditorIntent.SubmitConfirmationDismissed -> state = state.copy(
                submission = EditorSubmissionState.Ready)
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

@Composable
internal fun PostEditorTestContent(
    state: PostEditorState,
    onIntent: (PostEditorIntent) -> Unit = {},
    body: TextFieldState = remember { TextFieldState(state.bodyText) },
    events: PostEditorChromeEvents = remember { PostEditorChromeEvents() },
) {
    val scope = rememberCoroutineScope()
    val smileys = remember { SmileyPickerController(scope, searchWiki = { _, _ -> emptyList() }) }
    val previous = remember { arrayOf(state.copy(submitError = null,
        uploadError = null, legacyQuoteRequestDropped = false)) }
    SideEffect { events.observe(previous[0], state); previous[0] = state }
    val height = LocalConfiguration.current.screenHeightDp
    val fallbackOwner = remember { PostEditorTestActivityResultOwner() }
    val registryOwner = LocalActivityResultRegistryOwner.current ?: fallbackOwner
    CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
        PostEditorContent(state, body, null, onIntent, smileys, chromeEvents = events,
            windowMetrics = EditorWindowMetrics(height.dp, 0.dp, 0.dp))
    }
}

/** Register the real picker contracts even when a test replaces the activity-backed context. */
private class PostEditorTestActivityResultOwner : ActivityResultRegistryOwner {
    override val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            error("Image picker launches require an activity-backed test host")
        }
    }
}
