package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.SubmissionToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorEventCollectorTest {
    private val session = EditorSession("opening", "alice", "surface")
    private val errorMappers = EditorErrorMappers(
        isReadError = { it == EditorErrors.formNetwork },
        resolvesReadError = { error, quotesResolved -> error.afterFormLoaded(quotesResolved) == null },
    )
    private val collector = EditorEventCollector(errorMappers).apply { open(session) }
    private var projection = EditorEventProjection(session)
    private val attempt = EditorDraftStore.AttemptToken("alice", session.key, "attempt", 1)
    private fun observe(next: EditorEventProjection) { collector.observe(projection, next); projection = next }
    private fun start(id: Long = 1) {
        observe(projection.copy(submitError = null))
        observe(projection.copy(submission = EditorSubmissionState.PersistingAttempt(SubmissionToken(id, session))))
    }
    private fun visible() = requireNotNull(collector.state.visibleEvent)
    private fun acknowledge() = collector.dispatch(EditorEventInput.Acknowledge(visible().key))

    @Test fun `success and accepted uncertainty finish silently and reject late deliveries`() {
        listOf(EditorSubmissionState.Succeeded(attempt), EditorSubmissionState.Uncertain(attempt, accepted = true))
            .forEachIndexed { index, result ->
                start(index.toLong() + 1)
                val progress = visible()
                observe(projection.copy(submission = EditorSubmissionState.Sending(attempt)))
                assertEquals(progress, visible())
                observe(projection.copy(submission = result))
                assertNull(collector.state.visibleEvent)
                assertTrue(collector.state.currentOperations.single { it.ref == progress.key.operation }.finished)
                collector.dispatch(EditorEventInput.Received(progress.copy(
                    key = progress.key.copy(occurrence = 1),
                    content = EditorEventContent.Problem(EditorErrors.network))))
                assertNull(collector.state.visibleEvent)
            }
    }

    @Test fun `certain and ambiguous failures replace progress and finish the matching send`() {
        listOf(EditorErrors.network, EditorErrors.uncertain).forEachIndexed { index, error ->
            start(index.toLong() + 1)
            val operation = visible().key.operation
            val result = if (error == EditorErrors.uncertain) EditorSubmissionState.Uncertain(attempt)
                else EditorSubmissionState.Ready
            observe(projection.copy(submission = result, submitError = error))
            assertEquals(operation, visible().key.operation)
            assertEquals(EditorEventContent.Problem(error), visible().content)
            assertTrue(collector.state.currentOperations.single { it.ref == operation }.finished)
            acknowledge()
            assertNull(collector.state.visibleEvent)
        }
    }

    @Test fun `refused validation is local and launching a send removes it`() {
        observe(projection.copy(submitError = EditorErrors.missingRecipients))
        assertEquals(EditorLocalOperation.Validation, (visible().key.operation as OperationRef.Local).kind)
        start()
        assertTrue(visible().content is EditorEventContent.InProgress)
        assertEquals(1, collector.state.events.size)
    }

    @Test fun `identical local refusals are distinct occurrences after each observed null reset`() {
        val cases = listOf(
            EditorErrors.missingTitle to EditorLocalOperation.Validation,
            EditorErrors.missingRecipients to EditorLocalOperation.Validation,
            EditorErrors.unknownRecipient to EditorLocalOperation.Notice,
        )
        cases.forEach { (error, kind) ->
            observe(projection.copy(submitError = null))
            observe(projection.copy(submitError = error))
            val first = visible()
            assertEquals(kind, (first.key.operation as OperationRef.Local).kind)
            collector.dispatch(EditorEventInput.Activate(first.key))
            assertNull(collector.state.visibleEvent)
            observe(projection) // An unchanged result is not another refusal.
            assertNull(collector.state.visibleEvent)
            observe(projection.copy(submitError = null))
            observe(projection.copy(submitError = error))
            val second = visible()
            assertEquals(first.content, second.content)
            assertNotEquals(first.key, second.key)
            assertTrue(second.arrivalOrder > first.arrivalOrder)
            assertEquals(listOf(second), collector.state.events)
            acknowledge()
        }
    }

    @Test fun `identical send failures after observed null resets never leave sending progress`() {
        repeat(2) { index ->
            start(index.toLong() + 1)
            val progress = visible()
            assertTrue(progress.content is EditorEventContent.InProgress)
            observe(projection.copy(submission = EditorSubmissionState.Ready, submitError = EditorErrors.network))
            assertEquals(progress.key.operation, visible().key.operation)
            assertEquals(EditorEventContent.Problem(EditorErrors.network), visible().content)
            assertFalse(collector.state.events.any { it.content is EditorEventContent.InProgress })
            assertTrue(collector.state.currentOperations.single { it.ref == progress.key.operation }.finished)
            // Keep the error visible until the next launch, as after a failed send without acknowledgement.
        }
    }

    @Test fun `validation during an active send completes it before exposing a local error`() {
        start()
        val progress = visible()
        observe(projection.copy(submission = EditorSubmissionState.Ready, submitError = EditorErrors.missingSubject))
        assertEquals(EditorLocalOperation.Validation, (visible().key.operation as OperationRef.Local).kind)
        assertTrue(collector.state.currentOperations.single { it.ref == progress.key.operation }.finished)
        acknowledge()
        assertNull(collector.state.visibleEvent)
        collector.dispatch(EditorEventInput.Received(progress.copy(key = progress.key.copy(occurrence = 1))))
        assertNull(collector.state.visibleEvent)
    }

    @Test fun `upload progress keeps its key and completion has separate visual and accessible summaries`() {
        observe(projection.copy(isUploading = true, uploadProgress = UploadProgress(0, 2)))
        val key = visible().key
        observe(projection.copy(uploadProgress = UploadProgress(1, 2)))
        assertEquals(key, visible().key)
        assertEquals(listOf(2, 2), collector.visibleEvent!!.summary.arguments)
        observe(projection.copy(uploadProgress = UploadProgress(2, 2)))
        observe(projection.copy(isUploading = false, uploadProgress = null))
        val finished = visible().content as EditorEventContent.Finished
        assertEquals(key.operation, visible().key.operation)
        assertEquals(2, finished.summary.quantity)
        assertEquals(2, finished.accessibleSummary.quantity)
        assertTrue(finished.summary.resourceId != finished.accessibleSummary.resourceId)
    }

    @Test fun `failed upload replaces progress with the mapped failure and no finish`() {
        val mapped = EditorEventCollector(errorMappers.copy(uploadError = { EditorErrors.tooLarge }))
            .apply { open(session) }
        val uploading = projection.copy(isUploading = true)
        mapped.observe(projection, uploading)
        val operation = mapped.state.visibleEvent!!.key.operation
        val failed = projection.copy(uploadError = EditorErrors.uploadNetwork)
        mapped.observe(uploading, failed)
        val event = mapped.state.events.single()
        assertEquals(operation, event.key.operation)
        assertEquals(EditorEventContent.Problem(EditorErrors.tooLarge), event.content)
        val terminal = mapped.state
        mapped.observe(failed, failed)
        assertEquals(terminal, mapped.state)
        mapped.dispatch(EditorEventInput.Acknowledge(event.key))
        assertNull(mapped.state.visibleEvent)
    }

    @Test fun `image limit can finish an upload and deliver its information without residual progress`() {
        val mapped = EditorEventCollector(errorMappers.copy(
            isUploadSuccessful = { it == null || it == EditorErrors.imageLimit },
        )).apply { open(session) }
        val uploading = projection.copy(isUploading = true, uploadProgress = UploadProgress(2, 2))
        mapped.observe(projection, uploading)
        val operation = mapped.state.visibleEvent!!.key.operation
        val limited = projection.copy(uploadError = EditorErrors.imageLimit)
        mapped.observe(uploading, limited)
        val events = mapped.state.events
        assertEquals(2, events.size)
        assertEquals(operation, events.first().key.operation)
        assertEquals(2, (events.first().content as EditorEventContent.Finished).summary.quantity)
        assertEquals(EditorEventContent.Problem(EditorErrors.imageLimit), events.last().content)
        assertEquals(EditorLocalOperation.Notice, (events.last().key.operation as OperationRef.Local).kind)
        assertTrue(mapped.state.currentOperations.single { it.ref == operation }.finished)
        assertFalse(events.any { it.content is EditorEventContent.InProgress })
        mapped.observe(limited, limited)
        assertEquals(events, mapped.state.events)
    }

    @Test fun `repeated read failure is delivered again and a successful reload retires it`() {
        repeat(2) { index ->
            observe(projection.copy(isLoadingForm = true, readFailed = false))
            observe(projection.copy(isLoadingForm = false, readFailed = true, submitError = EditorErrors.formNetwork))
            assertEquals(EditorLocalOperation.FormLoad, (visible().key.operation as OperationRef.Local).kind)
            if (index == 0) acknowledge()
        }
        observe(projection.copy(isLoadingForm = true, readFailed = false))
        observe(projection.copy(isLoadingForm = false))
        assertNull(collector.state.visibleEvent)
        assertTrue(collector.state.currentOperations.last().finished)
    }

    @Test fun `private message quote read failure is retired by its surface after a successful retry`() {
        val mp = EditorEventCollector(EditorErrorMappers(
            isReadError = { it == EditorErrors.sessionExpired },
            resolvesReadError = { error, _ -> error == EditorErrors.sessionExpired },
        )).apply { open(session) }
        val loading = projection.copy(isLoadingForm = true, readKind = EditorLocalOperation.QuoteFetch)
        val failed = loading.copy(isLoadingForm = false, readFailed = true, submitError = EditorErrors.sessionExpired)
        mp.observe(projection, loading)
        mp.observe(loading, failed)
        assertEquals(EditorLocalOperation.QuoteFetch,
            (mp.state.visibleEvent!!.key.operation as OperationRef.Local).kind)
        val retry = loading.copy(submitError = EditorErrors.sessionExpired)
        mp.observe(failed, retry)
        assertEquals(EditorEventContent.Problem(EditorErrors.sessionExpired), mp.state.visibleEvent!!.content)
        mp.observe(retry, failed.copy(readFailed = false, submitError = null))
        assertTrue(mp.state.events.isEmpty())
        assertTrue(mp.state.currentOperations.single().finished)
    }

    @Test fun `successful reads cannot retire send failures or uncertainty even with an eager surface policy`() {
        val mapped = EditorEventCollector(errorMappers.copy(resolvesReadError = { _, _ -> true }))
            .apply { open(session) }
        val otherRead = EditorEvent(EventKey(session, OperationRef.Local(EditorLocalOperation.QuoteFetch, 1), 0),
            EditorEventContent.Problem(EditorErrors.sessionExpired), 1)
        mapped.dispatch(EditorEventInput.Received(otherRead))
        val sendErrors = listOf(EditorErrors.sessionExpired, EditorErrors.uncertain).mapIndexed { index, error ->
            EditorEvent(EventKey(session, OperationRef.Submission(SubmissionToken(index.toLong() + 1, session)), 0),
                EditorEventContent.Problem(error), index.toLong() + 2)
        }
        sendErrors.forEach { mapped.dispatch(EditorEventInput.Received(it)) }
        val loading = projection.copy(isLoadingForm = true)
        mapped.observe(projection, loading)
        mapped.observe(loading, projection)
        assertEquals(listOf(otherRead) + sendErrors, mapped.state.events)
    }

    @Test fun `successful durable write clears save information without acknowledging uncertainty`() {
        observe(projection.copy(submitError = EditorErrors.draftSaveFailed))
        start()
        observe(projection.copy(submission = EditorSubmissionState.Uncertain(attempt),
            submitError = EditorErrors.uncertain))
        collector.problem(EditorErrors.draftSaveFailed)
        val previousSave = collector.state.currentOperations.last().ref as OperationRef.Local
        val save = previousSave.copy(sequence = previousSave.sequence + 1)
        collector.dispatch(EditorEventInput.ObserveOperation(session, save))
        assertFalse(collector.state.currentOperations.single { it.ref == save }.finished)
        observe(projection.copy(durableWriteGeneration = 1))
        assertEquals(listOf(EditorErrors.uncertain), collector.state.events.map {
            (it.content as EditorEventContent.Problem).error
        })
        assertTrue(collector.state.currentOperations.single { it.ref == save }.finished)
        val saved = collector.state
        observe(projection) // Re-observation never repeats a successful write or an occurrence.
        assertEquals(saved, collector.state)
    }

    @Test fun `bootstrap notices transfer once and changing owner rejects the old session`() {
        val bootstrap = EditorEventCollector(errorMappers)
        bootstrap.problem(EditorErrors.noImageReceived)
        bootstrap.open(session)
        val notice = bootstrap.state.visibleEvent!!
        assertEquals(session, notice.key.session)
        bootstrap.open(session)
        assertEquals(listOf(notice), bootstrap.state.events)
        bootstrap.open(session.copy(id = "next", owner = "bob"))
        bootstrap.dispatch(EditorEventInput.Received(notice))
        assertNull(bootstrap.state.visibleEvent)
        assertTrue(bootstrap.state.currentOperations.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `observation cannot implicitly open another session`() {
        collector.observe(projection, projection.copy(session = session.copy(id = "next", owner = "bob")))
    }

    @Test fun `ED170 an uncertain delivery binds its exact attempt once and only in its session`() {
        start()
        observe(projection.copy(submission = EditorSubmissionState.Sending(attempt)))
        // Cancellation can deliver the uncertainty before publishing the uncertain state.
        observe(projection.copy(submitError = EditorErrors.uncertain))
        val key = visible().key
        assertNull(collector.uncertainAttempt(key))
        observe(projection.copy(submission = EditorSubmissionState.Uncertain(attempt)))
        assertEquals(attempt, collector.uncertainAttempt(key))
        observe(projection.copy(submission = EditorSubmissionState.Uncertain(attempt.copy(id = "retry"))))
        assertEquals(attempt, collector.uncertainAttempt(key))
        assertNull(collector.uncertainAttempt(key.copy(occurrence = key.occurrence + 1)))
        collector.open(session.copy(id = "next"))
        assertNull(collector.uncertainAttempt(key))
    }

    @Test fun `ED45 ED170 leaving an unsent reservation retires sending and redelivers a kept uncertainty once`() {
        val uncertain = EditorSubmissionState.Uncertain(attempt)
        observe(projection.copy(submission = uncertain, submitError = EditorErrors.uncertain))
        val first = visible()
        assertEquals(attempt, collector.uncertainAttempt(first.key))
        acknowledge()
        // A failed acknowledgement reserves PersistingAttempt without sending, then keeps the same uncertainty.
        observe(projection.copy(submission = EditorSubmissionState.PersistingAttempt(SubmissionToken(1, session))))
        assertTrue(visible().content is EditorEventContent.InProgress)
        observe(projection.copy(submission = uncertain))
        val again = visible()
        assertNotEquals(first.key, again.key)
        assertEquals(listOf(again), collector.state.events)
        assertEquals(EditorEventContent.Problem(EditorErrors.uncertain), again.content)
        assertEquals(attempt, collector.uncertainAttempt(again.key))
        observe(projection)
        assertEquals(listOf(again), collector.state.events)
        // A successful acknowledgement returns to Ready without any delivery.
        acknowledge()
        observe(projection.copy(submission = EditorSubmissionState.PersistingAttempt(SubmissionToken(2, session))))
        observe(projection.copy(submission = EditorSubmissionState.Ready, submitError = null))
        assertNull(collector.state.visibleEvent)
        assertTrue(collector.state.currentOperations.filter { it.ref is OperationRef.Submission }.all { it.finished })
    }
}
