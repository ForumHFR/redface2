package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.SubmissionToken
import fr.forumhfr.redface2.core.ui.editor.EditorBackState
import fr.forumhfr.redface2.core.ui.editor.EditorBarGesture
import fr.forumhfr.redface2.core.ui.editor.EditorBarMode
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorEventAction
import fr.forumhfr.redface2.core.ui.editor.EditorHostActions
import fr.forumhfr.redface2.core.ui.post.PostMediaDiskCachePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMessageReplyChromeAdapterTest {
    private val f = PrivateMessageReplyChromeFixture()

    @Test fun `ED35 projection requires loaded form nonblank body and neither send nor upload`() {
        val ready = f.state
        assertTrue(f.adapter.state.sendActivatable)
        listOf(ready.copy(isInitializing = true), ready.copy(formAvailable = false),
            ready.copy(isLoadingForm = true), ready.copy(isUploading = true), ready.copy(isSubmitting = true),
            ready.copy(bodyText = " \n")).forEach {
            f.state = it
            assertFalse(f.adapter.state.sendActivatable)
            assertFalse(f.adapter.state.postAllowed)
        }
        f.state = ready.copy(isUploading = true)
        assertFalse(f.adapter.state.canInsert)
        assertTrue(f.adapter.state.optionsEnabled)
        f.adapter.imageActions.onUpload()
        assertEquals(0, f.launches)
        f.state = ready.copy(isSubmitting = true)
        assertTrue(f.adapter.state.scaffold.bodyReadOnly)
        assertFalse(f.adapter.state.recipientsEnabled)
        f.adapter.signature(true)
        f.adapter.addRecipient("nouveau")
        f.adapter.openRecipients()
        assertTrue(f.calls.isEmpty())
        assertNull(f.adapter.surface)
    }
    @Test fun `accepted submission states lock all mutations while unaccepted uncertainty stays editable`() {
        val attempt = AttemptToken("alice", f.session.key, "attempt", 1)
        val ready = f.state
        listOf(EditorSubmissionState.PersistingAttempt(SubmissionToken(1, f.session)),
            EditorSubmissionState.Sending(attempt), EditorSubmissionState.Succeeded(attempt),
            EditorSubmissionState.Uncertain(attempt, accepted = true)).forEach { submission ->
            f.state = ready.copy(submission = submission)
            assertFalse(f.adapter.state.postAllowed)
            assertFalse(f.adapter.state.scaffold.controlsEnabled)
            assertFalse(f.adapter.state.canInsert)
        }
        f.state = ready.copy(submission = EditorSubmissionState.Uncertain(attempt))
        assertTrue(f.adapter.state.canInsert)
        assertTrue(f.adapter.state.sendActivatable)
        assertEquals(ready.conversationTitle, PrivateMessageReplyChromeState(ready.copy(isLoadingForm = true)).context)
        assertEquals("", PrivateMessageReplyChromeState(ready.copy(formAvailable = false)).context)
    }
    @Test fun `ED31 ED60 reply offers upload and img wrapping without URL or header and disables disk cache`() {
        assertNull(f.adapter.onOpenHeader)
        assertNull(f.adapter.imageActions.onInsertUrl)
        assertTrue(f.adapter.imageActions.canWrapSelection)
        assertEquals(PostMediaDiskCachePolicy.DISABLED, f.adapter.state.mediaDiskCachePolicy)
        f.adapter.imageActions.onUpload()
        assertEquals(1, f.launches)
        assertEquals(PrivateMessageReplyChromeSurface.Picker, f.adapter.surface)
    }
    @Test fun `ED111 automatic manager waits for owner form opens once and memory survives adapter recreation`() {
        f.adapter.openInitialRecipients(true)
        assertNull(f.adapter.surface)
        f.state = f.state.copy(canManageRecipients = true, recipients = listOf("alice", "Administration"))
        f.adapter.openInitialRecipients(true)
        assertEquals(PrivateMessageReplyChromeSurface.Recipients, f.adapter.surface)
        val recreated = f.createAdapter()
        assertEquals(f.adapter.surface, recreated.surface)
        recreated.closeSurface()
        recreated.openInitialRecipients(true)
        assertNull(recreated.surface)
        recreated.openRecipients()
        assertEquals(PrivateMessageReplyChromeSurface.Recipients, recreated.surface)
        assertFalse(f.state.recipientsDirty)
        assertEquals(listOf("alice", "Administration"), f.state.recipients)
        assertTrue(f.calls.isEmpty())
        f.memory.reset()
        assertFalse(f.memory.recipientEntryHandled)
        assertNull(recreated.surface)
    }
    @Test fun `ED111 retained recipient callbacks obey live ownership loading and minimum membership`() {
        val add = f.adapter::addRecipient
        val remove = f.adapter::removeRecipient
        add("alice"); remove("alice")
        assertTrue(f.calls.isEmpty())
        f.state = f.state.copy(canManageRecipients = true, recipients = listOf("Administration"))
        remove("Administration")
        assertTrue(f.calls.isEmpty())
        f.state = f.state.copy(recipients = listOf("alice", "Administration"))
        remove("Administration")
        assertEquals(listOf("remove:Administration"), f.calls)
        f.state = f.state.copy(isLoadingForm = true)
        add("bob"); remove("alice")
        assertEquals(1, f.calls.size)
    }
    @Test fun `ED85 Back carries displayed event and skips masked mode without acknowledging it`() {
        f.adapter.gesture(EditorBarGesture.ToggleFormat)
        f.transition(f.state.copy(submitError = EditorErrors.antiFlood))
        val event = f.events.visibleEvent!!
        f.adapter.back(EditorBackState(event = event))
        assertEquals(listOf("close"), f.calls)
        assertEquals(EditorBarMode.Format, f.adapter.scaffoldChrome().bar.mode)
        assertEquals(event, f.events.visibleEvent)
    }
    @Test fun `ED85 event detail closes before disarming and keeps exact arm token`() {
        f.transition(f.state.copy(submitError = EditorErrors.antiFlood))
        val event = f.events.visibleEvent!!
        f.adapter.barActions.onEvent(event.key, EditorEventAction.Details)
        val arm = ArmToken(1, f.session, 0, 4_000L)
        f.state = f.state.copy(submission = EditorSubmissionState.Armed(arm))
        val confirm = f.adapter.submitActions
        f.adapter.back()
        assertNull(f.adapter.detail)
        assertTrue(f.calls.isEmpty())
        assertNull(f.events.visibleEvent)
        f.adapter.back()
        assertEquals(listOf("disarm"), f.calls)
        f.state = f.state.copy(submission = EditorSubmissionState.Armed(arm.copy(id = 2)))
        confirm.onConfirmSubmit()
        assertEquals(arm, f.confirmed)
    }
    @Test fun `ED86 manager and picker return selection and IME memory to the body exactly once`() {
        f.state = f.state.copy(canManageRecipients = true, recipients = listOf("alice"))
        f.adapter.focusMemory.imeBeforeSurface = true
        f.adapter.openRecipients()
        f.body.edit { selection = TextRange.Zero }
        val recreated = f.createAdapter()
        assertSame(f.adapter.focusMemory, recreated.focusMemory)
        assertTrue(recreated.focusMemory.imeBeforeSurface)
        recreated.back()
        assertTrue(recreated.consumeReturn())
        assertFalse(recreated.consumeReturn())
        var returned = false
        recreated.returnToBody { returned = true }
        assertTrue(returned)
        assertEquals(TextRange(6, 1), f.body.selection)
        f.adapter.imageActions.onUpload()
        f.adapter.closeSurface()
        assertTrue(f.adapter.consumeReturn())
        assertFalse(f.adapter.consumeReturn())
        f.memory.reset()
        assertFalse(f.adapter.focusMemory.imeBeforeSurface)
    }
    @Test fun `ED44 successful and failed GET do not retire submission events`() {
        listOf(EditorErrors.invalidForm, EditorErrors.uncertain).forEach { error ->
            val f = PrivateMessageReplyChromeFixture()
            f.transition(f.state.copy(submitError = null))
            f.transition(f.state.copy(submitError = error))
            val event = f.events.visibleEvent!!
            f.transition(f.state.copy(isLoadingForm = true))
            f.transition(f.state.copy(isLoadingForm = false, formError = true))
            f.transition(f.state.copy(isLoadingForm = true, formError = false))
            f.transition(f.state.copy(isLoadingForm = false))
            assertTrue(f.events.state.events.any { it == event })
        }
    }
    @Test fun `ED45 recipient refusal offers details without completion or header`() {
        f.transition(f.state.copy(submitError = EditorErrors.refused))
        val event = requireNotNull(f.adapter.scaffoldChrome().event)
        assertEquals(EditorEventAction.Details, event.action)
        assertNull(f.adapter.onOpenHeader)
        f.adapter.barActions.onEvent(event.key, EditorEventAction.Complete)
        assertNull(f.adapter.detail)
        f.adapter.barActions.onEvent(event.key, EditorEventAction.Details)
        assertEquals(event, f.adapter.detail)
        assertNull(f.adapter.surface)
        assertTrue(f.calls.isEmpty())
    }
    @Test fun `ED45 reload uses only a current detail and never replays a POST`() {
        f.transition(f.state.copy(submitError = EditorErrors.invalidForm))
        val event = f.events.visibleEvent!!
        f.adapter.barActions.onEvent(event.key, EditorEventAction.Details)
        f.state = f.state.copy(isLoadingForm = true)
        f.adapter.reload(event.key)
        assertTrue(f.calls.isEmpty())
        f.state = f.state.copy(isLoadingForm = false)
        f.adapter.reload(event.key)
        f.adapter.reload(event.key)
        assertEquals(listOf("reload"), f.calls)
        assertNull(f.adapter.detail)
    }
}

internal class PrivateMessageReplyChromeFixture {
    val session = EditorSession("opening", "alice", "mp-reply:42")
    val events = PrivateMessageReplyEvents().apply { open(session) }
    val memory = PrivateMessageReplyChromeMemory()
    var state by mutableStateOf(PrivateMessageReplyUiState(
        isLoadingForm = false, formAvailable = true, bodyText = "Bonjour", conversationTitle = "Conversation privée"))
    val body = TextFieldState("Bonjour", TextRange(6, 1))
    val calls = mutableListOf<String>()
    var launches = 0
    var confirmed: ArmToken? = null
    val actions = PrivateMessageReplyChromeActions(
        EditorHostActions({ calls += "send" }, { confirmed = it }, {
            calls += "disarm"
            state = state.copy(submission = EditorSubmissionState.Ready)
        }, { calls += "format" }, {
            calls += "preview"
            state = state.copy(isPreviewVisible = !state.isPreviewVisible)
        }, { calls += "close" }),
        { calls += "signature:$it" }, { calls += "smileyDisabled:$it" }, { calls += "notification:$it" },
        { calls += "smiley:$it" }, {}, { calls += "restore" }, { calls += "discard" },
        { calls += "add:$it" }, { calls += "remove:$it" }, { calls += "reload" })
    val adapter = createAdapter()
    fun createAdapter() = PrivateMessageReplyChromeAdapter({ state }, body, events, actions,
        { launches++ }, {}, memory)
    fun transition(next: PrivateMessageReplyUiState) { events.observe(state, next); state = next }
}
