package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.ui.editor.EditorCause
import fr.forumhfr.redface2.core.ui.editor.EditorErrorMappers
import fr.forumhfr.redface2.core.ui.editor.EditorEventCollector
import fr.forumhfr.redface2.core.ui.editor.EditorEventInput
import fr.forumhfr.redface2.core.ui.editor.EditorEventProjection
import fr.forumhfr.redface2.core.ui.editor.EditorHostEvents
import fr.forumhfr.redface2.core.ui.editor.afterFormLoaded

/** Prepared for T2; the existing screen keeps its banners until the T3 chrome switch. */
internal class TopicFormEvents : EditorHostEvents {
    private val collector = EditorEventCollector(EditorErrorMappers(
        isReadError = { it.cause in setOf(EditorCause.FormNetwork, EditorCause.InvalidForm) },
        resolvesReadError = { error, _ -> error.afterFormLoaded() == null },
        uploadError = { it.takeUnless { error ->
            error.cause in setOf(EditorCause.PickerAbsent, EditorCause.NoImageReceived)
        } },
        isUploadSuccessful = { it == null || it.cause == EditorCause.ImageLimit },
    ))
    override val state get() = collector.state
    override val visibleEvent get() = collector.visibleEvent
    private var failedRead = false
    private var projection = EditorEventProjection(state.session)

    override fun dispatch(input: EditorEventInput) = collector.dispatch(input)
    fun open(session: EditorSession) {
        if (session != state.session) projection = EditorEventProjection(session)
        collector.open(session)
    }
    fun readFailed() { failedRead = true }
    fun durableWriteSucceeded() {
        val written = projection.copy(durableWriteGeneration = projection.durableWriteGeneration + 1)
        collector.observe(projection, written)
        projection = written
    }
    fun observe(previous: TopicFormState, current: TopicFormState) {
        if (!previous.isLoadingForm && current.isLoadingForm) failedRead = false
        val next = current.project()
        collector.observe(previous.project(), next)
        projection = next
    }
    private fun TopicFormState.project() = EditorEventProjection(
        session = state.session, submission = submission, isLoadingForm = isLoadingForm,
        readFailed = failedRead, submitError = submitError, isUploading = isUploading,
        uploadProgress = uploadProgress, uploadError = uploadError,
        durableWriteGeneration = projection.durableWriteGeneration,
    )
}
