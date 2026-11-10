package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.ui.editor.EditorCause
import fr.forumhfr.redface2.core.ui.editor.EditorErrorMappers
import fr.forumhfr.redface2.core.ui.editor.EditorEventCollector
import fr.forumhfr.redface2.core.ui.editor.EditorEventCommand
import fr.forumhfr.redface2.core.ui.editor.EditorEventInput
import fr.forumhfr.redface2.core.ui.editor.EditorEventProjection
import fr.forumhfr.redface2.core.ui.editor.EditorHostEvents
import fr.forumhfr.redface2.core.ui.editor.EditorLocalOperation
import fr.forumhfr.redface2.core.ui.editor.EventKey
import fr.forumhfr.redface2.core.ui.editor.OperationRef

/** New MP session events; GET failures stay full-screen, Masquer binds only its exact uncertain attempt. */
internal class PrivateMessageComposeEvents : EditorHostEvents {
    private val collector = EditorEventCollector(EditorErrorMappers(
        // GET failures stay in formError (ED-130); no read can retire a submission event.
        isReadError = { false },
        resolvesReadError = { _, _ -> false },
        isUploadSuccessful = { it == null || it.cause == EditorCause.ImageLimit },
    ))
    override val state get() = collector.state
    override val visibleEvent get() = collector.visibleEvent
    private var projection = EditorEventProjection(state.session)

    override fun dispatch(input: EditorEventInput) = collector.dispatch(input)
    override fun uncertainAttempt(key: EventKey) = collector.uncertainAttempt(key)
    /** The host opens its header directly; this port only selects the field, without a second gesture. */
    fun completingHeader(onComplete: (EditorCause) -> Unit): EditorHostEvents = object : EditorHostEvents by this {
        override fun dispatch(input: EditorEventInput) = collector.dispatch(input).also { transition ->
            transition.commands.filterIsInstance<EditorEventCommand.CompleteHeader>().forEach {
                onComplete(it.cause)
            }
        }
    }
    fun open(session: EditorSession) {
        if (session != state.session) projection = EditorEventProjection(session)
        collector.open(session)
    }
    fun durableWriteSucceeded() {
        val written = projection.copy(durableWriteGeneration = projection.durableWriteGeneration + 1)
        collector.observe(projection, written)
        projection = written
    }
    fun observe(previous: PrivateMessageComposeUiState, current: PrivateMessageComposeUiState) {
        val before = previous.project()
        val next = current.project()
        collector.observe(before, next)
        // Full-screen GET failures finish their work without claiming FormLoaded or retiring send errors.
        if (before.isLoadingForm && !next.isLoadingForm && next.readFailed) {
            state.currentOperations.lastOrNull {
                (it.ref as? OperationRef.Local)?.kind == EditorLocalOperation.FormLoad && !it.finished
            }?.let { collector.dispatch(EditorEventInput.OperationCompleted(state.session, it.ref)) }
        }
        projection = next
    }
    private fun PrivateMessageComposeUiState.project() = EditorEventProjection(
        session = state.session, submission = submission, isLoadingForm = isLoadingForm && !isInitializing,
        readFailed = formError, submitError = submitError, isUploading = isUploading,
        uploadProgress = uploadProgress, uploadError = uploadError,
        durableWriteGeneration = projection.durableWriteGeneration,
    )
}
