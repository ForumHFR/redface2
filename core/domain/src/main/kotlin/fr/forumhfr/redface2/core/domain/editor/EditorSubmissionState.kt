package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken

/** ED-164/170: a surface opening has its own identity, even when a sheet reuses its ViewModel. */
data class EditorSession(val id: String, val owner: String?, val key: String)

/** ED-164/166: an arm belongs to a session and expires on the injected monotonic clock. */
data class ArmToken(
    val id: Long,
    val session: EditorSession,
    val armedAtNanos: Long,
    val timeoutMillis: Long,
)

/** ED-160: synchronous reservation, made before suspending on durable storage. */
data class SubmissionToken(val id: Long, val session: EditorSession)

/** ED-170: a durable acknowledgement reserves the editor without launching another send. */
enum class EditorSubmissionPurpose { Send, Acknowledge }

/** ED-172: stale upload callbacks cannot release another session's upload lock. */
data class EditorUploadToken(val id: Long, val session: EditorSession)

/** ED-161/169/170: accepted-but-unmarked success stays locked, permits one pop and restores uncertain. */
sealed interface EditorSubmissionState {
    data object Ready : EditorSubmissionState
    data class Armed(val token: ArmToken) : EditorSubmissionState
    data class PersistingAttempt(
        val submission: SubmissionToken,
        val purpose: EditorSubmissionPurpose = EditorSubmissionPurpose.Send,
    ) : EditorSubmissionState
    data class Sending(val attempt: AttemptToken) : EditorSubmissionState
    data class Succeeded(val attempt: AttemptToken) : EditorSubmissionState
    data class Uncertain(val attempt: AttemptToken?, val accepted: Boolean = false) : EditorSubmissionState

    val blocksBodyMutation: Boolean
        get() = when (this) {
            is PersistingAttempt, is Sending, is Succeeded -> true
            is Uncertain -> accepted
            Ready, is Armed -> false
        }
}

/** ED-164: the first tap either arms or reserves one attempt; repeated taps are ignored. */
sealed interface SubmissionRequest {
    data object Ignored : SubmissionRequest
    data class Armed(val token: ArmToken) : SubmissionRequest
    data class Accepted(val token: SubmissionToken) : SubmissionRequest
}
