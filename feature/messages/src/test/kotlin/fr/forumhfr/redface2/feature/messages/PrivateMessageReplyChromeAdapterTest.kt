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
import fr.forumhfr.redface2.core.ui.editor.EditorErrorSurface
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorEventAction
import fr.forumhfr.redface2.core.ui.editor.EditorHostActions
import fr.forumhfr.redface2.core.ui.editor.EventKey
import fr.forumhfr.redface2.core.ui.post.PostMediaDiskCachePolicy
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.model.editor.EditorShortcutSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMessageReplyChromeAdapterTest {
    private val f = PrivateMessageReplyChromeFixture()
    private val uncertain = EditorErrors.uncertain(EditorErrorSurface.MessageReply)

    @Test fun `1450 Back closes the draft sheet before disarming and schedules one return without a decision`() {
        f.state = f.state.copy(restorableDraft = "Brouillon privé")
        f.adapter.open(PrivateMessageReplyChromeSurface.Restore)
        assertTrue(f.adapter.draftOfferOpen)
        val arm = ArmToken(1, f.session, 0, 4_000L)
        f.state = f.state.copy(submission = EditorSubmissionState.Armed(arm))
        f.adapter.back()
        assertFalse(f.adapter.draftOfferOpen)
        assertNull(f.adapter.surface)
        assertEquals(EditorSubmissionState.Armed(arm), f.state.submission)
        assertTrue(f.calls.isEmpty())
        assertTrue(f.adapter.consumeReturn())
        f.adapter.closeDraftOffer()
        assertFalse(f.adapter.consumeReturn())
        f.adapter.back()
        assertEquals(listOf("disarm"), f.calls)
    }

    @Test fun `ED36 projects exactly the allowed shortcuts on this surface`() {
        val f = PrivateMessageReplyChromeFixture()
        val surface = EditorShortcutSurface.PRIVATE_MESSAGE_REPLY
        EditorShortcut.entries.forEach { choice ->
            f.state = f.state.copy(editorShortcut = choice)
            val shortcut = f.adapter.state.scaffold.shortcut
            assertEquals(choice.takeIf { it.isAvailableOn(surface) }, shortcut?.choice)
            if (shortcut != null) assertEquals(surface, shortcut.surface)
        }
    }

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
    @Test fun `ED45 Masquer acknowledges only the exact uncertain delivery once`() {
        // Every uncertain error carries details (ED-112): Masquer is reached from the detail, never the bar.
        val attempt = AttemptToken("alice", f.session.key, "attempt", 1)
        f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(attempt), submitError = uncertain))
        val key = f.events.state.visibleEvent!!.key
        f.adapter.barActions.onEvent(key, EditorEventAction.Details)
        f.adapter.dismissDetails(key)
        assertEquals(listOf("acknowledge"), f.calls)
        assertNull(f.adapter.detail)
        assertNull(f.events.state.visibleEvent)
        f.adapter.dismissDetails(key)
        assertEquals(listOf("acknowledge"), f.calls)
    }
    @Test fun `ED45 an old uncertainty detail cannot acknowledge a replacement or close its detail`() {
        val attempt = AttemptToken("alice", f.session.key, "old", 1)
        f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(attempt), submitError = uncertain))
        val old = f.events.state.visibleEvent!!
        f.adapter.barActions.onEvent(old.key, EditorEventAction.Details)
        f.transition(f.state.copy(submission = EditorSubmissionState.PersistingAttempt(
            SubmissionToken(3, f.session)), submitError = null))
        f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(attempt.copy(id = "new")),
            submitError = uncertain))
        val replacement = f.events.state.visibleEvent!!
        assertNotEquals(old.key, replacement.key)
        f.adapter.dismissDetails(old.key)
        assertTrue(f.calls.isEmpty())
        assertEquals(replacement, f.events.state.visibleEvent)
        f.adapter.barActions.onEvent(replacement.key, EditorEventAction.Details)
        f.adapter.dismissDetails(old.key)
        assertEquals(replacement, f.adapter.detail)
        assertTrue(f.calls.isEmpty())
        f.adapter.dismissDetails(replacement.key)
        assertEquals(listOf("acknowledge"), f.calls)
    }
    @Test fun `ED170 cancellation can deliver uncertainty before its submission state`() {
        val attempt = AttemptToken("alice", f.session.key, "cancelled", 1)
        f.transition(f.state.copy(submission = EditorSubmissionState.Sending(attempt), submitError = uncertain))
        f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(attempt)))
        val key = f.events.state.visibleEvent!!.key
        f.adapter.barActions.onEvent(key, EditorEventAction.Details)
        f.adapter.dismissDetails(key)
        assertEquals(listOf("acknowledge"), f.calls)
    }
    @Test fun `ED170 a retained delivery cannot borrow a different or accepted attempt`() {
        val attempt = AttemptToken("alice", f.session.key, "old", 1)
        listOf(EditorSubmissionState.Uncertain(attempt.copy(id = "new")),
            EditorSubmissionState.Uncertain(attempt, accepted = true)).forEachIndexed { index, newer ->
            f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(attempt), submitError = null))
            f.transition(f.state.copy(submitError = uncertain))
            val key = f.events.state.visibleEvent!!.key
            f.adapter.barActions.onEvent(key, EditorEventAction.Details)
            f.transition(f.state.copy(submission = newer))
            f.adapter.dismissDetails(key)
            assertTrue("attempt guard $index", f.calls.isEmpty())
        }
    }
    @Test fun `ED170 a retained detail cannot acknowledge a new session`() {
        val attempt = AttemptToken("alice", f.session.key, "attempt", 1)
        f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(attempt), submitError = uncertain))
        val key = f.events.state.visibleEvent!!.key
        f.adapter.barActions.onEvent(key, EditorEventAction.Details)
        f.events.open(f.session.copy(id = "new-opening"))
        f.adapter.dismissDetails(key)
        assertTrue(f.calls.isEmpty())
        assertNull(f.events.state.visibleEvent)
    }
    @Test fun `ED85 ED170 Back closing the detail and a form failure never acknowledge uncertainty`() {
        listOf<(PrivateMessageReplyChromeFixture, EventKey) -> Unit>(
            { fixture, _ -> fixture.adapter.back() },
            { fixture, key -> fixture.adapter.closeDetails(key) },
            { fixture, _ -> fixture.adapter.closeLayersForFormFailure {} },
        ).forEachIndexed { index, close ->
            val f = PrivateMessageReplyChromeFixture()
            val attempt = AttemptToken("alice", f.session.key, "attempt", 1)
            f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(attempt), submitError = uncertain))
            val key = f.events.state.visibleEvent!!.key
            f.adapter.barActions.onEvent(key, EditorEventAction.Details)
            close(f, key)
            assertNull("detail closed $index", f.adapter.detail)
            assertTrue("no acknowledgement $index", f.calls.isEmpty())
            // A closed detail cannot acknowledge later, and leaving the editor is not an acknowledgement.
            f.adapter.dismissDetails(key)
            f.adapter.back()
            assertEquals("leave only $index", listOf("close"), f.calls)
        }
    }
    @Test fun `ED45 Masquer of a failed attempt write calls only the local port once in Ready and Armed`() {
        val armed = EditorSubmissionState.Armed(ArmToken(1, f.session, 0, 4_000L))
        listOf(EditorSubmissionState.Ready, armed).forEachIndexed { index, submission ->
            f.transition(f.state.copy(submission = submission, submitError = null))
            f.transition(f.state.copy(submitError = EditorErrors.draftWriteFailed))
            val key = f.events.state.visibleEvent!!.key
            f.adapter.barActions.onEvent(key, EditorEventAction.Details)
            assertEquals("detail $index", key, f.adapter.detail?.key)
            f.adapter.dismissDetails(key)
            f.adapter.dismissDetails(key)
            // Never the durable port: « Brouillon non écrit » has no attempt to acknowledge.
            assertEquals(List(index + 1) { "acknowledgeLocal" }, f.calls)
            assertNull(f.adapter.detail)
            assertNull(f.events.state.visibleEvent)
        }
    }
    @Test fun `ED85 Back closing the detail and a form failure never acknowledge a failed attempt write`() {
        listOf<(PrivateMessageReplyChromeFixture, EventKey) -> Unit>(
            { fixture, _ -> fixture.adapter.back() },
            { fixture, key -> fixture.adapter.closeDetails(key) },
            { fixture, _ -> fixture.adapter.closeLayersForFormFailure {} },
        ).forEachIndexed { index, close ->
            val f = PrivateMessageReplyChromeFixture()
            f.transition(f.state.copy(submitError = EditorErrors.draftWriteFailed))
            val key = f.events.state.visibleEvent!!.key
            f.adapter.barActions.onEvent(key, EditorEventAction.Details)
            close(f, key)
            assertNull("detail closed $index", f.adapter.detail)
            assertNull("visual acknowledgement only $index", f.events.state.visibleEvent)
            assertTrue("no acknowledgement $index", f.calls.isEmpty())
            // A closed detail cannot acknowledge later, and leaving the editor is not an acknowledgement.
            f.adapter.dismissDetails(key)
            f.adapter.back()
            assertEquals("leave only $index", listOf("close"), f.calls)
        }
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
        }, { calls += "close" }, onAcknowledgeUncertain = { calls += "acknowledge" },
            onAcknowledgeLocal = { calls += "acknowledgeLocal" }),
        { calls += "signature:$it" }, { calls += "smileyDisabled:$it" }, { calls += "notification:$it" },
        { calls += "smiley:$it" }, {}, { calls += "restore" }, { calls += "discard" },
        { calls += "add:$it" }, { calls += "remove:$it" }, { calls += "reload" })
    val adapter = createAdapter()
    fun createAdapter() = PrivateMessageReplyChromeAdapter({ state }, body, events, actions,
        { launches++ }, {}, memory)
    fun transition(next: PrivateMessageReplyUiState) { events.observe(state, next); state = next }
}
