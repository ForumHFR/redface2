package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.BeginAttemptResult
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The contract fake must reject the same terminal transitions as Room. */
class VersionedEditorDraftStoreTest {
    @Test
    fun `published attempt rejects every subsequent outcome`() = runTest {
        val store = VersionedEditorDraftStore()
        val token = (store.beginAttempt("tester", "key", Draft("body"), 0) as BeginAttemptResult.Started).token
        store.finishAttempt(token, AttemptOutcome.PUBLISHED)
        val published = store.entries["key"]

        for (outcome in AttemptOutcome.entries) {
            assertEquals(StoreWriteResult.Conflict, store.finishAttempt(token, outcome))
            assertEquals(published, store.entries["key"])
        }
    }

    @Test
    fun `legacy deletion cannot erase a durable attempt`() = runTest {
        val store = VersionedEditorDraftStore()
        val token = (store.beginAttempt("tester", "key", Draft("body"), 0) as BeginAttemptResult.Started).token
        store.delete("tester", "key")
        assertEquals(token, store.entries["key"]?.attempt?.token)
        store.finishAttempt(token, AttemptOutcome.PUBLISHED)
        store.delete("tester", "key")
        assertEquals(token, store.entries["key"]?.attempt?.token)
    }

    @Test
    fun `published cleanup refuses a newer content revision like Room`() = runTest {
        val store = VersionedEditorDraftStore()
        val token = (store.beginAttempt("tester", "key", Draft("body"), 0) as BeginAttemptResult.Started).token
        store.saveUnresolved(token, Draft("edited"), 1)
        store.finishAttempt(token, AttemptOutcome.PUBLISHED)

        assertEquals(StoreWriteResult.Conflict, store.erasePublishedContent(token))
        assertEquals("edited", store.entries["key"]?.draft?.body)
    }
}
