package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.editor.ArmToken
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
    fun `ED43 - FIFO follows arrivalOrder even when the event list is reversed`() {
        val first = problem(validation, EditorErrors.empty, 1)
        val second = problem(submission, EditorErrors.uncertain, 2)
        val information = event(notice, EditorEventContent.Information(text), 3)
        val finished = event(upload, EditorEventContent.Finished(text), 4)
        val received = containing(first, second, information, finished)
        var state = received.copy(events = received.events.reversed())
        listOf(first, second, information, finished).forEach { expected ->
            assertEquals(expected, state.visibleEvent)
            state = state.step(EditorEventInput.Acknowledge(expected.key))
        }
        assertTrue(state.events.isEmpty())
    }

    @Test
    fun `ED43 - delivery preserves distinct operations of the same kind in FIFO order`() {
        listOf(
            upload to OperationRef.Upload(EditorUploadToken(2, session)),
            submission to submission.copy(token = SubmissionToken(2, session)),
            notice to notice.copy(sequence = 2),
        ).forEach { (previous, next) ->
            val old = problem(previous, EditorErrors.uploadNetwork, 1)
            val other = problem(validation, EditorErrors.empty, 2)
            var state = containing(old, other).step(EditorEventInput.ObserveOperation(session, next))
            val replacement = problem(next, EditorErrors.tooLarge, 3)
            state = state.step(EditorEventInput.Received(replacement))
            assertEquals(listOf(old, other, replacement), state.events)
            assertEquals(old, state.visibleEvent)
            state = state.step(EditorEventInput.Acknowledge(old.key))
            assertEquals(other, state.visibleEvent)
            assertEquals(replacement, state.step(EditorEventInput.Acknowledge(other.key)).visibleEvent)
        }
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
    fun `ED43 - same operation replacement takes a new FIFO place and exact old key cannot dismiss it`() {
        val first = event(upload, EditorEventContent.InProgress(text), 1)
        val other = event(submission, EditorEventContent.InProgress(text), 2)
        val replacement = event(upload, EditorEventContent.InProgress(text), 3)
        val state = containing(first, other).step(EditorEventInput.Received(replacement))
        assertEquals(listOf(other, replacement), state.events)
        assertEquals(other, state.visibleEvent)
        val finished = event(upload, EditorEventContent.Finished(text), 4)
        val terminal = state.step(EditorEventInput.Received(finished))
        assertEquals(terminal, terminal.step(EditorEventInput.Acknowledge(first.key)))
        assertEquals(listOf(other), terminal.step(EditorEventInput.Acknowledge(finished.key)).events)
    }

    @Test
    fun `invariant 2 - stale operations and repeated launch callbacks are rejected`() {
        val old = event(upload, EditorEventContent.InProgress(text), 1)
        val next = OperationRef.Upload(EditorUploadToken(2, session))
        val running = event(next, EditorEventContent.InProgress(text), 2)
        val state = containing(old).step(EditorEventInput.OperationStarted(running))
        assertEquals(listOf(running), state.events)
        val staleInputs = listOf(
            EditorEventInput.OperationStarted(old),
            EditorEventInput.ObserveOperation(session, upload),
            EditorEventInput.Received(problem(upload, EditorErrors.tooLarge, 99)),
            EditorEventInput.UploadAdvanced(old.key, UploadProgress(1, 2)),
            EditorEventInput.OperationCompleted(session, upload),
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
    fun `superseded upload progress cannot mask the current terminal in either callback order`() {
        val old = event(upload, EditorEventContent.InProgress(text), 1)
        val next = OperationRef.Upload(EditorUploadToken(2, session))
        val active = event(next, EditorEventContent.InProgress(text), 2)
        val finished = event(next, EditorEventContent.Finished(text), 3)
        val progress = UploadProgress(1, 2)
        val advances = listOf(EditorEventInput.UploadAdvanced(old.key, progress),
            EditorEventInput.UploadAdvanced(active.key, progress))
        val terminals = listOf(EditorEventInput.Received(problem(upload, EditorErrors.tooLarge, 99)),
            EditorEventInput.Received(finished))
        listOf(EditorEventInput.ObserveOperation(session, next), EditorEventInput.OperationStarted(active),
            EditorEventInput.Received(active)).forEach { reservation ->
            val reserved = containing(old).step(reservation)
            assertFalse(reserved.events.contains(old))
            listOf(false, true).forEach { reversed ->
                var state = reserved.step(EditorEventInput.Received(active))
                (if (reversed) advances.reversed() else advances).forEach { state = state.step(it) }
                assertEquals(listOf(active.copy(content = EditorEventContent.InProgress(text, progress))), state.events)
                (if (reversed) terminals.reversed() else terminals).forEach { state = state.step(it) }
                assertEquals(listOf(finished), state.events)
                assertEquals(finished, state.visibleEvent)
                val closed = reduceEditorEvents(state, EditorEventInput.Activate(finished.key))
                assertTrue(closed.state.events.isEmpty())
                assertTrue(closed.gestureConsumed)
                advances.forEach { assertEquals(closed.state, closed.state.step(it)) }
            }
        }
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
    fun `ED45 - silent completion removes only its progress and rejects late deliveries`() {
        val sending = event(submission, EditorEventContent.InProgress(text), 1)
        val running = event(upload, EditorEventContent.InProgress(text), 2)
        val information = event(notice, EditorEventContent.Information(text), 3)
        val started = EditorEventState(session).step(EditorEventInput.OperationStarted(sending))
        val state = started.step(EditorEventInput.Received(running)).step(EditorEventInput.Received(information))
        val completion = EditorEventInput.OperationCompleted(session, submission)
        val completed = reduceEditorEvents(state, completion)
        assertEquals(listOf(running, information), completed.state.events)
        assertEquals(running, completed.state.visibleEvent)
        assertEquals(
            listOf(EditorEventOperation(submission, 1, finished = true)) + state.currentOperations.drop(1),
            completed.state.currentOperations,
        )
        assertTrue(completed.commands.isEmpty())
        assertFalse(completed.gestureConsumed)
        val lateInputs = listOf(
            completion,
            EditorEventInput.OperationStarted(sending),
            EditorEventInput.Received(event(submission, EditorEventContent.InProgress(text), 4)),
            EditorEventInput.Received(problem(submission, EditorErrors.uncertain, 5)),
        )
        lateInputs.forEach {
            assertEquals(EditorEventTransition(completed.state), reduceEditorEvents(completed.state, it))
        }
        val terminal = containing(problem(submission, EditorErrors.uncertain, 6))
        assertEquals(EditorEventTransition(terminal), reduceEditorEvents(terminal, completion))
        val unknown = EditorEventInput.OperationCompleted(session, form)
        assertEquals(EditorEventTransition(completed.state), reduceEditorEvents(completed.state, unknown))
    }

    @Test
    fun `observing a retry preserves terminals and launch replaces only the displayed error or finish`() {
        val retries = listOf(
            upload to OperationRef.Upload(EditorUploadToken(2, session)),
            submission to submission.copy(token = SubmissionToken(2, session)),
            notice to notice.copy(sequence = 2),
        )
        val contents = listOf(
            EditorEventContent.Problem(EditorErrors.network),
            EditorEventContent.Finished(text),
            EditorEventContent.Information(text),
        )
        retries.forEach { (previous, retry) ->
            val progress = event(previous, EditorEventContent.InProgress(text), 1)
            assertTrue(containing(progress).observe(retry).events.isEmpty())
            contents.forEach { content ->
                val terminal = event(previous, content, 1)
                val observed = containing(terminal).observe(retry)
                assertEquals(listOf(terminal), observed.events)
                assertEquals(EditorEventOperation(retry), observed.currentOperations.single())
                val running = event(retry, EditorEventContent.InProgress(text), 2)
                val expected = if (content is EditorEventContent.Information) {
                    listOf(terminal, running)
                } else {
                    listOf(running)
                }
                assertEquals(expected, observed.step(EditorEventInput.OperationStarted(running)).events)
            }
        }
    }

    @Test
    fun `ED31 - picker observation without upload delivery neither creates nor replaces an event`() {
        val next = OperationRef.Upload(EditorUploadToken(2, session))
        val existing = problem(upload, EditorErrors.tooLarge, 1)
        listOf(EditorEventState(session), containing(existing)).forEach { state ->
            val observed = state.observe(next)
            assertEquals(state.events, observed.events)
            assertEquals(state.visibleEvent, observed.visibleEvent)
            assertEquals(observed, observed.observe(next))
        }
        // RESULT_CANCELED supplies no launch/delivery input; its boundary belongs to #1573.
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
    fun `submission token matches across deliveries without a durable attempt token`() {
        val same = OperationRef.Submission(SubmissionToken(1, session.copy()))
        val sending = event(same, EditorEventContent.InProgress(text), 1)
        val started = EditorEventState(session).observe(submission).step(EditorEventInput.OperationStarted(sending))
        assertEquals(listOf(sending), started.events)
        val uncertain = problem(submission, EditorErrors.uncertain, 2)
        val received = started.step(EditorEventInput.Received(uncertain))
        assertEquals(listOf(uncertain), received.events)
        assertEquals(submission, received.currentOperations.single().ref)
        val save = OperationRef.Local(EditorLocalOperation.DraftSave, 1)
        val withSave = received.step(EditorEventInput.Received(problem(save, EditorErrors.draftSaveFailed, 3)))
        val written = withSave.step(EditorEventInput.DurableWriteSucceeded(session, submission))
        assertEquals(listOf(uncertain), written.events)
    }

    @Test
    fun `submission retry after rejected attempt write rejects callbacks from the old reservation`() {
        val failed = problem(submission, EditorErrors.draftWriteFailed, 1)
        val retry = submission.copy(token = SubmissionToken(2, session))
        val running = event(retry, EditorEventContent.InProgress(text), 2)
        val state = containing(failed).observe(retry).step(EditorEventInput.OperationStarted(running))
        assertEquals(listOf(running), state.events)
        listOf(
            EditorEventInput.ObserveOperation(session, submission),
            EditorEventInput.OperationStarted(event(submission, running.content, 99)),
            EditorEventInput.Received(problem(submission, EditorErrors.uncertain, 99)),
            EditorEventInput.OperationCompleted(session, submission),
            EditorEventInput.DurableWriteSucceeded(session, submission),
        ).forEach { assertEquals(EditorEventTransition(state), reduceEditorEvents(state, it)) }
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
            EditorEventInput.ObserveOperation(nextSession, submission),
            EditorEventInput.Received(problem(submission, EditorErrors.uncertain, 2)),
            EditorEventInput.Received(problem(submission, EditorErrors.uncertain, 2).let {
                it.copy(key = it.key.copy(session = nextSession)) // Relabeling cannot change the token's owner.
            }),
            EditorEventInput.OperationStarted(event(upload, EditorEventContent.InProgress(text), 2)),
            EditorEventInput.OperationCompleted(session, upload),
            EditorEventInput.OperationCompleted(nextSession, upload), // A foreign token cannot finish local work.
            EditorEventInput.OperationCompleted(nextSession, submission),
            EditorEventInput.FormLoaded(session, form),
            EditorEventInput.DurableWriteSucceeded(session, upload),
            EditorEventInput.NextGesture(session),
            EditorEventInput.Activate(error.key),
            EditorEventInput.CloseDetails(error.key),
        )
        inputs.forEach { assertEquals(EditorEventTransition(state), reduceEditorEvents(state, it)) }
        val nextUpload = OperationRef.Upload(EditorUploadToken(1, nextSession))
        val nextError = error.copy(key = EventKey(nextSession, nextUpload, 1))
        val nextOpened = state.step(EditorEventInput.Received(nextError)).step(EditorEventInput.Activate(nextError.key))
        assertEquals(nextError.key, nextOpened.detailKey)
        val staleClosed = reduceEditorEvents(nextOpened, EditorEventInput.CloseDetails(error.key))
        assertEquals(EditorEventTransition(nextOpened), staleClosed)
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
        assertEquals(listOf(information, sending), started.events)
        assertEquals(sending, started.visibleEvent)
    }

    @Test
    fun `ED44 - launch replaces the displayed error and preserves all unrelated hidden terminals`() {
        val failedUpload = problem(upload, EditorErrors.tooLarge, 1)
        val hiddenValidation = problem(validation, EditorErrors.missingTitle, 2)
        val information = event(notice, EditorEventContent.Information(text), 3)
        val insertion = OperationRef.Local(EditorLocalOperation.QuoteInsertion, 1)
        val finished = event(insertion, EditorEventContent.Finished(text), 4)
        val state = containing(failedUpload, hiddenValidation, information, finished)
        assertEquals(failedUpload, state.visibleEvent)
        val sending = event(submission, EditorEventContent.InProgress(text), 6)
        val started = state.step(EditorEventInput.OperationStarted(sending))
        val retained = listOf(hiddenValidation, information, finished)
        assertEquals(retained + sending, started.events)
        assertEquals(hiddenValidation, started.visibleEvent)
        val acknowledged = started.step(EditorEventInput.Acknowledge(hiddenValidation.key))
            .step(EditorEventInput.OperationCompleted(session, submission))
        assertEquals(information, acknowledged.visibleEvent)
        assertEquals(finished, acknowledged.step(EditorEventInput.Acknowledge(information.key)).visibleEvent)
        val save = OperationRef.Local(EditorLocalOperation.DraftSave, 1)
        val progress = event(save, EditorEventContent.InProgress(text), 5)
        val withProgress = state.step(EditorEventInput.Received(progress))
            .step(EditorEventInput.OperationStarted(sending))
        assertEquals(retained + progress + sending, withProgress.events)
    }

    @Test
    fun `ED44 - launch replaces the displayed finish but preserves a hidden finish and information`() {
        val finished = event(upload, EditorEventContent.Finished(text), 1)
        val information = event(notice, EditorEventContent.Information(text), 2)
        val insertion = OperationRef.Local(EditorLocalOperation.QuoteInsertion, 1)
        val hiddenFinish = event(insertion, EditorEventContent.Finished(text), 3)
        val sending = event(submission, EditorEventContent.InProgress(text), 4)
        val state = containing(finished, information, hiddenFinish)
        assertEquals(finished, state.visibleEvent)
        assertEquals(
            listOf(information, hiddenFinish, sending), state.step(EditorEventInput.OperationStarted(sending)).events,
        )
    }

    @Test
    fun `ED44 - a new upload resolves its hidden failure but preserves other hidden terminals`() {
        val finished = event(upload, EditorEventContent.Finished(text), 1)
        val visible = problem(validation, EditorErrors.missingTitle, 2)
        val previous = OperationRef.Upload(EditorUploadToken(2, session))
        val failed = problem(previous, EditorErrors.uploadNetwork, 3)
        val failedSend = problem(submission, EditorErrors.network, 4)
        val information = event(notice, EditorEventContent.Information(text), 5)
        val retry = OperationRef.Upload(EditorUploadToken(3, session))
        val running = event(retry, EditorEventContent.InProgress(text), 6)
        val state = containing(finished, visible, failed, failedSend, information).observe(retry)
        assertEquals(visible, state.visibleEvent)
        val started = state.step(EditorEventInput.OperationStarted(running))
        assertEquals(listOf(finished, failedSend, information, running), started.events)
        assertEquals(failedSend, started.visibleEvent)
    }

    @Test
    fun `invariant 3 - uncertainty survives upload and other launches until acknowledgement or send launch`() {
        val uncertain = problem(submission, EditorErrors.uncertain, 1)
        val save = OperationRef.Local(EditorLocalOperation.DraftSave, 1)
        val information = problem(save, EditorErrors.draftSaveFailed, 2)
        val nextSend = submission.copy(token = SubmissionToken(2, session))
        val state = containing(uncertain, information).observe(nextSend)
        assertEquals(listOf(uncertain, information), state.events)
        val insertion = OperationRef.Local(EditorLocalOperation.QuoteInsertion, 1)
        listOf(upload, insertion).forEach { ref ->
            val running = event(ref, EditorEventContent.InProgress(text), 3)
            val started = state.step(EditorEventInput.OperationStarted(running))
            assertEquals(listOf(uncertain, information, running), started.events)
            assertEquals(uncertain, started.visibleEvent)
            assertEquals(listOf(information, running), started.step(EditorEventInput.Acknowledge(uncertain.key)).events)
        }
        val sending = event(nextSend, EditorEventContent.InProgress(text), 4)
        assertEquals(listOf(information, sending), state.step(EditorEventInput.OperationStarted(sending)).events)
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
    fun `ED45 - form and quote load success remove their progress without another event`() {
        listOf(form, quote).forEach { ref ->
            val loading = event(ref, EditorEventContent.InProgress(text), 1)
            val running = event(upload, EditorEventContent.InProgress(text), 2)
            val information = event(notice, EditorEventContent.Information(text), 3)
            val state = containing(loading, running, information)
            val loaded = reduceEditorEvents(state, EditorEventInput.FormLoaded(session, ref))
            assertEquals(listOf(running, information), loaded.state.events)
            assertEquals(running, loaded.state.visibleEvent)
            assertTrue(loaded.state.currentOperations.first().finished)
            assertTrue(loaded.commands.isEmpty())
            assertFalse(loaded.gestureConsumed)
            val late = EditorEventInput.Received(problem(ref, EditorErrors.formNetwork, 4))
            assertEquals(EditorEventTransition(loaded.state), reduceEditorEvents(loaded.state, late))
        }
    }

    @Test
    fun `ED44 - FormLoad resolves its quote failure only when quotesResolved is true`() {
        val previous = form.copy(sequence = 0)
        val formFailure = problem(previous, EditorErrors.formNetwork, 1)
        val quoteFailure = problem(form, EditorErrors.quoteFetchFailed, 2)
        val independentQuote = problem(quote, EditorErrors.quoteFetchFailed, 3)
        val validationError = problem(validation, EditorErrors.missingTitle, 4)
        val uncertain = problem(submission, EditorErrors.uncertain, 5)
        val others = listOf(independentQuote, validationError, uncertain)
        val retry = form.copy(sequence = 2)
        val loading = containing(formFailure, quoteFailure, *others.toTypedArray()).observe(retry)
        val unresolved = loading.step(EditorEventInput.FormLoaded(session, retry))
        assertEquals(listOf(quoteFailure) + others, unresolved.events)
        val resolved = loading.step(EditorEventInput.FormLoaded(session, retry, quotesResolved = true))
        assertEquals(others, resolved.events)
        assertTrue(resolved.currentOperations.first { it.ref == retry }.finished)
        val late = EditorEventInput.Received(problem(retry, EditorErrors.quoteFetchFailed, 6))
        assertEquals(resolved, resolved.step(late))
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
    fun `ED45 - durable save success removes its progress while attempt writes keep sending progress`() {
        val save = OperationRef.Local(EditorLocalOperation.DraftSave, 1)
        val failed = problem(save, EditorErrors.draftSaveFailed, 1)
        val nextSave = save.copy(sequence = 2)
        val saving = event(nextSave, EditorEventContent.InProgress(text), 2)
        val sending = event(submission, EditorEventContent.InProgress(text), 3)
        val uploading = event(upload, EditorEventContent.InProgress(text), 4)
        val state = containing(failed, saving, sending, uploading)
        val attemptWritten = state.step(EditorEventInput.DurableWriteSucceeded(session, submission))
        assertEquals(listOf(saving, sending, uploading), attemptWritten.events)
        assertFalse(attemptWritten.currentOperations.first { it.ref == submission }.finished)
        val written = reduceEditorEvents(state, EditorEventInput.DurableWriteSucceeded(session, nextSave))
        assertEquals(listOf(sending, uploading), written.state.events)
        assertEquals(sending, written.state.visibleEvent)
        assertTrue(written.state.currentOperations.first { it.ref == nextSave }.finished)
        assertFalse(written.state.currentOperations.first { it.ref == submission }.finished)
        assertTrue(written.commands.isEmpty())
        assertFalse(written.gestureConsumed)
        val late = EditorEventInput.Received(problem(nextSave, EditorErrors.draftSaveFailed, 5))
        assertEquals(written.state, written.state.step(late))
    }

    @Test
    fun `ED44 - first gesture on information is consumed before tools arming or sending`() {
        val contents = listOf(
            EditorEventContent.Information(text),
            EditorEventContent.Problem(EditorErrors.noImageReceived),
            EditorEventContent.Problem(EditorErrors.imageLimit),
            EditorEventContent.Problem(EditorErrors.draftSaveFailed),
        )
        contents.forEach { content ->
            val state = containing(event(notice, content, 1))
            val consumed = reduceEditorEvents(state, EditorEventInput.NextGesture(session))
            assertNull(consumed.state.visibleEvent)
            assertTrue(consumed.gestureConsumed)
            assertTrue(consumed.commands.isEmpty())
            assertEquals(state.currentOperations, consumed.state.currentOperations)
            assertFalse(reduceEditorEvents(consumed.state, EditorEventInput.NextGesture(session)).gestureConsumed)
        }
        listOf(event(upload, EditorEventContent.Finished(text), 1),
            problem(validation, EditorErrors.empty, 1)).forEach { event ->
            val state = containing(event)
            assertEquals(EditorEventTransition(state), reduceEditorEvents(state, EditorEventInput.NextGesture(session)))
        }
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
        assertTrue(completed.gestureConsumed)
        listOf(EditorErrors.quoteFetchFailed, EditorErrors.invalidForm, EditorErrors.formNetwork,
            EditorErrors.noImageReceived, EditorErrors.imageLimit).forEach { error ->
            val detail = problem(quote, error, 2)
            val opened = reduceEditorEvents(containing(detail), EditorEventInput.Activate(detail.key))
            assertEquals(listOf(EditorEventCommand.OpenDetails(detail)), opened.commands)
            assertEquals(detail.key, opened.state.detailKey)
            assertEquals(containing(detail).events, opened.state.events)
            assertTrue(opened.gestureConsumed)
        }
        val plain = event(notice, EditorEventContent.Information(text), 3)
        val dismissed = reduceEditorEvents(containing(plain), EditorEventInput.Activate(plain.key))
        assertNull(dismissed.state.visibleEvent)
        assertTrue(dismissed.commands.isEmpty())
        assertTrue(dismissed.gestureConsumed)
    }

    @Test
    fun `ED45 - activating a present but hidden event neither acts nor consumes the gesture`() {
        val visible = problem(validation, EditorErrors.empty, 1)
        val contents = listOf(
            EditorEventContent.Problem(EditorErrors.missingTitle),
            EditorEventContent.Problem(EditorErrors.uploadNetwork),
            EditorEventContent.Information(text),
        )
        contents.forEach { content ->
            val hidden = event(notice, content, 2)
            val state = containing(visible, hidden)
            assertEquals(visible, state.visibleEvent)
            assertTrue(state.events.contains(hidden))
            assertEquals(EditorEventTransition(state), reduceEditorEvents(state, EditorEventInput.Activate(hidden.key)))
            val revealed = state.step(EditorEventInput.Acknowledge(visible.key))
            assertTrue(reduceEditorEvents(revealed, EditorEventInput.Activate(hidden.key)).gestureConsumed)
        }
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
        assertTrue(closed.gestureConsumed)
        assertEquals(replaced, replaced.step(EditorEventInput.Activate(error.key)))
    }

    @Test
    fun `ED45 - stale detail closure in the same session leaves a newer detail and focus untouched`() {
        val error = problem(upload, EditorErrors.uploadNetwork, 1)
        val initial = containing(error).step(EditorEventInput.Activate(error.key))
        val next = OperationRef.Upload(EditorUploadToken(2, session))
        val running = event(next, EditorEventContent.InProgress(text), 2)
        val replacement = problem(next, EditorErrors.tooLarge, 3)
        val opened = initial.step(EditorEventInput.OperationStarted(running))
            .step(EditorEventInput.Received(replacement)).step(EditorEventInput.Activate(replacement.key))
        assertEquals(replacement.key, opened.detailKey)
        val stale = EditorEventInput.CloseDetails(error.key)
        assertEquals(EditorEventTransition(opened), reduceEditorEvents(opened, stale))
        val closed = reduceEditorEvents(opened, EditorEventInput.CloseDetails(replacement.key))
        assertEquals(listOf(EditorEventCommand.ReturnToBody), closed.commands)
        assertNull(closed.state.detailKey)
        assertTrue(closed.state.events.isEmpty())
        assertTrue(closed.gestureConsumed)
        val alreadyClosed = reduceEditorEvents(closed.state, stale)
        assertEquals(EditorEventTransition(closed.state), alreadyClosed)
    }

    @Test
    fun `ED43 - upload progress never regresses within the same occurrence`() {
        val running = event(upload, EditorEventContent.InProgress(text, UploadProgress(0, 3)), 1)
        val state = containing(running)
        val advanced = state.step(EditorEventInput.UploadAdvanced(running.key, UploadProgress(2, 3)))
        val regression = EditorEventInput.UploadAdvanced(running.key, UploadProgress(1, 3))
        assertEquals(EditorEventTransition(advanced), reduceEditorEvents(advanced, regression))
        val repeated = EditorEventInput.UploadAdvanced(running.key, UploadProgress(2, 3))
        assertEquals(EditorEventTransition(advanced), reduceEditorEvents(advanced, repeated))
        val last = reduceEditorEvents(advanced, EditorEventInput.UploadAdvanced(running.key, UploadProgress(3, 3)))
        assertEquals(
            listOf(running.copy(content = EditorEventContent.InProgress(text, UploadProgress(3, 3)))),
            last.state.events,
        )
        assertEquals(advanced.currentOperations, last.state.currentOperations)
        assertTrue(last.commands.isEmpty())
        assertFalse(last.gestureConsumed)
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
        assertEquals(retry, retried.currentOperations.single().ref)
    }

    @Test
    fun `invariant 6 - visually acknowledging uncertainty keeps the submission identity and watermarks`() {
        val error = problem(submission, EditorErrors.uncertain, 1)
        val opened = containing(error).step(EditorEventInput.Activate(error.key))
        val closed = reduceEditorEvents(opened, EditorEventInput.CloseDetails(error.key))
        assertEquals(opened.copy(events = emptyList(), detailKey = null), closed.state)
        assertEquals(submission, closed.state.currentOperations.single().ref)
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
