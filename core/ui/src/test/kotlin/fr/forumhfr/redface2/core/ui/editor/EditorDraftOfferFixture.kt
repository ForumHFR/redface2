package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme

/** No ViewModel or draft store: decisions are captured, closing must never call them. */
internal class EditorDraftOfferFixture {
    enum class Surface { Smileys, Options, Picker, Offer }
    val session = EditorSession("opening", "alice", "editor")
    val body = TextFieldState("Bonjour", TextRange(6, 1))
    val bodyFocus = FocusRequester()
    var offers by mutableStateOf<List<EditorDraftStore.DraftOffer>>(emptyList())
    var available by mutableStateOf(true)
    var compact by mutableStateOf(false)
    var restoreEnabled by mutableStateOf(true)
    var discardEnabled by mutableStateOf(true)
    var state by mutableStateOf(EditorHostState(EditorScaffoldChrome(), EditorSubmissionState.Ready, true, true))
    val calls = mutableListOf<String>()
    var returns = 0
    private var sequence = 0L
    val events = object : EditorHostEvents {
        override var state by mutableStateOf(EditorEventState(session))
        override fun dispatch(input: EditorEventInput) = reduceEditorEvents(state, input).also { state = it.state }
    }
    val host = EditorHost(
        { state }, body, events,
        EditorHostActions({ calls += "arm" }, { calls += "send" }, { calls += "disarm" },
            { calls += "format" }, { calls += "preview" }, { calls += "leave" }),
        EditorHostSurfaces(Surface.Smileys, Surface.Options, Surface.Picker,
            draftOffer = Surface.Offer),
        {}, {}, EditorHostMemory(),
    )

    fun notice(): EventKey {
        val key = EventKey(session, OperationRef.Local(EditorLocalOperation.Notice, ++sequence), 0)
        events.dispatch(EditorEventInput.Received(EditorEvent(key,
            EditorEventContent.Information(EditorText(R.string.editor_draft_offer_title),
                EditorText(R.string.editor_action_details)), sequence)))
        return key
    }

    @Composable
    fun Content() {
        val status = if (!available) null else EditorAppBarStatus(R.drawable.ic_ms_restore_page, "Offre de brouillon",
            { host.open(Surface.Offer) }, showDot = true)
        RedfaceTheme(darkTheme = false, dynamicColor = false) {
            EditorScaffold(body, EditorWindowMetrics(if (compact) 400.dp else 640.dp, 0.dp, 0.dp),
                "Répondre", "Sujet", host.scaffoldChrome(secondStatus = status), host.barActions,
                host.submitState, host.submitActions, host.imageActions, host::closeRequested,
                bodyFocusRestoreAllowed = host.surface == null && host.detail == null,
                bodyFocusRequester = bodyFocus, surfaceFocus = host.focusMemory,
                onSystemBack = { host.back() },
                overlay = { onReturn, bodyContent ->
                    LaunchedEffect(host.returnGeneration) {
                        if (host.consumeReturn()) {
                            returns++
                            host.returnToBody(onReturn)
                        }
                    }
                    bodyContent()
                    EditorDraftOfferSheet(host.draftOfferOpen, available, host::closeDraftOffer,
                        onRestore = { calls += "restore"; host.closeDraftOffer() },
                        onDiscard = { calls += "discard"; host.closeDraftOffer() },
                        offers = offers,
                        onRestoreOffer = { calls += "restore:$it"; host.closeDraftOffer() },
                        onDiscardOffer = { calls += "discard:$it"; host.closeDraftOffer() },
                        restoreEnabled = restoreEnabled, discardEnabled = discardEnabled)
                })
        }
    }
}
