package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.SubmissionToken
import fr.forumhfr.redface2.core.ui.editor.EditorErrorClass
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.EditorEventInput
import fr.forumhfr.redface2.core.ui.editor.EditorLocalOperation
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
            SubmissionToken(1, host.session))))
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
            SubmissionToken(2, host.session))))
        assertTrue(host.events.state.visibleEvent!!.content is EditorEventContent.InProgress)
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
}
