package fr.forumhfr.redface2.core.data.editor

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.database.dao.EditorDraftDao
import fr.forumhfr.redface2.core.database.entities.EditorDraftEntity
import fr.forumhfr.redface2.core.database.entities.EditorDraftOfferEntity
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.editor.EditorBodyProvenance
import fr.forumhfr.redface2.core.domain.editor.EditorDraftSession
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

/** ED-121 w7: quote-only C is discarded durably, independently of the UI and of offered D. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RoomEditorQuoteOnlyTest {
    private lateinit var database: RedfaceDatabase
    private val auth = MutableStateFlow<AuthState>(AuthState.Authenticated("Alice"))
    private val quoteA = "[quotemsg=101]one[/quotemsg]"
    private val quoteB = "[quotemsg=202]two[/quotemsg]"
    private val requests = listOf(
        PendingInlineQuoteRequest(23, 35421, listOf(PendingInlineQuoteRequest.Quote(101, 1))),
        PendingInlineQuoteRequest.privateMessage(42, listOf(PendingInlineQuoteRequest.Quote(101, 1, 0))),
    )

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            RedfaceDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() { database.close() }

    @Test fun `discard commits before callback with an empty revision and D unchanged`() = runTest {
        for (request in requests) {
            val store = store()
            seedQuoteOnly(request)
            val before = checkNotNull(store.loadEntry("alice", request.key))
            val offers = store.loadOffers("alice", request.key)
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
            val fresh = EditorDraftSession(store(paused), request.key)
            fresh.load()
            val discard = async { fresh.discardQuoteOnly() }
            committed.await()
            try {
                val afterDeath = checkNotNull(store().loadEntry("alice", request.key))
                assertEquals("", afterDeath.draft.body)
                assertNull(afterDeath.draft.bodyProvenance)
                assertNull(afterDeath.draft.pendingInlineQuoteRequest)
                assertEquals(before.version + 1, afterDeath.version)
                assertEquals(offers, store().loadOffers("alice", request.key))
                assertEquals(StoreWriteResult.Conflict,
                    store.saveIfVersion("alice", request.key, before.draft, before.version))
            } finally {
                returnGate.complete(Unit)
            }
            assertTrue(discard.await() is StoreWriteResult.Written)
            assertNull(fresh.bodyProvenance)
        }
    }

    @Test fun `new Cite B atomically replaces A and only B survives delivery and another death`() = runTest {
        for (request in requests) {
            val store = store()
            seedQuoteOnly(request)
            val before = checkNotNull(store.loadEntry("alice", request.key))
            val offers = store.loadOffers("alice", request.key)
            val b = if (request.isPrivate) PendingInlineQuoteRequest.privateMessage(42,
                listOf(PendingInlineQuoteRequest.Quote(202, 1, 0))) else PendingInlineQuoteRequest(23, 35421,
                listOf(PendingInlineQuoteRequest.Quote(202, 1)))
            assertTrue(store.enqueueInlineQuoteRequest("alice", request.key, b, before.version)
                is StoreWriteResult.Written)
            val afterAction = checkNotNull(store().loadEntry("alice", request.key))
            assertEquals("", afterAction.draft.body)
            assertNull(afterAction.draft.bodyProvenance)
            assertEquals(b, afterAction.draft.pendingInlineQuoteRequest)
            val afterDeath = EditorDraftSession(store(), request.key)
            afterDeath.load()
            assertTrue(afterDeath.consumeInlineQuoteRequest(b, Draft(quoteB, isPrivate = request.isPrivate),
                deliveredBody = quoteB) is StoreWriteResult.Written)
            val delivered = checkNotNull(store().loadEntry("alice", request.key)).draft
            assertEquals(quoteB, delivered.body)
            assertEquals(listOf(quoteB), delivered.bodyProvenance?.deliveries?.map { it.body })
            assertNull(delivered.pendingInlineQuoteRequest)
            assertEquals(offers, store.loadOffers("alice", request.key))
        }
    }

    @Test fun `discard and new Cite roll back entirely on disk failure and reject stale owners and versions`() = runTest {
        val request = requests.first()
        val store = store()
        seedQuoteOnly(request)
        val before = checkNotNull(store.loadEntry("alice", request.key))
        val offers = store.loadOffers("alice", request.key)
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_quote_discard BEFORE UPDATE ON editor_drafts
            BEGIN SELECT RAISE(ABORT, 'disk failure'); END""")
        assertTrue(store.discardQuoteOnly("alice", request.key, before.version) is StoreWriteResult.Failed)
        assertTrue(store.enqueueInlineQuoteRequest("alice", request.key, request, before.version) is StoreWriteResult.Failed)
        assertEquals(before, store.loadEntry("alice", request.key))
        assertEquals(offers, store.loadOffers("alice", request.key))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_quote_discard")
        assertEquals(StoreWriteResult.Conflict, store.discardQuoteOnly("alice", request.key, before.version - 1))
        auth.value = AuthState.Authenticated("Bob")
        assertEquals(StoreWriteResult.OwnerChanged, store.discardQuoteOnly("alice", request.key, before.version))
        auth.value = AuthState.Authenticated("Alice")
        assertEquals(before, store.loadEntry("alice", request.key))
    }

    @Test fun `edited reordered or incomplete batches are retained and attempts cannot be discarded`() = runTest {
        val request = requests.first()
        val store = store()
        val batch = "$quoteA\n\n$quoteB"
        val proof = EditorBodyProvenance().delivered(request.scope, batch)
        val bodies = listOf(batch + "typed", batch.replace("one", "edited"), "$quoteB\n\n$quoteA", quoteA)
        for ((index, body) in bodies.withIndex()) {
            val key = "reply:23:${100 + index}"
            store.saveIfVersion("alice", key, Draft(body, bodyProvenance = proof), 0)
            assertEquals(StoreWriteResult.Conflict, store.discardQuoteOnly("alice", key, 1))
            assertEquals(body, store.loadEntry("alice", key)?.draft?.body)
        }
        for (outcome in listOf(AttemptOutcome.UNCERTAIN, AttemptOutcome.PUBLISHED)) {
            val key = "reply:23:${if (outcome == AttemptOutcome.UNCERTAIN) 200 else 201}"
            val draft = Draft(batch, bodyProvenance = proof)
            val token = (store.beginAttempt("alice", key, draft, 0) as BeginAttemptResult.Started).token
            store.finishAttempt(token, outcome)
            val before = store.loadEntry("alice", key)
            assertEquals(StoreWriteResult.Conflict, store.discardQuoteOnly("alice", key, token.version))
            assertEquals(before, store.loadEntry("alice", key))
        }
    }

    private suspend fun seedQuoteOnly(request: PendingInlineQuoteRequest) {
        val store = store()
        store.saveIfVersion("alice", request.key, Draft("D", isPrivate = request.isPrivate), 0)
        val session = EditorDraftSession(store, request.key)
        session.load()
        session.enqueueInlineQuoteRequest(request)
        session.consumeInlineQuoteRequest(request, Draft(quoteA + "\n  ", isPrivate = request.isPrivate), deliveredBody = quoteA)
    }

    private fun store(dao: EditorDraftDao = database.editorDraftDao()) = RoomEditorDraftStore(
        mockk<AuthRepository> { every { observeAuthState() } returns auth }, dao,
        Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC), Dispatchers.IO,
    )
}
