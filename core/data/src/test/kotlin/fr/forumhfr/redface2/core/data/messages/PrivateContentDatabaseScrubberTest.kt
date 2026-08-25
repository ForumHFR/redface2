package fr.forumhfr.redface2.core.data.messages

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import java.io.File
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric so Room can open a real on-disk SQLite file (and its WAL) — same runner and SDK pin
 * as the other suites that build a `RedfaceDatabase` (`PrivateMessageContentDaoTest`,
 * `DefaultTopicCacheMaintenanceTest`, `TopicRepositoryImplTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PrivateContentDatabaseScrubberTest {
    private lateinit var context: Context
    private lateinit var database: RedfaceDatabase
    private lateinit var databaseFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(RedfaceDatabase.DATABASE_NAME)
        databaseFile = context.getDatabasePath(RedfaceDatabase.DATABASE_NAME)
        database = Room.databaseBuilder(
            context,
            RedfaceDatabase::class.java,
            RedfaceDatabase.DATABASE_NAME,
        )
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(RedfaceDatabase.DATABASE_NAME)
    }

    @Test
    fun `privacy purge removes sentinel bytes from database and WAL`() = runTest {
        val scrubber = RoomPrivateContentDatabaseScrubber(
            database = database,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val cache = RoomPrivateMessageThreadDiskCache(database.privateMessageContentDao(), scrubber)
        cache.replace("Alice", sentinelThread(), Instant.parse("2026-08-25T12:00:00Z"))

        assertTrue("sentinel must really reach SQLite before the purge", containsSentinel())

        cache.clearAll()

        assertEquals(0, rowCount("mp_messages"))
        assertEquals(0, rowCount("mp_thread_pages"))
        assertFalse("sentinel must disappear from redface.db and redface.db-wal", containsSentinel())
    }

    private fun rowCount(table: String): Int = database.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM $table")
        .use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun containsSentinel(): Boolean {
        val sentinel = SENTINEL.encodeToByteArray()
        return listOf(databaseFile, File("${databaseFile.path}-wal"))
            .filter(File::exists)
            .any { file -> file.readBytes().containsSubsequence(sentinel) }
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        return indices
            .take(size - needle.size + 1)
            .any { offset -> needle.indices.all { index -> this[offset + index] == needle[index] } }
    }

    private fun sentinelThread(): PrivateMessageThread = PrivateMessageThread(
        threadId = 42,
        subject = "subject",
        correspondent = "correspondent",
        messages = listOf(
            Post(
                numreponse = 7,
                author = "author",
                date = Instant.parse("2026-08-25T12:00:00Z"),
                content = PostContent(
                    listOf(PostBlock.Paragraph(listOf(PostInline.Text(SENTINEL)))),
                ),
                avatarUrl = null,
                isEditable = false,
                isOwnPost = false,
                quotedAuthors = emptyList(),
                postIndex = null,
            ),
        ),
        page = 1,
        totalPages = 1,
    )

    private companion object {
        const val SENTINEL = "RF2_PRIVATE_MP_SENTINEL_1040_LOT7"
    }
}
