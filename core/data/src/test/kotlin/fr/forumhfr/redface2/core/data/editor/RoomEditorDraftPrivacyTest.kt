package fr.forumhfr.redface2.core.data.editor

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.database.dao.EditorDraftDao
import fr.forumhfr.redface2.core.database.entities.EditorDraftEntity
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.editor.EditorDraftSession
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
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

/** Real Room writes on both sides of the account-switch purge, without a production test hook. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RoomEditorDraftPrivacyTest {
    private lateinit var database: RedfaceDatabase
    private lateinit var dao: PausedDraftDao
    private lateinit var store: RoomEditorDraftStore
    private val auth = MutableStateFlow<AuthState>(AuthState.Authenticated("Alice"))
    private var ownerReadGate: Gate? = null
    private val snapshot = Draft("body", "subject", "recipient", isPrivate = true)
    private val key = "mpnew"

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), RedfaceDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = PausedDraftDao(database.editorDraftDao())
        val repository = mockk<AuthRepository> {
            every { observeAuthState() } answers {
                auth.onCompletion {
                    // first() has consumed the active account. Delay returning that authorization
                    // until after a purge, before the store can enter its transaction exclusion.
                    val gate = ownerReadGate
                    ownerReadGate = null
                    gate?.pause()
                }
            }
        }
        store = RoomEditorDraftStore(
            repository, dao, Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC), Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `stale unresolved autosave cannot refill private fields after purge`() = runTest {
        assertStaleAutosaveRejected(reconnectBeforeResume = false)
    }

    /** Regression for f1a753e2: reconnecting the same pseudo must not revive a pre-purge autosave. */
    @Test
    fun `stale unresolved autosave is rejected even after the original account reconnects`() = runTest {
        assertStaleAutosaveRejected(reconnectBeforeResume = true)
    }

    /** Regression for 1306c177: purging A must not invalidate B's pending content flush. */
    @Test
    fun `another account purge cannot reject the active editor flush`() = runTest {
        store.saveIfVersion("alice", key, snapshot, 0)
        auth.value = AuthState.Authenticated("Bob")
        val session = EditorDraftSession(store, key)
        assertNull(session.load())
        val edited = snapshot.copy(body = "Bob's last edit", subject = "Bob's subject", recipients = "charlie")
        val gate = Gate().also { ownerReadGate = it }
        val write = async { session.save(edited) }
        gate.entered.await()

        try {
            store.purgePrivateForUser("alice")
            assertNull(dao.get("alice|$key"))
        } finally {
            gate.resume.complete(Unit)
        }

        assertEquals(StoreWriteResult.Written(1), write.await())
        assertEquals(edited.copy(updatedAt = 1234), store.loadEntry("bob", key)?.draft)
    }

    /** Regression for 1306c177: metadata-only certainty must survive a purge before its lock. */
    @Test
    fun `certain failure resolves after an intervening purge without restoring private content`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler, name = "draft-io")
        val dispatchedStore = RoomEditorDraftStore(
            mockk<AuthRepository> { every { observeAuthState() } returns auth },
            dao, Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC), dispatcher,
        )
        val token = (dispatchedStore.beginAttempt("alice", key, snapshot, 0) as BeginAttemptResult.Started).token
        auth.value = AuthState.Authenticated("Bob")
        val finish = async(start = CoroutineStart.UNDISPATCHED) {
            dispatchedStore.finishAttempt(token, AttemptOutcome.CERTAIN_FAILURE)
        }
        assertFalse(finish.isCompleted)
        // Enter the same IO context without dispatching: purge advances the generation first.
        val purge = async(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            dispatchedStore.purgePrivateForUser("alice")
        }
        assertTrue(dao.purgeEntered.isCompleted)
        purge.await()

        assertEquals(StoreWriteResult.Written(token.version), finish.await())
        val row = checkNotNull(dao.get("alice|$key"))
        assertNull(row.attemptId)
        assertNull(row.attemptVersion)
        assertNull(row.attemptState)
        assertEquals(token.version, row.contentVersion)
        assertEquals("", row.body)
        assertNull(row.subject)
        assertNull(row.recipients)
        assertEquals(StoreWriteResult.Conflict, dispatchedStore.finishAttempt(token, AttemptOutcome.CERTAIN_FAILURE))
    }

    private suspend fun assertStaleAutosaveRejected(reconnectBeforeResume: Boolean) = coroutineScope {
        val token = (store.beginAttempt("alice", key, snapshot, 0) as BeginAttemptResult.Started).token
        store.finishAttempt(token, AttemptOutcome.UNCERTAIN)
        val session = EditorDraftSession(store, key)
        session.load()
        val gate = Gate().also { ownerReadGate = it }
        val write = async { session.save(snapshot.copy(body = "edited")) }
        gate.entered.await()

        try {
            auth.value = AuthState.Authenticated("Bob")
            session.invalidate()
            store.purgePrivateForUser("alice")
            assertEmptyMarker(token)
            if (reconnectBeforeResume) auth.value = AuthState.Authenticated("Alice")
        } finally {
            gate.resume.complete(Unit)
        }

        assertEquals(StoreWriteResult.OwnerChanged, write.await())
        assertEmptyMarker(token)
        auth.value = AuthState.Authenticated("Alice")
        val restored = checkNotNull(EditorDraftSession(store, key).load())
        assertEquals("", restored.draft.body)
        assertNull(restored.draft.subject)
        assertNull(restored.draft.recipients)
        assertEquals(token, restored.attempt?.token)
        assertEquals(AttemptStatus.UNCERTAIN, restored.attempt?.status)
    }

    @Test
    fun `all content writers reject stale authorization including absent rows`() = runTest {
        assertStaleWritersRejected(reconnectBeforeResume = false)
    }

    @Test
    fun `all content writers reject a pre purge authorization after the same owner reconnects`() = runTest {
        assertStaleWritersRejected(reconnectBeforeResume = true)
    }

    private suspend fun assertStaleWritersRejected(reconnectBeforeResume: Boolean) = coroutineScope {
        for (writer in ContentWriter.entries) {
            for (existing in listOf(false, true)) {
                auth.value = AuthState.Authenticated("Alice")
                val contextKey = if (writer in listOf(ContentWriter.QUOTE_ENTRY, ContentWriter.QUOTE_CONSUMPTION)) {
                    "reply:23:${1000 + writer.ordinal * 2 + if (existing) 1 else 0}"
                } else {
                    "$writer:$existing"
                }
                val version = if (existing) prepareExisting(writer, contextKey) else 0L
                val gate = Gate().also { ownerReadGate = it }
                val write = async { writeContent(writer, contextKey, version) }
                gate.entered.await()

                auth.value = AuthState.Authenticated("Bob")
                store.purgePrivateForUser("alice")
                if (reconnectBeforeResume) auth.value = AuthState.Authenticated("Alice")
                gate.resume.complete(Unit)
                val result = write.await()

                if (writer != ContentWriter.LEGACY) {
                    assertEquals(writer.name, StoreWriteResult.OwnerChanged, result)
                }
                val row = dao.get("alice|$contextKey")
                if (existing && writer == ContentWriter.NEW_COMPOSITION) {
                    assertEquals(AttemptStatus.PUBLISHED.name, row?.attemptState)
                    assertEquals("", row?.body)
                    assertNull(row?.subject)
                    assertNull(row?.recipients)
                } else {
                    assertNull(writer.name, row)
                }
            }
        }
    }

    @Test
    fun `purge waits for an authorized write already entering its Room transaction`() = runTest {
        val gate = Gate().also { dao.writeGate = it }
        val write = async { store.saveIfVersion("alice", key, snapshot, 0) }
        gate.entered.await() // Owner accepted, DAO reached, transaction not started yet.
        auth.value = AuthState.Authenticated("Bob")
        val purge = async { store.purgePrivateForUser("alice") }
        runCurrent()

        try {
            assertFalse(dao.purgeEntered.isCompleted)
        } finally {
            gate.resume.complete(Unit)
        }
        assertEquals(StoreWriteResult.Written(1), write.await())
        purge.await()
        assertNull(dao.get("alice|$key"))
    }

    private suspend fun prepareExisting(writer: ContentWriter, contextKey: String): Long {
        if (writer == ContentWriter.NEW_COMPOSITION) {
            val token = (store.beginAttempt("alice", contextKey, snapshot, 0) as BeginAttemptResult.Started).token
            store.finishAttempt(token, AttemptOutcome.PUBLISHED)
        } else {
            val pending = if (writer == ContentWriter.QUOTE_CONSUMPTION) quoteRequest(contextKey) else null
            store.saveIfVersion("alice", contextKey, snapshot.copy(pendingInlineQuoteRequest = pending), 0)
        }
        return 1L
    }

    private suspend fun writeContent(
        writer: ContentWriter,
        contextKey: String,
        version: Long,
    ): StoreWriteResult? = when (writer) {
        ContentWriter.LEGACY -> {
            store.save("alice", contextKey, snapshot)
            null
        }
        ContentWriter.VERSIONED -> store.saveIfVersion("alice", contextKey, snapshot, version)
        ContentWriter.NEW_COMPOSITION -> store.startNewComposition("alice", contextKey, snapshot, version)
        ContentWriter.ATTEMPT -> {
            val result = store.beginAttempt("alice", contextKey, snapshot, version) as BeginAttemptResult.Rejected
            result.reason
        }
        ContentWriter.QUOTE_ENTRY -> store.enqueueInlineQuoteRequest(
            "alice", contextKey, quoteRequest(contextKey), version,
        )
        ContentWriter.QUOTE_CONSUMPTION -> store.consumeInlineQuoteRequest(
            "alice", contextKey, quoteRequest(contextKey), version, snapshot,
        )
    }

    private suspend fun assertEmptyMarker(token: AttemptToken) {
        val row = checkNotNull(dao.get("alice|$key"))
        assertEquals(token.id, row.attemptId)
        assertEquals(token.version, row.attemptVersion)
        assertEquals(token.version, row.contentVersion)
        assertEquals(AttemptStatus.UNCERTAIN.name, row.attemptState)
        assertEquals("", row.body)
        assertNull(row.subject)
        assertNull(row.recipients)
    }

    private fun quoteRequest(contextKey: String) = PendingInlineQuoteRequest(
        23, contextKey.substringAfterLast(':').toInt(), listOf(PendingInlineQuoteRequest.Quote(101, 1)),
    )

    private enum class ContentWriter { LEGACY, VERSIONED, NEW_COMPOSITION, ATTEMPT, QUOTE_ENTRY, QUOTE_CONSUMPTION }

    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()

        suspend fun pause() {
            entered.complete(Unit)
            resume.await()
        }
    }

    private class PausedDraftDao(private val delegate: EditorDraftDao) : EditorDraftDao by delegate {
        var writeGate: Gate? = null
        val purgeEntered = CompletableDeferred<Unit>()

        override suspend fun saveVersioned(
            draft: EditorDraftEntity,
            expectedVersion: Long,
            newComposition: Boolean,
        ): Boolean {
            writeGate?.pause()
            return delegate.saveVersioned(draft, expectedVersion, newComposition)
        }

        override suspend fun deletePrivateForUser(ownerId: String) {
            purgeEntered.complete(Unit)
            delegate.deletePrivateForUser(ownerId)
        }
    }
}
