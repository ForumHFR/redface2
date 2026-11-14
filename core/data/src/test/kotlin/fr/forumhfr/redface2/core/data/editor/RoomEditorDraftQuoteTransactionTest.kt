package fr.forumhfr.redface2.core.data.editor

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.editor.EditorDraftSession
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.BeginAttemptResult
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import fr.forumhfr.redface2.core.domain.editor.PendingInlineQuoteRequest
import fr.forumhfr.redface2.core.domain.editor.UnreadableInlineQuoteRequestException
import fr.forumhfr.redface2.core.model.AuthState
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1553: body/request durability, account isolation and metadata-only outcomes on real Room. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RoomEditorDraftQuoteTransactionTest {
    private lateinit var database: RedfaceDatabase
    private lateinit var store: RoomEditorDraftStore
    private val auth = MutableStateFlow<AuthState>(AuthState.Authenticated("Alice"))
    private val request = request()
    private val key = request.key
    private val clock = Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), RedfaceDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = newStore()
    }

    @After
    fun tearDown() { database.close() }

    @Test
    fun `entry creates an empty row and merges without touching existing fields`() = runTest {
        assertEquals(StoreWriteResult.Written(1), store.enqueueInlineQuoteRequest("alice", key, request, 0))
        val empty = checkNotNull(store.loadEntry("alice", key))
        assertEquals(Draft("", updatedAt = 1234, pendingInlineQuoteRequest = request), empty.draft)
        val offered = empty.draft.copy(body = "offered", subject = "subject", recipients = "recipient")
        store.saveIfVersion("alice", key, offered, 1)
        val second = PendingInlineQuoteRequest(23, 35421,
            listOf(PendingInlineQuoteRequest.Quote(101, 4), PendingInlineQuoteRequest.Quote(202, 2)))
        assertEquals(StoreWriteResult.Written(3), store.enqueueInlineQuoteRequest("alice", key, second, 2))
        assertEquals(
            offered.copy(pendingInlineQuoteRequest = request.merge(second)), store.loadEntry("alice", key)?.draft,
        )
        assertEquals(1234L, database.editorDraftDao().get("alice|$key")?.updatedAt)
    }

    @Test
    fun `two stores racing to create the row have only one successful CAS`() = runTest {
        val results = listOf(store, newStore()).map { writer ->
            async(Dispatchers.Default) { writer.enqueueInlineQuoteRequest("alice", key, request, 0) }
        }.awaitAll()
        assertEquals(1, results.count { it == StoreWriteResult.Written(1) })
        assertEquals(1, results.count { it == StoreWriteResult.Conflict })
        assertEquals(request, store.loadEntry("alice", key)?.draft?.pendingInlineQuoteRequest)
    }

    @Test
    fun `entry and ignore preserve exact unresolved identity state and independent content version`() = runTest {
        for ((index, status) in listOf(AttemptStatus.IN_PROGRESS, AttemptStatus.UNCERTAIN).withIndex()) {
            val scoped = request(topicId = 35421 + index)
            val token = (store.beginAttempt("alice", scoped.key, Draft("uncertain body", "subject"), 0)
                as BeginAttemptResult.Started).token
            if (status == AttemptStatus.UNCERTAIN) store.finishAttempt(token, AttemptOutcome.UNCERTAIN)
            val original = checkNotNull(store.loadEntry("alice", scoped.key))
            assertEquals(StoreWriteResult.Written(2), store.enqueueInlineQuoteRequest("alice", scoped.key, scoped, 1))
            val dormant = checkNotNull(store.loadEntry("alice", scoped.key))
            assertEquals(original.attempt, dormant.attempt)
            assertEquals(original.draft.copy(pendingInlineQuoteRequest = scoped), dormant.draft)
            assertEquals(StoreWriteResult.Conflict,
                store.consumeInlineQuoteRequest("alice", scoped.key, scoped, 2, Draft("unsafe delivery")))
            assertEquals(StoreWriteResult.Written(3), store.consumeInlineQuoteRequest("alice", scoped.key, scoped, 2))
            val ignored = checkNotNull(store.loadEntry("alice", scoped.key))
            assertEquals(original.attempt, ignored.attempt)
            assertEquals(original.draft, ignored.draft)
            assertEquals(StoreWriteResult.Written(3), store.finishAttempt(token, AttemptOutcome.CERTAIN_FAILURE))
        }
    }

    @Test
    fun `delivery and ignoring the offered body are distinct atomic writes`() = runTest {
        val offered = Draft("old body", "subject", "recipient", pendingInlineQuoteRequest = request)
        store.saveIfVersion("alice", key, offered, 0)
        // Ignoring the offered body still carries the session-owned request.
        store.saveIfVersion("alice", key, Draft("", pendingInlineQuoteRequest = request), 1)
        assertEquals(request, store.loadEntry("alice", key)?.draft?.pendingInlineQuoteRequest)
        assertEquals(StoreWriteResult.Written(3),
            store.consumeInlineQuoteRequest("alice", key, request, 2, Draft("fresh typing and quote")))
        val delivered = checkNotNull(newStore().loadEntry("alice", key))
        assertEquals(Draft("fresh typing and quote", updatedAt = 1234), delivered.draft)
        assertEquals(StoreWriteResult.Conflict, store.consumeInlineQuoteRequest("alice", key, request, 2, offered))
        assertEquals(StoreWriteResult.Conflict, store.saveIfVersion("alice", key, offered, 2))
        assertEquals(delivered, store.loadEntry("alice", key))
    }

    @Test
    fun `failed delivery rolls back both body and request removal`() = runTest {
        store.saveIfVersion("alice", key, Draft("before", pendingInlineQuoteRequest = request), 0)
        val before = store.loadEntry("alice", key)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_quote_delivery AFTER UPDATE ON editor_drafts
            WHEN NEW.pendingInlineQuoteRequest IS NULL BEGIN SELECT RAISE(ABORT, 'disk failure'); END
            """.trimIndent(),
        )
        assertTrue(store.consumeInlineQuoteRequest("alice", key, request, 1, Draft("after")) is StoreWriteResult.Failed)
        assertEquals(before, store.loadEntry("alice", key))
        assertTrue(store.loadOffers("alice", key).isEmpty())
    }

    @Test
    fun `death while a delivery resolves merged D rolls back C, Q and D together - ED121 ED132`() = runTest {
        store.saveIfVersion("alice", key, Draft("D"), 0)
        assertEquals(StoreWriteResult.Written(2), store.enqueueInlineQuoteRequest("alice", key, request, 1))
        val offer = store.loadOffers("alice", key).single()
        val before = store.loadEntry("alice", key)
        val delivered = Draft("D\n\n[quotemsg=101]one[/quotemsg]", offerToResolve = offer, offerToPreserve = offer)
        // The row write succeeds, then the transaction dies before the offer is removed.
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER die_before_offer_removal BEFORE DELETE ON editor_draft_offers
            BEGIN SELECT RAISE(ABORT, 'process death'); END
            """.trimIndent(),
        )
        assertTrue(store.consumeInlineQuoteRequest("alice", key, request, 2, delivered) is StoreWriteResult.Failed)
        // Neither a durable quote with Q still pending, nor D lost: the whole write is undone.
        assertEquals(before, newStore().loadEntry("alice", key))
        assertEquals(listOf(offer), newStore().loadOffers("alice", key))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER die_before_offer_removal")
        assertEquals(StoreWriteResult.Written(3), store.consumeInlineQuoteRequest("alice", key, request, 2, delivered))
        val after = checkNotNull(newStore().loadEntry("alice", key))
        assertEquals(delivered.body, after.draft.body)
        assertNull(after.draft.pendingInlineQuoteRequest)
        assertTrue(newStore().loadOffers("alice", key).isEmpty())
        // The same D can never be consumed twice.
        assertEquals(StoreWriteResult.Conflict, store.consumeInlineQuoteRequest("alice", key, request, 3, delivered))
    }

    @Test
    fun `failed entry leaves the original draft untouched`() = runTest {
        store.saveIfVersion("alice", key, Draft("offered", "subject"), 0)
        val before = store.loadEntry("alice", key)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_quote_entry AFTER UPDATE ON editor_drafts
            WHEN NEW.pendingInlineQuoteRequest IS NOT NULL BEGIN SELECT RAISE(ABORT, 'disk failure'); END
            """.trimIndent(),
        )
        assertTrue(store.enqueueInlineQuoteRequest("alice", key, request, 1) is StoreWriteResult.Failed)
        assertEquals(before, store.loadEntry("alice", key))
        assertTrue(store.loadOffers("alice", key).isEmpty())
    }

    @Test
    fun `publication clears the request without advancing content version even when cleanup fails`() = runTest {
        val snapshot = Draft("sent body", "subject", pendingInlineQuoteRequest = request)
        val token = (store.beginAttempt("alice", key, snapshot, 0) as BeginAttemptResult.Started).token
        assertEquals(request, store.loadEntry("alice", key)?.draft?.pendingInlineQuoteRequest)
        assertEquals(StoreWriteResult.Written(1), store.finishAttempt(token, AttemptOutcome.PUBLISHED))
        val published = checkNotNull(store.loadEntry("alice", key))
        assertEquals(1L, published.version)
        assertNull(published.draft.pendingInlineQuoteRequest)
        assertNull(store.load("alice", key))
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_quote_cleanup BEFORE UPDATE ON editor_drafts
            WHEN OLD.attemptState = 'PUBLISHED' AND NEW.body = ''
            BEGIN SELECT RAISE(ABORT, 'disk failure'); END
            """.trimIndent(),
        )
        assertTrue(store.erasePublishedContent(token) is StoreWriteResult.Failed)
        assertEquals(published, store.loadEntry("alice", key))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_quote_cleanup")
        assertEquals(StoreWriteResult.Written(2), store.enqueueInlineQuoteRequest("alice", key, request, 1))
        val fresh = checkNotNull(store.loadEntry("alice", key))
        assertEquals(Draft("", updatedAt = 1234, pendingInlineQuoteRequest = request), fresh.draft)
        assertNull(fresh.attempt)
        assertEquals(StoreWriteResult.Conflict, store.erasePublishedContent(token))
    }

    @Test
    fun `entry compared before a metadata only publication cannot restore its old request`() = runTest {
        val token = (store.beginAttempt("alice", key, Draft("sent", pendingInlineQuoteRequest = request), 0)
            as BeginAttemptResult.Started).token
        val dao = database.editorDraftDao()
        val before = checkNotNull(dao.get("alice|$key"))
        store.finishAttempt(token, AttemptOutcome.PUBLISHED)
        assertFalse(dao.mutateInlineQuoteRequest(before.copy(contentVersion = 2), before))
        assertNull(dao.get("alice|$key")?.pendingInlineQuoteRequest)
        assertEquals(1L, dao.get("alice|$key")?.contentVersion)
    }

    @Test
    fun `uncertainty certain failure and unresolved editing retain the remaining request`() = runTest {
        val snapshot = Draft("body", pendingInlineQuoteRequest = request)
        val token = (store.beginAttempt("alice", key, snapshot, 0) as BeginAttemptResult.Started).token
        store.finishAttempt(token, AttemptOutcome.UNCERTAIN)
        store.saveUnresolved(token, snapshot.copy(body = "edited"), 1)
        assertEquals(request, store.loadEntry("alice", key)?.draft?.pendingInlineQuoteRequest)
        store.finishAttempt(token, AttemptOutcome.CERTAIN_FAILURE)
        assertEquals(request, store.loadEntry("alice", key)?.draft?.pendingInlineQuoteRequest)
        assertEquals(StoreWriteResult.Written(3), store.consumeInlineQuoteRequest("alice", key, request, 2))
        assertEquals("edited", store.loadEntry("alice", key)?.draft?.body)
        assertNull(store.loadEntry("alice", key)?.attempt)
    }

    @Test
    fun `wrong scope stale generation and stale request never mutate content`() = runTest {
        store.enqueueInlineQuoteRequest("alice", key, request, 0)
        val before = store.loadEntry("alice", key)
        assertEquals(StoreWriteResult.Conflict, store.enqueueInlineQuoteRequest("alice", "mpnew", request, 0))
        assertEquals(StoreWriteResult.Conflict, store.enqueueInlineQuoteRequest("alice", key, request(35395), 1))
        assertEquals(StoreWriteResult.Conflict, store.enqueueInlineQuoteRequest("alice", key, request, 0))
        assertEquals(StoreWriteResult.Conflict, store.consumeInlineQuoteRequest("alice", key, request, 0))
        assertEquals(
            StoreWriteResult.Conflict, store.consumeInlineQuoteRequest("alice", key, request(numreponse = 202), 1),
        )
        assertEquals(before, store.loadEntry("alice", key))
    }

    @Test
    fun `owner switch rejects entry and consumption and a new store reloads only its account`() = runTest {
        store.enqueueInlineQuoteRequest("alice", key, request, 0)
        auth.value = AuthState.Authenticated("Bob")
        assertEquals(StoreWriteResult.OwnerChanged, store.enqueueInlineQuoteRequest("alice", key, request, 1))
        assertEquals(StoreWriteResult.OwnerChanged, store.consumeInlineQuoteRequest("alice", key, request, 1))
        assertEquals(StoreWriteResult.OwnerChanged, store.enqueueInlineQuoteRequest(null, key, request, 0))
        assertNull(newStore().loadEntry("alice", key))
        assertNull(newStore().loadEntry("bob", key))
        auth.value = AuthState.Authenticated("Alice")
        assertEquals(request, newStore().loadEntry("alice", key)?.draft?.pendingInlineQuoteRequest)
    }

    @Test
    fun `malformed and unknown documents are preserved on read entry and consumption failures`() = runTest {
        store.enqueueInlineQuoteRequest("alice", key, request, 0)
        val dao = database.editorDraftDao()
        val valid = checkNotNull(dao.get("alice|$key"))
        val invalid = listOf(
            "not json", checkNotNull(valid.pendingInlineQuoteRequest).replace("\"version\":1", "\"version\":9"),
        )
        for (raw in invalid) {
            val corrupt = valid.copy(pendingInlineQuoteRequest = raw)
            dao.upsert(corrupt)
            assertTrue(runCatching { store.loadEntry("alice", key) }.exceptionOrNull() is IOException)
            assertTrue(store.enqueueInlineQuoteRequest("alice", key, request, 1) is StoreWriteResult.Failed)
            assertTrue(store.consumeInlineQuoteRequest("alice", key, request, 1) is StoreWriteResult.Failed)
            assertEquals(corrupt, dao.get("alice|$key"))
        }
    }

    @Test
    fun `legacy writes keep pending coordinates and deleting the row removes them`() = runTest {
        store.enqueueInlineQuoteRequest("alice", key, request, 0)
        store.save("alice", key, Draft("legacy editor"))
        assertEquals(request, store.loadEntry("alice", key)?.draft?.pendingInlineQuoteRequest)
        store.delete("alice", key)
        assertNull(store.loadEntry("alice", key))
    }

    @Test
    fun `request metadata keeps the body date and terminal cleanup never increments content version`() = runTest {
        store.enqueueInlineQuoteRequest("alice", key, request, 0)
        val laterClock = Clock.fixed(Instant.ofEpochMilli(5000), ZoneOffset.UTC)
        val later = newStore(laterClock)
        later.enqueueInlineQuoteRequest("alice", key, request, 1)
        assertEquals(1234L, later.loadEntry("alice", key)?.draft?.updatedAt)
        later.consumeInlineQuoteRequest("alice", key, request, 2)
        assertEquals(1234L, later.loadEntry("alice", key)?.draft?.updatedAt)
        val snapshot = Draft("sent body", pendingInlineQuoteRequest = request)
        val token = (store.beginAttempt("alice", key, snapshot, 3) as BeginAttemptResult.Started).token
        later.finishAttempt(token, AttemptOutcome.PUBLISHED)
        assertEquals(5000L, later.loadEntry("alice", key)?.draft?.updatedAt)
        assertEquals(4L, later.loadEntry("alice", key)?.version)
        // Defensive cleanup also removes coordinates left by an older writer of a published row.
        val dao = database.editorDraftDao()
        val published = checkNotNull(dao.get("alice|$key"))
        dao.upsert(published.copy(pendingInlineQuoteRequest = PendingInlineQuoteRequestCodec.encode(request, key)))
        val cleanup = newStore(Clock.fixed(Instant.ofEpochMilli(9000), ZoneOffset.UTC))
        assertEquals(StoreWriteResult.Written(4), cleanup.erasePublishedContent(token))
        assertNull(dao.get("alice|$key")?.pendingInlineQuoteRequest)
        assertEquals(4L, dao.get("alice|$key")?.contentVersion)
        assertEquals(9000L, dao.get("alice|$key")?.updatedAt)
        assertEquals(StoreWriteResult.Written(5), later.startNewComposition("alice", key, snapshot, 4))
        assertEquals(request, later.loadEntry("alice", key)?.draft?.pendingInlineQuoteRequest)
    }

    @Test
    fun `M1 raw discard preserves all fields and the unresolved attempt without decoding`() = runTest {
        val token = (store.beginAttempt("alice", key, Draft("body", "subject", "recipient"), 0)
            as BeginAttemptResult.Started).token
        store.finishAttempt(token, AttemptOutcome.UNCERTAIN)
        val dao = database.editorDraftDao()
        val raw = checkNotNull(dao.get("alice|$key")).copy(pendingInlineQuoteRequest = "unreadable")
        dao.upsert(raw)
        assertTrue(runCatching { store.loadEntry("alice", key) }.isFailure)
        assertEquals(StoreWriteResult.Written(raw.contentVersion + 1),
            store.discardUnreadableInlineQuoteRequest("alice", key, raw.contentVersion))
        assertEquals(raw.copy(pendingInlineQuoteRequest = null, contentVersion = raw.contentVersion + 1),
            dao.get("alice|$key"))
        val reopened = checkNotNull(store.loadEntry("alice", key))
        assertEquals(token, reopened.attempt?.token)
        assertEquals("body", reopened.draft.body)
    }

    @Test
    fun `M1 failed or wrong owner raw discard leaves the entire unreadable row unchanged`() = runTest {
        store.enqueueInlineQuoteRequest("alice", key, request, 0)
        val dao = database.editorDraftDao()
        val raw = checkNotNull(dao.get("alice|$key")).copy(pendingInlineQuoteRequest = "unreadable")
        dao.upsert(raw)
        assertEquals(StoreWriteResult.OwnerChanged,
            store.discardUnreadableInlineQuoteRequest("bob", key, raw.contentVersion))
        database.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER reject_raw_discard BEFORE UPDATE ON editor_drafts
                BEGIN SELECT RAISE(ABORT, 'disk failure'); END""".trimIndent(),
        )
        assertTrue(
            store.discardUnreadableInlineQuoteRequest("alice", key, raw.contentVersion) is StoreWriteResult.Failed,
        )
        assertEquals(raw, dao.get("alice|$key"))
    }

    @Test
    fun `unreadable row replaced by Q2 before Ignore preserves Q2 and requires a reload`() = runTest {
        store.enqueueInlineQuoteRequest("alice", key, request, 0)
        val dao = database.editorDraftDao()
        val raw = checkNotNull(dao.get("alice|$key")).copy(pendingInlineQuoteRequest = "unreadable")
        dao.upsert(raw)
        val stale = EditorDraftSession(store, key)
        val failure = runCatching { stale.load() }.exceptionOrNull()
        assertTrue(failure is UnreadableInlineQuoteRequestException)
        assertEquals(raw.contentVersion, (failure as UnreadableInlineQuoteRequestException).contentVersion)
        // Another session repairs the row and records a new explicit quote action.
        dao.upsert(raw.copy(pendingInlineQuoteRequest = null, contentVersion = raw.contentVersion + 1))
        val other = EditorDraftSession(newStore(), key)
        other.load()
        val q2 = PendingInlineQuoteRequest(23, 35421, listOf(PendingInlineQuoteRequest.Quote(202, 2)))
        assertTrue(other.enqueueInlineQuoteRequest(q2) is StoreWriteResult.Written)
        val replacement = dao.get("alice|$key")
        assertEquals(StoreWriteResult.Conflict, stale.discardUnreadableInlineQuoteRequest())
        assertEquals(replacement, dao.get("alice|$key"))
        assertTrue(stale.hasUnreadableInlineQuoteRequest)
        stale.load()
        assertEquals(q2, stale.pendingInlineQuoteRequest)
        assertFalse(stale.hasUnreadableInlineQuoteRequest)
        assertEquals(StoreWriteResult.Conflict, stale.discardUnreadableInlineQuoteRequest())
        assertEquals(replacement, dao.get("alice|$key"))
    }

    @Test
    fun `published entry starts empty and a new editor session never resurrects the sent body`() = runTest {
        val token = (store.beginAttempt("alice", key, Draft("sent body"), 0) as BeginAttemptResult.Started).token
        store.finishAttempt(token, AttemptOutcome.PUBLISHED)
        store.enqueueInlineQuoteRequest("alice", key, request, token.version)
        val session = EditorDraftSession(newStore(), key)
        val fresh = checkNotNull(session.load())
        assertEquals("", fresh.draft.body)
        assertNull(fresh.attempt)
        assertEquals(request, session.pendingInlineQuoteRequest)
    }

    @Test
    fun `1450 C refreshes retention but D expires using its own original date`() = runTest {
        val day = 86_400_000L
        val oldStore = newStore(Clock.fixed(Instant.ofEpochMilli(clock.millis() - 31 * day), ZoneOffset.UTC))
        oldStore.saveIfVersion("alice", key, Draft("D"), 0)
        val session = EditorDraftSession(store, key)
        val offered = checkNotNull(session.load()).draft.body
        assertTrue(session.save(Draft("C")) is StoreWriteResult.Written)
        database.editorDraftDao().deleteOlderThan(clock.millis() - 30 * day)
        val retained = checkNotNull(newStore().loadEntry("alice", key))
        assertEquals("D", offered)
        assertTrue(newStore().loadOffers("alice", key).isEmpty())
        assertEquals("C", retained.draft.body)
        assertEquals(clock.millis(), retained.draft.updatedAt)
    }

    private fun newStore(writeClock: Clock = clock) = RoomEditorDraftStore(
        mockk<AuthRepository> { every { observeAuthState() } returns auth },
        database.editorDraftDao(), writeClock, Dispatchers.IO,
    )

    private fun request(topicId: Int = 35421, numreponse: Int = 101) =
        PendingInlineQuoteRequest(23, topicId, listOf(PendingInlineQuoteRequest.Quote(numreponse, 1)))
}
