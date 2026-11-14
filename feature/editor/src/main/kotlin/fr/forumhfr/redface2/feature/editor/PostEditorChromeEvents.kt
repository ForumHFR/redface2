package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.ui.editor.EditorCause
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.EditorErrorMappers
import fr.forumhfr.redface2.core.ui.editor.EditorEventCollector
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.EditorEventInput
import fr.forumhfr.redface2.core.ui.editor.EditorEventProjection
import fr.forumhfr.redface2.core.ui.editor.EditorHostEvents
import fr.forumhfr.redface2.core.ui.editor.EditorLocalOperation
import fr.forumhfr.redface2.core.ui.editor.EditorText
import fr.forumhfr.redface2.core.ui.editor.EventKey
import fr.forumhfr.redface2.core.ui.editor.OperationRef
import fr.forumhfr.redface2.core.ui.editor.afterFormLoaded

/** Post-only projection and notices; the VM delivers transitions before StateFlow can conflate them. */
internal class PostEditorChromeEvents : EditorHostEvents {
    private val collector = EditorEventCollector(EditorErrorMappers(
        isReadError = { it.cause in setOf(
            EditorCause.FormNetwork, EditorCause.InvalidForm, EditorCause.QuoteFetchFailed) },
        resolvesReadError = { error, quotesResolved -> error.afterFormLoaded(quotesResolved) == null },
        uploadError = { it.takeUnless { error ->
            error.cause in setOf(EditorCause.PickerAbsent, EditorCause.NoImageReceived)
        } },
        isUploadSuccessful = { it == null || it.cause == EditorCause.ImageLimit },
    ))
    override val state get() = collector.state
    override val visibleEvent get() = collector.visibleEvent
    private var readFailed = false
    private var projection = EditorEventProjection(state.session)

    override fun dispatch(input: EditorEventInput) = collector.dispatch(input)
    override fun uncertainAttempt(key: EventKey) = collector.uncertainAttempt(key)
    fun open(session: EditorSession) {
        if (session != state.session) {
            projection = EditorEventProjection(session)
        }
        collector.open(session)
    }
    fun readFailed() { readFailed = true }
    fun durableWriteSucceeded() {
        val written = projection.copy(durableWriteGeneration = projection.durableWriteGeneration + 1)
        collector.observe(projection, written)
        projection = written
    }
    fun pickerProblem(error: EditorError) = collector.problem(error)
    fun quoteAlreadyDelivered() = collector.information(EditorText(R.string.editor_quote_already_delivered))

    fun observe(previous: PostEditorState, current: PostEditorState) {
        if (!previous.isLoadingForm && current.isLoadingForm) readFailed = false
        val next = current.eventsProjection()
        collector.observe(previous.eventsProjection(), next)
        projection = next
        val readEnded = previous.isLoadingForm && !current.isLoadingForm
        if (readEnded && !readFailed && current.isFetchingQuote) {
            // A quote form also supplies ordinary metadata. Retire a failed ordinary read that a
            // decision overtook, even though the collector correctly keeps distinct read identities.
            state.events.filter { event ->
                val error = (event.content as? EditorEventContent.Problem)?.error
                (event.key.operation as? OperationRef.Local)?.kind == EditorLocalOperation.FormLoad &&
                    error != null && error.afterFormLoaded(current.pendingQuotes.isEmpty()) == null
            }.forEach { collector.dispatch(EditorEventInput.Acknowledge(it.key)) }
        }
        if (current.legacyQuoteRequestDropped && !previous.legacyQuoteRequestDropped) {
            collector.information(EditorText(R.string.editor_chrome_legacy_quote_summary),
                EditorText(R.string.editor_legacy_quote_dropped))
        }
    }

    private fun PostEditorState.eventsProjection() = EditorEventProjection(
        session = state.session,
        submission = submission,
        isLoadingForm = isLoadingForm,
        readKind = if (isFetchingQuote) EditorLocalOperation.QuoteFetch else EditorLocalOperation.FormLoad,
        readFailed = readFailed,
        quotesResolved = pendingQuotes.isEmpty(),
        submitError = submitError,
        isUploading = isUploading,
        uploadProgress = uploadProgress,
        uploadError = uploadError,
        durableWriteGeneration = projection.durableWriteGeneration,
    )
}
