package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.SubmissionToken
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.EditorBackAction
import fr.forumhfr.redface2.core.ui.editor.EditorBackState
import fr.forumhfr.redface2.core.ui.editor.EditorBarGesture
import fr.forumhfr.redface2.core.ui.editor.EditorBarMode
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorEventAction
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.EditorEventInput
import fr.forumhfr.redface2.core.ui.editor.EditorSheet
import fr.forumhfr.redface2.core.ui.editor.UploadProgress
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.model.editor.EditorShortcutSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostEditorChromeAdapterTest {
    private val host = PostEditorChromeFixture()

    @Test fun `v078 the draft status opens directly and closing never decides the offer`() {
        host.transition(host.state.copy(restorableDraft = "D", bodyText = "live quote"))
        assertNull(host.events.visibleEvent)
        assertTrue(host.adapter.state.sendActivatable)
        host.adapter.openDraftOffer()
        assertTrue(host.adapter.draftOfferOpen)
        host.adapter.closeDraftOffer()
        assertFalse(host.adapter.draftOfferOpen)
        assertTrue(host.adapter.consumeReturn())
        assertFalse(host.adapter.consumeReturn())
        assertEquals("D", host.state.restorableDraft)
        assertTrue(host.calls.isEmpty())
    }

    @Test fun `v078 save failure Reload remains available with D and refuses uncertainty or invalidation`() {
        val pending = listOf(QuoteSelection(QuoteLocator(1, 101, null), "", ""))
        host.transition(host.state.copy(pendingQuotes = pending, submitError = EditorErrors.draftSaveFailed))
        val failure = host.events.visibleEvent!!
        host.adapter.barActions.onEvent(failure.key, EditorEventAction.Details)
        assertTrue(host.adapter.canReload(failure.key))
        host.state = host.state.copy(blocksFormReload = true)
        assertFalse(host.adapter.canReload(failure.key))
        host.adapter.reload(failure.key)
        host.state = host.state.copy(blocksFormReload = false)
        host.state = host.state.copy(restorableDraft = "D")
        assertTrue(host.adapter.canReload(failure.key))
        host.state = host.state.copy(restorableDraft = null, submission = EditorSubmissionState.Uncertain(null))
        assertFalse(host.adapter.canReload(failure.key))
        host.adapter.reload(failure.key)
        host.state = host.state.copy(submission = EditorSubmissionState.Ready, pendingQuotes = emptyList())
        assertFalse(host.adapter.canReload(failure.key))
        host.adapter.reload(failure.key)
        assertTrue(host.calls.isEmpty())
    }

    @Test fun `1450 quote recovery rejects busy and stale panel callbacks`() {
        host.state = host.state.copy(pendingQuotes = listOf(QuoteSelection(QuoteLocator(1, 101, null), "", "")))
        host.adapter.open(PostEditorChromeSurface.PendingQuote)
        host.state = host.state.copy(isLoadingForm = true)
        host.adapter.reloadPendingQuote()
        assertTrue(host.calls.isEmpty())
        host.state = host.state.copy(isLoadingForm = false)
        host.state = host.state.copy(blocksFormReload = true)
        assertFalse(host.adapter.hasPendingQuoteRecovery)
        host.adapter.reloadPendingQuote()
        assertTrue(host.calls.isEmpty())
        host.state = host.state.copy(blocksFormReload = false)
        host.adapter.closeSurface()
        host.adapter.reloadPendingQuote()
        assertTrue(host.calls.isEmpty())
        host.adapter.open(PostEditorChromeSurface.PendingQuote)
        host.adapter.reloadPendingQuote()
        host.adapter.reloadPendingQuote()
        assertEquals(listOf(PostEditorIntent.ReloadRequested), host.calls)
    }

    @Test fun `ED36 projects exactly the allowed shortcuts on this surface`() {
        val f = PostEditorChromeFixture()
        val surface = EditorShortcutSurface.POST
        EditorShortcut.entries.forEach { choice ->
            f.state = f.state.copy(editorShortcut = choice)
            val shortcut = f.adapter.state.scaffold.shortcut
            assertEquals(choice.takeIf { it.isAvailableOn(surface) }, shortcut?.choice)
            if (shortcut != null) assertEquals(surface, shortcut.surface)
        }
    }

    @Test fun `unknown edit body rejects retained insertion callbacks but keeps options and restore`() {
        val actions = host.adapter.barActions
        val images = host.adapter.imageActions
        for (loading in listOf(true, false)) {
            host.state = host.state.copy(mode = PostEditorMode.Edit, isLoadingForm = loading)
            assertTrue(host.adapter.state.scaffold.bodyReadOnly)
            assertFalse(host.adapter.state.canInsert)
            assertFalse(host.adapter.state.sendActivatable)
            actions.onBbcode(BbcodeAction.Bold)
            actions.onSmileys()
            images.onUpload()
            images.onInsertUrl!!()
            host.adapter.smiley(":jap:")
            host.adapter.imageUrl("https://example.test/image.png")
            assertNull(host.adapter.surface)
            assertEquals(0, host.launches)
            assertTrue(host.calls.isEmpty())
            actions.onOptions()
            assertEquals(PostEditorChromeSurface.Options, host.adapter.surface)
            host.adapter.closeSurface()
        }
        host.adapter.signature(true)
        assertEquals(listOf(PostEditorIntent.ToggleSignature(true)), host.calls)
        host.state = host.state.copy(bodyText = "", draftRestoreSeed = "", restorableDraft = "Brouillon")
        assertTrue(host.state.canRestoreDraft)
        host.adapter.restoreDraft()
        assertEquals(PostEditorIntent.DraftRestoreRequested, host.calls.last())
        host.state = host.state.copy(draftHydratedFromForm = true)
        assertTrue(host.adapter.state.canInsert)
        assertFalse(host.adapter.state.scaffold.bodyReadOnly)
        host.state = host.state.copy(mode = PostEditorMode.Reply, draftHydratedFromForm = false, isLoadingForm = true)
        assertTrue(host.adapter.state.canInsert)
        assertFalse(host.adapter.state.scaffold.bodyReadOnly)
    }

    @Test fun `unknown edit body reloads uncertainty only from a current detail after the GET ends`() {
        host.state = host.state.copy(mode = PostEditorMode.Edit, isLoadingForm = true)
        val event = host.error(EditorErrors.uncertain)
        host.adapter.barActions.onEvent(event.key, EditorEventAction.Details)
        host.adapter.reload(event.key)
        assertTrue(host.calls.isEmpty())
        host.state = host.state.copy(isLoadingForm = false, draftHydratedFromForm = true)
        host.adapter.reload(event.key)
        assertTrue(host.calls.isEmpty())
        host.state = host.state.copy(draftHydratedFromForm = false)
        host.adapter.reload(event.key)
        assertEquals(listOf(PostEditorIntent.ReloadRequested), host.calls)
        assertNull(host.adapter.detail)
        host.adapter.reload(event.key)
        assertEquals(1, host.calls.size)
    }

    @Test fun `image URL confirmation closes the dialog whether the insertion is accepted or refused`() {
        // ImageUrlDialog leaves closing to its adapter (gate T3 r2 C2): both outcomes must close here.
        host.adapter.open(PostEditorChromeSurface.Url)
        assertEquals(PostEditorChromeSurface.Url, host.adapter.surface)
        host.adapter.imageUrl("https://example.test/a.png")
        assertEquals(listOf(PostEditorIntent.ImageUrlInserted("https://example.test/a.png")), host.calls)
        assertNull(host.adapter.surface)
        assertTrue(host.adapter.consumeReturn())

        host.adapter.open(PostEditorChromeSurface.Url)
        assertEquals(PostEditorChromeSurface.Url, host.adapter.surface)
        host.state = host.state.copy(isUploading = true)
        assertFalse(host.adapter.state.canInsert)
        host.adapter.imageUrl("https://example.test/b.png")
        assertEquals(listOf(PostEditorIntent.ImageUrlInserted("https://example.test/a.png")), host.calls)
        assertNull(host.adapter.surface)
        assertTrue(host.adapter.consumeReturn())
    }

    @Test fun `ED31 picker cancellation only returns focus without a delivery or intent`() {
        host.adapter.imageActions.onUpload()
        host.adapter.pickerCancelled()
        assertEquals(1, host.launches)
        assertTrue(host.calls.isEmpty())
        assertTrue(host.events.state.events.isEmpty())
    }

    @Test fun `ED53 quote panels retain panel focus ownership while Restore defers exactly one return`() {
        host.adapter.barActions.onOptions()
        host.adapter.closeSurface()
        assertTrue(host.adapter.consumeReturn())
        val generation = host.adapter.returnGeneration
        listOf(PostEditorChromeSurface.Quotes, PostEditorChromeSurface.PendingQuote).forEach { surface ->
            host.adapter.open(surface)
            assertEquals(surface, host.adapter.surface)
            assertEquals(generation, host.adapter.returnGeneration)
            host.adapter.closeSurface()
            assertNull(host.adapter.surface)
            assertEquals(generation, host.adapter.returnGeneration)
            assertFalse(host.adapter.consumeReturn())
        }
        host.adapter.open(PostEditorChromeSurface.Restore)
        host.adapter.closeDraftOffer()
        assertEquals(generation + 1, host.adapter.returnGeneration)
        assertTrue(host.adapter.consumeReturn())
        host.adapter.closeDraftOffer()
        assertFalse(host.adapter.consumeReturn())
    }
    @Test fun `ED35 real form evidence and strict post guard are independent`() {
        val source = host.state
        assertFalse(PostEditorChromeState(source.copy(hasLoadedForm = false)).sendActivatable)
        assertFalse(PostEditorChromeState(source.copy(isLoadingForm = true)).sendActivatable)
        assertFalse(PostEditorChromeState(source.copy(isUploading = true)).sendActivatable)
        assertFalse(PostEditorChromeState(source.copy(isSubmitting = true)).sendActivatable)
        assertFalse(PostEditorChromeState(source.copy(quoteStorageUnavailable = true)).sendActivatable)
        assertFalse(PostEditorChromeState(source.copy(bodyText = " ")).sendActivatable)
        host.state = source.copy(subcat = null)
        assertTrue(host.adapter.state.sendActivatable)
        assertFalse(host.adapter.state.postAllowed)
        assertFalse(PostEditorChromeState(source.copy(hasAuthenticatedForm = false)).postAllowed)
    }

    @Test fun `ED34 confirmation retains its exact arm`() {
        val old = ArmToken(1, host.session, 0, 4_000L)
        host.state = host.state.copy(submission = EditorSubmissionState.Armed(old))
        val retained = host.adapter.submitActions
        host.state = host.state.copy(submission = EditorSubmissionState.Armed(old.copy(id = 2)))
        retained.onConfirmSubmit()
        assertEquals(PostEditorIntent.SubmitConfirmed(old), host.calls.last())
    }

    @Test fun `ED43 ED45 stale details cannot dismiss or remedy their replacement`() {
        val a = host.error(EditorErrors.formNetwork)
        host.adapter.barActions.onEvent(a.key, EditorEventAction.Details)
        host.transition(host.state.copy(submission = EditorSubmissionState.PersistingAttempt(
            SubmissionToken(3, host.session))))
        host.transition(host.state.copy(submission = EditorSubmissionState.Ready,
            submitError = EditorErrors.quoteFetchFailed))
        val b = host.events.state.visibleEvent!!
        host.adapter.reload(a.key)
        assertTrue(host.calls.isEmpty())
        host.adapter.closeDetails(a.key)
        assertEquals(b, host.events.state.visibleEvent)
        host.adapter.barActions.onEvent(b.key, EditorEventAction.Details)
        host.adapter.reload(b.key)
        assertEquals(listOf(PostEditorIntent.ReloadRequested), host.calls)
        assertNull(host.adapter.detail)
    }

    @Test fun `ED45 Masquer acknowledges only the exact uncertain delivery once`() {
        // Every uncertain error carries details (ED-112): Masquer is reached from the detail, never the bar.
        val attempt = EditorDraftStore.AttemptToken("alice", host.session.key, "attempt", 1)
        host.transition(host.state.copy(submission = EditorSubmissionState.Uncertain(attempt),
            submitError = EditorErrors.uncertain))
        val key = host.events.state.visibleEvent!!.key
        host.adapter.barActions.onEvent(key, EditorEventAction.Details)
        host.adapter.dismissDetails(key)
        assertEquals(listOf(PostEditorIntent.ErrorDismissed), host.calls)
        assertNull(host.adapter.detail)
        assertNull(host.events.state.visibleEvent)
        host.adapter.dismissDetails(key)
        assertEquals(listOf(PostEditorIntent.ErrorDismissed), host.calls)
    }

    @Test fun `ED45 an old uncertainty detail cannot acknowledge a replacement or close its detail`() {
        val attempt = EditorDraftStore.AttemptToken("alice", host.session.key, "old", 1)
        host.transition(host.state.copy(submission = EditorSubmissionState.Uncertain(attempt),
            submitError = EditorErrors.uncertain))
        val old = host.events.state.visibleEvent!!
        host.adapter.barActions.onEvent(old.key, EditorEventAction.Details)
        host.transition(host.state.copy(submission = EditorSubmissionState.PersistingAttempt(
            SubmissionToken(3, host.session)), submitError = null))
        host.transition(host.state.copy(submission = EditorSubmissionState.Uncertain(attempt.copy(id = "new")),
            submitError = EditorErrors.uncertain))
        val replacement = host.events.state.visibleEvent!!
        assertNotEquals(old.key, replacement.key)
        host.adapter.dismissDetails(old.key)
        assertTrue(host.calls.isEmpty())
        assertEquals(replacement, host.events.state.visibleEvent)
        host.adapter.barActions.onEvent(replacement.key, EditorEventAction.Details)
        host.adapter.dismissDetails(old.key)
        assertEquals(replacement, host.adapter.detail)
        assertTrue(host.calls.isEmpty())
        host.adapter.dismissDetails(replacement.key)
        assertEquals(listOf(PostEditorIntent.ErrorDismissed), host.calls)
    }

    @Test fun `ED170 cancellation can deliver uncertainty before its submission state`() {
        val attempt = EditorDraftStore.AttemptToken("alice", host.session.key, "cancelled", 1)
        host.transition(host.state.copy(submission = EditorSubmissionState.Sending(attempt),
            submitError = EditorErrors.uncertain))
        host.transition(host.state.copy(submission = EditorSubmissionState.Uncertain(attempt)))
        val key = host.events.state.visibleEvent!!.key
        host.adapter.barActions.onEvent(key, EditorEventAction.Details)
        host.adapter.dismissDetails(key)
        assertEquals(listOf(PostEditorIntent.ErrorDismissed), host.calls)
    }

    @Test fun `ED170 a retained delivery cannot borrow a different or accepted attempt`() {
        val attempt = EditorDraftStore.AttemptToken("alice", host.session.key, "old", 1)
        listOf(EditorSubmissionState.Uncertain(attempt.copy(id = "new")),
            EditorSubmissionState.Uncertain(attempt, accepted = true)).forEachIndexed { index, newer ->
            host.transition(host.state.copy(submission = EditorSubmissionState.Uncertain(attempt),
                submitError = null))
            host.transition(host.state.copy(submitError = EditorErrors.uncertain))
            val key = host.events.state.visibleEvent!!.key
            host.adapter.barActions.onEvent(key, EditorEventAction.Details)
            host.transition(host.state.copy(submission = newer))
            host.adapter.dismissDetails(key)
            assertTrue("attempt guard $index", host.calls.isEmpty())
        }
    }

    @Test fun `ED170 a retained detail cannot acknowledge a new session`() {
        val attempt = EditorDraftStore.AttemptToken("alice", host.session.key, "attempt", 1)
        host.transition(host.state.copy(submission = EditorSubmissionState.Uncertain(attempt),
            submitError = EditorErrors.uncertain))
        val key = host.events.state.visibleEvent!!.key
        host.adapter.barActions.onEvent(key, EditorEventAction.Details)
        host.events.open(host.session.copy(id = "new-opening"))
        host.adapter.dismissDetails(key)
        assertTrue(host.calls.isEmpty())
        assertNull(host.events.state.visibleEvent)
    }

    @Test fun `ED45 retained detail keeps its remedy while the event key is current`() {
        val event = host.error(EditorErrors.formNetwork)
        host.adapter.barActions.onEvent(event.key, EditorEventAction.Details)
        assertEquals(event, host.adapter.detail)
        host.adapter.reload(event.key)
        assertEquals(listOf(PostEditorIntent.ReloadRequested), host.calls)
        assertNull(host.adapter.detail)
        assertNull(host.events.state.visibleEvent)
    }

    @Test fun `ED82 upload progress and fifty body emissions keep identity and acknowledged terminals stay hidden`() {
        host.transition(host.state.copy(isUploading = true, uploadProgress = UploadProgress(0, 3)))
        val key = host.events.state.visibleEvent!!.key
        repeat(50) { host.transition(host.state.copy(bodyText = "Texte $it")) }
        host.transition(host.state.copy(uploadProgress = UploadProgress(2, 3)))
        assertEquals(key, host.events.state.visibleEvent!!.key)
        host.transition(host.state.copy(uploadProgress = UploadProgress(3, 3)))
        host.transition(host.state.copy(isUploading = false, uploadProgress = null))
        val terminal = host.events.state.visibleEvent!!
        assertEquals(key.operation, terminal.key.operation)
        assertNotEquals(key.occurrence, terminal.key.occurrence)
        host.adapter.barActions.onEvent(terminal.key, EditorEventAction.Dismiss)
        host.transition(host.state.copy(bodyText = "Encore"))
        assertNull(host.events.state.visibleEvent)
        assertTrue(host.calls.isEmpty())
    }

    @Test fun `ED34 accepted submission precedes POST and success never produces a success event`() {
        host.transition(host.state.copy(submission = EditorSubmissionState.PersistingAttempt(SubmissionToken(2,
            host.session))))
        assertTrue(host.events.state.visibleEvent!!.content is EditorEventContent.InProgress)
        val event = host.events.state.visibleEvent
        host.transition(host.state.copy(bodyText = "inchangé"))
        assertEquals(event, host.events.state.visibleEvent)
        host.transition(host.state.copy(submission = EditorSubmissionState.Succeeded(
            EditorDraftStore.AttemptToken("alice", host.session.key, "attempt", 1))))
        assertNull(host.events.state.visibleEvent)
        // Completed through the reducer: the operation is finished, so a later occurrence of it is dropped.
        assertTrue(host.events.state.currentOperations.single { it.ref == event!!.key.operation }.finished)
        host.events.dispatch(EditorEventInput.Received(event!!.copy(key = event.key.copy(
            occurrence = event.key.occurrence + 1))))
        assertNull(host.events.state.visibleEvent)
        host.events.open(host.session.copy(id = "account-change", owner = "bob"))
        assertNull(host.events.state.visibleEvent)
        host.events.dispatch(EditorEventInput.Received(event))
        assertNull(host.events.state.visibleEvent)
    }

    @Test fun `ED85 consumes separate layers before preview and save`() {
        val layers = mutableListOf<EditorBackAction>()
        host.adapter.barActions.onSmileys()
        host.adapter.back(EditorBackState(smileyDetailOpen = true), layers::add)
        assertEquals(listOf(EditorBackAction.ReturnToSmileyGrid), layers)
        host.adapter.back(onLayerBack = layers::add)
        assertEquals(EditorBackAction.CloseSheet(EditorSheet.Smileys), layers.last())
        host.adapter.back(EditorBackState(tooltipOpen = true), layers::add)
        assertEquals(EditorBackAction.CloseTooltip, layers.last())
        host.adapter.gesture(EditorBarGesture.ToggleFormat)
        host.adapter.gesture(EditorBarGesture.OpenColor)
        host.adapter.back(); host.adapter.back()
        val token = ArmToken(1, host.session, 0, 4_000L)
        host.state = host.state.copy(submission = EditorSubmissionState.Armed(token))
        host.adapter.back()
        assertEquals(PostEditorIntent.SubmitConfirmationDismissed, host.calls.single())
        host.adapter.barActions.onOptions(); host.adapter.back()
        host.state = host.state.copy(isPreviewVisible = true)
        host.adapter.back(); host.adapter.back()
        assertEquals(listOf(PostEditorIntent.SubmitConfirmationDismissed, PostEditorIntent.TogglePreview,
            PostEditorIntent.CloseRequested), host.calls)
    }

    @Test fun `ED85 Back skips a Format mode masked by a displayed event without acknowledging it`() {
        host.adapter.gesture(EditorBarGesture.ToggleFormat)
        val event = host.error(EditorErrors.formNetwork)
        host.adapter.back()
        assertEquals(listOf(PostEditorIntent.CloseRequested), host.calls)
        assertEquals(event.key, host.events.state.visibleEvent?.key)
        assertEquals(EditorBarMode.Format, host.memory.bar.mode)
        host.events.dispatch(EditorEventInput.Acknowledge(event.key))
        host.adapter.back() // The retained Format level is visible again and closes locally.
        assertEquals(listOf(PostEditorIntent.CloseRequested), host.calls)
        assertEquals(EditorBarMode.Normal, host.memory.bar.mode)
        host.adapter.back()
        assertEquals(listOf(PostEditorIntent.CloseRequested, PostEditorIntent.CloseRequested), host.calls)
    }

    @Test fun `ED85 Back disarms each masked mode before the next Back closes it`() {
        val token = ArmToken(1, host.session, 0, 4_000L)
        val modes = listOf(
            EditorBarMode.Format to listOf(EditorBarGesture.ToggleFormat),
            EditorBarMode.Color to listOf(EditorBarGesture.ToggleFormat, EditorBarGesture.OpenColor),
            EditorBarMode.Image to listOf(EditorBarGesture.OpenImage),
        )
        modes.forEach { (mode, gestures) ->
            gestures.forEach(host.adapter::gesture)
            host.state = host.state.copy(submission = EditorSubmissionState.Armed(token))
            val calls = host.calls.size
            host.adapter.back()
            assertEquals(PostEditorIntent.SubmitConfirmationDismissed, host.calls.last())
            assertEquals(EditorSubmissionState.Ready, host.state.submission)
            assertEquals(mode, host.memory.bar.mode)
            host.adapter.back()
            val expected = if (mode == EditorBarMode.Color) EditorBarMode.Format else EditorBarMode.Normal
            assertEquals(expected, host.memory.bar.mode)
            assertEquals(calls + 1, host.calls.size)
        }
    }

    @Test fun `ED85 Back closes event details before disarming and preserves the exact arm`() {
        val event = host.error(EditorErrors.network)
        host.adapter.barActions.onEvent(event.key, EditorEventAction.Details)
        val token = ArmToken(1, host.session, 0, 4_000L)
        val armed = EditorSubmissionState.Armed(token)
        host.state = host.state.copy(submission = armed)
        host.adapter.back()
        assertNull(host.adapter.detail)
        assertNull(host.events.state.detailKey)
        assertNull(host.events.state.visibleEvent)
        assertEquals(armed, host.state.submission)
        assertTrue(host.calls.isEmpty())
        host.adapter.back()
        assertEquals(EditorSubmissionState.Ready, host.state.submission)
        assertEquals(listOf(PostEditorIntent.SubmitConfirmationDismissed), host.calls)
    }
}
