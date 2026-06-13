package fr.forumhfr.redface2.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.database.entities.EditorDraftEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-DB coverage of the privacy-critical DELETE clauses on [EditorDraftDao] (#405). Unlike the
 * store/CacheInvalidator tests (which only `coVerify` the call on a mock), these exercise the SQL
 * EFFECT against an in-memory Room database, so a regression that drops or inverts the
 * `AND isPrivate = 1` filter — or flips the TTL comparison — fails loudly here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EditorDraftDaoTest {

    private lateinit var database: RedfaceDatabase
    private lateinit var dao: EditorDraftDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
            RedfaceDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = database.editorDraftDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `upsert then get round-trips every column`() = runTest {
        dao.upsert(
            draft(
                draftKey = "alice|mpnew",
                ownerId = "alice",
                body = "secret body",
                subject = "secret subject",
                recipients = "bob",
                updatedAt = 1_700_000_000_000L,
                isPrivate = true,
            ),
        )

        val stored = dao.get("alice|mpnew")
        assertNotNull(stored)
        assertEquals("secret body", stored!!.body)
        assertEquals("secret subject", stored.subject)
        assertEquals("bob", stored.recipients)
        assertEquals(true, stored.isPrivate)
    }

    @Test
    fun `deletePrivateForUser removes ONLY the targeted owner's private drafts`() = runTest {
        dao.upsert(draft("alice|mpreply:1", ownerId = "alice", isPrivate = true))
        // A public draft of the SAME owner must survive logout — only MP drafts are sensitive.
        dao.upsert(draft("alice|reply:23:1", ownerId = "alice", isPrivate = false))
        // Another account's private draft must never be touched by alice's logout.
        dao.upsert(draft("bob|mpreply:2", ownerId = "bob", isPrivate = true))

        dao.deletePrivateForUser("alice")

        // Alice's MP draft is gone …
        assertNull(dao.get("alice|mpreply:1"))
        // … her public post draft survives (bounded only by the TTL purge) …
        assertNotNull(dao.get("alice|reply:23:1"))
        // … and Bob's MP draft is untouched.
        assertNotNull(dao.get("bob|mpreply:2"))
    }

    @Test
    fun `deleteOlderThan is a strict less-than at the cutoff boundary`() = runTest {
        val cutoff = 1_700_000_000_000L
        dao.upsert(draft("alice|old", ownerId = "alice", updatedAt = cutoff - 1))
        dao.upsert(draft("alice|exact", ownerId = "alice", updatedAt = cutoff))
        dao.upsert(draft("alice|fresh", ownerId = "alice", updatedAt = cutoff + 1))

        dao.deleteOlderThan(cutoff)

        // Strictly older than the cutoff is purged …
        assertNull(dao.get("alice|old"))
        // … the row exactly at the cutoff is KEPT (strict `<`, no off-by-one) …
        assertNotNull(dao.get("alice|exact"))
        // … and anything newer is obviously kept.
        assertNotNull(dao.get("alice|fresh"))
    }

    @Test
    fun `deleteByKey removes a single row`() = runTest {
        dao.upsert(draft("alice|edit:23:99", ownerId = "alice"))
        dao.upsert(draft("alice|reply:23:1", ownerId = "alice"))

        dao.deleteByKey("alice|edit:23:99")

        assertNull(dao.get("alice|edit:23:99"))
        assertNotNull(dao.get("alice|reply:23:1"))
    }

    // One parameter per entity column — a faithful test factory, not a design smell.
    @Suppress("LongParameterList")
    private fun draft(
        draftKey: String,
        ownerId: String,
        body: String = "body",
        subject: String? = null,
        recipients: String? = null,
        updatedAt: Long = 1_700_000_000_000L,
        isPrivate: Boolean = false,
    ) = EditorDraftEntity(
        draftKey = draftKey,
        ownerId = ownerId,
        body = body,
        subject = subject,
        recipients = recipients,
        updatedAt = updatedAt,
        isPrivate = isPrivate,
    )
}
