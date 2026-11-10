package fr.forumhfr.redface2.feature.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.EditorUploadToken
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.editor.EditorCause
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.EditorEvent
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.EditorEventInput
import fr.forumhfr.redface2.core.ui.editor.EditorEventState
import fr.forumhfr.redface2.core.ui.editor.EditorLocalOperation
import fr.forumhfr.redface2.core.ui.editor.EditorText
import fr.forumhfr.redface2.core.ui.editor.EventKey
import fr.forumhfr.redface2.core.ui.editor.OperationRef
import fr.forumhfr.redface2.core.ui.editor.reduceEditorEvents
import fr.forumhfr.redface2.feature.editor.R as EditorR

/** VM-owned deliveries, reduced synchronously before StateFlow can conflate them. Never saved in a Bundle. */
internal class PostEditorChromeEvents {
    var state by mutableStateOf(EditorEventState(EditorSession("unloaded", null, "unloaded")))
        private set
    private var sequence = 0L
    private var arrival = 0L
    private var read: OperationRef.Local? = null
    private var upload: OperationRef.Upload? = null
    private var sending: OperationRef.Submission? = null
    private var uncertainty: Pair<EventKey, EditorDraftStore.AttemptToken?>? = null
    private var readFailed = false
    val visibleEvent get() = state.visibleEvent?.let { event ->
        val content = event.content as? EditorEventContent.InProgress
        val progress = content?.uploadProgress
        if (content == null || progress == null) event else event.copy(content = content.copy(
            summary = EditorText(R.string.editor_event_uploading,
                listOf((progress.completed + 1).coerceAtMost(progress.total), progress.total))))
    }
    fun readFailed() { readFailed = true }
    fun quoteAlreadyDelivered() = deliver(local(EditorLocalOperation.Notice), EditorEventContent.Information(
        EditorText(EditorR.string.editor_quote_already_delivered)))
    fun pickerProblem(error: EditorError) = problem(error, null)
    fun dispatch(input: EditorEventInput) = reduceEditorEvents(state, input).also { state = it.state }
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
    fun uncertainAttempt(key: EventKey) = uncertainty?.takeIf {
        it.first == key && key.session == state.session
    }?.second

    /** Text, selection, previews and arm observations do not deliver an occurrence. */
    fun observe(previous: PostEditorState, current: PostEditorState) {
        observeSubmission(previous.submission, current.submission)
        if (!previous.isLoadingForm && current.isLoadingForm) {
            readFailed = false
            read = local(if (current.pendingQuotes.isEmpty()) EditorLocalOperation.FormLoad
                else EditorLocalOperation.QuoteFetch)
            dispatch(EditorEventInput.ObserveOperation(state.session, requireNotNull(read)))
        }
        observeUpload(previous, current)
        observeErrors(previous, current)
        // Cancellation may deliver the error before publishing the uncertain state. Bind once, never to a retry.
        uncertainty = uncertainty?.let { (key, attempt) ->
            key to (attempt ?: (current.submission as? EditorSubmissionState.Uncertain)?.attempt)
        }
        if (current.legacyQuoteRequestDropped && !previous.legacyQuoteRequestDropped) {
            deliver(local(EditorLocalOperation.Notice), EditorEventContent.Information(
                EditorText(EditorR.string.editor_chrome_legacy_quote_summary),
                EditorText(EditorR.string.editor_legacy_quote_dropped)))
        }
    }
    private fun observeErrors(previous: PostEditorState, current: PostEditorState) {
        val readEnded = previous.isLoadingForm && !current.isLoadingForm
        val error = current.submitError
        val failedRead = readEnded && readFailed && error?.isReadError == true
        if (error != null && (error != previous.submitError || failedRead)) {
            val key = problem(error, if (error.isReadError && readEnded) read else sending)
            if (error.cause == EditorCause.Uncertain) {
                uncertainty = key to (current.submission as? EditorSubmissionState.Uncertain)?.attempt
            }
        }
        if (readEnded && !readFailed) read?.let {
            dispatch(EditorEventInput.FormLoaded(state.session, it, current.pendingQuotes.isEmpty()))
        }
        val uploadError = current.uploadError?.takeUnless {
            it.cause in setOf(EditorCause.PickerAbsent, EditorCause.NoImageReceived)
        }
        if (uploadError != null && uploadError != previous.uploadError) {
            problem(uploadError, upload.takeUnless { current.isUploading })
        }
    }
    fun durableWriteSucceeded() {
        state.currentOperations.lastOrNull {
            (it.ref as? OperationRef.Local)?.kind == EditorLocalOperation.DraftSave
        }?.let { dispatch(EditorEventInput.DurableWriteSucceeded(state.session, it.ref)) }
    }
    private fun observeSubmission(previous: EditorSubmissionState, current: EditorSubmissionState) {
        if (previous == current) return
        if (current is EditorSubmissionState.PersistingAttempt) {
            sending = OperationRef.Submission(current.submission)
            deliver(requireNotNull(sending), EditorEventContent.InProgress(EditorText(R.string.editor_event_sending)),
                started = true)
        }
        if (current is EditorSubmissionState.Succeeded ||
            current is EditorSubmissionState.Uncertain && current.accepted
        ) {
            // ED-88: terminal success is relayed by the existing effect, never an event. The reducer ends the
            // operation silently (R1a-1 PR 1b), so a late delivery for it is dropped.
            sending?.let { dispatch(EditorEventInput.OperationCompleted(state.session, it)) }
        }
    }
    private fun observeUpload(previous: PostEditorState, current: PostEditorState) {
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
        val successful = current.uploadError == null || current.uploadError.cause == EditorCause.ImageLimit
        if (previous.isUploading && !current.isUploading && successful) {
            val count = previous.uploadProgress?.completed ?: 1
            deliver(operation, EditorEventContent.Finished(
                EditorText(R.plurals.editor_event_images_inserted_summary, listOf(count), count),
                accessibleSummary = EditorText(R.plurals.editor_event_images_inserted, listOf(count), count),
            ))
        }
    }
    private fun problem(error: EditorError, operation: OperationRef?): EventKey {
        val fallback = if (error.cause == EditorCause.DraftSaveFailed) {
            EditorLocalOperation.DraftSave
        } else { EditorLocalOperation.Notice }
        val active = operation?.takeIf { ref -> state.currentOperations.any { it.ref == ref && !it.finished } }
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

private val EditorError.isReadError: Boolean
    get() = cause in setOf(EditorCause.FormNetwork, EditorCause.InvalidForm, EditorCause.QuoteFetchFailed)
