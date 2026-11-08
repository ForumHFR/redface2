package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptIdentity
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.SubmissionToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EditorEventTest {
    private val session = EditorSession("session", "alice", "topic:23:42")
    private val key = EventKey(session, OperationRef.Local(EditorLocalOperation.Notice, 1), 1)

    @Test
    fun `attached attempt metadata requires an exact known owner and context`() {
        val identity = AttemptIdentity("alice", session.key, "attempt-1")
        val ref = OperationRef.Submission(SubmissionToken(1, session), identity)
        assertEquals(identity, ref.attempt)
        val anonymous = SubmissionToken(1, session.copy(owner = null))
        assertNull(OperationRef.Submission(anonymous).attempt)
        assertThrows(IllegalArgumentException::class.java) { ref.copy(attempt = identity.copy(owner = "bob")) }
        assertThrows(IllegalArgumentException::class.java) { ref.copy(attempt = identity.copy(key = "other")) }
        val emptyOwner = anonymous.copy(session = anonymous.session.copy(owner = ""))
        assertEquals("", OperationRef.Submission(emptyOwner, identity.copy(owner = "")).attempt?.owner)
    }

    @Test
    fun `anonymous arming accepts a prepared attempt without attaching an identity`() {
        val anonymous = SubmissionToken(1, session.copy(owner = null))
        val prepared = AttemptIdentity("", anonymous.session.key, "prepared-at-arm")
        val ref = OperationRef.Submission(anonymous, attempt = prepared)
        assertNull(ref.attempt)
        assertEquals(OperationRef.Submission(anonymous), ref)
        assertEquals(OperationRef.Submission(anonymous).hashCode(), ref.hashCode())
        val known = OperationRef.Submission(SubmissionToken(1, session), prepared.copy(owner = "alice"))
        assertEquals(ref, known.copy(token = anonymous))
        assertNull(known.copy(token = anonymous).attempt)
    }

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
