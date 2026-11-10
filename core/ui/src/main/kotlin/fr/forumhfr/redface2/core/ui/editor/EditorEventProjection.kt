package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionPurpose
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.SubmissionToken

/**
 * Surface-owned operation facts, delivered synchronously before StateFlow can conflate transitions.
 * [submissionOperation] exposes a Send reservation only after [submitError] is reset to null, before storage;
 * the collector retains its identity through Sending/Uncertain. The reset may follow the reservation.
 * [readFailed] distinguishes a failed/cancelled GET from a successful load, even with an unchanged error.
 * Before every validation/send attempt (including local refusals), the surface clears [submitError] to null
 * and synchronously observes that reset before publishing the result. This makes identical refusals/failures
 * distinct occurrences. Submit errors are delivered only here, never also through EditorEventCollector.problem.
 * An explicit acknowledgement of an uncertain attempt (ED-45/170) is not an attempt: [submission] goes through
 * PersistingAttempt with purpose Acknowledge, so [submissionOperation] stays null regardless of [submitError].
 * It never delivers sending progress or ED-44 cleanup; the collector signals a kept uncertainty on failure.
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
        get() = (submission as? EditorSubmissionState.PersistingAttempt)
            ?.takeIf { it.purpose == EditorSubmissionPurpose.Send && submitError == null }?.submission

    init { require(readKind == EditorLocalOperation.FormLoad || readKind == EditorLocalOperation.QuoteFetch) }
}
