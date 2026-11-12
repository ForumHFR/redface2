package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.BeginAttemptResult
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import java.io.IOException
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

    @Test fun `fresh opening discards quote-only C before autosave and preserves D across another death`() = runBlocking {
        val store = VersionedEditorDraftStore()
        store.preload(key, Draft("D", updatedAt = 1234))
        val first = EditorDraftSession(store, key)
        first.load()
        first.enqueueInlineQuoteRequest(request)
        first.consumeInlineQuoteRequest(request, Draft(quote + "\n  "), deliveredBody = quote)
        val oldVersion = checkNotNull(store.entries[key]).version
        val offers = store.loadOffers("tester", key)
        val fresh = EditorDraftSession(store, key)
        fresh.load()
        val gate = CompletableDeferred<Unit>().also { store.saveReturnGate = it }
        val discard = async(start = CoroutineStart.UNDISPATCHED) { fresh.discardQuoteOnly() }
        try {
            // Process death before the discard callback or any autosave still sees the empty revision.
            val next = EditorDraftSession(store, key)
            val durable = checkNotNull(next.load())
            assertEquals("", durable.draft.body)
            assertNull(durable.draft.bodyProvenance)
            assertNull(next.pendingInlineQuoteRequest)
            assertEquals(oldVersion + 1, durable.version)
            assertEquals(offers, next.offers)
        } finally {
            gate.complete(Unit)
        }
        assertTrue(discard.await() is StoreWriteResult.Written)
        assertNull(fresh.bodyProvenance)
        assertEquals(StoreWriteResult.Conflict, first.save(Draft(quote)))
        assertTrue(fresh.save(Draft("new typing")) is StoreWriteResult.Written)
        assertNull(store.saved[key]?.bodyProvenance)
        assertEquals(offers, store.loadOffers("tester", key))
    }

    @Test fun `new Cite B drops only quote-only A and resets session provenance before B is delivered`() = runBlocking {
        val store = VersionedEditorDraftStore()
        store.preload(key, Draft("D", updatedAt = 1234))
        val session = EditorDraftSession(store, key)
        session.load()
        session.enqueueInlineQuoteRequest(request)
        session.consumeInlineQuoteRequest(request, Draft(quote), deliveredBody = quote)
        val offers = store.loadOffers("tester", key)
        val b = PendingInlineQuoteRequest(23, 42, listOf(PendingInlineQuoteRequest.Quote(202, 1)))
        val bodyB = "[quotemsg=202]two[/quotemsg]"
        assertTrue(session.enqueueInlineQuoteRequest(b) is StoreWriteResult.Written)
        assertEquals("", store.saved[key]?.body)
        assertNull(store.saved[key]?.bodyProvenance)
        assertNull(session.bodyProvenance)
        val afterDeath = EditorDraftSession(store, key)
        assertEquals("", afterDeath.load()?.draft?.body)
        assertEquals(b, afterDeath.pendingInlineQuoteRequest)
        assertTrue(afterDeath.consumeInlineQuoteRequest(b, Draft(bodyB), deliveredBody = bodyB)
            is StoreWriteResult.Written)
        assertEquals(bodyB, store.saved[key]?.body)
        assertEquals(listOf(bodyB), store.saved[key]?.bodyProvenance?.deliveries?.map { it.body })
        assertEquals(offers, store.loadOffers("tester", key))
    }

    @Test fun `discard failures and stale revisions never change quote-only C or its offers`() = runBlocking {
        val store = VersionedEditorDraftStore()
        store.preload(key, Draft(quote, bodyProvenance = EditorBodyProvenance().delivered(request.scope, quote)))
        val session = EditorDraftSession(store, key)
        session.load()
        val original = checkNotNull(store.entries[key])
        store.saveFailure = IOException("disk")
        assertTrue(session.discardQuoteOnly() is StoreWriteResult.Failed)
        assertEquals(original, store.entries[key])
        assertEquals(original.draft.bodyProvenance, session.bodyProvenance)
        store.saveFailure = null
        store.entries[key] = original.copy(draft = original.draft.copy(body = quote + "typed"), version = 1)
        assertEquals(StoreWriteResult.Conflict, session.discardQuoteOnly())
        assertEquals(quote + "typed", store.saved[key]?.body)
        assertEquals(original.draft.bodyProvenance, session.bodyProvenance)
    }

    @Test fun `autosave during Undo then Redo of Restore keeps the insertion boundary before first delivery`() = runBlocking {
        val store = VersionedEditorDraftStore()
        store.preload(key, Draft("D"))
        val session = EditorDraftSession(store, key)
        session.load()
        session.enqueueInlineQuoteRequest(request)
        session.save(Draft("typed"))
        val offer = checkNotNull(session.offered("D"))
        val merged = mergeEditorBody("D", "typed")
        session.resolveOffer(offer, Draft(merged.body, bodyProvenance = merged.provenance))
        session.save(Draft("typed")) // The user undoes Restore and leaves enough time for autosave.
        assertEquals(0, session.bodyProvenance?.quotesEnd)
        session.save(Draft(merged.body)) // Redo changes the text, without replaying the offer decision.
        assertEquals(merged.provenance, session.bodyProvenance)
        val inserted = insertEditorQuotes(merged.body, quote, checkNotNull(session.bodyProvenance))
        assertEquals("D\n\n$quote\n\ntyped", inserted.body)
        assertTrue(session.consumeInlineQuoteRequest(request, Draft(inserted.body, bodyProvenance = inserted.provenance),
            deliveredBody = quote) is StoreWriteResult.Written)
        assertTrue(store.loadOffers("tester", key).isEmpty())
        val afterDeath = EditorDraftSession(store, key)
        assertEquals(inserted.body, afterDeath.load()?.draft?.body)
        assertEquals(inserted.provenance.copy(deliveries = listOf(InlineQuoteDelivery(request.scope, quote))),
            afterDeath.bodyProvenance)
        // Undoing the later quote returns to the cached restored body, but never undelivers Q.
        session.save(Draft(merged.body))
        assertEquals(listOf(InlineQuoteDelivery(request.scope, quote)), session.bodyProvenance?.deliveries)
        assertEquals(merged.provenance.quotesEnd, session.bodyProvenance?.quotesEnd)
        assertNull(session.pendingInlineQuoteRequest)
        assertEquals(StoreWriteResult.Conflict, session.consumeInlineQuoteRequest(request, Draft(inserted.body)))
    }

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
