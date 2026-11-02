package fr.forumhfr.redface2.core.domain.diagnostics

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionFailure
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** ED-170, case 7/K16: unrelated diagnostic identity, never an arm/attempt token or editor key. */
class EditorSubmissionTrace(private val log: DiagnosticsLog) : AbstractCoroutineContextElement(Key) {
    private val id = UUID.randomUUID().toString()

    fun durable(writeMillis: Long) = record("attempt_durable writeMs=${writeMillis.coerceAtLeast(0)}")
    fun requestBodyEnd() = record("request_body_end")
    fun result(outcome: Result) = record("result class=${outcome.name}")

    fun storageFailure(stage: EditorSubmissionFailure, result: StoreWriteResult) {
        val kind = when (result) {
            is StoreWriteResult.Written -> "written"
            StoreWriteResult.OwnerChanged -> "owner_changed"
            StoreWriteResult.Conflict -> "conflict"
            is StoreWriteResult.Failed -> "failed"
        }
        record("storage_failure stage=${stage.name} kind=$kind", DiagnosticsLog.Level.WARN)
    }

    private fun record(event: String, level: DiagnosticsLog.Level = DiagnosticsLog.Level.INFO) =
        log.record(level, TAG, "trace=$id $event")

    enum class Result { SUCCESS, CERTAIN_FAILURE, UNCERTAIN, DRAFT_WRITE_FAILED }

    companion object Key : CoroutineContext.Key<EditorSubmissionTrace> {
        const val TAG = "EditorSubmission"
    }
}
