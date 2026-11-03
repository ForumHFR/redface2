package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.BeginAttemptResult
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The contract fake must reject the same terminal transitions as Room. */
class VersionedEditorDraftStoreTest {
    @Test
    fun `private purge deletes ordinary drafts and erases only attempt content for the departing owner`() = runTest {
        val store = VersionedEditorDraftStore()
        val privateDraft = Draft("body", "subject", "recipient", isPrivate = true, updatedAt = 1234)
        store.preload("ordinary", privateDraft)
        store.preload("public", privateDraft.copy(isPrivate = false))
        val publicEntry = store.entries.getValue("public")
        val attempts = AttemptStatus.entries.associate { status ->
            val key = "attempt:${status.name}"
            val token = (store.beginAttempt("tester", key, privateDraft, 0) as BeginAttemptResult.Started).token
            if (status != AttemptStatus.IN_PROGRESS) {
                store.finishAttempt(token, AttemptOutcome.valueOf(status.name))
            }
            key to store.entries.getValue(key)
        }
        store.activeOwner = "other"
        store.preload("ordinary", privateDraft)
        val otherEntries = store.entries.toMap()

        store.purgePrivateForUser("TeStEr")

        assertEquals(otherEntries, store.entries)
        assertNull(store.loadEntry("tester", "ordinary"))
        store.activeOwner = "tester"
        assertNull(store.loadEntry("tester", "ordinary"))
        assertEquals(publicEntry, store.entries["public"])
        for ((key, entry) in attempts) {
            assertEquals(
                entry.copy(draft = privateDraft.copy(body = "", subject = null, recipients = null)),
                store.loadEntry("tester", key),
            )
        }
    }

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

    @Test
    fun `certain failure with missing owner or draft conflicts without creating entries`() = runTest {
        val store = VersionedEditorDraftStore()
        val token = (store.beginAttempt("tester", "key", Draft("body"), 0) as BeginAttemptResult.Started).token
        val original = store.entries.toMap()

        for (missing in listOf(token.copy(owner = "absent"), token.copy(key = "absent"))) {
            assertEquals(StoreWriteResult.Conflict, store.finishAttempt(missing, AttemptOutcome.CERTAIN_FAILURE))
            assertEquals(original, store.entries)
        }
    }
}
