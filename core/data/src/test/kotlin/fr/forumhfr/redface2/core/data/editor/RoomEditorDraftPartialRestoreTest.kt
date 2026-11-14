package fr.forumhfr.redface2.core.data.editor

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.database.dao.EditorDraftDao
import fr.forumhfr.redface2.core.database.entities.EditorDraftEntity
import fr.forumhfr.redface2.core.database.entities.EditorDraftOfferEntity
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.EditorDraftSession
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.BeginAttemptResult
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionBinding
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionCallbacks
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionEnvironment
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionErrors
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.model.AuthState
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/**
 * ED-170: a read that fails after the row was read never hides a durable attempt. Real Room rows and
 * transactions; only the reads issued by the store itself are made to fail, a given number of times.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RoomEditorDraftPartialRestoreTest {
    private lateinit var database: RedfaceDatabase
    private lateinit var dao: FailingReads
    private lateinit var store: RoomEditorDraftStore
    private val snapshot = Draft("sent body", "sent subject", "bob", isPrivate = true)
    private val key = "mpnew"

    /** The store's own reads fail on demand; Room's transactions keep calling the real DAO. */
    private class FailingReads(private val real: EditorDraftDao) : EditorDraftDao by real {
        var offerFailures = 0
        var rowFailures = 0

        override suspend fun get(draftKey: String): EditorDraftEntity? {
            if (rowFailures > 0) {
                rowFailures--
                throw SQLiteException("disk I/O error")
            }
            return real.get(draftKey)
        }

        override suspend fun offers(ownerId: String, key: String): List<EditorDraftOfferEntity> {
            if (offerFailures > 0) {
                offerFailures--
                throw SQLiteException("disk I/O error")
            }
            return real.offers(ownerId, key)
        }
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), RedfaceDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = FailingReads(database.editorDraftDao())
        store = RoomEditorDraftStore(
            mockk<AuthRepository> {
                every { observeAuthState() } returns MutableStateFlow<AuthState>(AuthState.Authenticated("Alice"))
            },
            dao,
            Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC),
            Dispatchers.IO,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `an attempt whose offers cannot be read once still opens as uncertain with its content`() = runTest {
        for (status in listOf(AttemptStatus.IN_PROGRESS, AttemptStatus.UNCERTAIN)) {
            val contextKey = "mpreply:${status.name}"
            val token = (store.beginAttempt("alice", contextKey, snapshot, 0, "attempt-$status")
                as BeginAttemptResult.Started).token
            if (status == AttemptStatus.UNCERTAIN) store.finishAttempt(token, AttemptOutcome.UNCERTAIN)
            val errors = mutableListOf<String?>()
            val binding = binding(contextKey, errors)
            dao.offerFailures = 1
            val restored = binding.restore(contextKey)
            assertEquals(0, dao.offerFailures)
            // Body, subject and recipients reach the surface; the editor is not an empty Ready one.
            assertEquals(snapshot.copy(updatedAt = 1234), restored?.draft)
            assertEquals(token, restored?.attempt?.token)
            assertEquals(EditorSubmissionState.Uncertain(token), binding.state)
            assertEquals(listOf<String?>("uncertain"), errors)
            assertTrue(binding.drafts.offersUnavailable)
            val row = checkNotNull(store.loadEntry("alice", contextKey))
            assertEquals(snapshot.copy(updatedAt = 1234), row.draft)
            assertEquals(status, row.attempt?.status)
            // What the user edits from the restored text stays on the attempt and is never offered.
            assertTrue(binding.persist(snapshot.copy(body = "sent body, edited")) is StoreWriteResult.Written)
            assertEquals(AttemptStatus.UNCERTAIN, store.loadEntry("alice", contextKey)?.attempt?.status)
            assertTrue(store.loadOffers("alice", contextKey).isEmpty())
            // The offers are readable again: the next opening is complete.
            val reopened = binding(contextKey, mutableListOf())
            assertEquals("sent body, edited", reopened.restore(contextKey)?.draft?.body)
            assertFalse(reopened.drafts.offersUnavailable)
            assertEquals(EditorSubmissionState.Uncertain(token), reopened.state)
        }
    }

    @Test
    fun `a row read failing in SQLite is a draft error and not a crash`() = runTest {
        assertEquals(StoreWriteResult.Written(1), store.saveIfVersion("alice", key, snapshot, 0))
        dao.rowFailures = 1
        val failure = runCatching { store.loadEntry("alice", key) }.exceptionOrNull()
        assertTrue(failure is IOException)
        // Coroutine stack-trace recovery may wrap the thrown exception in a copy of itself.
        assertTrue(generateSequence(failure) { it.cause }.any { it is SQLiteException })
        val errors = mutableListOf<String?>()
        val binding = binding(key, errors)
        dao.rowFailures = 1
        assertNull(binding.restore(key))
        assertEquals(listOf<String?>("save"), errors)
        assertEquals(EditorSubmissionState.Ready, binding.state)
        // The unread row is at version 1: a session that could not read it cannot replace it.
        assertEquals(StoreWriteResult.Conflict, binding.persist(Draft("typed over an unread row")))
        assertEquals("sent body", store.loadEntry("alice", key)?.draft?.body)
    }

    private fun CoroutineScope.binding(contextKey: String, errors: MutableList<String?>) =
        EditorSubmissionBinding<Unit, String>(
            EditorSubmissionEnvironment(this, DiagnosticsClock { 0L }, DiagnosticsLog()),
            EditorDraftSession(store, contextKey),
            EditorSubmissionCallbacks(state = {}, error = { errors += it }, success = {}),
            EditorSubmissionErrors("uncertain", "draft", "session", "unknown", draftSaveFailed = "save"),
        )
}
