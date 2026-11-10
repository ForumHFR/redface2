package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.SubmissionToken
import fr.forumhfr.redface2.core.model.editor.ImagePickerContract
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EditorBarGesture
import fr.forumhfr.redface2.core.ui.editor.EditorBarMode
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorEvent
import fr.forumhfr.redface2.core.ui.editor.EditorEventAction
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.EditorEventInput
import fr.forumhfr.redface2.core.ui.editor.EditorHostActions
import fr.forumhfr.redface2.core.ui.editor.EditorLocalOperation
import fr.forumhfr.redface2.core.ui.editor.EditorRecipientInput
import fr.forumhfr.redface2.core.ui.editor.EditorRecipientState
import fr.forumhfr.redface2.core.ui.editor.EditorSubjectInput
import fr.forumhfr.redface2.core.ui.editor.EventKey
import fr.forumhfr.redface2.core.ui.editor.OperationRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivateMessageComposeChromeAdapterTest {
    @get:Rule val compose = createComposeRule()
    private val f = PrivateMessageComposeChromeFixture()
    @Test fun `ED35 projection separates activation from strict POST and freezes every send phase`() {
        assertTrue(f.adapter.state.host.sendActivatable)
        assertFalse(f.adapter.state.host.postAllowed)
        val ready = f.state
        listOf(ready.copy(isLoadingForm = true), ready.copy(formAvailable = false),
            ready.copy(isInitializing = true), ready.copy(isUploading = true), ready.copy(bodyText = " \n"))
            .forEach { f.state = it; assertFalse(f.adapter.state.host.sendActivatable) }
        val attempt = AttemptToken("alice", f.session.key, "attempt", 1)
        listOf(EditorSubmissionState.PersistingAttempt(SubmissionToken(1, f.session)),
            EditorSubmissionState.Sending(attempt), EditorSubmissionState.Succeeded(attempt),
            EditorSubmissionState.Uncertain(attempt, accepted = true)).forEach {
            f.state = ready.copy(submission = it)
            assertFalse(f.adapter.state.scaffold.controlsEnabled)
            assertTrue(f.adapter.state.scaffold.bodyReadOnly)
            f.adapter.signature(true); f.adapter.openHeader(); f.adapter.restoreDraft()
            assertNull(f.adapter.surface)
        }
        assertTrue(f.calls.isEmpty())
        f.state = ready.copy(submission = EditorSubmissionState.Uncertain(attempt))
        assertTrue(f.adapter.state.host.canInsert)
    }
    @Test fun `ED113 entry waits for loading and opens empty only once`() {
        f.state = f.state.copy(isLoadingForm = true)
        f.adapter.openInitialHeader()
        assertTrue(f.saved.keys().isEmpty())
        f.state = f.state.copy(isLoadingForm = false)
        f.adapter.openInitialHeader()
        assertEquals(PrivateMessageComposeChromeSurface.Header, f.adapter.surface)
        val entryKey = PrivateMessageComposeChromeMemory.INITIAL_HEADER_HANDLED
        assertEquals(setOf(entryKey), f.saved.keys())
        assertEquals(true, f.saved.get<Boolean>(entryKey))
        assertEquals(f.adapter.surface, f.createAdapter().surface)
        f.adapter.closeSurface()
        f.adapter.openInitialHeader()
        assertNull(f.adapter.surface)
        val fromSavedLatch = f.createAdapter(PrivateMessageComposeChromeMemory(f.saved))
        fromSavedLatch.openInitialHeader()
        assertNull(fromSavedLatch.surface)
        val recreated = f.createAdapter(PrivateMessageComposeChromeMemory(SavedStateHandle(), recreated = true))
        recreated.openInitialHeader()
        assertNull(recreated.surface)
    }
    @Test fun `ED113 prefilled entry stays closed even after explicitly removing its last chip`() {
        f.state = f.state.copy(recipientState = EditorRecipientState(listOf("alice")))
        f.adapter.openInitialHeader()
        f.state = f.state.copy(recipientState = EditorRecipientState())
        f.adapter.openInitialHeader()
        assertNull(f.adapter.surface)
    }
    @Test fun `ED113 blank chips open the initial header only without an uncommitted pseudo`() {
        for (buffer in listOf("", "alice")) {
            val fixture = PrivateMessageComposeChromeFixture()
            fixture.state = fixture.state.copy(recipientState = EditorRecipientState(listOf("", " ", "\t"), buffer))
            fixture.adapter.openInitialHeader()
            assertEquals(if (buffer.isBlank()) PrivateMessageComposeChromeSurface.Header else null,
                fixture.adapter.surface)
        }
    }
    @Test fun `ED113 summary ignores blank chips and counts only nonblank recipients`() {
        f.state = f.state.copy(recipientState = EditorRecipientState(listOf("", " ", "\t")), subject = "Sujet privé")
        compose.setContent { RedfaceTheme { Text(f.adapter.context()) } }
        compose.onNodeWithText("Ajouter des destinataires · Sujet privé").assertExists()
        compose.runOnIdle {
            f.state = f.state.copy(recipientState = EditorRecipientState(listOf("", "alice", " ", "bob", "\t")))
        }
        compose.onNodeWithText("2 destinataires · Sujet privé").assertExists()
    }
    @Test fun `ED45 completion targets recipients or subject without consuming the next information`() {
        listOf(EditorErrors.unknownRecipient, EditorErrors.incompleteRecipient, EditorErrors.missingSubject)
            .forEachIndexed { index, error ->
                f.transition(f.state.copy(submitError = null))
                f.transition(f.state.copy(submitError = error))
                val event = f.events.visibleEvent!!
                // UnknownRecipient already delivered a terminal Notice; a later notice needs a fresh sequence.
                val sequence = f.events.state.currentOperations.map { it.ref }
                    .filterIsInstance<OperationRef.Local>().maxOf { it.sequence } + 1
                val info = EditorEvent(EventKey(f.session,
                    OperationRef.Local(EditorLocalOperation.Notice, sequence), 0),
                    EditorEventContent.Information(EditorErrors.imageLimit.summary), 100L + index)
                f.events.dispatch(EditorEventInput.Received(info))
                assertEquals(listOf(event, info), f.events.state.events)
                assertEquals(event, f.events.visibleEvent)
                f.adapter.barActions.onEvent(event.key, EditorEventAction.Complete)
                assertEquals(PrivateMessageComposeChromeSurface.Header, f.adapter.surface)
                assertEquals(if (error == EditorErrors.missingSubject) PrivateMessageComposeHeaderField.Subject
                    else PrivateMessageComposeHeaderField.Recipients, f.memory.field)
                assertEquals(info, f.events.visibleEvent)
                f.adapter.closeSurface()
                f.adapter.barActions.onEvent(f.events.visibleEvent!!.key, EditorEventAction.Dismiss)
            }
    }
    @Test fun `ED85 displayed event skips masked format and detail closes before exact-token disarm`() {
        f.adapter.gesture(EditorBarGesture.ToggleFormat)
        f.transition(f.state.copy(submitError = EditorErrors.antiFlood))
        val event = f.events.visibleEvent!!
        f.adapter.back()
        assertEquals(listOf("close"), f.calls)
        assertEquals(EditorBarMode.Format, f.adapter.scaffoldChrome().bar.mode)
        assertEquals(event, f.events.visibleEvent)
        f.calls.clear()
        f.adapter.barActions.onEvent(event.key, EditorEventAction.Details)
        assertEquals(EditorBarMode.Normal, f.adapter.scaffoldChrome().bar.mode)
        val arm = ArmToken(1, f.session, 0, 4_000)
        f.state = f.state.copy(submission = EditorSubmissionState.Armed(arm))
        val confirm = f.adapter.submitActions
        f.adapter.back()
        assertNull(f.adapter.detail)
        assertTrue(f.calls.isEmpty())
        f.adapter.back()
        assertEquals(listOf("disarm"), f.calls)
        f.state = f.state.copy(recipients = "alice", subject = "Sujet",
            recipientState = EditorRecipientState(listOf("alice")),
            submission = EditorSubmissionState.Armed(arm.copy(id = 2)))
        confirm.onConfirmSubmit()
        assertEquals(arm, f.confirmed)
    }
    @Test fun `ED31 image uses system picker and wrapping without URL and cancellation returns once`() {
        assertTrue(f.adapter.imageActions.canWrapSelection)
        assertNull(f.adapter.imageActions.onInsertUrl)
        f.adapter.imageActions.onUpload()
        assertEquals(1, f.launches)
        f.adapter.picker(ImagePickerEvent.Cancelled(ImagePickerContract.OPEN_MULTIPLE_DOCUMENTS, 0))
        assertNull(f.adapter.surface)
        assertTrue(f.adapter.consumeReturn())
        assertFalse(f.adapter.consumeReturn())
        assertNull(f.events.visibleEvent)
    }
}

internal class PrivateMessageComposeChromeFixture {
    val session = EditorSession("opening", "alice", "mp-compose")
    val events = PrivateMessageComposeEvents().apply { open(session) }
    val saved = SavedStateHandle()
    val memory = PrivateMessageComposeChromeMemory(saved)
    var state by mutableStateOf(PrivateMessageComposeUiState(
        isLoadingForm = false, formAvailable = true, bodyKnown = true, bodyText = "Bonjour"))
    val body = TextFieldState("Bonjour", TextRange(6, 1))
    val recipients = EditorRecipientInput({ state.recipientState }, { state = state.copy(recipientState = it) })
    val subject = EditorSubjectInput(70, { state.subject }, { state = state.copy(subject = it) })
    val calls = mutableListOf<String>()
    var confirmed: ArmToken? = null
    var launches = 0
    var openSmileys: () -> Unit = {}
    val actions = PrivateMessageComposeChromeActions(
        host = EditorHostActions({ calls += "send" }, { confirmed = it }, { calls += "disarm" },
            {}, { state = state.copy(isPreviewVisible = !state.isPreviewVisible) }, { calls += "close" }),
        signature = { calls += "signature:$it" }, smileyDisabled = {}, notification = {}, smiley = {}, picker = {},
        restore = { calls += "restore" }, discard = { calls += "discard" }, reload = { calls += "reload" })
    val adapter = createAdapter()
    fun createAdapter(memory: PrivateMessageComposeChromeMemory = this.memory) = PrivateMessageComposeChromeAdapter(
        { state }, body, events, actions, { launches++ }, { openSmileys() }, memory)
    fun transition(next: PrivateMessageComposeUiState) { events.observe(state, next); state = next }
}
