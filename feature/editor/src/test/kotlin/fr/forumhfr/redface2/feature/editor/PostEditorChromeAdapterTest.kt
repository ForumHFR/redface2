package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.SubmissionToken
import fr.forumhfr.redface2.core.ui.editor.EditorBackAction
import fr.forumhfr.redface2.core.ui.editor.EditorBackState
import fr.forumhfr.redface2.core.ui.editor.EditorBarGesture
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorEventAction
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.EditorEventInput
import fr.forumhfr.redface2.core.ui.editor.EditorSheet
import fr.forumhfr.redface2.core.ui.editor.UploadProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostEditorChromeAdapterTest {
    private val host = PostEditorChromeFixture()
    @Test fun `ED31 picker cancellation only returns focus without a delivery or intent`() {
        host.adapter.imageActions.onUpload()
        host.adapter.pickerCancelled()
        assertEquals(1, host.launches)
        assertTrue(host.calls.isEmpty())
        assertTrue(host.events.state.events.isEmpty())
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

    @Test fun `ED85 closes smiley detail sheet tooltip color format arm detail panel then preview before save`() {
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
        host.events.dispatch(EditorEventInput.Acknowledge(event.key))
        host.adapter.back() // The retained Format level is visible again and closes locally.
        assertEquals(listOf(PostEditorIntent.CloseRequested), host.calls)
        host.adapter.back()
        assertEquals(listOf(PostEditorIntent.CloseRequested, PostEditorIntent.CloseRequested), host.calls)
    }
}
