package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.SubmissionToken
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.model.editor.EditorShortcutSurface
import fr.forumhfr.redface2.core.model.editor.ImagePickerContract
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.ui.editor.EditorBarGesture
import fr.forumhfr.redface2.core.ui.editor.EditorBarMode
import fr.forumhfr.redface2.core.ui.editor.EditorErrorSurface
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorEventAction
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.EditorEventRank
import fr.forumhfr.redface2.core.ui.editor.EditorHostActions
import fr.forumhfr.redface2.core.ui.editor.EditorRecipientInput
import fr.forumhfr.redface2.core.ui.editor.EditorRecipientState
import fr.forumhfr.redface2.core.ui.editor.EditorSubjectInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMessageComposeChromeAdapterTest {
    private val f = PrivateMessageComposeChromeFixture()
    private val uncertain = EditorErrors.uncertain(EditorErrorSurface.MessageCompose)

    @Test fun `1450 Back closes the draft sheet before disarming and leaves the header return to its panel`() {
        f.state = f.state.copy(restorableDraft = "Brouillon privé")
        f.adapter.openHeader()
        f.adapter.closeSurface()
        assertFalse(f.adapter.consumeReturn())
        f.adapter.open(PrivateMessageComposeChromeSurface.Restore)
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
        val f = PrivateMessageComposeChromeFixture()
        val surface = EditorShortcutSurface.PRIVATE_MESSAGE_COMPOSE
        EditorShortcut.entries.forEach { choice ->
            f.state = f.state.copy(editorShortcut = choice)
            val shortcut = f.adapter.state.scaffold.shortcut
            assertEquals(choice.takeIf { it.isAvailableOn(surface) }, shortcut?.choice)
            if (shortcut != null) assertEquals(surface, shortcut.surface)
        }
    }

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
    @Test fun `ED113 the entry focus policy freezes only once retained and reset forgets it`() {
        val prefilled = EditorRecipientState(listOf("alice"))
        // A composition that is never applied only reads: the next one still sees the live recipients.
        assertTrue(f.memory.autoFocusBody(prefilled))
        assertFalse(f.memory.autoFocusBody(EditorRecipientState()))
        f.memory.retainAutoFocus(true)
        f.memory.retainAutoFocus(false)
        assertTrue(f.memory.autoFocusBody(EditorRecipientState()))
        f.memory.reset()
        assertFalse(f.memory.autoFocusBody(EditorRecipientState()))
        assertTrue(f.memory.autoFocusBody(EditorRecipientState(buffer = "bo")))
    }
    @Test fun `ED45 completion targets recipients or subject without consuming the next information`() {
        listOf(EditorErrors.unknownRecipient, EditorErrors.incompleteRecipient, EditorErrors.missingSubject)
            .forEach { error ->
                f.transition(f.state.copy(submitError = null, uploadError = null))
                f.transition(f.state.copy(submitError = error))
                val event = f.events.visibleEvent!!
                // The production path: the collector numbers the picker's information itself.
                f.transition(f.state.copy(uploadError = EditorErrors.imageLimit))
                val info = f.events.state.events.last()
                assertEquals(EditorEventContent.Problem(EditorErrors.imageLimit), info.content)
                assertEquals(EditorEventRank.Information, info.rank)
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
    @Test fun `ED45 Masquer acknowledges only the exact uncertain delivery once`() {
        // Every uncertain error carries details (ED-112): Masquer is reached from the detail, never the bar.
        val attempt = AttemptToken("alice", f.session.key, "attempt", 1)
        f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(attempt), submitError = uncertain))
        val key = f.events.visibleEvent!!.key
        f.adapter.barActions.onEvent(key, EditorEventAction.Details)
        assertTrue(f.calls.isEmpty())
        f.adapter.dismissDetails(key)
        assertEquals(listOf("acknowledge"), f.calls)
        assertNull(f.adapter.detail)
        assertNull(f.events.visibleEvent)
        f.adapter.dismissDetails(key)
        assertEquals(listOf("acknowledge"), f.calls)
    }
    @Test fun `ED45 ED85 Back and closing the uncertainty detail never acknowledge`() {
        val attempt = AttemptToken("alice", f.session.key, "attempt", 1)
        f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(attempt), submitError = uncertain))
        val first = f.events.visibleEvent!!.key
        f.adapter.barActions.onEvent(first, EditorEventAction.Details)
        f.adapter.back()
        assertNull(f.adapter.detail)
        assertNull(f.events.visibleEvent)
        f.transition(f.state.copy(submitError = null))
        f.transition(f.state.copy(submitError = uncertain))
        val second = f.events.visibleEvent!!.key
        f.adapter.barActions.onEvent(second, EditorEventAction.Details)
        f.adapter.closeDetails(second)
        assertNull(f.adapter.detail)
        f.adapter.openHeader()
        assertEquals(PrivateMessageComposeChromeSurface.Header, f.adapter.surface)
        f.adapter.back()
        assertNull(f.adapter.surface)
        assertTrue(f.calls.isEmpty())
    }
    @Test fun `ED45 an old uncertainty detail cannot acknowledge a replacement or close its detail`() {
        val attempt = AttemptToken("alice", f.session.key, "old", 1)
        f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(attempt), submitError = uncertain))
        val old = f.events.visibleEvent!!
        f.adapter.barActions.onEvent(old.key, EditorEventAction.Details)
        f.transition(f.state.copy(submission = EditorSubmissionState.PersistingAttempt(SubmissionToken(3, f.session)),
            submitError = null))
        f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(attempt.copy(id = "new")),
            submitError = uncertain))
        val replacement = f.events.visibleEvent!!
        assertNotEquals(old.key, replacement.key)
        f.adapter.dismissDetails(old.key)
        assertTrue(f.calls.isEmpty())
        assertEquals(replacement, f.events.visibleEvent)
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
        val key = f.events.visibleEvent!!.key
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
            val key = f.events.visibleEvent!!.key
            f.adapter.barActions.onEvent(key, EditorEventAction.Details)
            f.transition(f.state.copy(submission = newer))
            f.adapter.dismissDetails(key)
            assertTrue("attempt guard $index", f.calls.isEmpty())
        }
    }
    @Test fun `ED170 a retained detail cannot acknowledge a new session`() {
        val attempt = AttemptToken("alice", f.session.key, "attempt", 1)
        f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(attempt), submitError = uncertain))
        val key = f.events.visibleEvent!!.key
        f.adapter.barActions.onEvent(key, EditorEventAction.Details)
        f.events.open(f.session.copy(id = "new-opening"))
        f.adapter.dismissDetails(key)
        assertTrue(f.calls.isEmpty())
        assertNull(f.events.visibleEvent)
    }
    @Test fun `ED130 a form failure closes every layer without acknowledging or claiming the entry`() {
        val attempt = AttemptToken("alice", f.session.key, "attempt", 1)
        f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(attempt), submitError = uncertain))
        val key = f.events.visibleEvent!!.key
        f.adapter.barActions.onEvent(key, EditorEventAction.Details)
        assertEquals(key, f.adapter.detail?.key)
        var dismissed = 0
        f.adapter.closeLayersForFormFailure { dismissed++ }
        assertNull(f.adapter.detail)
        f.adapter.dismissDetails(key)
        assertTrue("a closed detail never acknowledges", f.calls.isEmpty())
        listOf<() -> Unit>({ f.adapter.openHeader() }, { f.adapter.open(PrivateMessageComposeChromeSurface.Restore) },
            f.adapter.barActions.onOptions, f.adapter.barActions.onSmileys).forEach { open ->
            open()
            assertTrue(f.adapter.surface != null)
            f.adapter.closeLayersForFormFailure { dismissed++ }
            assertNull(f.adapter.surface)
        }
        assertEquals("only the smiley sheet owns a picker", 1, dismissed)
        assertTrue(f.calls.isEmpty())
        assertTrue("the automatic entry latch is untouched", f.saved.keys().isEmpty())
    }
    @Test fun `ED45 local Masquer of a failed attempt write calls only the local port once`() {
        f.transition(f.state.copy(submitError = EditorErrors.draftWriteFailed))
        val event = f.events.visibleEvent!!
        assertEquals(EditorEventAction.Details, event.action)
        // The bar cannot dismiss a delivery whose action opens its detail.
        f.adapter.barActions.onEvent(event.key, EditorEventAction.Dismiss)
        f.adapter.barActions.onEvent(event.key, EditorEventAction.Details)
        assertEquals(event, f.adapter.detail)
        assertTrue(f.calls.isEmpty())
        f.adapter.dismissDetails(event.key)
        f.adapter.dismissDetails(event.key)
        assertEquals(listOf("acknowledge local"), f.calls)
        assertNull(f.adapter.detail)
        assertNull(f.events.visibleEvent)
    }
    @Test fun `ED45 ED85 Back and closing the failed attempt write detail never acknowledge locally`() {
        f.transition(f.state.copy(submitError = EditorErrors.draftWriteFailed))
        val first = f.events.visibleEvent!!.key
        f.adapter.barActions.onEvent(first, EditorEventAction.Details)
        f.adapter.back()
        assertNull(f.adapter.detail)
        assertNull(f.events.visibleEvent)
        f.transition(f.state.copy(submitError = null))
        f.transition(f.state.copy(submitError = EditorErrors.draftWriteFailed))
        val second = f.events.visibleEvent!!.key
        assertNotEquals(first, second)
        f.adapter.barActions.onEvent(second, EditorEventAction.Details)
        f.adapter.closeDetails(second)
        assertNull(f.adapter.detail)
        assertNull(f.events.visibleEvent)
        f.adapter.dismissDetails(second)
        assertTrue(f.calls.isEmpty())
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
    @Test fun `ED31 picker and upload failures are visible problems while the image limit finishes the upload`() {
        listOf(EditorErrors.pickerAbsent, EditorErrors.noImageReceived, EditorErrors.tooLarge).forEach { error ->
            val fixture = PrivateMessageComposeChromeFixture()
            // The VM reports picker failures without an upload; only a real upload failure ends one.
            if (error == EditorErrors.tooLarge) fixture.transition(fixture.state.copy(isUploading = true))
            fixture.transition(fixture.state.copy(isUploading = false, uploadError = error))
            assertEquals(EditorEventContent.Problem(error), fixture.adapter.scaffoldChrome().event?.content)
        }
        // The VM raises the limit when the upload starts; the trimmed batch still finishes as a success.
        f.transition(f.state.copy(isUploading = true, uploadError = EditorErrors.imageLimit))
        f.transition(f.state.copy(isUploading = false))
        val contents = f.events.state.events.map { it.content }
        assertEquals(listOf(EditorEventContent.Problem(EditorErrors.imageLimit)),
            contents.filterIsInstance<EditorEventContent.Problem>())
        assertEquals(1, contents.count { it is EditorEventContent.Finished })
        assertTrue(contents.none { it is EditorEventContent.InProgress })
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
            {}, { state = state.copy(isPreviewVisible = !state.isPreviewVisible) }, { calls += "close" },
            onAcknowledgeUncertain = { calls += "acknowledge" }, onAcknowledgeLocal = { calls += "acknowledge local" }),
        signature = { calls += "signature:$it" }, smileyDisabled = {}, notification = {}, smiley = {}, picker = {},
        restore = { calls += "restore" }, discard = { calls += "discard" }, reload = { calls += "reload" })
    val adapter = createAdapter()
    fun createAdapter(memory: PrivateMessageComposeChromeMemory = this.memory) = PrivateMessageComposeChromeAdapter(
        { state }, body, events, actions, { launches++ }, { openSmileys() }, memory)
    fun transition(next: PrivateMessageComposeUiState) { events.observe(state, next); state = next }
}
