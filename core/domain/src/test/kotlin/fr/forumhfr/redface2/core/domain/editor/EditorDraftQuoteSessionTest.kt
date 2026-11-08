package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.BeginAttemptResult
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1553: session-owned coordinates cannot be lost or resurrected by field snapshots. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorDraftQuoteSessionTest {
    private val key = EditorDraftKey.reply(23, 35421)
    private val request = PendingInlineQuoteRequest(23, 35421, listOf(PendingInlineQuoteRequest.Quote(101, 1)))

    @Test
    fun `entry without a row creates generation one before any body write`() = runTest {
        val store = VersionedEditorDraftStore()
        val session = EditorDraftSession(store, key)
        assertNull(session.load())
        assertEquals(StoreWriteResult.Written(1), session.enqueueInlineQuoteRequest(request))
        assertEquals(Draft(body = "", pendingInlineQuoteRequest = request), store.entries[key]?.draft)
        assertEquals(request, session.pendingInlineQuoteRequest)
    }

    @Test
    fun `entry preserves the offered draft and ignoring that draft keeps the request`() = runTest {
        val store = VersionedEditorDraftStore()
        val offered = Draft("offered", "subject", "recipient")
        store.preload(key, offered)
        val session = EditorDraftSession(store, key)
        session.load()
        session.enqueueInlineQuoteRequest(request)
        assertEquals(offered.copy(pendingInlineQuoteRequest = request), store.entries[key]?.draft)
        session.save(Draft(body = ""))
        assertEquals("", store.entries[key]?.draft?.body)
        assertEquals(request, store.entries[key]?.draft?.pendingInlineQuoteRequest)
    }

    @Test
    fun `ignoring only the quote preserves the offered body and its headers`() = runTest {
        val store = VersionedEditorDraftStore()
        val offered = Draft("offered", "subject", "recipient")
        store.preload(key, offered)
        val session = EditorDraftSession(store, key)
        session.load()
        session.enqueueInlineQuoteRequest(request)
        assertEquals(StoreWriteResult.Written(2), session.consumeInlineQuoteRequest(request))
        assertEquals(offered, store.entries[key]?.draft)
        assertNull(session.pendingInlineQuoteRequest)
    }

    @Test
    fun `delivery commits body and removal then old autosave cannot resurrect coordinates`() = runTest {
        val store = VersionedEditorDraftStore()
        val session = EditorDraftSession(store, key)
        session.load()
        session.enqueueInlineQuoteRequest(request)
        val stale = Draft("old text", pendingInlineQuoteRequest = request)
        assertEquals(StoreWriteResult.Written(2), session.consumeInlineQuoteRequest(request, Draft("quoted body")))
        assertEquals("quoted body", store.entries[key]?.draft?.body)
        assertNull(store.entries[key]?.draft?.pendingInlineQuoteRequest)
        session.save(stale)
        assertNull(store.entries[key]?.draft?.pendingInlineQuoteRequest)
        val restored = EditorDraftSession(store, key)
        restored.load()
        assertNull(restored.pendingInlineQuoteRequest)
    }

    @Test
    fun `new quote merged during a GET prevents consumption of an older request`() = runTest {
        val store = VersionedEditorDraftStore()
        val session = EditorDraftSession(store, key)
        session.load()
        session.enqueueInlineQuoteRequest(request)
        val second = PendingInlineQuoteRequest(23, 35421, listOf(PendingInlineQuoteRequest.Quote(202, 2)))
        session.enqueueInlineQuoteRequest(second)
        assertEquals(StoreWriteResult.Conflict, session.consumeInlineQuoteRequest(request, Draft("late GET")))
        assertEquals(request.merge(second), store.entries[key]?.draft?.pendingInlineQuoteRequest)
    }

    @Test
    fun `unresolved entry stays dormant and ignore never acknowledges the attempt`() = runTest {
        for (status in listOf(AttemptStatus.IN_PROGRESS, AttemptStatus.UNCERTAIN)) {
            val store = VersionedEditorDraftStore()
            val token = (store.beginAttempt("tester", key, Draft("uncertain body"), 0)
                as BeginAttemptResult.Started).token
            if (status == AttemptStatus.UNCERTAIN) store.finishAttempt(token, AttemptOutcome.UNCERTAIN)
            val attempt = store.entries[key]?.attempt
            val session = EditorDraftSession(store, key)
            session.load()
            session.enqueueInlineQuoteRequest(request)
            assertEquals(attempt, store.entries[key]?.attempt)
            assertEquals(
                StoreWriteResult.Conflict, session.consumeInlineQuoteRequest(request, Draft("unsafe delivery")),
            )
            assertEquals(StoreWriteResult.Written(3), session.consumeInlineQuoteRequest(request))
            assertEquals(attempt, store.entries[key]?.attempt)
            assertEquals("uncertain body", store.entries[key]?.draft?.body)
        }
    }

    @Test
    fun `acknowledgement preserves the dormant request for later delivery`() = runTest {
        val store = VersionedEditorDraftStore()
        val token = (store.beginAttempt("tester", key, Draft("body"), 0) as BeginAttemptResult.Started).token
        val session = EditorDraftSession(store, key)
        session.load()
        session.enqueueInlineQuoteRequest(request)
        store.finishFailure = IOException("disk")
        assertTrue(session.finish(token, AttemptOutcome.CERTAIN_FAILURE) is StoreWriteResult.Failed)
        assertEquals(request, session.pendingInlineQuoteRequest)
        assertEquals(StoreWriteResult.Conflict, session.consumeInlineQuoteRequest(request, Draft("unsafe delivery")))
        store.finishFailure = null
        assertEquals(StoreWriteResult.Written(2), session.finish(token, AttemptOutcome.CERTAIN_FAILURE))
        assertEquals(request, session.pendingInlineQuoteRequest)
        assertEquals(StoreWriteResult.Written(3), session.consumeInlineQuoteRequest(request, Draft("body and quote")))
        assertNull(store.entries[key]?.attempt)
    }

    @Test
    fun `begin uses the owned request rather than the caller snapshot`() = runTest {
        val store = VersionedEditorDraftStore()
        val session = EditorDraftSession(store, key)
        session.load()
        session.enqueueInlineQuoteRequest(request)
        val token = (session.begin(Draft("body"), "attempt") as BeginAttemptResult.Started).token
        assertEquals(request, store.entries[key]?.draft?.pendingInlineQuoteRequest)
        session.finish(token, AttemptOutcome.PUBLISHED)
        val stale = Draft("new body", pendingInlineQuoteRequest = request)
        session.begin(stale, "new-attempt")
        assertNull(store.entries[key]?.draft?.pendingInlineQuoteRequest)
    }

    @Test
    fun `publication removes the request even if cleanup fails and a new action starts empty`() = runTest {
        val store = VersionedEditorDraftStore()
        val session = EditorDraftSession(store, key)
        session.load()
        val token = (session.begin(Draft("sent body"), "attempt") as BeginAttemptResult.Started).token
        session.enqueueInlineQuoteRequest(request)
        assertEquals(StoreWriteResult.Written(2), session.finish(token, AttemptOutcome.PUBLISHED))
        store.cleanupFailure = IOException("disk")
        assertTrue(session.erase(token) is StoreWriteResult.Failed)
        assertNull(session.pendingInlineQuoteRequest)
        assertNull(store.entries[key]?.draft?.pendingInlineQuoteRequest)
        assertEquals(StoreWriteResult.Written(3), session.enqueueInlineQuoteRequest(request))
        assertEquals(Draft(body = "", pendingInlineQuoteRequest = request), store.entries[key]?.draft)
        assertNull(store.entries[key]?.attempt)
    }

    @Test
    fun `conflict owner change and failure do not adopt or remove requests`() = runTest {
        val store = VersionedEditorDraftStore()
        val stale = EditorDraftSession(store, key)
        stale.load()
        store.enqueueInlineQuoteRequest("tester", key, request, 0)
        assertEquals(StoreWriteResult.Conflict, stale.enqueueInlineQuoteRequest(request))
        assertNull(stale.pendingInlineQuoteRequest)
        val session = EditorDraftSession(store, key)
        session.load()
        store.saveFailure = IOException("disk")
        assertTrue(session.consumeInlineQuoteRequest(request) is StoreWriteResult.Failed)
        assertEquals(request, session.pendingInlineQuoteRequest)
        store.saveFailure = null
        store.activeOwner = "other"
        assertEquals(StoreWriteResult.OwnerChanged, session.consumeInlineQuoteRequest(request))
        assertEquals(request, session.pendingInlineQuoteRequest)
    }

    @Test
    fun `consumption adopts committed result despite cancellation and serializes queued autosave`() = runTest {
        val store = VersionedEditorDraftStore()
        val session = EditorDraftSession(store, key)
        session.load()
        session.enqueueInlineQuoteRequest(request)
        val stale = checkNotNull(store.entries[key]).draft
        val gate = CompletableDeferred<Unit>()
        store.saveReturnGate = gate
        val delivery = async { session.consumeInlineQuoteRequest(request, Draft("body and quote")) }
        runCurrent()
        assertNull(store.entries[key]?.draft?.pendingInlineQuoteRequest)
        delivery.cancel()
        val autosave = async { session.save(stale) }
        runCurrent()
        assertEquals(2L, store.entries[key]?.version)
        gate.complete(Unit)
        delivery.cancelAndJoin()
        assertEquals(StoreWriteResult.Written(3), autosave.await())
        assertNull(session.pendingInlineQuoteRequest)
        assertNull(store.entries[key]?.draft?.pendingInlineQuoteRequest)
    }

    @Test
    fun `entry adopts its committed generation despite cancellation`() = runTest {
        val store = VersionedEditorDraftStore()
        val session = EditorDraftSession(store, key)
        session.load()
        val gate = CompletableDeferred<Unit>()
        store.saveReturnGate = gate
        val entry = async { session.enqueueInlineQuoteRequest(request) }
        runCurrent()
        assertEquals(1L, store.entries[key]?.version)
        entry.cancel()
        gate.complete(Unit)
        entry.cancelAndJoin()
        assertEquals(request, session.pendingInlineQuoteRequest)
        assertEquals(StoreWriteResult.Written(2), session.save(Draft("new text")))
        assertEquals(request, store.entries[key]?.draft?.pendingInlineQuoteRequest)
    }
    @Test
    fun `M9 an old closing flush cannot overwrite a quote row that won the CAS`() = runTest {
        val store = VersionedEditorDraftStore()
        store.preload(key, Draft("last durable body"))
        val closing = EditorDraftSession(store, key)
        closing.load()
        val producer = EditorDraftSession(store, key)
        producer.load()
        producer.enqueueInlineQuoteRequest(request)
        assertEquals(StoreWriteResult.Conflict, closing.save(Draft("unflushed tail")))
        assertEquals("last durable body", store.entries[key]?.draft?.body)
        assertEquals(request, store.entries[key]?.draft?.pendingInlineQuoteRequest)
    }

}
