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
    private var pendingQuoteEvent: EventKey? = null
    private var quoteAwaitingDecision = false
    private var readFailed = false
    private var projection = EditorEventProjection(state.session)

    override fun dispatch(input: EditorEventInput) = collector.dispatch(input)
    override fun uncertainAttempt(key: EventKey) = collector.uncertainAttempt(key)
    fun open(session: EditorSession) {
        if (session != state.session) {
            projection = EditorEventProjection(session)
            pendingQuoteEvent = null
            quoteAwaitingDecision = false
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
        if (previous.isLoadingForm && !current.isLoadingForm && !readFailed && current.isFetchingQuote) {
            // A quote form also supplies ordinary metadata. Retire a failed ordinary read that a
            // decision overtook, even though the collector correctly keeps distinct read identities.
            state.events.filter { event ->
                val error = (event.content as? EditorEventContent.Problem)?.error
                (event.key.operation as? OperationRef.Local)?.kind == EditorLocalOperation.FormLoad &&
                    error != null && error.afterFormLoaded(current.pendingQuotes.isEmpty()) == null
            }.forEach { collector.dispatch(EditorEventInput.Acknowledge(it.key)) }
        }
        observePendingQuote(current)
        if (current.legacyQuoteRequestDropped && !previous.legacyQuoteRequestDropped) {
            collector.information(EditorText(R.string.editor_chrome_legacy_quote_summary),
                EditorText(R.string.editor_legacy_quote_dropped))
        }
    }

    fun isPendingQuoteEvent(key: EventKey): Boolean = key == pendingQuoteEvent &&
        state.events.any { it.key == key }

    private fun observePendingQuote(current: PostEditorState) {
        val waiting = current.isQuoteAwaitingDraftDecision && state.session.owner != null
        if (waiting && !quoteAwaitingDecision) {
            // ED-43: waiting for a decision is Information, below errors and active operations.
            // ED-44: normal information acknowledgement/next-gesture rules apply; ED-132 also
            // retires this exact notice when its condition ends. It must never outlive the offer.
            // The shared event control announces Information with a polite live region (ED-95).
            pendingQuoteEvent = collector.information(EditorText(R.string.editor_quote_pending),
                EditorText(R.string.editor_quote_pending))
        } else if (!waiting) {
            pendingQuoteEvent?.let { collector.dispatch(EditorEventInput.Acknowledge(it)) }
            pendingQuoteEvent = null
        }
        quoteAwaitingDecision = waiting
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
