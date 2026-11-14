package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.ui.editor.EditorCause
import fr.forumhfr.redface2.core.ui.editor.EditorErrorMappers
import fr.forumhfr.redface2.core.ui.editor.EditorEventCollector
import fr.forumhfr.redface2.core.ui.editor.EditorEventInput
import fr.forumhfr.redface2.core.ui.editor.EditorEventProjection
import fr.forumhfr.redface2.core.ui.editor.EditorHostEvents
import fr.forumhfr.redface2.core.ui.editor.EventKey

/**
 * Reply projection for the shared collector; form/quote failures remain full-screen (ED-130).
 * The collector binds each uncertain delivery to its durable attempt for the host's explicit Masquer (ED-45/170).
 */
internal class PrivateMessageReplyEvents : EditorHostEvents {
    private val collector = EditorEventCollector(EditorErrorMappers(
        // GET/quote failures stay in formError (ED-130); no read can retire a submission event.
        isReadError = { false },
        resolvesReadError = { _, _ -> false },
        uploadError = { it.takeUnless { error ->
            error.cause in setOf(EditorCause.PickerAbsent, EditorCause.NoImageReceived)
        } },
        isUploadSuccessful = { it == null || it.cause == EditorCause.ImageLimit },
    ))
    override val state get() = collector.state
    override val visibleEvent get() = collector.visibleEvent
    private var projection = EditorEventProjection(state.session)

    override fun dispatch(input: EditorEventInput) = collector.dispatch(input)
    override fun uncertainAttempt(key: EventKey) = collector.uncertainAttempt(key)
    fun open(session: EditorSession) {
        if (session != state.session) projection = EditorEventProjection(session)
        collector.open(session)
    }
    fun durableWriteSucceeded() {
        val written = projection.copy(durableWriteGeneration = projection.durableWriteGeneration + 1)
        collector.observe(projection, written)
        projection = written
    }
    fun observe(previous: PrivateMessageReplyUiState, current: PrivateMessageReplyUiState) {
        val next = current.project()
        collector.observe(previous.project(), next)
        projection = next
    }
    private fun PrivateMessageReplyUiState.project() = EditorEventProjection(
        session = state.session, submission = submission, isLoadingForm = isLoadingForm,
        readFailed = formError, submitError = submitError, isUploading = isUploading,
        uploadProgress = uploadProgress, uploadError = uploadError,
        durableWriteGeneration = projection.durableWriteGeneration,
    )
}
