package fr.forumhfr.redface2.core.data.editor

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.EditorDraftSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionBinding
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionCallbacks
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionEnvironment
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionErrors
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.BeginAttemptResult
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import fr.forumhfr.redface2.core.model.AuthState
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ED-161/170: real Room transactions and CAS, including rollback and tombstone protection. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RoomEditorDraftStoreTransactionTest {
    private lateinit var database: RedfaceDatabase
    private lateinit var store: RoomEditorDraftStore
    private val auth = MutableStateFlow<AuthState>(AuthState.Authenticated("Alice"))
    private val snapshot = Draft("body", "subject", "bob", isPrivate = true)
    private val key = "mpnew"

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), RedfaceDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = RoomEditorDraftStore(
            mockk<AuthRepository> { every { observeAuthState() } returns auth },
            database.editorDraftDao(),
            Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC),
            Dispatchers.IO,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `begin commits full snapshot and durable identity together`() = runTest {
        val token = begin()
        val entry = checkNotNull(store.loadEntry("alice", key))
        assertEquals(snapshot.copy(updatedAt = 1234), entry.draft)
        assertEquals(1L, entry.version)
        assertEquals(token, entry.attempt?.token)
        assertEquals(AttemptStatus.IN_PROGRESS, entry.attempt?.status)
    }

    @Test
    fun `failed transaction rolls back both snapshot and attempt`() = runTest {
        assertEquals(StoreWriteResult.Written(1), store.saveIfVersion("alice", key, snapshot, 0))
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_attempt AFTER UPDATE ON editor_drafts
            WHEN NEW.attemptState = 'IN_PROGRESS' BEGIN SELECT RAISE(ABORT, 'disk failure'); END
            """.trimIndent(),
        )
        val result = store.beginAttempt("alice", key, snapshot.copy(body = "new body"), 1)
        assertTrue((result as BeginAttemptResult.Rejected).reason is StoreWriteResult.Failed)
        val entry = checkNotNull(store.loadEntry("alice", key))
        assertEquals("body", entry.draft.body)
        assertEquals(1L, entry.version)
        assertNull(entry.attempt)
    }

    @Test
    fun `two racing attempt transactions authorize only one sender`() = runTest {
        val results = List(2) {
            async(Dispatchers.Default) { store.beginAttempt("alice", key, snapshot, 0) }
        }.awaitAll()
        assertEquals(1, results.count { it is BeginAttemptResult.Started })
        assertEquals(1, results.count { it == BeginAttemptResult.Rejected(StoreWriteResult.Conflict) })
        assertEquals(1L, store.loadEntry("alice", key)?.version)
    }

    @Test
    fun `stale content version never overwrites a newer draft`() = runTest {
        assertEquals(StoreWriteResult.Written(1), store.saveIfVersion("alice", key, snapshot, 0))
        assertEquals(StoreWriteResult.Conflict, store.saveIfVersion("alice", key, Draft("stale"), 0))
        assertEquals(
            BeginAttemptResult.Rejected(StoreWriteResult.Conflict),
            store.beginAttempt("alice", key, Draft("stale"), 0),
        )
        assertEquals("body", store.loadEntry("alice", key)?.draft?.body)
    }

    @Test
    fun `owner switch and anonymous session are explicit rejected writes`() = runTest {
        auth.value = AuthState.Authenticated("bob")
        assertEquals(
            BeginAttemptResult.Rejected(StoreWriteResult.OwnerChanged),
            store.beginAttempt("alice", key, snapshot, 0),
        )
        assertEquals(StoreWriteResult.OwnerChanged, store.saveIfVersion(null, key, snapshot, 0))
        assertEquals(StoreWriteResult.OwnerChanged, store.startNewComposition("alice", key, snapshot, 0))
        assertNull(database.editorDraftDao().get("alice|$key"))
    }

    @Test
    fun `owner switch after begin cannot finish or erase the previous account row`() = runTest {
        val token = begin()
        auth.value = AuthState.Authenticated("bob")
        assertEquals(StoreWriteResult.OwnerChanged, store.saveUnresolved(token, Draft("wrong owner"), 1))
        assertEquals(StoreWriteResult.OwnerChanged, store.finishAttempt(token, AttemptOutcome.PUBLISHED))
        assertEquals(StoreWriteResult.OwnerChanged, store.erasePublishedContent(token))
        assertNull(store.loadEntry("alice", key))
        auth.value = AuthState.Authenticated("alice")
        assertEquals(AttemptStatus.IN_PROGRESS, store.loadEntry("alice", key)?.attempt?.status)
    }

    @Test
    fun `certain non-emission can clear an inactive owner's attempt without exposing content`() = runTest {
        val token = begin()
        auth.value = AuthState.Authenticated("bob")
        assertEquals(StoreWriteResult.Written(1), store.finishAttempt(token, AttemptOutcome.CERTAIN_FAILURE))
        assertNull(store.loadEntry("alice", key))
        assertEquals(StoreWriteResult.Conflict, store.finishAttempt(token, AttemptOutcome.CERTAIN_FAILURE))
        auth.value = AuthState.Authenticated("alice")
        assertNull(store.loadEntry("alice", key)?.attempt)
        assertEquals("body", store.loadEntry("alice", key)?.draft?.body)
    }

    @Test
    fun `private purge restores only empty uncertainty and never published content`() = runTest {
        for (status in AttemptStatus.entries) {
            val contextKey = "mpreply:${status.name}"
            val token = (store.beginAttempt("alice", contextKey, snapshot, 0) as BeginAttemptResult.Started).token
            if (status != AttemptStatus.IN_PROGRESS) {
                store.finishAttempt(token, AttemptOutcome.valueOf(status.name))
            }
            auth.value = AuthState.Authenticated("bob")
            store.purgePrivateForUser("alice")
            assertNull(store.loadEntry("alice", contextKey))
            auth.value = AuthState.Authenticated("alice")
            var submitError: String? = null
            val binding = EditorSubmissionBinding<Unit, String>(
                EditorSubmissionEnvironment(this, DiagnosticsClock { 0L }, DiagnosticsLog()),
                EditorDraftSession(store, contextKey),
                EditorSubmissionCallbacks(state = {}, error = { submitError = it }, success = {}),
                EditorSubmissionErrors("uncertain", "draft", "session", "unknown"),
            )
            val restored = binding.restore(contextKey)
            if (status == AttemptStatus.PUBLISHED) {
                assertNull(restored)
                assertNull(store.load("alice", contextKey))
                assertNull(submitError)
            } else {
                assertEquals(EditorSubmissionState.Uncertain(token), binding.state)
                assertEquals("uncertain", submitError)
                assertEquals(
                    snapshot.copy(body = "", subject = null, recipients = null, updatedAt = 1234), restored?.draft,
                )
            }
        }
    }

    @Test
    fun `pending attempt refuses autosave and a second attempt even at current version`() = runTest {
        val token = begin()
        assertEquals(StoreWriteResult.Conflict, store.saveIfVersion("alice", key, Draft("late"), token.version))
        assertEquals(
            BeginAttemptResult.Rejected(StoreWriteResult.Conflict),
            store.beginAttempt("alice", key, Draft("duplicate"), token.version),
        )
        store.save("alice", key, Draft("legacy late"))
        assertEquals("body", store.loadEntry("alice", key)?.draft?.body)
    }

    @Test
    fun `certain failure clears only the attempt and permits a versioned save`() = runTest {
        val token = begin()
        assertEquals(StoreWriteResult.Written(1), store.finishAttempt(token, AttemptOutcome.CERTAIN_FAILURE))
        assertNull(store.loadEntry("alice", key)?.attempt)
        assertEquals("body", store.loadEntry("alice", key)?.draft?.body)
        assertEquals(StoreWriteResult.Written(2), store.saveIfVersion("alice", key, Draft("retry body"), 1))
        assertEquals(StoreWriteResult.Conflict, store.finishAttempt(token, AttemptOutcome.PUBLISHED))
    }

    @Test
    fun `uncertain attempt restores with content and is replaced only by another attempt`() = runTest {
        val token = begin()
        store.finishAttempt(token, AttemptOutcome.UNCERTAIN)
        val entry = checkNotNull(store.loadEntry("alice", key))
        assertEquals(AttemptStatus.UNCERTAIN, entry.attempt?.status)
        assertEquals("body", entry.draft.body)
        assertEquals(StoreWriteResult.Conflict, store.saveIfVersion("alice", key, Draft("late"), 1))
        assertEquals(
            BeginAttemptResult.Rejected(StoreWriteResult.Conflict),
            store.beginAttempt("alice", key, Draft("unacknowledged retry"), entry.version),
        )
        assertEquals(StoreWriteResult.Written(1), store.finishAttempt(token, AttemptOutcome.CERTAIN_FAILURE))
        val retry = store.beginAttempt("alice", key, Draft("retry body"), entry.version)
        assertTrue(retry is BeginAttemptResult.Started)
        assertFalse(token.id == (retry as BeginAttemptResult.Started).token.id)
        assertEquals(StoreWriteResult.Conflict, store.finishAttempt(token, AttemptOutcome.PUBLISHED))
    }

    @Test
    fun `editing recovered in progress preserves uncertainty and independent attempt version`() = runTest {
        val token = begin()
        assertEquals(StoreWriteResult.Written(2), store.saveUnresolved(token, Draft("edited"), 1))
        val entry = checkNotNull(store.loadEntry("alice", key))
        assertEquals("edited", entry.draft.body)
        assertEquals(2L, entry.version)
        assertEquals(token, entry.attempt?.token)
        assertEquals(AttemptStatus.UNCERTAIN, entry.attempt?.status)
        assertEquals(StoreWriteResult.Conflict, store.saveUnresolved(token, Draft("stale"), 1))
        assertEquals(StoreWriteResult.Written(2), store.finishAttempt(token, AttemptOutcome.CERTAIN_FAILURE))
        assertEquals("edited", store.loadEntry("alice", key)?.draft?.body)
        val retry = store.beginAttempt("alice", key, Draft("edited"), 2) as BeginAttemptResult.Started
        assertEquals(3L, retry.token.version)
        assertEquals(StoreWriteResult.Conflict, store.saveUnresolved(token, Draft("old callback"), 3))
    }

    @Test
    fun `editing uncertain content keeps headers and cannot overwrite published content`() = runTest {
        val token = begin()
        store.finishAttempt(token, AttemptOutcome.UNCERTAIN)
        val edited = snapshot.copy(body = "new text", subject = "new subject", recipients = "charlie")
        assertEquals(StoreWriteResult.Written(2), store.saveUnresolved(token, edited, 1))
        assertEquals(edited.copy(updatedAt = 1234), store.loadEntry("alice", key)?.draft)
        assertEquals(AttemptStatus.UNCERTAIN, store.loadEntry("alice", key)?.attempt?.status)
        store.finishAttempt(token, AttemptOutcome.CERTAIN_FAILURE)
        val retry = store.beginAttempt("alice", key, edited, 2) as BeginAttemptResult.Started
        store.finishAttempt(retry.token, AttemptOutcome.PUBLISHED)
        assertEquals(StoreWriteResult.Conflict, store.saveUnresolved(retry.token, Draft("late"), 3))
    }

    @Test
    fun `published content erasure retains row and blocks late saves and deletes`() = runTest {
        val token = begin()
        store.finishAttempt(token, AttemptOutcome.PUBLISHED)
        assertEquals(StoreWriteResult.Written(1), store.erasePublishedContent(token))
        assertEquals(StoreWriteResult.Conflict, store.saveIfVersion("alice", key, Draft("late"), 1))
        store.save("alice", key, Draft("legacy late"))
        store.delete("alice", key)
        val entry = checkNotNull(store.loadEntry("alice", key))
        assertEquals(AttemptStatus.PUBLISHED, entry.attempt?.status)
        assertEquals(token, entry.attempt?.token)
        assertEquals("", entry.draft.body)
        assertNull(entry.draft.subject)
        assertNull(entry.draft.recipients)
        assertNull(store.load("alice", key))
        assertNotNull(database.editorDraftDao().get("alice|$key"))
    }

    @Test
    fun `cleanup failure is reported with published marker and content retained`() = runTest {
        val token = begin()
        store.finishAttempt(token, AttemptOutcome.PUBLISHED)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_cleanup BEFORE UPDATE ON editor_drafts
            WHEN NEW.body = '' BEGIN SELECT RAISE(ABORT, 'disk failure'); END
            """.trimIndent(),
        )
        assertTrue(store.erasePublishedContent(token) is StoreWriteResult.Failed)
        val entry = checkNotNull(store.loadEntry("alice", key))
        assertEquals(AttemptStatus.PUBLISHED, entry.attempt?.status)
        assertEquals("body", entry.draft.body)
    }

    @Test
    fun `new composition advances generation and rejects old attempt and autosaves`() = runTest {
        val token = begin()
        store.finishAttempt(token, AttemptOutcome.PUBLISHED)
        store.erasePublishedContent(token)
        assertEquals(StoreWriteResult.Written(2), store.startNewComposition("alice", key, Draft("new"), 1))
        assertEquals(StoreWriteResult.Conflict, store.erasePublishedContent(token))
        assertEquals(StoreWriteResult.Conflict, store.saveIfVersion("alice", key, Draft("old"), 1))
        assertEquals(StoreWriteResult.Conflict, store.finishAttempt(token, AttemptOutcome.UNCERTAIN))
        val entry = checkNotNull(store.loadEntry("alice", key))
        assertNull(entry.attempt)
        assertEquals("new", entry.draft.body)
        assertEquals(2L, entry.version)
    }

    @Test
    fun `wrong token identity cannot finish or erase content`() = runTest {
        val token = begin()
        assertEquals(StoreWriteResult.Conflict, store.finishAttempt(token.copy(id = "wrong"), AttemptOutcome.PUBLISHED))
        assertEquals(StoreWriteResult.Conflict, store.erasePublishedContent(token.copy(version = 2)))
        assertEquals("body", store.loadEntry("alice", key)?.draft?.body)
    }

    private suspend fun begin(): AttemptToken =
        (store.beginAttempt("alice", key, snapshot, 0) as BeginAttemptResult.Started).token
}
