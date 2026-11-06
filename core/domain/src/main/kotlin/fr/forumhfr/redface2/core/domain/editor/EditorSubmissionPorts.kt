package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.BeginAttemptResult
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult

/** ED-162/163: adapters classify at the emission boundary; no network types enter the controller. */
enum class EditorSendOutcome { SUCCESS, CERTAIN_FAILURE, UNCERTAIN }

/** Local failure stages for content-free diagnostics (ED-161/170). */
enum class EditorSubmissionFailure { ATTEMPT, QUIESCE, OUTCOME, CLEANUP }

/**
 * ED-161/170: ports capture one immutable snapshot/session. Storage failures return explicit results.
 * [stopDraftWrites] cancels and joins autosaves before the published marker. [recordFailure] must not throw.
 * A failed marker preserves the text and the one success pop, with a terminal Uncertain(accepted = true).
 * Transport cancellation after emission leaves the durable attempt unresolved; adapters classify other failures.
 * Proven local cancellation is resolved durably before propagation, without restoring an invalidated session.
 */
interface EditorSubmissionPorts {
    suspend fun beginAttempt(): BeginAttemptResult
    suspend fun send(attempt: AttemptToken): EditorSendOutcome
    suspend fun stopDraftWrites()
    suspend fun finishAttempt(attempt: AttemptToken, outcome: AttemptOutcome): StoreWriteResult
    suspend fun erasePublishedContent(attempt: AttemptToken): StoreWriteResult
    fun recordFailure(stage: EditorSubmissionFailure, result: StoreWriteResult)
}
