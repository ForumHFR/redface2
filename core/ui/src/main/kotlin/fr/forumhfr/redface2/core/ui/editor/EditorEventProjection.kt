package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.SubmissionToken

/**
 * Surface-owned operation facts, delivered synchronously before StateFlow can conflate transitions.
 * [submissionOperation] is reserved before storage; the collector retains it through Sending/Uncertain.
 * [readFailed] distinguishes a failed/cancelled GET from a successful load, even with an unchanged error.
 * Before every validation/send attempt (including local refusals), the surface clears [submitError] to null
 * and synchronously observes that reset before publishing the result. This makes identical refusals/failures
 * distinct occurrences. Submit errors are delivered only here, never also through EditorEventCollector.problem.
 * An explicit acknowledgement of an uncertain attempt (ED-45/170) is not an attempt: [submission] goes through
 * PersistingAttempt without sending while [submitError] is kept; the collector retires that reservation when
 * it leaves for Ready or a kept Uncertain, and signals the kept uncertainty again if acknowledging failed.
 * [durableWriteGeneration] increases after each successful owner-bound write, including silent autosaves.
 * Text, selection, options, preview and arming never create an event.
 */
@Suppress("LongParameterList") // One projection at the surface boundary, without business state in core/ui.
data class EditorEventProjection(
    val session: EditorSession,
    val submission: EditorSubmissionState = EditorSubmissionState.Ready,
    val isLoadingForm: Boolean = false,
    val readKind: EditorLocalOperation = EditorLocalOperation.FormLoad,
    val readFailed: Boolean = false,
    val quotesResolved: Boolean = true,
    val submitError: EditorError? = null,
    val isUploading: Boolean = false,
    val uploadProgress: UploadProgress? = null,
    val uploadError: EditorError? = null,
    val durableWriteGeneration: Long = 0,
) {
    val submissionOperation: SubmissionToken?
        get() = (submission as? EditorSubmissionState.PersistingAttempt)?.submission

    init { require(readKind == EditorLocalOperation.FormLoad || readKind == EditorLocalOperation.QuoteFetch) }
}
