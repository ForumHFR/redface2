package fr.forumhfr.redface2.core.data.flags

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.database.dao.FlagCacheSnapshot
import fr.forumhfr.redface2.core.database.entities.FetchMode
import fr.forumhfr.redface2.core.database.entities.FlagCategoryCoverageEntity
import fr.forumhfr.redface2.core.database.entities.FlagTopicEntity
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshStatus
import fr.forumhfr.redface2.core.model.FlagFailureKind
import fr.forumhfr.redface2.core.model.FlagSource
import fr.forumhfr.redface2.core.model.FlagType
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FlagCacheStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "flag-coverage-984.db"
    private val old = Instant.parse("2026-05-03T10:00:00Z")
    private val now = old.plusSeconds(120)
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var database: RedfaceDatabase
    private lateinit var store: FlagCacheStore

    @Before
    fun setUp() {
        context.deleteDatabase(name)
        open()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(name)
    }

    private fun open() {
        database = Room.databaseBuilder(context, RedfaceDatabase::class.java, name)
            .allowMainThreadQueries().build()
        store = FlagCacheStore(database.flagDao(), clock, UnconfinedTestDispatcher())
    }

    @Test
    fun `global positives use baseline source vocabulary and stay limited after reopening Room - P2c`() = runTest {
        val initial = FlagCacheSnapshot(listOf(row(13).copy(source = FlagSource.STICKY)), coverage(listOf(13)))
        database.flagDao().replaceSnapshot("alice", FlagType.CYAN, initial)
        val observation = row(23).toFlag()
        val batch = FlagRefreshBatch(null, emptyList(), now, globalOnly = true, observations = listOf(observation))
        store.save("alice", FlagType.CYAN, store.merge("alice", FlagType.CYAN, batch))
        database.close()
        open()
        val loaded = requireNotNull(store.load(FlagType.CYAN, "alice"))
        assertTrue(loaded.result.coverage.isGlobalObservation)
        assertFalse(loaded.isFresh)
        val disk = database.flagDao().getSnapshot("alice", FlagType.CYAN)
        assertEquals(initial.rows.single(), disk.rows.single { it.cat == 13 })
        // 94379fcc0's generated DAO uses an exhaustive enum reader (no tolerant converter).
        for (table in listOf("flag_topics", "flag_category_coverage")) {
            database.openHelper.readableDatabase.query("SELECT source FROM $table").use { cursor ->
                while (cursor.moveToNext()) BaselineFlagSource.valueOf(cursor.getString(0))
            }
        }
        assertEquals(FlagSource.LEGACY_UNKNOWN, disk.rows.single { it.cat == 23 }.source)
        store.save("alice", FlagType.CYAN, store.merge("alice", FlagType.CYAN,
            FlagRefreshBatch(null, emptyList(), now.plusSeconds(1), globalOnly = true)))
        assertEquals(disk.rows, database.flagDao().getSnapshot("alice", FlagType.CYAN).rows)
    }

    @Test
    fun `Room global observations survive failed buckets despite historical complete coverage - P2c fix2`() = runTest {
        for (historicalSuccess in listOf(old, now)) {
            for (catalogueAvailable in listOf(true, false)) {
                database.flagDao().replaceSnapshot("alice", FlagType.CYAN, FlagCacheSnapshot(
                    listOf(row(13), row(23)), coverage(listOf(13, 23), historicalSuccess),
                ))
                val observation = row(23).copy(topicId = 99).toFlag()
                store.save("alice", FlagType.CYAN, store.merge("alice", FlagType.CYAN,
                    FlagRefreshBatch(null, emptyList(), now, globalOnly = true,
                        observations = listOf(observation))))
                database.close()
                open()
                val persisted = database.flagDao().getFlags("alice", FlagType.CYAN).single { it.topicId == 99 }
                assertEquals(FlagSource.LEGACY_UNKNOWN, persisted.source)
                assertEquals(now, persisted.fetchedAt)
                val failed = if (catalogueAvailable) FlagRefreshBatch(listOf(13, 23), listOf(
                    outcome(13, FlagSource.BUCKET), outcome(13, FlagSource.STICKY),
                    outcome(23, FlagSource.BUCKET, FlagFailureKind.DEADLINE),
                    outcome(23, FlagSource.STICKY, FlagFailureKind.DEADLINE),
                ), now.plusSeconds(1)) else FlagRefreshBatch(null, emptyList(), now.plusSeconds(1),
                    cause = java.io.IOException("catalogue offline"))
                store.save("alice", FlagType.CYAN, store.merge("alice", FlagType.CYAN, failed))
                database.close()
                open()
                assertEquals(persisted, database.flagDao().getFlags("alice", FlagType.CYAN)
                    .single { it.topicId == 99 })
                assertFalse(requireNotNull(store.load(FlagType.CYAN, "alice")).isFresh)
                val success = FlagRefreshBatch(listOf(13, 23), listOf(
                    outcome(13, FlagSource.BUCKET), outcome(13, FlagSource.STICKY),
                    outcome(23, FlagSource.BUCKET), outcome(23, FlagSource.STICKY),
                ), now.plusSeconds(2))
                store.save("alice", FlagType.CYAN, store.merge("alice", FlagType.CYAN, success))
                assertTrue(database.flagDao().getFlags("alice", FlagType.CYAN).isEmpty())
                assertEquals(FlagRefreshStatus.COMPLETE,
                    requireNotNull(store.load(FlagType.CYAN, "alice")).result.refreshStatus)
            }
        }
    }

    @Test
    fun `migrated rows without coverage stay unverified and stale after reopening Room`() = runTest {
        database.flagDao().replaceSnapshot("alice", FlagType.CYAN, FlagCacheSnapshot(
            listOf(row(13).copy(source = FlagSource.LEGACY_UNKNOWN)), emptyList(),
        ))
        database.close()
        open()
        val loaded = requireNotNull(store.load(FlagType.CYAN, "alice"))
        assertEquals(FlagRefreshStatus.UNVERIFIED, loaded.result.refreshStatus)
        assertTrue(loaded.result.coverage.categories.isEmpty())
        assertNull(loaded.result.issue)
        assertFalse(loaded.isFresh)
    }

    @Test
    fun `failed category and successful empty category survive closing and reopening Room`() = runTest {
        database.flagDao().replaceSnapshot("alice", FlagType.CYAN, FlagCacheSnapshot(
            listOf(row(13), row(23)), coverage(listOf(13, 23)),
        ))
        val batch = FlagRefreshBatch(listOf(13, 23), listOf(
            outcome(13, FlagSource.BUCKET, FlagFailureKind.NETWORK),
            outcome(13, FlagSource.STICKY),
            outcome(23, FlagSource.BUCKET),
            outcome(23, FlagSource.STICKY),
        ), now)
        store.save("alice", FlagType.CYAN, store.merge("alice", FlagType.CYAN, batch))
        database.close()
        open()

        val loaded = requireNotNull(store.load(FlagType.CYAN, "alice"))
        assertEquals(listOf(13), loaded.result.flags.map { it.cat })
        assertEquals(FlagRefreshStatus.PARTIAL, loaded.result.refreshStatus)
        assertFalse(loaded.isFresh)
        assertEquals(old, database.flagDao().getFlags("alice", FlagType.CYAN).single().fetchedAt)
        val failed = loaded.result.coverage.categories.single { it.cat == 13 && it.source == FlagSource.BUCKET }
        assertEquals(old, failed.lastSuccessAt)
        assertEquals(now, failed.lastAttemptAt)
        assertEquals(FlagFailureKind.NETWORK, failed.failure)
        assertTrue(loaded.result.coverage.covers(23))
        assertNull(store.load(FlagType.CYAN, "bob"))
    }

    @Test
    fun `complete empty cache is fresh but invalidate then load means no cache`() = runTest {
        database.flagDao().replaceSnapshot("alice", FlagType.CYAN,
            FlagCacheSnapshot(emptyList(), coverage(listOf(13), now)))
        assertTrue(requireNotNull(store.load(FlagType.CYAN, "alice")).isFresh)
        store.invalidate("alice", FlagType.CYAN)
        assertNull(store.load(FlagType.CYAN, "alice"))
        assertTrue(database.flagDao().getCoverage("alice", FlagType.CYAN).isEmpty())
    }

    @Test
    fun `local favorite decoration preserves source dates and coverage`() = runTest {
        val before = coverage(listOf(13))
        database.flagDao().replaceSnapshot("alice", FlagType.CYAN,
            FlagCacheSnapshot(listOf(row(13)), before))
        store.markFavorite("alice", FlagType.CYAN, 13, 1)
        val after = database.flagDao().getSnapshot("alice", FlagType.CYAN)
        assertTrue(after.rows.single().isFavorite)
        assertEquals(old, after.rows.single().fetchedAt)
        assertEquals(FlagSource.BUCKET, after.rows.single().source)
        assertEquals(before, after.coverage)
    }

    @Test
    fun `one recent category cannot freshen older coverage even when it has the newest row`() = runTest {
        database.flagDao().replaceSnapshot("alice", FlagType.CYAN, FlagCacheSnapshot(
            listOf(row(23).copy(fetchedAt = now)), coverage(listOf(13)) + coverage(listOf(23), now),
        ))
        val loaded = requireNotNull(store.load(FlagType.CYAN, "alice"))
        assertEquals(FlagRefreshStatus.COMPLETE, loaded.result.refreshStatus)
        assertFalse(loaded.isFresh)
    }

    @Test
    fun `legacy rows retire after both sources succeeded and removed categories are purged`() = runTest {
        database.flagDao().replaceSnapshot("alice", FlagType.CYAN, FlagCacheSnapshot(
            listOf(row(13).copy(source = FlagSource.LEGACY_UNKNOWN), row(23)), emptyList(),
        ))
        val first = FlagRefreshBatch(listOf(13), listOf(outcome(13, FlagSource.BUCKET),
            outcome(13, FlagSource.STICKY, FlagFailureKind.NETWORK)), now)
        store.save("alice", FlagType.CYAN, store.merge("alice", FlagType.CYAN, first))
        assertEquals(listOf(13), database.flagDao().getFlags("alice", FlagType.CYAN).map { it.cat })
        assertTrue(database.flagDao().getCoverage("alice", FlagType.CYAN).all { it.cat == 13 })
        val second = FlagRefreshBatch(listOf(13), listOf(
            outcome(13, FlagSource.BUCKET, FlagFailureKind.NETWORK), outcome(13, FlagSource.STICKY),
        ), now.plusSeconds(1))
        store.save("alice", FlagType.CYAN, store.merge("alice", FlagType.CYAN, second))
        assertTrue(database.flagDao().getFlags("alice", FlagType.CYAN).isEmpty())
        assertNotNull(store.load(FlagType.CYAN, "alice"))
    }

    @Test
    fun `account and global purges remove coverage and single deletion preserves it`() = runTest {
        val alice = FlagCacheSnapshot(listOf(row(13)), coverage(listOf(13), now))
        val bob = FlagCacheSnapshot(listOf(row(13).copy(userId = "bob")),
            coverage(listOf(13), now).map { it.copy(userId = "bob") })
        database.flagDao().replaceSnapshot("alice", FlagType.CYAN, alice)
        database.flagDao().replaceSnapshot("bob", FlagType.CYAN, bob)
        store.delete("alice", FlagType.CYAN, 13, 1)
        assertTrue(requireNotNull(store.load(FlagType.CYAN, "alice")).isFresh)
        database.flagDao().deleteAllForUser("alice")
        assertNull(store.load(FlagType.CYAN, "alice"))
        assertNotNull(store.load(FlagType.CYAN, "bob"))
        database.flagDao().deleteAll()
        assertNull(store.load(FlagType.CYAN, "bob"))
    }

    private enum class BaselineFlagSource { BUCKET, STICKY, LEGACY_UNKNOWN }

    private fun coverage(cats: List<Int>, at: Instant = old): List<FlagCategoryCoverageEntity> = cats.flatMap { cat ->
        listOf(FlagSource.BUCKET, FlagSource.STICKY).map { source ->
            FlagCategoryCoverageEntity("alice", FlagType.CYAN, cat, source, at, at, null)
        }
    }

    private fun outcome(cat: Int, source: FlagSource, failure: FlagFailureKind? = null) =
        CategoryRefreshOutcome(cat, source, receivedAt = now, failure = failure)

    private fun row(cat: Int) = FlagTopicEntity(
        userId = "alice", type = FlagType.CYAN, cat = cat, subcat = null, topicId = 1,
        title = "Cached topic", totalPages = 1, replyCount = 1, hasUnread = true,
        lastReadPage = 1, lastPostReadId = null, firstPostAuthor = "alice", lastReplyAuthor = "bob",
        lastReplyAt = "2026-05-03 10:00", fetchedAt = old, authMode = FetchMode.AUTHENTICATED,
        source = FlagSource.BUCKET,
    )
}
