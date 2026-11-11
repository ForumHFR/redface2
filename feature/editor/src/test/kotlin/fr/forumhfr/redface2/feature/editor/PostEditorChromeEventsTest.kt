package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.SubmissionToken
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.ui.editor.EditorErrorClass
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorEventAction
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.EditorEventInput
import fr.forumhfr.redface2.core.ui.editor.EditorEventRank
import fr.forumhfr.redface2.core.ui.editor.EditorLocalOperation
import fr.forumhfr.redface2.core.ui.editor.EditorText
import fr.forumhfr.redface2.core.ui.editor.OperationRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct state injections test delivery routing without running PostEditorViewModel.
 * missingTitle/missingSubcategory exercise prepared validation routing; the post VM emits missingSubcat (Certain).
 */
class PostEditorChromeEventsTest {
    private val host = PostEditorChromeFixture()
    private val attempt = EditorDraftStore.AttemptToken("alice", host.session.key, "attempt", 1)

    @Test fun `1450 pending quote is one Information below a form error and survives metadata hydration`() {
        host.transition(host.state.copy(restorableDraft = "D", pendingQuotes = listOf(
            QuoteSelection(QuoteLocator(1, 101, null), "", "")), isLoadingForm = true))
        val pending = host.events.visibleEvent!!
        assertEquals(EditorText(R.string.editor_quote_pending), pending.summary)
        assertEquals(EditorEventRank.Information, pending.rank)
        assertEquals(EditorEventAction.Details, pending.action)
        assertEquals(5_000L, (pending.content as EditorEventContent.Information).timeoutMillis)
        host.events.readFailed()
        host.transition(host.state.copy(isLoadingForm = false, submitError = EditorErrors.formNetwork))
        val error = host.events.visibleEvent!!
        assertEquals(EditorEventRank.Error, error.rank)
        assertEquals(EditorLocalOperation.FormLoad, (error.key.operation as OperationRef.Local).kind)
        host.transition(host.state.copy(isLoadingForm = true))
        host.transition(host.state.copy(isLoadingForm = false, submitError = null))
        assertEquals(listOf(pending), host.events.state.events)
        assertEquals(pending, host.events.visibleEvent)
    }

    @Test fun `1450 ordinary error overtaken by a decision retires after a successful quote form`() {
        host.transition(host.state.copy(isLoadingForm = true))
        host.events.readFailed()
        host.transition(host.state.copy(isLoadingForm = false, submitError = EditorErrors.formNetwork))
        host.transition(host.state.copy(isLoadingForm = true, isFetchingQuote = true))
        host.transition(host.state.copy(isLoadingForm = false, submitError = null))
        assertTrue(host.events.state.events.isEmpty())
    }

    @Test fun `1450 pending information follows ED44 without lifting Q or redelivering on observation`() {
        host.transition(host.state.copy(restorableDraft = "D", pendingQuotes = listOf(
            QuoteSelection(QuoteLocator(1, 101, null), "", ""))))
        val key = host.events.visibleEvent!!.key
        assertTrue(host.events.dispatch(EditorEventInput.NextGesture(host.session)).gestureConsumed)
        assertNull(host.events.visibleEvent)
        assertTrue(host.events.isPendingQuoteEvent(key))
        host.transition(host.state.copy(bodyText = "typing"))
        assertNull(host.events.visibleEvent)
        assertTrue(host.state.pendingQuotes.isNotEmpty())
        assertEquals("D", host.state.restorableDraft)
    }

    @Test fun `1450 pending notice retires by exact key when offer or request ends and on account change`() {
        for (endOffer in listOf(true, false)) {
            val fixture = PostEditorChromeFixture()
            fixture.transition(fixture.state.copy(restorableDraft = "D", pendingQuotes = listOf(
                QuoteSelection(QuoteLocator(1, 101, null), "", ""))))
            val pending = fixture.events.visibleEvent!!
            // It can be hidden by another event when its underlying condition ends.
            val error = fixture.error(EditorErrors.formNetwork)
            fixture.transition(if (endOffer) fixture.state.copy(restorableDraft = null)
                else fixture.state.copy(pendingQuotes = emptyList()))
            assertTrue(fixture.events.state.events.none { it.key == pending.key })
            assertEquals(error, fixture.events.visibleEvent)
        }
        host.transition(host.state.copy(restorableDraft = "D", pendingQuotes = listOf(
            QuoteSelection(QuoteLocator(1, 101, null), "", ""))))
        host.events.open(EditorSession("changed", null, host.session.key))
        host.events.observe(host.state, host.state)
        assertTrue(host.events.state.events.isEmpty())
    }

    @Test fun `ED88 silent success completes the operation and rejects late same-session deliveries`() {
        val successes = listOf(EditorSubmissionState.Succeeded(attempt),
            EditorSubmissionState.Uncertain(attempt, accepted = true))
        successes.forEachIndexed { index, success ->
            host.transition(host.state.copy(submission = EditorSubmissionState.PersistingAttempt(
                SubmissionToken(index.toLong() + 1, host.session))))
            val progress = host.events.state.visibleEvent!!
            host.transition(host.state.copy(submission = success))
            assertNull(host.events.state.visibleEvent)
            assertTrue(host.events.state.currentOperations.single { it.ref == progress.key.operation }.finished)
            host.events.dispatch(EditorEventInput.Received(progress.copy(
                key = progress.key.copy(occurrence = progress.key.occurrence + 1),
                content = EditorEventContent.Problem(EditorErrors.network))))
            assertNull(host.events.state.visibleEvent)
        }
    }

    @Test fun `ED43 sending purges injected validation errors while retaining unrelated hidden errors`() {
        val network = host.error(EditorErrors.formNetwork)
        host.transition(host.state.copy(submitError = EditorErrors.missingTitle))
        val validation = host.events.state.events.last()
        assertEquals(EditorLocalOperation.Validation, (validation.key.operation as OperationRef.Local).kind)
        // A second non-displayed error survives launch unless it belongs to validation or the same kind.
        val other = host.error(EditorErrors.invalidForm, operation = 2)
        assertEquals(network, host.events.state.visibleEvent)
        host.transition(host.state.copy(submission = EditorSubmissionState.PersistingAttempt(
            SubmissionToken(1, host.session)), submitError = null))
        assertTrue(validation !in host.events.state.events)
        assertTrue(network !in host.events.state.events)
        assertTrue(other in host.events.state.events)
    }

    @Test fun `injected validation stays local and completes the current submission operation`() {
        host.transition(host.state.copy(submission = EditorSubmissionState.PersistingAttempt(
            SubmissionToken(1, host.session))))
        val progress = host.events.state.visibleEvent!!
        host.transition(host.state.copy(submitError = EditorErrors.missingSubcategory))
        val validation = host.events.state.visibleEvent!!
        assertEquals(EditorLocalOperation.Validation, (validation.key.operation as OperationRef.Local).kind)
        assertTrue(host.events.state.currentOperations.single { it.ref == progress.key.operation }.finished)
        assertEquals(listOf(validation), host.events.state.events)
        host.events.dispatch(EditorEventInput.Acknowledge(validation.key))
        assertNull(host.events.state.visibleEvent)
        host.events.dispatch(EditorEventInput.Received(progress.copy(
            key = progress.key.copy(occurrence = progress.key.occurrence + 1))))
        assertNull(host.events.state.visibleEvent)
        host.transition(host.state.copy(submission = EditorSubmissionState.PersistingAttempt(
            SubmissionToken(2, host.session)), submitError = null))
        assertTrue(host.events.state.visibleEvent!!.content is EditorEventContent.InProgress)
    }

    @Test fun `ED44 ED170 post retry starts only when launch resets the reserved error`() {
        host.transition(host.state.copy(submission = EditorSubmissionState.Uncertain(attempt),
            submitError = EditorErrors.uncertain))
        val uncertain = host.events.state.visibleEvent!!
        host.transition(host.state.copy(submitError = null))
        host.transition(host.state.copy(submitError = EditorErrors.missingTitle))
        val retained = host.events.state.events
        host.transition(host.state.copy(submission = EditorSubmissionState.PersistingAttempt(
            SubmissionToken(1, host.session))))
        assertEquals(retained, host.events.state.events)
        assertEquals(uncertain, host.events.state.visibleEvent)
        host.transition(host.state.copy(submitError = null))
        assertTrue(host.events.state.events.single().content is EditorEventContent.InProgress)
        val sending = host.events.state
        host.transition(host.state)
        assertEquals(sending, host.events.state)
    }

    @Test fun `missingSubcat remains Certain and completes the submission through its error event`() {
        host.transition(host.state.copy(submission = EditorSubmissionState.PersistingAttempt(
            SubmissionToken(1, host.session))))
        val operation = host.events.state.visibleEvent!!.key.operation
        host.transition(host.state.copy(submission = EditorSubmissionState.Ready,
            submitError = EditorErrors.missingSubcat))
        val failure = host.events.state.visibleEvent!!
        val error = (failure.content as EditorEventContent.Problem).error
        assertEquals(EditorErrors.missingSubcat, error)
        assertEquals(EditorErrorClass.Certain, error.errorClass)
        assertEquals(operation, failure.key.operation)
        assertTrue(host.events.state.currentOperations.single { it.ref == operation }.finished)
        host.events.dispatch(EditorEventInput.Acknowledge(failure.key))
        assertNull(host.events.state.visibleEvent)
    }

    @Test fun `post read policy retires existing form and quote errors after successful reloads`() {
        listOf(EditorErrors.formNetwork, EditorErrors.invalidForm, EditorErrors.quoteFetchFailed).forEach { error ->
            host.transition(host.state.copy(isLoadingForm = true, submitError = null))
            host.events.readFailed()
            host.transition(host.state.copy(isLoadingForm = false, submitError = error))
            val failure = host.events.state.visibleEvent!!
            assertEquals(EditorEventContent.Problem(error), failure.content)
            assertEquals(EditorLocalOperation.FormLoad, (failure.key.operation as OperationRef.Local).kind)
            host.transition(host.state.copy(isLoadingForm = true))
            assertEquals(failure, host.events.state.visibleEvent)
            host.transition(host.state.copy(isLoadingForm = false, submitError = null))
            assertNull(host.events.state.visibleEvent)
            assertTrue(host.events.state.events.isEmpty())
            assertTrue(host.events.state.currentOperations.single().finished)
        }
    }
}
