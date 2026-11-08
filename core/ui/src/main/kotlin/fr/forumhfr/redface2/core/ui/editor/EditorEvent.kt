package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptIdentity
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorUploadToken
import fr.forumhfr.redface2.core.domain.editor.SubmissionToken

/** Host identities; sequences increase within each operation kind for the lifetime of a session. */
sealed interface OperationRef {
    /**
     * Matched by token; attempt metadata is attached only if the session owner is known (non-null).
     * Anonymous arming can prepare an identity with an empty owner; construction and copy discard it.
     * After observation, metadata is learned from deliveries (OperationStarted/Received), not re-observation.
     */
    class Submission(val token: SubmissionToken, attempt: AttemptIdentity? = null) : OperationRef {
        val attempt: AttemptIdentity? = attempt?.takeIf { token.session.owner != null }?.also {
            require(it.owner == token.session.owner && it.key == token.session.key)
        }

        fun copy(token: SubmissionToken = this.token, attempt: AttemptIdentity? = this.attempt): Submission =
            Submission(token, attempt)

        override fun equals(other: Any?): Boolean =
            other is Submission && token == other.token && attempt == other.attempt

        override fun hashCode(): Int = 31 * token.hashCode() + (attempt?.hashCode() ?: 0)

        override fun toString(): String = "Submission(token=$token, attempt=$attempt)"
    }
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
    data class Finished(val summary: EditorText, val detail: EditorText? = null) : EditorEventContent
    data class Information(val summary: EditorText, val detail: EditorText? = null) : EditorEventContent
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

    /** ED-45: catalogue Reload and Dismiss are not necessarily the event control's action. */
    val action: EditorEventAction?
        get() = when {
            content is EditorEventContent.InProgress -> null
            (content as? EditorEventContent.Problem)?.error?.action == EditorErrorAction.Complete ->
                EditorEventAction.Complete
            detail != null -> EditorEventAction.Details
            else -> EditorEventAction.Dismiss
        }
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

/** Pending maintainer arbitration: equal ranks use FIFO. Replacement is in [replaceEditorEventsByKind]. */
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

/** Structural equality includes metadata; operation matching must not depend on when it was learned. */
internal fun OperationRef.sameOperation(other: OperationRef): Boolean = when (this) {
    is OperationRef.Submission -> other is OperationRef.Submission && token == other.token
    is OperationRef.Upload, is OperationRef.Local -> this == other
}

internal val EditorEvent.isAmbiguous: Boolean
    get() = (content as? EditorEventContent.Problem)?.error?.errorClass == EditorErrorClass.Ambiguous

internal val OperationRef.isRead: Boolean
    get() = this is OperationRef.Local && kind in setOf(EditorLocalOperation.FormLoad, EditorLocalOperation.QuoteFetch)
