package fr.forumhfr.redface2.core.data.editor

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.database.dao.EditorDraftDao
import fr.forumhfr.redface2.core.database.entities.EditorDraftEntity
import fr.forumhfr.redface2.core.database.entities.EditorDraftOfferEntity
import fr.forumhfr.redface2.core.domain.editor.insertEditorQuotes
import fr.forumhfr.redface2.core.domain.editor.mergeEditorBody
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.editor.EditorDraftSession
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.BeginAttemptResult
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import fr.forumhfr.redface2.core.domain.editor.PendingInlineQuoteRequest
import fr.forumhfr.redface2.core.model.AuthState
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RoomEditorDraftOfferTest {
    private lateinit var database: RedfaceDatabase
    private val auth = MutableStateFlow<AuthState>(AuthState.Authenticated("Alice"))
    private var now = 1234L
    private val q = PendingInlineQuoteRequest(23, 35421, listOf(PendingInlineQuoteRequest.Quote(101, 1)))
    private val key = q.key

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            RedfaceDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() { database.close() }

    @Test fun `typing before GET protects D with its original date and keeps multiple versions`() = runTest {
        val store = store()
        store.saveIfVersion("alice", key, Draft("D"), 0)
        now = 5000
        val editor = EditorDraftSession(store(), key)
        editor.load()
        editor.enqueueInlineQuoteRequest(q)
        assertEquals(1234L, store.loadEntry("alice", key)?.draft?.updatedAt)
        editor.save(Draft("typed before GET"))
        val reopened = EditorDraftSession(store(), key)
        reopened.load()
        reopened.save(Draft("second C"))
        val offers = store.loadOffers("alice", key)
        assertEquals(listOf("typed before GET", "D"), offers.map { it.draft.body })
        assertEquals(listOf(5000L, 1234L), offers.map { it.draft.updatedAt })
        assertEquals("second C", store.load("alice", key)?.body)
        database.editorDraftDao().deleteOlderThan(3000)
        assertEquals(listOf("typed before GET"), store.loadOffers("alice", key).map { it.draft.body })
        assertEquals("second C", store.load("alice", key)?.body)
    }

    @Test fun `raw and legacy reply writes protect D even without a loaded editor session`() = runTest {
        for (legacy in listOf(false, true)) {
            val rawKey = "reply:23:${if (legacy) 43 else 42}"
            val store = store()
            store.saveIfVersion("alice", rawKey, Draft("D"), 0)
            if (legacy) store.save("alice", rawKey, Draft("C"))
            else assertTrue(store.saveIfVersion("alice", rawKey, Draft("C"), 1) is StoreWriteResult.Written)
            assertEquals(listOf("D"), store.loadOffers("alice", rawKey).map { it.draft.body })
            assertEquals("C", store.load("alice", rawKey)?.body)
        }
    }

    @Test fun `failed C write rolls back the offer copy and concurrent CAS cannot copy the loser`() = runTest {
        val store = store()
        store.saveIfVersion("alice", key, Draft("D"), 0)
        val editor = EditorDraftSession(store, key)
        editor.load()
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_C BEFORE UPDATE ON editor_drafts
            BEGIN SELECT RAISE(ABORT, 'disk failure'); END""")
        assertTrue(editor.save(Draft("C")) is StoreWriteResult.Failed)
        assertTrue(store.loadOffers("alice", key).isEmpty())
        assertEquals("D", store.load("alice", key)?.body)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_C")
        val other = EditorDraftSession(store(), key)
        other.load()
        val results = listOf(async { editor.save(Draft("C1")) }, async { other.save(Draft("C2")) }).awaitAll()
        assertEquals(1, results.count { it is StoreWriteResult.Written })
        assertEquals(1, results.count { it == StoreWriteResult.Conflict })
        assertEquals(listOf("D"), store.loadOffers("alice", key).map { it.draft.body })
    }

    @Test fun `restore commits C and consumes only the selected D atomically before return`() = runTest {
        val store = store()
        store.saveIfVersion("alice", key, Draft("D"), 0)
        val editor = EditorDraftSession(store, key)
        editor.load()
        editor.save(Draft("C"))
        val offer = store.loadOffers("alice", key).single()
        assertTrue(editor.resolveOffer(offer, Draft("D\n\nC")) is StoreWriteResult.Written)
        assertTrue(store().loadOffers("alice", key).isEmpty())
        assertEquals("D\n\nC", store().load("alice", key)?.body)
        assertEquals(StoreWriteResult.Conflict, editor.resolveOffer(offer, Draft("D\n\nD\n\nC")))
    }

    @Test fun `autosave winning the restore race consumes D with C in the Room transaction`() = runTest {
        val store = store()
        store.saveIfVersion("alice", key, Draft("D"), 0)
        val editor = EditorDraftSession(store, key)
        editor.load()
        editor.save(Draft("typed"))
        val offer = store.loadOffers("alice", key).single()
        val merge = mergeEditorBody("D", "typed")
        val snapshot = Draft(merge.body, bodyProvenance = merge.provenance, offerToResolve = offer)
        assertTrue(editor.save(snapshot) is StoreWriteResult.Written)
        assertTrue(store().loadOffers("alice", key).isEmpty())
        assertEquals(merge.body, store().load("alice", key)?.body)
        assertEquals(merge.provenance, store().load("alice", key)?.bodyProvenance)
        assertTrue(editor.save(snapshot.copy(body = merge.body + " more")) is StoreWriteResult.Written)
        assertTrue(store().loadOffers("alice", key).isEmpty())
    }

    @Test fun `sending C keeps D outside the published body filter and its retention date`() = runTest {
        val store = store()
        store.saveIfVersion("alice", key, Draft("D"), 0)
        val editor = EditorDraftSession(store, key)
        editor.load()
        val token = (editor.begin(Draft("C"), "send") as BeginAttemptResult.Started).token
        editor.finish(token, AttemptOutcome.PUBLISHED)
        editor.erase(token)
        assertNull(store.load("alice", key))
        assertEquals(listOf("D"), store.loadOffers("alice", key).map { it.draft.body })
        database.editorDraftDao().deleteOlderThan(1235)
        assertTrue(store.loadOffers("alice", key).isEmpty())
    }

    @Test fun `MP request is private at creation and its offers are purged with its account`() = runTest {
        val store = store()
        val mp = PendingInlineQuoteRequest.privateMessage(42, listOf(PendingInlineQuoteRequest.Quote(101, 1, 0)))
        store.enqueueInlineQuoteRequest("alice", mp.key, mp, 0)
        assertTrue(checkNotNull(store.loadEntry("alice", mp.key)).draft.isPrivate)
        val editor = EditorDraftSession(store, mp.key)
        editor.load()
        editor.save(Draft("D", isPrivate = true))
        val next = EditorDraftSession(store, mp.key)
        next.load()
        next.save(Draft("C", isPrivate = true))
        assertEquals("D", store.loadOffers("alice", mp.key).single().draft.body)
        auth.value = AuthState.Authenticated("Bob")
        assertTrue(store.loadOffers("alice", mp.key).isEmpty())
        store.purgePrivateForUser("alice")
        auth.value = AuthState.Authenticated("Alice")
        assertTrue(store.loadOffers("alice", mp.key).isEmpty())
        assertNull(store.loadEntry("alice", mp.key))
    }

    @Test
    fun `death before delivery keeps Q and D while a committed delivery is authoritative before callback`() = runTest {
        val store = store()
        store.saveIfVersion("alice", key, Draft("D"), 0)
        val opening = EditorDraftSession(store, key)
        opening.load()
        opening.enqueueInlineQuoteRequest(q)
        val before = EditorDraftSession(store(), key)
        before.load()
        assertEquals(q, before.pendingInlineQuoteRequest)
        assertEquals("D", before.offers.single().draft.body)
        val committed = CompletableDeferred<Unit>()
        val returnGate = CompletableDeferred<Unit>()
        val delegate = database.editorDraftDao()
        val paused = object : EditorDraftDao by delegate {
            override suspend fun mutateInlineQuoteRequest(
                draft: EditorDraftEntity, expected: EditorDraftEntity?, offer: EditorDraftOfferEntity?,
            ): Boolean = delegate.mutateInlineQuoteRequest(draft, expected, offer).also {
                committed.complete(Unit)
                returnGate.await()
            }
        }
        val editor = EditorDraftSession(store(paused), key)
        editor.load()
        val quote = "[quotemsg=101]one[/quotemsg]"
        val delivery = async { editor.consumeInlineQuoteRequest(q, Draft(quote + "\n"), deliveredBody = quote) }
        committed.await()
        try {
            val after = EditorDraftSession(store(), key)
            val durable = checkNotNull(after.load())
            assertNull(after.pendingInlineQuoteRequest)
            assertEquals(quote + "\n", durable.draft.body)
            assertTrue(checkNotNull(durable.draft.bodyProvenance).isQuoteOnly(durable.draft.body))
            assertEquals(listOf("D"), after.offers.map { it.draft.body })
        } finally {
            returnGate.complete(Unit)
        }
        assertTrue(delivery.await() is StoreWriteResult.Written)
        val reopened = EditorDraftSession(store(), key)
        reopened.load()
        reopened.save(Draft("typed next time"))
        assertEquals(listOf("D"), store.loadOffers("alice", key).map { it.draft.body })
    }

    @Test fun `death after restore before GET keeps the durable D quote typing boundary`() = runTest {
        val store = store()
        store.saveIfVersion("alice", key, Draft("D"), 0)
        val editor = EditorDraftSession(store, key)
        editor.load()
        editor.enqueueInlineQuoteRequest(q)
        editor.save(Draft("typed during GET"))
        val offer = store.loadOffers("alice", key).single()
        val merge = mergeEditorBody(offer.draft.body, "typed during GET")
        editor.resolveOffer(offer, Draft(merge.body, bodyProvenance = merge.provenance))
        val afterDeath = EditorDraftSession(store(), key)
        val durable = checkNotNull(afterDeath.load()).draft
        afterDeath.resumeContent() // Certified ED-81 resume, not a new offer of C.
        val quote = "[quotemsg=101]one[/quotemsg]"
        val insertion = insertEditorQuotes(durable.body, quote, checkNotNull(durable.bodyProvenance))
        assertEquals("D\n\n$quote\n\ntyped during GET", insertion.body)
        assertTrue(afterDeath.consumeInlineQuoteRequest(q, Draft(insertion.body, bodyProvenance = insertion.provenance),
            deliveredBody = quote) is StoreWriteResult.Written)
        assertTrue(store.loadOffers("alice", key).isEmpty())
        assertNull(store.loadEntry("alice", key)?.draft?.pendingInlineQuoteRequest)
    }

    @Test
    fun `failed offer consumption rolls back C and a committed consumption needs no callback to survive`() = runTest {
        val store = store()
        store.saveIfVersion("alice", key, Draft("D"), 0)
        val editor = EditorDraftSession(store, key)
        editor.load()
        editor.save(Draft("C"))
        val offer = store.loadOffers("alice", key).single()
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_offer_delete BEFORE DELETE ON editor_draft_offers
            BEGIN SELECT RAISE(ABORT, 'disk failure'); END""")
        assertTrue(editor.resolveOffer(offer, Draft("D\n\nC")) is StoreWriteResult.Failed)
        assertEquals("C", store.load("alice", key)?.body)
        assertEquals(listOf(offer), store.loadOffers("alice", key))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_offer_delete")
        val committed = CompletableDeferred<Unit>()
        val returnGate = CompletableDeferred<Unit>()
        val delegate = database.editorDraftDao()
        val paused = object : EditorDraftDao by delegate {
            override suspend fun resolveOffer(
                draft: EditorDraftEntity, expectedVersion: Long, offer: EditorDraftOfferEntity,
                protection: EditorDraftOfferEntity?,
            ): Boolean = delegate.resolveOffer(draft, expectedVersion, offer, protection).also {
                committed.complete(Unit)
                returnGate.await()
            }
        }
        val restoring = EditorDraftSession(store(paused), key)
        restoring.load()
        restoring.resumeContent()
        val restore = async { restoring.resolveOffer(offer, Draft("D\n\nC")) }
        committed.await()
        try {
            assertTrue(store().loadOffers("alice", key).isEmpty())
            assertEquals("D\n\nC", store().load("alice", key)?.body)
        } finally {
            returnGate.complete(Unit)
        }
        assertTrue(restore.await() is StoreWriteResult.Written)
    }

    @Test fun `resolve D after sending C accepts missing published and unresolved rows atomically`() = runTest {
        val states = listOf(null, AttemptStatus.PUBLISHED, AttemptStatus.IN_PROGRESS, AttemptStatus.UNCERTAIN)
        for ((index, status) in states.withIndex()) {
            for (restoring in listOf(false, true)) {
                val topic = 100 + index * 2 + if (restoring) 1 else 0
                val request = PendingInlineQuoteRequest(23, topic, q.quotes)
                val caseKey = request.key
                val store = store()
                store.saveIfVersion("alice", caseKey, Draft("D", updatedAt = 1234), 0)
                val sending = EditorDraftSession(store, caseKey)
                sending.load()
                sending.enqueueInlineQuoteRequest(request)
                sending.save(Draft("C"))
                if (status == null) database.editorDraftDao().deleteByKey("alice|$caseKey") else {
                    val token = (sending.begin(Draft("C"), "send") as BeginAttemptResult.Started).token
                    if (status != AttemptStatus.IN_PROGRESS) {
                        sending.finish(token, if (status == AttemptStatus.PUBLISHED) AttemptOutcome.PUBLISHED
                            else AttemptOutcome.UNCERTAIN)
                    }
                    if (status == AttemptStatus.PUBLISHED) sending.erase(token)
                }
                val editor = EditorDraftSession(store(), caseKey)
                val before = editor.load()
                val offer = editor.offers.single()
                val current = store.load("alice", caseKey)?.body.orEmpty()
                val draft = Draft(if (restoring) "D\n\n$current" else current)
                val version = before?.version ?: 0L
                assertEquals(StoreWriteResult.Conflict,
                    store.resolveOffer("alice", caseKey, offer, version - 1, draft))
                assertEquals(before, store.loadEntry("alice", caseKey))
                assertEquals(listOf(offer), store.loadOffers("alice", caseKey))
                assertTrue(editor.resolveOffer(offer, draft) is StoreWriteResult.Written)
                val after = checkNotNull(store().loadEntry("alice", caseKey))
                assertEquals(version + 1, after.version)
                assertEquals(draft.body, after.draft.body)
                assertEquals(before?.draft?.pendingInlineQuoteRequest, after.draft.pendingInlineQuoteRequest)
                assertTrue(store().loadOffers("alice", caseKey).isEmpty())
                if (status in listOf(AttemptStatus.IN_PROGRESS, AttemptStatus.UNCERTAIN)) {
                    assertEquals(before?.attempt?.token, after.attempt?.token)
                    assertEquals(AttemptStatus.UNCERTAIN, after.attempt?.status)
                } else assertNull(after.attempt)
                assertEquals(StoreWriteResult.Conflict, editor.resolveOffer(offer, draft))
                assertTrue(editor.save(Draft("later typing")) is StoreWriteResult.Written)
            }
        }
    }

    @Test fun `ignoring Q protects a newly appeared D before the metadata revision changes`() = runTest {
        val store = store()
        store.enqueueInlineQuoteRequest("alice", key, q, 0)
        // D appeared after enqueue (typing while GET fails); the next opening offers this revision.
        val row = checkNotNull(database.editorDraftDao().get("alice|$key"))
        database.editorDraftDao().upsert(row.copy(body = "D", updatedAt = 1234))
        val editor = EditorDraftSession(store, key)
        editor.load()
        val offer = checkNotNull(editor.offered("D"))
        assertTrue(editor.consumeInlineQuoteRequest(q) is StoreWriteResult.Written)
        assertEquals(listOf(offer), store.loadOffers("alice", key))
        assertEquals(1234L, store.loadEntry("alice", key)?.draft?.updatedAt)
        assertTrue(editor.save(Draft("C")) is StoreWriteResult.Written)
        assertEquals(listOf(offer), store.loadOffers("alice", key))
        assertTrue(editor.resolveOffer(offer, Draft("D\n\nC")) is StoreWriteResult.Written)
        assertEquals("D\n\nC", store.load("alice", key)?.body)
        assertTrue(store.loadOffers("alice", key).isEmpty())
    }

    @Test fun `invalid optional provenance never blocks reading writing or enqueueing C`() = runTest {
        val invalid = listOf("not json",
            """{"version":1,"draftEnd":21,"quotesEnd":4,"deliveries":[]}""",
            """{"version":99,"draftEnd":0,"quotesEnd":0,"deliveries":[]}""")
        for ((index, raw) in invalid.withIndex()) {
            val request = PendingInlineQuoteRequest(23, 700 + index, q.quotes)
            val caseKey = request.key
            val store = store()
            database.editorDraftDao().upsert(EditorDraftEntity("alice|$caseKey", "alice", "C", "subject", null,
                1234, false, bodyProvenance = raw))
            val loaded = checkNotNull(store.loadEntry("alice", caseKey))
            assertEquals("C", loaded.draft.body)
            assertNull(loaded.draft.bodyProvenance)
            // Both paths used to decode the corrupt source before writing.
            assertTrue(store.enqueueInlineQuoteRequest("alice", caseKey, request, 0) is StoreWriteResult.Written)
            assertTrue(store.saveIfVersion("alice", caseKey, Draft("C edited", subject = "subject"), 1)
                is StoreWriteResult.Written)
            assertEquals("C edited", store.load("alice", caseKey)?.body)
            assertNull(store.load("alice", caseKey)?.bodyProvenance)
            assertEquals(listOf("C"), store.loadOffers("alice", caseKey).map { it.draft.body })
        }
    }

    private fun store(dao: EditorDraftDao = database.editorDraftDao()) = RoomEditorDraftStore(
        mockk<AuthRepository> { every { observeAuthState() } returns auth }, dao,
        Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC), Dispatchers.IO,
    )
}
