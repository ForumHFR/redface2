package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.SubmissionToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorEventTest {
    private val session = EditorSession("session", "alice", "topic:23:42")
    private val key = EventKey(session, OperationRef.Local(EditorLocalOperation.Notice, 1), 1)

    @Test
    fun `submission identity uses the accepted token and its exact session without durable metadata`() {
        val ref = OperationRef.Submission(SubmissionToken(1, session))
        val equivalent = OperationRef.Submission(SubmissionToken(1, session.copy()))
        assertTrue(ref.sameOperation(equivalent))
        assertEquals(ref.hashCode(), equivalent.hashCode())
        assertFalse(ref.sameOperation(ref.copy(token = SubmissionToken(2, session))))
        listOf(session.copy(id = "reopened"), session.copy(owner = "bob"), session.copy(key = "other")).forEach {
            assertFalse(ref.belongsTo(it))
            assertFalse(ref.sameOperation(OperationRef.Submission(SubmissionToken(1, it))))
        }
    }

    @Test
    fun `anonymous submission is representable and never aliases an empty or known owner`() {
        val anonymous = SubmissionToken(1, session.copy(owner = null))
        val ref = OperationRef.Submission(anonymous)
        assertEquals(OperationRef.Submission(anonymous), ref)
        listOf("", "alice").forEach { owner ->
            val known = ref.copy(token = anonymous.copy(session = session.copy(owner = owner)))
            assertFalse(ref.sameOperation(known))
            assertFalse(known.belongsTo(anonymous.session))
            assertEquals(ref, known.copy(token = anonymous))
        }
    }

    @Test
    fun `invariant 4 - Complete precedes detail and Reload and Dismiss with details open details`() {
        assertEquals(
            setOf(EditorEventAction.Complete, EditorEventAction.Details, EditorEventAction.Dismiss),
            EditorEventAction.entries.toSet(),
        )
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
    fun `ED43 - finished summaries carry visual and accessible texts independently`() {
        val visual = EditorErrors.network.summary
        val full = requireNotNull(EditorErrors.network.detail)
        val finished = event(EditorEventContent.Finished(visual, accessibleSummary = full))
        assertEquals(visual, finished.summary)
        assertEquals(full, finished.accessibleSummary)
        assertNull(finished.detail)
        assertEquals(EditorEventAction.Dismiss, finished.action)
        assertEquals(visual, event(EditorEventContent.Information(visual)).accessibleSummary)
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
            assertTrue(error.cause.toString(), event.action in EditorEventAction.entries)
        }
        assertEquals(EditorEventRank.Information, event(EditorEventContent.Finished(EditorErrors.empty.summary)).rank)
        assertEquals(EditorEventRank.InProgress, event(EditorEventContent.InProgress(EditorErrors.empty.summary)).rank)
    }

    private fun event(content: EditorEventContent) = EditorEvent(key, content, arrivalOrder = 1)
}
