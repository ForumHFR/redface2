package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.BeginAttemptResult
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Session adoption is exercised independently of Android/Room; Room covers the real transactions. */
class EditorDraftOfferSessionTest {
    private val request = PendingInlineQuoteRequest(23, 42, listOf(PendingInlineQuoteRequest.Quote(101, 1)))
    private val key = request.key
    private val quote = "[quotemsg=101]one[/quotemsg]"

    @Test fun `offer and C survive a new session before the committing save returns`() = runBlocking {
        val store = VersionedEditorDraftStore()
        store.preload(key, Draft("D", updatedAt = 1234))
        val session = EditorDraftSession(store, key)
        session.load()
        val gate = CompletableDeferred<Unit>().also { store.saveReturnGate = it }
        val save = async(start = CoroutineStart.UNDISPATCHED) { session.save(Draft("C")) }
        try {
            val reopened = EditorDraftSession(store, key)
            assertEquals("C", reopened.load()?.draft?.body)
            assertEquals(listOf("D"), reopened.offers.map { it.draft.body })
            assertEquals(1234L, reopened.offers.single().draft.updatedAt)
        } finally {
            gate.complete(Unit)
        }
        assertTrue(save.await() is StoreWriteResult.Written)
    }

    @Test fun `resolving D keeps Q and cannot consume or merge the same D twice`() = runBlocking {
        val store = VersionedEditorDraftStore()
        store.preload(key, Draft("D"))
        val session = EditorDraftSession(store, key)
        session.load()
        session.enqueueInlineQuoteRequest(request)
        session.save(Draft("typed"))
        val offer = checkNotNull(session.offered("D"))
        val merge = mergeEditorBody("D", "typed")
        assertTrue(session.resolveOffer(offer, Draft(merge.body, bodyProvenance = merge.provenance))
            is StoreWriteResult.Written)
        assertEquals(request, store.saved[key]?.pendingInlineQuoteRequest)
        assertEquals("D\n\ntyped", store.saved[key]?.body)
        assertTrue(store.loadOffers("tester", key).isEmpty())
        assertEquals(StoreWriteResult.Conflict, session.resolveOffer(offer, Draft("D\n\nD\n\ntyped")))
        assertEquals("D\n\ntyped", store.saved[key]?.body)
    }

    @Test fun `a delivered quote alone never becomes D and publication keeps the older D`() = runBlocking {
        val store = VersionedEditorDraftStore()
        store.preload(key, Draft("D"))
        val session = EditorDraftSession(store, key)
        session.load()
        session.enqueueInlineQuoteRequest(request)
        session.consumeInlineQuoteRequest(request, Draft(quote + "\n"), deliveredBody = quote)
        val reopened = EditorDraftSession(store, key)
        val current = checkNotNull(reopened.load()).draft
        assertTrue(checkNotNull(current.bodyProvenance).isQuoteOnly(current.body))
        assertNull(reopened.offered(quote + "\n"))
        reopened.save(Draft("C to send"))
        val token = (reopened.begin(Draft("C to send"), "send") as BeginAttemptResult.Started).token
        reopened.finish(token, AttemptOutcome.PUBLISHED)
        reopened.erase(token)
        assertNull(store.load("tester", key))
        assertEquals(listOf("D"), store.loadOffers("tester", key).map { it.draft.body })
        val next = EditorDraftSession(store, key)
        next.load()
        next.save(Draft("new composition"))
        assertNull(store.saved[key]?.bodyProvenance)
    }

    @Test fun `autosave after a restore consumes D atomically and a queued snapshot is idempotent`() = runBlocking {
        val store = VersionedEditorDraftStore()
        store.preload(key, Draft("D"))
        val session = EditorDraftSession(store, key)
        session.load()
        session.enqueueInlineQuoteRequest(request)
        session.save(Draft("typed"))
        val offer = checkNotNull(session.offered("D"))
        val merge = mergeEditorBody("D", "typed")
        val snapshot = Draft(merge.body, bodyProvenance = merge.provenance, offerToResolve = offer)
        assertTrue(session.save(snapshot) is StoreWriteResult.Written)
        assertTrue(store.loadOffers("tester", key).isEmpty())
        assertEquals(request, store.saved[key]?.pendingInlineQuoteRequest)
        assertEquals(merge.provenance, store.saved[key]?.bodyProvenance)
        assertTrue(session.save(snapshot.copy(body = merge.body + " more")) is StoreWriteResult.Written)
        assertTrue(store.loadOffers("tester", key).isEmpty())
        assertEquals(merge.body + " more", store.saved[key]?.body)
        assertNull(store.saved[key]?.offerToResolve)
    }

    @Test fun `send captures the restore decision even before its explicit flush`() = runBlocking {
        val store = VersionedEditorDraftStore()
        store.preload(key, Draft("D"))
        val session = EditorDraftSession(store, key)
        session.load()
        val offer = checkNotNull(session.offered("D"))
        val snapshot = Draft("D\n\ntyped", offerToResolve = offer)
        assertTrue(session.begin(snapshot, "send") is BeginAttemptResult.Started)
        assertTrue(store.loadOffers("tester", key).isEmpty())
        assertEquals(snapshot.body, store.saved[key]?.body)
    }

    @Test fun `a raw writer also protects the old reply before its first replacement`() = runBlocking {
        val store = VersionedEditorDraftStore()
        store.preload(key, Draft("D"))
        assertTrue(store.saveIfVersion("tester", key, Draft("C"), 0) is StoreWriteResult.Written)
        assertEquals(listOf("D"), store.loadOffers("tester", key).map { it.draft.body })
    }
}
