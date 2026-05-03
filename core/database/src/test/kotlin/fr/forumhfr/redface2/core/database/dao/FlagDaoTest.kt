package fr.forumhfr.redface2.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.database.entities.FetchMode
import fr.forumhfr.redface2.core.database.entities.FlagTopicEntity
import fr.forumhfr.redface2.core.model.FlagType
import java.time.Instant
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
class FlagDaoTest {

    private lateinit var database: RedfaceDatabase
    private lateinit var dao: FlagDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
            RedfaceDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = database.flagDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `replaceForType wipes previous rows for the same userId and type`() = runTest {
        dao.upsertAll(listOf(row(userId = "alice", topicId = 100, type = FlagType.CYAN)))

        val replacement = listOf(
            row(userId = "alice", topicId = 200, type = FlagType.CYAN),
            row(userId = "alice", topicId = 300, type = FlagType.CYAN),
        )
        dao.replaceForType("alice", FlagType.CYAN, replacement)

        val cyan = dao.getFlags("alice", FlagType.CYAN)
        assertEquals(setOf(200, 300), cyan.map { it.topicId }.toSet())
    }

    @Test
    fun `replaceForType does not touch other users or other types`() = runTest {
        dao.upsertAll(
            listOf(
                row(userId = "alice", topicId = 1, type = FlagType.CYAN),
                row(userId = "alice", topicId = 2, type = FlagType.RED),
                row(userId = "bob", topicId = 3, type = FlagType.CYAN),
            ),
        )

        dao.replaceForType("alice", FlagType.CYAN, emptyList())

        // Alice's CYAN tab is empty …
        assertTrue(dao.getFlags("alice", FlagType.CYAN).isEmpty())
        // … but her RED tab survives, and Bob's CYAN tab is untouched. This is the
        // isolation guarantee.
        assertEquals(listOf(2), dao.getFlags("alice", FlagType.RED).map { it.topicId })
        assertEquals(listOf(3), dao.getFlags("bob", FlagType.CYAN).map { it.topicId })
    }

    @Test
    fun `deleteAllForUser purges every type for that user only`() = runTest {
        dao.upsertAll(
            listOf(
                row(userId = "alice", topicId = 1, type = FlagType.CYAN),
                row(userId = "alice", topicId = 2, type = FlagType.RED),
                row(userId = "alice", topicId = 3, type = FlagType.FAVORITE),
                row(userId = "bob", topicId = 4, type = FlagType.CYAN),
            ),
        )

        dao.deleteAllForUser("alice")

        assertTrue(dao.getFlags("alice", FlagType.CYAN).isEmpty())
        assertTrue(dao.getFlags("alice", FlagType.RED).isEmpty())
        assertTrue(dao.getFlags("alice", FlagType.FAVORITE).isEmpty())
        assertEquals(1, dao.getFlags("bob", FlagType.CYAN).size)
    }

    @Test
    fun `userId equality is byte-exact — callers must lowercase before insert`() = runTest {
        // Pin the convention documented on FlagDao : the DAO does NOT normalise
        // userId. Inserting "Alice" then querying "alice" returns nothing. This
        // test fails loudly if Room ever switches to case-insensitive collation
        // on this column (which would mask write bugs upstream).
        dao.upsertAll(listOf(row(userId = "Alice", topicId = 1, type = FlagType.CYAN)))

        assertTrue(
            "case-mismatched userId must not return rows",
            dao.getFlags("alice", FlagType.CYAN).isEmpty(),
        )
        assertEquals(
            listOf(1),
            dao.getFlags("Alice", FlagType.CYAN).map { it.topicId },
        )
    }

    @Test
    fun `getFlags orders by lastReplyAt descending, lexicographic = chronological`() = runTest {
        // Pins the SQL `ORDER BY lastReplyAt DESC` invariant. The REST format
        // `YYYY-MM-DD HH:mm` makes lexicographic descending equal to chronological
        // descending — including across year boundaries, which the legacy HFR HTML
        // format `DD-MM-YYYY HH:mm` would have got wrong (`31-12-2025` < `01-01-2026`
        // in lex but `>` in time). Regression guard if anyone migrates the column to
        // the legacy format without realising.
        dao.upsertAll(
            listOf(
                row(userId = "alice", topicId = 10, type = FlagType.CYAN, lastReplyAt = "2025-12-31 23:59"),
                row(userId = "alice", topicId = 11, type = FlagType.CYAN, lastReplyAt = "2026-01-01 00:01"),
                row(userId = "alice", topicId = 12, type = FlagType.CYAN, lastReplyAt = "2025-06-15 12:00"),
            ),
        )
        assertEquals(
            listOf(11, 10, 12),
            dao.getFlags("alice", FlagType.CYAN).map { it.topicId },
        )
    }

    @Test
    fun `getLastFetchedAt returns null when no rows exist`() = runTest {
        assertNull(dao.getLastFetchedAt("alice", FlagType.CYAN))
    }

    @Test
    fun `getLastFetchedAt returns the most recent fetchedAt across rows of the same type`() = runTest {
        val older = Instant.parse("2026-04-26T17:00:00Z")
        val newer = Instant.parse("2026-04-26T18:00:00Z")
        dao.upsertAll(
            listOf(
                row(userId = "alice", topicId = 1, type = FlagType.CYAN, fetchedAt = older),
                row(userId = "alice", topicId = 2, type = FlagType.CYAN, fetchedAt = newer),
            ),
        )
        assertEquals(newer, dao.getLastFetchedAt("alice", FlagType.CYAN))
    }

    private fun row(
        userId: String,
        topicId: Int,
        type: FlagType,
        fetchedAt: Instant = Instant.parse("2026-04-26T18:00:00Z"),
        lastReplyAt: String = "2026-04-26 18:00",
    ): FlagTopicEntity = FlagTopicEntity(
        userId = userId,
        type = type,
        cat = 23,
        subcat = 550,
        topicId = topicId,
        title = "fixture topic $topicId",
        totalPages = 12,
        replyCount = 480,
        hasUnread = true,
        lastReadPage = 11,
        lastPostReadId = 555_000_000L + topicId,
        firstPostAuthor = "alice",
        lastReplyAuthor = "bob",
        lastReplyAt = lastReplyAt,
        fetchedAt = fetchedAt,
        authMode = FetchMode.AUTHENTICATED,
    )
}
