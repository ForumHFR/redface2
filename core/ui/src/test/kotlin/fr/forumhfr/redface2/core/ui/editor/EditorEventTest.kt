package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.editor.EditorSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorEventTest {
    private val session = EditorSession("session", "alice", "topic:23:42")
    private val key = EventKey(session, OperationRef.Local(EditorLocalOperation.Notice, 1), 1)

    @Test
    fun `invariant 4 - Complete precedes detail and Reload and Dismiss with details open details`() {
        val cases = listOf(
            EditorErrors.missingTitle to EditorEventAction.Complete,
            EditorErrors.missingSubcategory to EditorEventAction.Complete,
            EditorErrors.missingRecipients to EditorEventAction.Complete,
            EditorErrors.missingSubject to EditorEventAction.Complete,
            EditorErrors.unknownRecipient to EditorEventAction.Complete,
            EditorErrors.empty to EditorEventAction.Details,
            EditorErrors.quoteFetchFailed to EditorEventAction.Details,
            EditorErrors.invalidForm to EditorEventAction.Details,
            EditorErrors.formNetwork to EditorEventAction.Details,
            EditorErrors.noImageReceived to EditorEventAction.Details,
            EditorErrors.imageLimit to EditorEventAction.Details,
        )
        cases.forEach { (error, action) ->
            assertEquals(error.cause.toString(), action, event(EditorEventContent.Problem(error)).action)
        }
        val text = EditorErrors.network.summary
        assertEquals(EditorEventAction.Dismiss, event(EditorEventContent.Information(text)).action)
        assertEquals(EditorEventAction.Dismiss, event(EditorEventContent.Finished(text)).action)
        assertEquals(EditorEventAction.Details, event(EditorEventContent.Finished(text, text)).action)
        assertEquals(EditorEventAction.Details, event(EditorEventContent.Information(text, text)).action)
        assertNull(event(EditorEventContent.InProgress(text)).action)
    }

    @Test
    fun `presentation rank keeps the catalogue classification and full text`() {
        editorErrorSamples.forEach { error ->
            val event = event(EditorEventContent.Problem(error))
            val expected = if (error.errorClass == EditorErrorClass.Information) {
                EditorEventRank.Information
            } else {
                EditorEventRank.Error
            }
            assertEquals(error.cause.toString(), expected, event.rank)
            assertEquals(error.summary, event.summary)
            assertEquals(error.detail, event.detail)
            assertEquals(error, (event.content as EditorEventContent.Problem).error)
        }
        assertEquals(EditorEventRank.Information, event(EditorEventContent.Finished(EditorErrors.empty.summary)).rank)
        assertEquals(EditorEventRank.InProgress, event(EditorEventContent.InProgress(EditorErrors.empty.summary)).rank)
    }

    private fun event(content: EditorEventContent) = EditorEvent(key, content, arrivalOrder = 1)
}
