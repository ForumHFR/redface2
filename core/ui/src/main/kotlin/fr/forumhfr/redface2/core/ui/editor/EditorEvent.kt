package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorUploadToken
import fr.forumhfr.redface2.core.domain.editor.SubmissionToken

/** Host identities; sequences increase within each operation kind for the lifetime of a session. */
sealed interface OperationRef {
    /**
     * The accepted submission token exists before storage and distinguishes retries within a session.
     * Its session binds the opening, owner (including null) and context; no durable attempt is needed.
     * The host retains this token through Sending/Uncertain, including when beginAttempt is rejected.
     */
    data class Submission(val token: SubmissionToken) : OperationRef
    data class Upload(val token: EditorUploadToken) : OperationRef
    data class Local(val kind: EditorLocalOperation, val sequence: Long) : OperationRef {
        init { require(sequence >= 0) }
    }
}

enum class EditorLocalOperation { FormLoad, QuoteFetch, QuoteInsertion, DraftSave, Validation, Notice }

/** Occurrences increase within one operation; progress updates retain their existing occurrence. */
data class EventKey(val session: EditorSession, val operation: OperationRef, val occurrence: Long) {
    init { require(occurrence >= 0) }
}

sealed interface EditorEventContent {
    data class Problem(val error: EditorError) : EditorEventContent
    data class InProgress(val summary: EditorText, val uploadProgress: UploadProgress? = null) : EditorEventContent
    /** ED-43: the host supplies short visual and full accessible upload summaries explicitly. */
    data class Finished(
        val summary: EditorText,
        val detail: EditorText? = null,
        val accessibleSummary: EditorText = summary,
    ) : EditorEventContent
    /** No automatic retirement by default (ED-44); a surface may opt in for one notice only. */
    data class Information(
        val summary: EditorText,
        val detail: EditorText? = null,
        val timeoutMillis: Long? = null,
    ) : EditorEventContent {
        init { require(timeoutMillis == null || timeoutMillis > 0) }
    }
}

enum class EditorEventRank(val priority: Int) { Information(0), InProgress(1), Error(2) }
enum class EditorEventAction { Complete, Details, Dismiss }

/** [arrivalOrder] is a host-supplied, session-wide increasing order, not a timestamp or a TTL. */
data class EditorEvent(val key: EventKey, val content: EditorEventContent, val arrivalOrder: Long) {
    init { require(arrivalOrder >= 0) }

    val rank: EditorEventRank
        get() = when (val value = content) {
            is EditorEventContent.Problem -> if (value.error.errorClass == EditorErrorClass.Information) {
                EditorEventRank.Information
            } else {
                EditorEventRank.Error
            }
            is EditorEventContent.InProgress -> EditorEventRank.InProgress
            is EditorEventContent.Finished, is EditorEventContent.Information -> EditorEventRank.Information
        }

    val summary: EditorText
        get() = when (val value = content) {
            is EditorEventContent.Problem -> value.error.summary
            is EditorEventContent.InProgress -> value.summary
            is EditorEventContent.Finished -> value.summary
            is EditorEventContent.Information -> value.summary
        }

    val detail: EditorText?
        get() = when (val value = content) {
            is EditorEventContent.Problem -> value.error.detail
            is EditorEventContent.InProgress -> null
            is EditorEventContent.Finished -> value.detail
            is EditorEventContent.Information -> value.detail
        }

    val accessibleSummary: EditorText
        get() = (content as? EditorEventContent.Finished)?.accessibleSummary ?: summary

    /** ED-45: the tap can only complete, show details or dismiss; remedies belong to the detail. */
    val action: EditorEventAction?
        get() = when {
            content is EditorEventContent.InProgress -> null
            (content as? EditorEventContent.Problem)?.error?.action == EditorErrorAction.Complete ->
                EditorEventAction.Complete
            detail != null -> EditorEventAction.Details
            else -> EditorEventAction.Dismiss
        }
}

/** Surface projection shared by rendering and activation; identity and catalogue detail stay intact. */
internal fun EditorEvent.withCompletionCapability(canComplete: Boolean): EditorEvent {
    val error = (content as? EditorEventContent.Problem)?.error
        ?.takeIf { !canComplete && it.action == EditorErrorAction.Complete } ?: return this
    val action = if (error.detail == null) EditorErrorAction.Dismiss else EditorErrorAction.Details
    return copy(content = EditorEventContent.Problem(error.copy(action = action)))
}

/** A completed operation remains known so late progress/terminal callbacks cannot revive it. */
data class EditorEventOperation(
    val ref: OperationRef,
    val lastOccurrence: Long = -1,
    val finished: Boolean = false,
)

/**
 * Visual state only: pending quotes and durable attempts remain owned by the host.
 * Occurrence watermarks and finished operations reject replay, including after visual acknowledgement.
 */
data class EditorEventState(
    val session: EditorSession,
    val currentOperations: List<EditorEventOperation> = emptyList(),
    val events: List<EditorEvent> = emptyList(),
    val detailKey: EventKey? = null,
) {
    val visibleEvent: EditorEvent? get() = selectEditorEventFifo(events)
}

/** ED-43/45: equal ranks use FIFO; [replaceEditorEventsForOperation] assigns replacements their new place. */
fun selectEditorEventFifo(events: List<EditorEvent>): EditorEvent? = events.minWithOrNull(
    compareByDescending<EditorEvent> { it.rank.priority }.thenBy { it.arrivalOrder },
)

internal val OperationRef.sequence: Long
    get() = when (this) {
        is OperationRef.Submission -> token.id
        is OperationRef.Upload -> token.id
        is OperationRef.Local -> sequence
    }

internal fun OperationRef.belongsTo(session: EditorSession): Boolean = when (this) {
    is OperationRef.Submission -> token.session == session
    is OperationRef.Upload -> token.session == session
    is OperationRef.Local -> true // The containing input/key supplies the local operation's session.
}

internal fun OperationRef.sameKind(other: OperationRef): Boolean = when (this) {
    is OperationRef.Submission -> other is OperationRef.Submission
    is OperationRef.Upload -> other is OperationRef.Upload
    is OperationRef.Local -> other is OperationRef.Local && kind == other.kind
}

internal fun OperationRef.sameOperation(other: OperationRef): Boolean = this == other

internal val EditorEvent.isAmbiguous: Boolean
    get() = (content as? EditorEventContent.Problem)?.error?.errorClass == EditorErrorClass.Ambiguous

internal val OperationRef.isRead: Boolean
    get() = this is OperationRef.Local && kind in setOf(EditorLocalOperation.FormLoad, EditorLocalOperation.QuoteFetch)
