package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorDraftOfferInputTest {
    private val key = EditorDraftKey.reply(23, 35421)

    @Test
    fun `M5 durable offer input never overwrites D or acquires its pending quote`() = runTest {
        val store = VersionedEditorDraftStore()
        val pending = PendingInlineQuoteRequest(23, 35421, listOf(PendingInlineQuoteRequest.Quote(101, 1)))
        val offered = Draft("D", pendingInlineQuoteRequest = pending)
        store.preload(key, offered)
        val input = EditorDraftOfferInput.load(store, key, "tester", "D")
        assertTrue(input.save("C") is StoreWriteResult.Written)
        assertEquals(offered, store.saved[key])
        val reopened = EditorDraftOfferInput.load(store, key, "tester", "D")
        assertTrue(reopened.isPending)
        assertEquals("C", reopened.body)
        assertFalse(EditorDraftOfferInput.load(store, key, "tester", "different D").isPending)
        assertTrue(store.saved.filterKeys { it != key }.values.all { it.pendingInlineQuoteRequest == null })
    }

    @Test
    fun `Astra3 an empty current field still carries an undecided offer across escalation`() = runTest {
        val store = VersionedEditorDraftStore()
        val input = EditorDraftOfferInput.load(store, key, "tester", "D")
        input.save("")
        val reopened = EditorDraftOfferInput.load(store, key, "tester", "D")
        assertTrue(reopened.isPending)
        assertEquals("", reopened.body)
        reopened.clear()
        assertFalse(EditorDraftOfferInput.load(store, key, "tester", "D").isPending)
        assertEquals(StoreWriteResult.Conflict, input.save("stale text"))
    }

    @Test
    fun `a cancelled staged autosave still adopts its committed marker before clearing`() = runTest {
        val store = VersionedEditorDraftStore()
        val input = EditorDraftOfferInput.load(store, key, "tester", "D")
        val gate = CompletableDeferred<Unit>()
        store.saveReturnGate = gate
        val saving = launch { input.save("C") }
        runCurrent()
        saving.cancel()
        gate.complete(Unit)
        saving.join()
        assertTrue(input.isPending)
        input.clear()
        assertFalse(EditorDraftOfferInput.load(store, key, "tester", "D").isPending)
    }

    @Test
    fun `offer input cannot cross accounts or restore a decided scratch row`() = runTest {
        val store = VersionedEditorDraftStore()
        val input = EditorDraftOfferInput.load(store, key, "tester", "D")
        input.save("C")
        store.activeOwner = "other"
        assertEquals(StoreWriteResult.OwnerChanged, input.save("must not leak"))
        val other = EditorDraftOfferInput.load(store, key, "other", "D")
        assertFalse(other.isPending)
        assertEquals("", other.body)
        store.activeOwner = "tester"
        input.clear()
        assertFalse(EditorDraftOfferInput.load(store, key, "tester", "D").isPending)
    }
}
