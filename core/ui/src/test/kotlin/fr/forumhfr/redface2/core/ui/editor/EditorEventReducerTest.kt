package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptIdentity
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.EditorUploadToken
import fr.forumhfr.redface2.core.domain.editor.SubmissionToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorEventReducerTest {
    private val session = EditorSession("opening-a", "alice", "topic:23:42")
    private val upload = OperationRef.Upload(EditorUploadToken(1, session))
    private val validation = OperationRef.Local(EditorLocalOperation.Validation, 1)
    private val notice = OperationRef.Local(EditorLocalOperation.Notice, 1)
    private val form = OperationRef.Local(EditorLocalOperation.FormLoad, 1)
    private val quote = OperationRef.Local(EditorLocalOperation.QuoteFetch, 1)
    private val submission = OperationRef.Submission(SubmissionToken(1, session))
    private val text = EditorErrors.network.summary

    @Test
    fun `invariant 1 - session and operation replay preserve received and acknowledged terminals`() {
        val error = problem(validation, EditorErrors.empty, 1)
        val state = containing(error)
        val acknowledged = state.step(EditorEventInput.Acknowledge(error.key))
        assertEquals(state.copy(events = emptyList()), acknowledged)
        val replays = listOf(
            EditorEventInput.OpenSession(session),
            EditorEventInput.ObserveOperation(session, validation),
            EditorEventInput.Received(error),
            EditorEventInput.Received(error.copy(arrivalOrder = 99)),
            EditorEventInput.Received(error.copy(key = error.key.copy(occurrence = 99))),
        )
        // No typing/selection/time inputs exist: their lack of effects is structural, not exercised here.
        listOf(state, acknowledged).forEach { stable ->
            replays.forEach { assertEquals(EditorEventTransition(stable), reduceEditorEvents(stable, it)) }
        }
    }

    @Test
    fun `invariant 2 - lower ranks survive and equal ranks use FIFO`() {
        val running = event(upload, EditorEventContent.InProgress(text), 1)
        val information = event(notice, EditorEventContent.Information(text), 2)
        val first = problem(validation, EditorErrors.empty, 3)
        val second = problem(submission, EditorErrors.uncertain, 4)
        var state = containing(running, information, first, second)
        assertEquals(first, state.visibleEvent)
        state = state.step(EditorEventInput.Acknowledge(first.key))
        assertEquals(second, state.visibleEvent)
        state = state.step(EditorEventInput.Acknowledge(second.key))
        assertEquals(running, state.visibleEvent)
        val finished = event(upload, EditorEventContent.Finished(text), 5)
        state = state.step(EditorEventInput.Received(finished))
        assertEquals(information, state.visibleEvent)
        state = state.step(EditorEventInput.Acknowledge(information.key))
        assertEquals(finished, state.visibleEvent)
        assertEquals(listOf(finished), state.events)
    }

    @Test
    fun `invariant 2 - replacement gets its new FIFO position and purges old terminal`() {
        val old = problem(upload, EditorErrors.uploadNetwork, 1)
        val other = problem(validation, EditorErrors.empty, 2)
        val next = OperationRef.Upload(EditorUploadToken(2, session))
        var state = containing(old, other).step(EditorEventInput.ObserveOperation(session, next))
        val replacement = problem(next, EditorErrors.tooLarge, 3)
        state = state.step(EditorEventInput.Received(replacement))
        assertEquals(other, state.visibleEvent)
        assertFalse(state.events.contains(old))
        assertEquals(replacement, state.step(EditorEventInput.Acknowledge(other.key)).visibleEvent)
    }

    @Test
    fun `invariant 2 - older occurrences cannot replace a newer event of the same operation`() {
        val first = event(upload, EditorEventContent.InProgress(text), 1)
        val newer = event(upload, EditorEventContent.InProgress(text), 2)
        val state = containing(first).step(EditorEventInput.Received(newer))
        assertEquals(listOf(newer), state.events)
        val late = first.copy(content = EditorEventContent.Problem(EditorErrors.tooLarge), arrivalOrder = 99)
        assertEquals(state, state.step(EditorEventInput.Received(late)))
        assertEquals(state, state.step(EditorEventInput.UploadAdvanced(first.key, UploadProgress(1, 2))))
        val terminal = problem(upload, EditorErrors.tooLarge, 3)
        assertEquals(listOf(terminal), state.step(EditorEventInput.Received(terminal)).events)
    }

    @Test
    fun `invariant 2 - stale operations and repeated launch callbacks are rejected`() {
        val old = event(upload, EditorEventContent.InProgress(text), 1)
        val next = OperationRef.Upload(EditorUploadToken(2, session))
        val running = event(next, EditorEventContent.InProgress(text), 2)
        val state = containing(old).step(EditorEventInput.OperationStarted(running))
        val staleInputs = listOf(
            EditorEventInput.OperationStarted(old),
            EditorEventInput.ObserveOperation(session, upload),
            EditorEventInput.Received(problem(upload, EditorErrors.tooLarge, 99)),
            EditorEventInput.UploadAdvanced(old.key, UploadProgress(1, 2)),
            EditorEventInput.DurableWriteSucceeded(session, upload),
        )
        staleInputs.forEach { assertEquals(state, state.step(it)) }
        val error = problem(validation, EditorErrors.empty, 3)
        val withError = state.observe(validation).step(EditorEventInput.Received(error))
        assertEquals(withError, withError.step(EditorEventInput.OperationStarted(running)))
        val terminal = withError.step(EditorEventInput.Received(problem(next, EditorErrors.tooLarge, 4)))
        assertEquals(terminal, terminal.step(EditorEventInput.Received(event(next, old.content, 100))))
    }

    @Test
    fun `observing before effective launch preserves the launch and rejects subsequent starts`() {
        val error = problem(validation, EditorErrors.missingTitle, 1)
        val observed = containing(error).observe(submission)
        assertEquals(-1L, observed.currentOperations.last().lastOccurrence)
        val sending = event(submission, EditorEventContent.InProgress(text), 2)
        val started = observed.step(EditorEventInput.OperationStarted(sending))
        assertEquals(listOf(sending), started.events)
        val repeated = sending.copy(key = sending.key.copy(occurrence = 3), arrivalOrder = 3)
        assertEquals(started, started.step(EditorEventInput.OperationStarted(repeated)))
    }

    @Test
    fun `first Received registers an unobserved operation without applying launch cleanup`() {
        val error = problem(validation, EditorErrors.missingTitle, 1)
        val state = containing(error)
        listOf(EditorEventContent.InProgress(text), EditorEventContent.Problem(EditorErrors.tooLarge)).forEach {
            val incoming = event(upload, it, 2)
            val received = state.step(EditorEventInput.Received(incoming))
            assertEquals(listOf(error, incoming), received.events)
            assertEquals(2L, received.currentOperations.last().lastOccurrence)
            assertEquals(received, received.step(EditorEventInput.Received(incoming)))
        }
    }

    @Test
    fun `submission token matches before and after learning its durable attempt identity`() {
        val identity = AttemptIdentity("alice", session.key, "attempt-1")
        val known = submission.copy(attempt = identity)
        val sending = event(known, EditorEventContent.InProgress(text), 1)
        val started = EditorEventState(session).observe(submission).step(EditorEventInput.OperationStarted(sending))
        assertEquals(listOf(sending), started.events)
        val uncertain = problem(submission, EditorErrors.uncertain, 2)
        val received = started.step(EditorEventInput.Received(uncertain))
        assertEquals(listOf(uncertain), received.events)
        assertEquals(known, received.currentOperations.single().ref)
        val save = OperationRef.Local(EditorLocalOperation.DraftSave, 1)
        val withSave = received.step(EditorEventInput.Received(problem(save, EditorErrors.draftSaveFailed, 3)))
        val written = withSave.step(EditorEventInput.DurableWriteSucceeded(session, submission))
        assertEquals(listOf(uncertain), written.events)
    }

    @Test
    fun `invariant 2 - changing account clears all visual state and rejects old session callbacks`() {
        val error = problem(upload, EditorErrors.tooLarge, 1)
        val opened = containing(error).step(EditorEventInput.Activate(error.key))
        val nextSession = session.copy(id = "opening-b", owner = "bob")
        val state = opened.step(EditorEventInput.OpenSession(nextSession))
        assertEquals(EditorEventState(nextSession), state)
        val inputs = listOf(
            EditorEventInput.Received(error),
            EditorEventInput.ObserveOperation(session, upload),
            EditorEventInput.ObserveOperation(nextSession, upload), // Token still belongs to Alice.
            EditorEventInput.OperationStarted(event(upload, EditorEventContent.InProgress(text), 2)),
            EditorEventInput.FormLoaded(session, form),
            EditorEventInput.DurableWriteSucceeded(session, upload),
            EditorEventInput.NextGesture(session),
            EditorEventInput.Activate(error.key),
            EditorEventInput.CloseDetails(error.key),
        )
        inputs.forEach { assertEquals(EditorEventTransition(state), reduceEditorEvents(state, it)) }
        val reopened = state.step(EditorEventInput.OpenSession(session.copy(id = "opening-a-again")))
        assertEquals(reopened, reopened.step(EditorEventInput.Received(error)))
    }

    @Test
    fun `invariant 3 - arming preserves error and only effective launch applies ED44`() {
        val error = problem(validation, EditorErrors.missingTitle, 1)
        val information = event(notice, EditorEventContent.Information(text), 2)
        val state = containing(error, information)
        val arm = ArmToken(1, session, 0, 5_000)
        val bar = EditorBarState(EditorBarMode.Format).withSubmission(EditorSubmissionState.Armed(arm))
        assertEquals(arm, bar.arm)
        assertEquals(error, state.visibleEvent)
        assertEquals(state, state.step(EditorEventInput.NextGesture(session)))
        val sending = event(submission, EditorEventContent.InProgress(text), 3)
        val started = state.step(EditorEventInput.OperationStarted(sending))
        assertEquals(listOf(sending), started.events)
        assertEquals(sending, started.visibleEvent)
    }

    @Test
    fun `invariant 3 - launch clears a hidden validation error behind upload failure and every other terminal`() {
        val failedUpload = problem(upload, EditorErrors.tooLarge, 1)
        val hiddenValidation = problem(validation, EditorErrors.missingTitle, 2)
        val information = event(notice, EditorEventContent.Information(text), 3)
        val insertion = OperationRef.Local(EditorLocalOperation.QuoteInsertion, 1)
        val finished = event(insertion, EditorEventContent.Finished(text), 4)
        val state = containing(failedUpload, hiddenValidation, information, finished)
        assertEquals(failedUpload, state.visibleEvent)
        val sending = event(submission, EditorEventContent.InProgress(text), 6)
        val started = state.step(EditorEventInput.OperationStarted(sending))
        assertEquals(listOf(sending), started.events)
        assertEquals(sending, started.visibleEvent)
        val save = OperationRef.Local(EditorLocalOperation.DraftSave, 1)
        val progress = event(save, EditorEventContent.InProgress(text), 5)
        val withProgress = state.step(EditorEventInput.Received(progress))
            .step(EditorEventInput.OperationStarted(sending))
        assertEquals(listOf(progress, sending), withProgress.events)
    }

    @Test
    fun `invariant 3 - uncertainty survives upload and other launches until acknowledgement or send launch`() {
        val uncertain = problem(submission, EditorErrors.uncertain, 1)
        val nextSend = submission.copy(token = SubmissionToken(2, session))
        val state = containing(uncertain).observe(nextSend)
        assertEquals(listOf(uncertain), state.events)
        val insertion = OperationRef.Local(EditorLocalOperation.QuoteInsertion, 1)
        listOf(upload, insertion).forEach { ref ->
            val running = event(ref, EditorEventContent.InProgress(text), 2)
            val started = state.step(EditorEventInput.OperationStarted(running))
            assertEquals(listOf(uncertain, running), started.events)
            assertEquals(uncertain, started.visibleEvent)
            assertEquals(listOf(running), started.step(EditorEventInput.Acknowledge(uncertain.key)).events)
        }
        val sending = event(nextSend, EditorEventContent.InProgress(text), 3)
        assertEquals(listOf(sending), state.step(EditorEventInput.OperationStarted(sending)).events)
    }

    @Test
    fun `invariant 3 - successful form or quote GET with defaults removes only its own error`() {
        val own = problem(form, EditorErrors.formNetwork, 1)
        val others = listOf(
            problem(quote, EditorErrors.quoteFetchFailed, 2),
            problem(submission, EditorErrors.uncertain, 3),
            problem(validation, EditorErrors.missingSubcategory, 4),
            problem(upload, EditorErrors.tooLarge, 5),
        )
        val before = containing(own, *others.toTypedArray())
        val retry = form.copy(sequence = 2)
        val loading = before.observe(retry)
        assertTrue(loading.events.contains(own))
        assertEquals(loading, loading.step(EditorEventInput.FormLoaded(session, form)))
        val loaded = loading.step(EditorEventInput.FormLoaded(session, retry))
        assertEquals(others, loaded.events)
        assertEquals(loaded, loaded.step(EditorEventInput.Received(problem(form, EditorErrors.invalidForm, 99))))
        val quoteRetry = quote.copy(sequence = 2)
        val quoted = loaded.observe(quoteRetry).step(EditorEventInput.FormLoaded(session, quoteRetry))
        assertEquals(others.drop(1), quoted.events)
        val lateFailure = EditorEventInput.Received(problem(quoteRetry, EditorErrors.quoteFetchFailed, 99))
        assertEquals(quoted, quoted.step(lateFailure))
    }

    @Test
    fun `successful durable write purges draft save information without resolving the attempt error`() {
        val uncertain = problem(submission, EditorErrors.uncertain, 1)
        val failedAttempt = problem(validation, EditorErrors.draftWriteFailed, 2)
        val save = OperationRef.Local(EditorLocalOperation.DraftSave, 1)
        val failedSave = problem(save, EditorErrors.draftSaveFailed, 3)
        val state = containing(uncertain, failedAttempt, failedSave)
        assertEquals(uncertain, state.visibleEvent)
        val afterAttempt = state.step(EditorEventInput.Acknowledge(uncertain.key))
            .step(EditorEventInput.Acknowledge(failedAttempt.key))
        assertEquals(failedSave, afterAttempt.visibleEvent)
        val nextSave = save.copy(sequence = 2)
        val saving = state.observe(nextSave)
        assertTrue(saving.events.contains(failedSave))
        val written = saving.step(EditorEventInput.DurableWriteSucceeded(session, nextSave))
        assertEquals(listOf(uncertain, failedAttempt), written.events)
        val lateFailure = EditorEventInput.Received(problem(nextSave, EditorErrors.draftSaveFailed, 99))
        assertEquals(written, written.step(lateFailure))
    }

    @Test
    fun `only information disappears on the next discrete gesture`() {
        val contents = listOf(
            EditorEventContent.Information(text),
            EditorEventContent.Problem(EditorErrors.noImageReceived),
        )
        contents.forEach { content ->
            val state = containing(event(notice, content, 1))
            assertNull(state.step(EditorEventInput.NextGesture(session)).visibleEvent)
        }
        val finished = containing(event(upload, EditorEventContent.Finished(text), 1))
        assertEquals(finished, finished.step(EditorEventInput.NextGesture(session)))
    }

    @Test
    fun `invariant 4 - Complete opens the header and details are read before acknowledgement`() {
        val complete = problem(validation, EditorErrors.unknownRecipient, 1)
        val completed = reduceEditorEvents(containing(complete), EditorEventInput.Activate(complete.key))
        assertEquals(
            listOf(EditorEventCommand.CompleteHeader(complete.key, EditorCause.UnknownRecipient)), completed.commands,
        )
        assertEquals(containing(complete).currentOperations, completed.state.currentOperations)
        assertNull(completed.state.visibleEvent)
        val detail = problem(quote, EditorErrors.quoteFetchFailed, 2)
        val opened = reduceEditorEvents(containing(detail), EditorEventInput.Activate(detail.key))
        assertEquals(listOf(EditorEventCommand.OpenDetails(detail)), opened.commands)
        assertEquals(detail.key, opened.state.detailKey)
        assertEquals(detail, opened.state.visibleEvent)
        assertEquals(containing(detail).events, opened.state.events)
        val plain = event(notice, EditorEventContent.Information(text), 3)
        val dismissed = reduceEditorEvents(containing(plain), EditorEventInput.Activate(plain.key))
        assertNull(dismissed.state.visibleEvent)
        assertTrue(dismissed.commands.isEmpty())
    }

    @Test
    fun `invariant 5 - closing details acknowledges the captured key and preserves its remedy replacement`() {
        val error = problem(upload, EditorErrors.uploadNetwork, 1)
        val opened = containing(error).step(EditorEventInput.Activate(error.key))
        val next = OperationRef.Upload(EditorUploadToken(2, session))
        val running = event(next, EditorEventContent.InProgress(text), 2)
        val replacement = problem(next, EditorErrors.tooLarge, 3)
        val replaced = opened.step(EditorEventInput.OperationStarted(running))
            .step(EditorEventInput.Received(replacement))
        val closed = reduceEditorEvents(replaced, EditorEventInput.CloseDetails(error.key))
        assertEquals(listOf(replacement), closed.state.events)
        assertEquals(replaced.currentOperations, closed.state.currentOperations)
        assertEquals(closed.state, closed.state.step(EditorEventInput.Received(replacement)))
        assertNull(closed.state.detailKey)
        assertEquals(listOf(EditorEventCommand.ReturnToBody), closed.commands)
        assertEquals(replaced, replaced.step(EditorEventInput.Activate(error.key)))
    }

    @Test
    fun `stale detail closure returns focus without acknowledging a newer event`() {
        val error = problem(upload, EditorErrors.tooLarge, 2)
        val opened = containing(error).step(EditorEventInput.Activate(error.key))
        val stale = error.key.copy(occurrence = 1)
        val closed = reduceEditorEvents(opened, EditorEventInput.CloseDetails(stale))
        assertEquals(
            EditorEventTransition(opened.copy(detailKey = null), listOf(EditorEventCommand.ReturnToBody)), closed,
        )
        val alreadyClosed = reduceEditorEvents(closed.state, EditorEventInput.CloseDetails(stale))
        assertEquals(EditorEventTransition(closed.state), alreadyClosed)
    }

    @Test
    fun `invariant 5 - progress is never actionable or acknowledgeable even beneath an error`() {
        val running = event(upload, EditorEventContent.InProgress(text, UploadProgress(0, 2)), 1)
        val initial = containing(running)
        listOf(EditorEventInput.Activate(running.key), EditorEventInput.Acknowledge(running.key)).forEach {
            assertEquals(EditorEventTransition(initial), reduceEditorEvents(initial, it))
        }
        val error = problem(validation, EditorErrors.empty, 2)
        val covered = initial.observe(validation).step(EditorEventInput.Received(error))
        val advanced = reduceEditorEvents(covered, EditorEventInput.UploadAdvanced(running.key, UploadProgress(1, 2)))
        assertEquals(error, advanced.state.visibleEvent)
        val progress = advanced.state.events.first()
        assertEquals(running.key, progress.key)
        assertEquals(running.arrivalOrder, progress.arrivalOrder)
        assertEquals(UploadProgress(1, 2), (progress.content as EditorEventContent.InProgress).uploadProgress)
        assertTrue(advanced.commands.isEmpty())
        assertEquals(advanced.state, advanced.state.step(EditorEventInput.Acknowledge(running.key)))
    }

    @Test
    fun `invariant 6 - quote acknowledgement preserves watermarks rejects replay and permits a real retry`() {
        val error = problem(quote, EditorErrors.quoteFetchFailed, 1)
        val opened = containing(error).step(EditorEventInput.Activate(error.key))
        val closed = reduceEditorEvents(opened, EditorEventInput.CloseDetails(error.key))
        assertEquals(opened.copy(events = emptyList(), detailKey = null), closed.state)
        assertEquals(listOf(EditorEventCommand.ReturnToBody), closed.commands)
        val replayed = reduceEditorEvents(closed.state, EditorEventInput.Received(error))
        assertEquals(EditorEventTransition(closed.state), replayed)
        val direct = reduceEditorEvents(containing(error), EditorEventInput.Acknowledge(error.key))
        assertEquals(EditorEventTransition(closed.state), direct)
        val retry = quote.copy(sequence = 2)
        val retried = closed.state.observe(retry).step(EditorEventInput.FormLoaded(session, retry))
        assertTrue(retried.currentOperations.single().finished)
        assertNull(retried.visibleEvent)
    }

    @Test
    fun `invariant 6 - visually acknowledging uncertainty keeps the durable attempt identity`() {
        val identity = AttemptIdentity("alice", session.key, "attempt-1")
        val ref = submission.copy(attempt = identity)
        val error = problem(ref, EditorErrors.uncertain, 1)
        val opened = containing(error).step(EditorEventInput.Activate(error.key))
        val closed = reduceEditorEvents(opened, EditorEventInput.CloseDetails(error.key))
        assertEquals(opened.copy(events = emptyList(), detailKey = null), closed.state)
        assertEquals(identity, (closed.state.currentOperations.single().ref as OperationRef.Submission).attempt)
        assertEquals(listOf(EditorEventCommand.ReturnToBody), closed.commands)
        val freshKey = error.copy(key = error.key.copy(occurrence = 99), arrivalOrder = 99)
        val replayed = reduceEditorEvents(closed.state, EditorEventInput.Received(freshKey))
        assertEquals(EditorEventTransition(closed.state), replayed)
        val direct = reduceEditorEvents(containing(error), EditorEventInput.Acknowledge(error.key))
        assertEquals(closed.state, direct.state)
        assertTrue(direct.commands.isEmpty())
    }

    private fun event(ref: OperationRef, content: EditorEventContent, order: Long) =
        EditorEvent(EventKey(session, ref, order), content, order)

    private fun problem(ref: OperationRef, error: EditorError, order: Long) =
        event(ref, EditorEventContent.Problem(error), order)

    private fun containing(vararg events: EditorEvent): EditorEventState = events.fold(EditorEventState(session)) {
        state, event -> state.observe(event.key.operation).step(EditorEventInput.Received(event))
    }

    private fun EditorEventState.observe(ref: OperationRef) = step(EditorEventInput.ObserveOperation(session, ref))
    private fun EditorEventState.step(input: EditorEventInput) = reduceEditorEvents(this, input).state
}
