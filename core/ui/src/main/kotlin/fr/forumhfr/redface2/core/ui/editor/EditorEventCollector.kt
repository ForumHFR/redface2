package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.EditorUploadToken
import fr.forumhfr.redface2.core.ui.R

/**
 * VM-owned collector shared by full-screen editors; never saved in a Bundle.
 * The surface supplies [EditorEventProjection], error routing and direct occurrences. It rejects stale
 * asynchronous callbacks before delivery. This collector owns sequence/arrival counters and active refs;
 * every state change passes through [reduceEditorEvents]. Effects and durable attempts stay in the surface;
 * the collector only binds an uncertain delivery to its attempt for [EditorHost]'s explicit Masquer (ED-45/170).
 */
class EditorEventCollector(private val errors: EditorErrorMappers) : EditorHostEvents {
    override var state by mutableStateOf(EditorEventState(EditorSession("unloaded", null, "unloaded")))
        private set
    private var sequence = 0L
    private var arrival = 0L
    private var read: OperationRef.Local? = null
    private var upload: OperationRef.Upload? = null
    private var sending: OperationRef.Submission? = null
    private var uncertainty: Pair<EventKey, EditorDraftStore.AttemptToken?>? = null
    override val visibleEvent get() = state.visibleEvent?.let { event ->
        val content = event.content as? EditorEventContent.InProgress
        val progress = content?.uploadProgress
        if (content == null || progress == null) event else event.copy(content = content.copy(
            summary = EditorText(R.string.editor_event_uploading,
                listOf((progress.completed + 1).coerceAtMost(progress.total), progress.total))))
    }
    /** Direct picker failures and notices are occurrences; submit refusals use the projection exclusively. */
    fun problem(error: EditorError) = problem(error, null)
    fun information(summary: EditorText, detail: EditorText? = null) =
        deliver(local(EditorLocalOperation.Notice), EditorEventContent.Information(summary, detail))
    override fun dispatch(input: EditorEventInput) = reduceEditorEvents(state, input).also { state = it.state }
    /** The surface resets its [EditorHostMemory] before opening a new owner/session, then observes its baseline. */
    fun open(session: EditorSession) {
        if (session == state.session) return
        // Bootstrap notices/read failures precede the first owner-bound opening; transfer local deliveries once.
        val initial = state.events.filter { state.session.key == "unloaded" && it.key.operation is OperationRef.Local }
        dispatch(EditorEventInput.OpenSession(session))
        initial.forEach { dispatch(EditorEventInput.Received(it.copy(key = it.key.copy(session = session)))) }
        read = null
        upload = null
        sending = null
        uncertainty = null
    }

    /** Bind the exact delivery to its durable attempt, including restored attempts without a submission token. */
    override fun uncertainAttempt(key: EventKey) = uncertainty?.takeIf {
        it.first == key && key.session == state.session
    }?.second

    /**
     * Call [open] explicitly before observing a new session; both projections belong to that opening.
     * Text, selection, previews and arm observations do not deliver an occurrence.
     */
    fun observe(previous: EditorEventProjection, current: EditorEventProjection) {
        require(previous.session == state.session && current.session == state.session) {
            "The editor surface must call open(session) before observing projections for that session"
        }
        observeSubmission(previous, current)
        if (!previous.isLoadingForm && current.isLoadingForm) {
            read = local(current.readKind)
            dispatch(EditorEventInput.ObserveOperation(state.session, requireNotNull(read)))
        }
        observeUpload(previous, current)
        observeErrors(previous, current)
        settleUnsentReservation(previous, current)
        // Cancellation may deliver the error before publishing the uncertain state. Bind once, never to a retry.
        uncertainty = uncertainty?.let { (key, attempt) ->
            key to (attempt ?: (current.submission as? EditorSubmissionState.Uncertain)?.attempt)
        }
        if (current.durableWriteGeneration > previous.durableWriteGeneration) durableWriteSucceeded()
    }
    private fun observeErrors(previous: EditorEventProjection, current: EditorEventProjection) {
        val readEnded = previous.isLoadingForm && !current.isLoadingForm
        val error = current.submitError
        val failedRead = readEnded && current.readFailed && error?.let(errors.isReadError) == true
        // The surface observes a null reset before each validation/send attempt, including local refusals.
        if (error != null && (error != previous.submitError || failedRead)) {
            val operation = if (errors.isReadError(error) && readEnded) read else sending
            bindUncertainty(error, problem(error, operation), current)
        }
        if (readEnded && !current.readFailed) read?.let {
            dispatch(EditorEventInput.FormLoaded(
                state.session, it, errors.resolvesReadError, quotesResolved = current.quotesResolved,
            ))
        }
        observeUploadError(previous, current)
    }
    private fun observeUploadError(previous: EditorEventProjection, current: EditorEventProjection) {
        val uploadError = current.uploadError?.let(errors.uploadError)
        if (uploadError != null && uploadError != previous.uploadError?.let(errors.uploadError)) {
            problem(uploadError, upload.takeUnless { current.isUploading })
        }
    }
    /** A successful owner-bound write retires save information; it never acknowledges an uncertain send. */
    private fun durableWriteSucceeded() {
        state.currentOperations.lastOrNull {
            (it.ref as? OperationRef.Local)?.kind == EditorLocalOperation.DraftSave
        }?.let { dispatch(EditorEventInput.DurableWriteSucceeded(state.session, it.ref)) }
    }
    private fun observeSubmission(previous: EditorEventProjection, current: EditorEventProjection) {
        if (previous.submission == current.submission) return
        if (current.submission is EditorSubmissionState.PersistingAttempt) {
            sending = OperationRef.Submission(requireNotNull(current.submissionOperation))
            deliver(requireNotNull(sending), EditorEventContent.InProgress(EditorText(R.string.editor_event_sending)),
                started = true)
        }
        val submission = current.submission
        if (submission is EditorSubmissionState.Succeeded ||
            submission is EditorSubmissionState.Uncertain && submission.accepted
        ) {
            // ED-88: terminal success is relayed by the existing effect, never an event. The reducer ends the
            // operation silently (R1a-1 PR 1b), so a late delivery for it is dropped.
            sending?.let { dispatch(EditorEventInput.OperationCompleted(state.session, it)) }
        }
    }
    private fun bindUncertainty(error: EditorError, key: EventKey, current: EditorEventProjection) {
        if (error.cause == EditorCause.Uncertain) {
            uncertainty = key to (current.submission as? EditorSubmissionState.Uncertain)?.attempt
        }
    }
    /**
     * ED-45/170: acknowledging an uncertain attempt reserves PersistingAttempt without sending. Leaving it for
     * Ready or a kept uncertainty retires the dangling « Envoi… » (a delivered problem already finished the
     * operation, so this is then a no-op), and a failed acknowledgement signals the kept uncertainty again.
     */
    private fun settleUnsentReservation(previous: EditorEventProjection, current: EditorEventProjection) {
        val uncertain = (current.submission as? EditorSubmissionState.Uncertain)?.takeUnless { it.accepted }
        val unsent = current.submission == EditorSubmissionState.Ready || uncertain != null
        if (previous.submission !is EditorSubmissionState.PersistingAttempt || !unsent) return
        sending?.let { dispatch(EditorEventInput.OperationCompleted(state.session, it)) }
        val error = current.submitError
        if (uncertain != null && error?.cause == EditorCause.Uncertain && error == previous.submitError) {
            uncertainty = problem(error, null) to uncertain.attempt
        }
    }
    private fun observeUpload(previous: EditorEventProjection, current: EditorEventProjection) {
        if (!previous.isUploading && current.isUploading) {
            upload = OperationRef.Upload(EditorUploadToken(++sequence, state.session))
            val progress = current.uploadProgress
            deliver(requireNotNull(upload), EditorEventContent.InProgress(EditorText(R.string.editor_event_uploading,
                listOf(1, progress?.total ?: 1)), progress), started = true)
        }
        val operation = upload ?: return
        val key = state.events.find { it.key.operation == operation }?.key ?: return
        if (current.isUploading && current.uploadProgress != null) {
            dispatch(EditorEventInput.UploadAdvanced(key, current.uploadProgress))
        }
        val successful = errors.isUploadSuccessful(current.uploadError)
        if (previous.isUploading && !current.isUploading && successful) {
            val count = previous.uploadProgress?.completed ?: 1
            deliver(operation, EditorEventContent.Finished(
                EditorText(R.plurals.editor_event_images_inserted_summary, listOf(count), count),
                accessibleSummary = EditorText(R.plurals.editor_event_images_inserted, listOf(count), count),
            ))
        }
    }
    private fun problem(error: EditorError, operation: OperationRef?): EventKey {
        val fallback = when {
            error.errorClass == EditorErrorClass.Validation -> EditorLocalOperation.Validation
            error.cause == EditorCause.DraftSaveFailed -> EditorLocalOperation.DraftSave
            else -> EditorLocalOperation.Notice
        }
        if (fallback == EditorLocalOperation.Validation) {
            (operation as? OperationRef.Submission)?.let {
                dispatch(EditorEventInput.OperationCompleted(state.session, it))
            }
        }
        val active = operation?.takeUnless { fallback == EditorLocalOperation.Validation }
            ?.takeIf { ref -> state.currentOperations.any { it.ref == ref && !it.finished } }
        return deliver(active ?: local(fallback), EditorEventContent.Problem(error))
    }
    private fun local(kind: EditorLocalOperation) = OperationRef.Local(kind, ++sequence)
    private fun deliver(operation: OperationRef, content: EditorEventContent, started: Boolean = false): EventKey {
        val occurrence = state.currentOperations.find { it.ref == operation }?.lastOccurrence?.plus(1) ?: 0L
        val event = EditorEvent(EventKey(state.session, operation, occurrence), content, ++arrival)
        dispatch(if (started) EditorEventInput.OperationStarted(event) else EditorEventInput.Received(event))
        return event.key
    }
}
