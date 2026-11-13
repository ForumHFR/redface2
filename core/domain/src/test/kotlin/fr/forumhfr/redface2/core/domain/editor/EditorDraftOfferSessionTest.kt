package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Session adoption is exercised independently of Android/Room; Room covers the real transactions. */
class EditorDraftOfferSessionTest {
    private val request = PendingInlineQuoteRequest(23, 42, listOf(PendingInlineQuoteRequest.Quote(101, 1)))
    private val key = request.key
    private val quote = "[quotemsg=101]one[/quotemsg]"

    @Test fun `a failed merged offer blocks other decisions until C consumes it on every surface`() = runBlocking {
        val keys = listOf(key, EditorDraftKey.mpReply(42), EditorDraftKey.newTopic(23), EditorDraftKey.mpCompose())
        for (context in keys) {
            val store = VersionedEditorDraftStore()
            store.preload(context, Draft("second D", updatedAt = 2000))
            val first = EditorDraftStore.DraftOffer.from(EditorDraftStore.DraftEntry(Draft("first D"), 0))
            store.preloadOffer(context, first)
            val session = EditorDraftSession(store, context)
            session.load()
            val second = session.availableOffers.first { it.draft.body == "second D" }
            val merged = Draft("first D\n\ntyped", offerToResolve = first)
            store.saveFailure = IOException("disk full")
            assertTrue(session.save(merged) is StoreWriteResult.Failed)
            assertTrue(session.canDecideOffer(first, first))
            assertFalse(session.canDecideOffer(second, first))

            store.saveFailure = null
            assertTrue(session.save(merged) is StoreWriteResult.Written)
            assertTrue(session.canDecideOffer(second, first))
            assertTrue(session.save(Draft("second D\n\nfirst D\n\ntyped", offerToResolve = second))
                is StoreWriteResult.Written)
            val recreated = EditorDraftSession(store, context)
            assertEquals("second D\n\nfirst D\n\ntyped", recreated.load()?.draft?.body)
            recreated.resumeContent()
            assertTrue(recreated.availableOffers.isEmpty())
        }
    }

    @Test
    fun `an older autosave returning after a live restore does not lose its insertion boundary`() = runBlocking {
        val store = VersionedEditorDraftStore()
        val editor = EditorDraftSession(store, key)
        editor.load()
        val gate = CompletableDeferred<Unit>().also { store.saveReturnGate = it }
        val oldSave = async(start = CoroutineStart.UNDISPATCHED) { editor.save(Draft("typed")) }
        val merge = mergeEditorBody("D", "typed")
        editor.stageBodyMerge(merge)
        gate.complete(Unit)
        oldSave.await()
        val proof = editor.provenanceFor(merge.body)
        assertEquals(merge.provenance, proof)
        assertEquals("D\n\n$quote\n\ntyped", insertEditorQuotes(merge.body, quote, proof).body)
        assertTrue(editor.save(Draft(merge.body, bodyProvenance = proof)) is StoreWriteResult.Written)
        assertEquals(merge.provenance, editor.provenanceFor(merge.body))
    }

    @Test
    fun `offer resolution refuses publication since the session load even at the same content version`() = runBlocking {
        val store = VersionedEditorDraftStore()
        store.preload(key, Draft("D"))
        val writer = EditorDraftSession(store, key)
        writer.load()
        writer.save(Draft("C"))
        val token = (writer.begin(Draft("C"), "send") as BeginAttemptResult.Started).token
        val stale = EditorDraftSession(store, key)
        stale.load()
        val offer = stale.offers.single()
        writer.finish(token, AttemptOutcome.PUBLISHED)
        assertEquals(StoreWriteResult.Conflict, stale.resolveOffer(offer, Draft("D restored")))
        assertEquals(StoreWriteResult.Conflict, stale.save(Draft("D restored", offerToResolve = offer)))
        assertEquals(AttemptStatus.PUBLISHED, store.entries[key]?.attempt?.status)
        assertEquals(listOf("D"), store.loadOffers(store.activeOwner, key).map { it.draft.body })
    }

    @Test
    fun `removed then reinserted older quote keeps its scoped proof through autosave and death`() = runBlocking {
        val store = VersionedEditorDraftStore()
        val editor = EditorDraftSession(store, key)
        editor.load()
        editor.enqueueInlineQuoteRequest(request)
        editor.consumeInlineQuoteRequest(request, Draft("$quote\n\ntyped"), deliveredBody = quote)
        val next = PendingInlineQuoteRequest(23, 42, listOf(PendingInlineQuoteRequest.Quote(202, 1)))
        val second = "[quotemsg=202]two[/quotemsg]"
        editor.enqueueInlineQuoteRequest(next)
        editor.consumeInlineQuoteRequest(next, Draft("$quote\n\n$second\n\ntyped"), deliveredBody = second)
        editor.save(Draft("$second\n\ntyped"))
        val reopened = EditorDraftSession(store, key)
        reopened.load()
        reopened.save(Draft("$quote\n\n$second\n\ntyped"))
        val proof = checkNotNull(reopened.bodyProvenance)
        assertEquals("$quote\n\n$second\n\ntyped",
            mergeEditorBody(quote, "$quote\n\n$second\n\ntyped", provenance = proof).body)
    }

    @Test
    fun `fresh opening discards quote-only C before autosave and preserves D across another death`() = runBlocking {
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

    @Test
    fun `autosave during Undo then Redo of Restore keeps the insertion boundary before first delivery`() = runBlocking {
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
        assertTrue(session.consumeInlineQuoteRequest(request,
            Draft(inserted.body, bodyProvenance = inserted.provenance),
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

    @Test fun `delivery after a failed Restore write consumes merged D with C and never reoffers it`() = runBlocking {
        val store = VersionedEditorDraftStore()
        store.preload(key, Draft("D"))
        val session = EditorDraftSession(store, key)
        session.load()
        session.enqueueInlineQuoteRequest(request)
        val offer = checkNotNull(session.offered("D"))
        val restored = mergeEditorBody("D", "")
        store.saveFailure = IOException("disk full")
        assertTrue(session.resolveOffer(offer, Draft(restored.body, bodyProvenance = restored.provenance))
            is StoreWriteResult.Failed)
        val writesBefore = store.writes
        // One store transaction: a failure consumes neither D nor Q and leaves no quote in the row.
        val delivered = insertEditorQuotes(restored.body, quote, restored.provenance)
        val snapshot = Draft(delivered.body, bodyProvenance = delivered.provenance, offerToResolve = offer)
        assertTrue(session.consumeInlineQuoteRequest(request, snapshot, deliveredBody = quote)
            is StoreWriteResult.Failed)
        assertEquals(request, session.pendingInlineQuoteRequest)
        assertEquals("D", store.saved[key]?.body)
        assertEquals(request, store.saved[key]?.pendingInlineQuoteRequest)
        assertEquals(0, store.writes - writesBefore)
        store.saveFailure = null
        assertTrue(session.consumeInlineQuoteRequest(request, snapshot, deliveredBody = quote)
            is StoreWriteResult.Written)
        assertEquals("exactly one write carries C, Q and D", 1, store.writes - writesBefore)
        assertEquals("D\n\n$quote", store.saved[key]?.body)
        assertNull(store.saved[key]?.pendingInlineQuoteRequest)
        assertTrue(session.availableOffers.isEmpty())
        // ED-121: after a process death, D merged into C is not offered again (no duplicate Restore).
        val reopened = EditorDraftSession(store, key)
        reopened.load()
        assertTrue(store.loadOffers("tester", key).none { it.draft.body == "D" })
        assertNull(reopened.offered("D"))
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

    @Test fun `offer resolution through save preserves uncertainty and starts a composition after publication`() =
        runBlocking {
            for (outcome in listOf(AttemptOutcome.PUBLISHED, AttemptOutcome.UNCERTAIN)) {
                for (restoring in listOf(false, true)) {
                    val store = VersionedEditorDraftStore()
                    store.preload(key, Draft("D"))
                    val sending = EditorDraftSession(store, key)
                    sending.load()
                    val token = (sending.begin(Draft("C"), "send") as BeginAttemptResult.Started).token
                    sending.finish(token, outcome)
                    if (outcome == AttemptOutcome.PUBLISHED) sending.erase(token)
                    val editor = EditorDraftSession(store, key)
                    editor.load()
                    val offer = editor.offers.single()
                    val body = if (restoring) "D restored" else "new C"
                    assertTrue(editor.save(Draft(body, offerToResolve = offer)) is StoreWriteResult.Written)
                    assertTrue(store.loadOffers(store.activeOwner, key).isEmpty())
                    assertEquals(body, store.saved[key]?.body)
                    if (outcome == AttemptOutcome.UNCERTAIN) {
                        assertEquals(token, store.entries[key]?.attempt?.token)
                        assertEquals(AttemptStatus.UNCERTAIN, store.entries[key]?.attempt?.status)
                        assertTrue(editor.save(Draft("edited")) is StoreWriteResult.Written)
                        assertEquals(token, store.entries[key]?.attempt?.token)
                    } else {
                        assertNull(store.entries[key]?.attempt)
                        assertTrue(editor.begin(Draft("next send"), "next") is BeginAttemptResult.Started)
                    }
                }
            }
        }

    @Test fun `begin can atomically consume a preserved D after publication before starting the next send`() =
        runBlocking {
            val store = VersionedEditorDraftStore()
            store.preload(key, Draft("D"))
            val first = EditorDraftSession(store, key)
            first.load()
            val token = (first.begin(Draft("C"), "first") as BeginAttemptResult.Started).token
            first.finish(token, AttemptOutcome.PUBLISHED)
            first.erase(token)
            val second = EditorDraftSession(store, key)
            second.load()
            val offer = second.offers.single()
            assertTrue(second.begin(Draft("D", offerToResolve = offer), "second") is BeginAttemptResult.Started)
            assertEquals("D", store.saved[key]?.body)
            assertTrue(store.loadOffers(store.activeOwner, key).isEmpty())
            assertEquals(AttemptStatus.IN_PROGRESS, store.entries[key]?.attempt?.status)
        }

    @Test fun `a durable offer can be resolved when C was deleted before reopening`() = runBlocking {
        val store = VersionedEditorDraftStore()
        store.preload(key, Draft("D"))
        val first = EditorDraftSession(store, key)
        first.load()
        first.save(Draft("C"))
        store.entries.remove(key)
        val reopened = EditorDraftSession(store, key)
        assertNull(reopened.load())
        val offer = reopened.offers.single()
        assertTrue(reopened.resolveOffer(offer, Draft("D")) is StoreWriteResult.Written)
        assertEquals("D", store.saved[key]?.body)
        assertTrue(store.loadOffers(store.activeOwner, key).isEmpty())
        assertTrue(reopened.save(Draft("typing")) is StoreWriteResult.Written)
    }

    @Test fun `offer read IOException keeps the loaded revision writable and protects D`() = runBlocking {
        val store = VersionedEditorDraftStore()
        store.preload(key, Draft("D"))
        store.saveIfVersion(store.activeOwner, key, Draft("D", protectCurrent = false), 0)
        store.loadOffersFailure = IOException("disk read")
        val editor = EditorDraftSession(store, key)
        val failure = runCatching { editor.load() }.exceptionOrNull()
        assertTrue(failure is IOException)
        store.loadOffersFailure = null
        assertTrue(editor.save(Draft("C")) is StoreWriteResult.Written)
        assertEquals("C", store.saved[key]?.body)
        assertEquals(listOf("D"), store.loadOffers(store.activeOwner, key).map { it.draft.body })
    }

    @Test fun `a raw writer also protects the old reply before its first replacement`() = runBlocking {
        val store = VersionedEditorDraftStore()
        store.preload(key, Draft("D"))
        assertTrue(store.saveIfVersion("tester", key, Draft("C"), 0) is StoreWriteResult.Written)
        assertEquals(listOf("D"), store.loadOffers("tester", key).map { it.draft.body })
    }

    @Test fun `a title typed over a blank body keeps the offered new topic draft aside - ED121`() = runBlocking {
        val topic = EditorDraftKey.newTopic(23)
        val store = VersionedEditorDraftStore()
        store.preload(topic, Draft("D body", subject = "D title", updatedAt = 1234))
        val editor = EditorDraftSession(store, topic)
        editor.load()
        val offered = editor.availableOffers.single()
        assertEquals(Draft("D body", subject = "D title", updatedAt = 1234), offered.draft)

        assertTrue(editor.save(Draft("", subject = "typed title")) is StoreWriteResult.Written)

        assertEquals("typed title", store.saved[topic]?.subject)
        assertEquals("", store.saved[topic]?.body)
        assertEquals(listOf(offered), store.loadOffers(store.activeOwner, topic))
        assertEquals(listOf(offered), editor.availableOffers)
        // A new opening lists the title-only C and the older D; neither replaced the other.
        val reopened = EditorDraftSession(store, topic)
        reopened.load()
        assertEquals(setOf("typed title", "D title"), reopened.availableOffers.map { it.draft.subject }.toSet())
    }

    @Test fun `a body typed on a new topic no longer overwrites the offered draft - ED121`() = runBlocking {
        val topic = EditorDraftKey.newTopic(23)
        val store = VersionedEditorDraftStore()
        store.preload(topic, Draft("D body", subject = "D title"))
        val editor = EditorDraftSession(store, topic)
        editor.load()

        assertTrue(editor.save(Draft("C body")) is StoreWriteResult.Written)
        assertTrue(editor.save(Draft("C body, continued")) is StoreWriteResult.Written)

        assertEquals("C body, continued", store.saved[topic]?.body)
        assertEquals(listOf("D body"), store.loadOffers(store.activeOwner, topic).map { it.draft.body })
    }

    @Test fun `restoring a new topic offer consumes it with the write of C - ED121`() = runBlocking {
        val topic = EditorDraftKey.newTopic(23)
        val store = VersionedEditorDraftStore()
        store.preload(topic, Draft("D body", subject = "D title"))
        val editor = EditorDraftSession(store, topic)
        editor.load()
        val offered = editor.availableOffers.single()
        editor.save(Draft("", subject = "typed title"))

        val merged = Draft("D body", subject = "typed title", offerToResolve = editor.available(offered))
        assertTrue(editor.save(merged) is StoreWriteResult.Written)

        assertTrue(store.loadOffers(store.activeOwner, topic).isEmpty())
        assertTrue(editor.availableOffers.isEmpty())
        assertNull(editor.available(offered))
        assertEquals("D body", store.saved[topic]?.body)
        assertEquals("typed title", store.saved[topic]?.subject)
    }

    @Test fun `ignoring a new topic offer keeps the typed title - ED121`() = runBlocking {
        val topic = EditorDraftKey.newTopic(23)
        val store = VersionedEditorDraftStore()
        store.preload(topic, Draft("D body", subject = "D title"))
        val editor = EditorDraftSession(store, topic)
        editor.load()
        val offered = editor.availableOffers.single()

        assertTrue(editor.resolveOffer(offered, Draft("", subject = "typed title")) is StoreWriteResult.Written)

        assertEquals("typed title", store.saved[topic]?.subject)
        assertTrue(store.loadOffers(store.activeOwner, topic).isEmpty())
        assertTrue(editor.availableOffers.isEmpty())
    }

    @Test fun `a title alone is offered on a new topic and a blank row never is - ED121`() = runBlocking {
        val topic = EditorDraftKey.newTopic(23)
        val store = VersionedEditorDraftStore()
        store.preload(topic, Draft("  ", subject = "title only"))
        val titled = EditorDraftSession(store, topic)
        titled.load()
        assertEquals(listOf("title only"), titled.availableOffers.map { it.draft.subject })

        store.preload(topic, Draft("  ", subject = " "))
        val blank = EditorDraftSession(store, topic)
        blank.load()
        assertTrue(blank.availableOffers.isEmpty())
        assertTrue(blank.save(Draft("C")) is StoreWriteResult.Written)
        assertTrue(store.loadOffers(store.activeOwner, topic).isEmpty())
    }

    @Test fun `a new private message reads its recipients through the surface - ED121`() = runBlocking {
        val compose = EditorDraftKey.mpCompose()
        val emptyEnvelope = "\u0000redface-recipients-v1:0:"
        val store = VersionedEditorDraftStore()
        store.preload(compose, Draft("", recipients = emptyEnvelope, isPrivate = true))
        // The raw value is not blank: only the surface that owns the encoding knows it names nobody.
        val untouched = EditorDraftSession(store, compose) { false }
        untouched.load()
        assertTrue(untouched.availableOffers.isEmpty())

        store.preload(compose, Draft("", recipients = "alice", isPrivate = true))
        val routed = EditorDraftSession(store, compose) { it.recipients == "alice" }
        routed.load()
        val offered = routed.availableOffers.single()
        assertTrue(routed.save(Draft("", subject = "typed subject", recipients = "bob", isPrivate = true))
            is StoreWriteResult.Written)
        assertEquals(listOf(offered), store.loadOffers(store.activeOwner, compose))
        assertEquals("bob", store.saved[compose]?.recipients)
    }

    @Test fun `a certified resume of a new topic is C and is not copied aside - ED121`() = runBlocking {
        val topic = EditorDraftKey.newTopic(23)
        val store = VersionedEditorDraftStore()
        store.preload(topic, Draft("C body", subject = "C title"))
        val editor = EditorDraftSession(store, topic)
        editor.load()
        editor.resumeContent()

        assertTrue(editor.availableOffers.isEmpty())
        assertTrue(editor.save(Draft("C body, continued", subject = "C title")) is StoreWriteResult.Written)
        assertTrue(store.loadOffers(store.activeOwner, topic).isEmpty())
    }

    @Test fun `editing a message keeps its single row outside the offered drafts - ED121 scope`() = runBlocking {
        val store = VersionedEditorDraftStore()
        for (edit in listOf(EditorDraftKey.editFirstPost(23, 100), EditorDraftKey.editPost(23, 100))) {
            store.preload(edit, Draft("D body", subject = "D title"))
            val editor = EditorDraftSession(store, edit)
            editor.load()
            assertTrue(editor.availableOffers.isEmpty())
            assertTrue(editor.save(Draft("C body")) is StoreWriteResult.Written)
            assertTrue(store.loadOffers(store.activeOwner, edit).isEmpty())
        }
    }
}
