package fr.forumhfr.redface2.core.database.stats

import android.content.Context
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class NetworkStatsDaoTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `duplicate keys add counts and maximize peaks per build and UTC day`() = runTest {
        Room.inMemoryDatabaseBuilder(context, NetworkStatsDatabase::class.java).build().withDatabase { database ->
            val dao = database.statsDao()
            dao.merge(batch(25))
            dao.merge(batch(25))
            dao.merge(batch(26, versionCode = 73))
            dao.merge(NetworkStatsRows(emptyList(), emptyList(), listOf(
                NetworkPeakEntity(27, 72, "FORUM", 5, 1, 4),
                NetworkPeakEntity(25, 72, "FORUM", 1, 10, 3),
            )))

            val totals = dao.readDays(25, 30)
            val row = totals.requests.single { it.dimensions.versionCode == 72 }
            assertEquals(24L, row.dimensions.hourUtc)
            assertEquals(6L, row.requests)
            assertEquals(18L, row.bodyBytesIn)
            assertEquals(12L, row.bodyBytesOut)
            assertEquals(2L, row.incompleteBodies)
            assertEquals(8L, totals.noRequests.single { it.dimensions.versionCode == 72 }.count)
            assertEquals(NetworkPeakEntity(24, 72, "FORUM", 5, 10, 4),
                totals.peaks.single { it.versionCode == 72 })
            assertEquals(3L, totals.requests.single { it.dimensions.versionCode == 73 }.requests)
            assertEquals(25L, dao.firstHour())
        }
    }

    @Test
    fun `network transport is a key for both sent and unsent rows`() = runTest {
        Room.inMemoryDatabaseBuilder(context, NetworkStatsDatabase::class.java).build().withDatabase { database ->
            val dao = database.statsDao()
            dao.merge(batch(25, network = "WIFI"))
            dao.merge(batch(25, network = "CELLULAR"))
            dao.merge(batch(25, network = "WIFI"))

            val rows = dao.readDays(24, 48)
            assertEquals(mapOf("WIFI" to 6L, "CELLULAR" to 3L),
                rows.requests.associate { it.dimensions.network to it.requests })
            assertEquals(mapOf("WIFI" to 8L, "CELLULAR" to 4L),
                rows.noRequests.associate { it.dimensions.network to it.count })
        }
    }

    @Test
    fun `actions add by trigger and build and participate in retention and reset`() = runTest {
        Room.inMemoryDatabaseBuilder(context, NetworkStatsDatabase::class.java).build().withDatabase { database ->
            val dao = database.statsDao()
            val older = NetworkActionEntity(24, 72, "FLAG_REFRESH_ADMITTED", "PULL", 3)
            val latest = older.copy(hourUtc = 25, count = 2)
            val other = latest.copy(trigger = "TAB", count = 1)
            val nextBuild = latest.copy(versionCode = 73, count = 1)
            dao.merge(NetworkStatsRows(emptyList(), emptyList(), emptyList(), listOf(older, latest, other, nextBuild)))
            dao.merge(NetworkStatsRows(emptyList(), emptyList(), emptyList(), listOf(latest)))

            assertEquals(24L, dao.firstHour())
            assertEquals(setOf(72, 73), dao.versions().toSet())
            assertEquals(setOf(older.copy(count = 7), other.copy(hourUtc = 24), nextBuild.copy(hourUtc = 24)),
                dao.readDays(24, 48).actions.toSet())
            dao.purge(25)
            assertEquals(25L, dao.firstHour())
            assertEquals(4L, dao.readDays(24, 48).actions.single { it.versionCode == 72 && it.trigger == "PULL" }.count)
            dao.reset()
            assertTrue(dao.readDays(24, 48).actions.isEmpty())
            assertTrue(dao.versions().isEmpty())
            assertNull(dao.firstHour())
        }
    }

    @Test
    fun `retention purges old rows from every table and reset clears the rest`() = runTest {
        Room.inMemoryDatabaseBuilder(context, NetworkStatsDatabase::class.java).build().withDatabase { database ->
            val dao = database.statsDao()
            dao.merge(batch(9))
            dao.merge(batch(10))
            dao.purge(beforeHour = 10)

            val recent = dao.readDays(0, 24)
            assertEquals(3L, recent.requests.single().requests)
            assertEquals(4L, recent.noRequests.single().count)
            assertEquals(10L, dao.firstHour())
            val expired = dao.readDays(0, 10)
            assertTrue(expired.requests.isEmpty() && expired.noRequests.isEmpty() && expired.peaks.isEmpty())

            dao.reset()
            assertNull(dao.firstHour())
        }
    }

    @Test
    fun `the opened database and WAL files live under noBackupFilesDir`() = runTest {
        val name = NetworkStatsDatabase.DATABASE_NAME
        val file = File(context.noBackupFilesDir, name)
        try {
            NetworkStatsDatabase.open(context).withDatabase { database ->
                database.openHelper.setWriteAheadLoggingEnabled(true)
                database.statsDao().merge(batch(27))

                val openedPath = requireNotNull(database.openHelper.readableDatabase.path)
                assertEquals(file.canonicalPath, File(openedPath).canonicalPath)
                for (suffix in listOf("", "-shm", "-wal")) {
                    assertTrue(File(context.noBackupFilesDir, name + suffix).isFile)
                    assertTrue(!context.getDatabasePath(name + suffix).exists())
                }
            }
        } finally {
            context.deleteDatabase(file.absolutePath)
        }
    }

    @Test
    fun `opening version one over a future version recreates the statistics database`() = runTest {
        val name = File(context.noBackupFilesDir, NetworkStatsDatabase.DATABASE_NAME).absolutePath
        context.deleteDatabase(name)
        try {
            NetworkStatsDatabase.open(context).withDatabase { it.statsDao().merge(batch(27)) }
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { future ->
                future.execSQL("CREATE TABLE future_stats (version INTEGER NOT NULL)")
                future.version = 2
            }
            NetworkStatsDatabase.open(context).withDatabase { database ->
                assertNull(database.statsDao().firstHour())
                val sql = database.openHelper.readableDatabase
                assertEquals(1, sql.version)
                sql.query("SELECT name FROM sqlite_master WHERE name = 'future_stats'").use { cursor ->
                    assertEquals(0, cursor.count)
                }
                database.statsDao().merge(batch(28))
                assertEquals(28L, database.statsDao().firstHour())
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private inline fun NetworkStatsDatabase.withDatabase(block: (NetworkStatsDatabase) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    private fun batch(hour: Long, versionCode: Int = 72, network: String = "UNKNOWN"): NetworkStatsRows {
        val dims = NetworkDimensionsColumns(hour, versionCode, "HTML_FORUM2", "AUTH", "UNKNOWN", "NONE", network)
        return NetworkStatsRows(
            listOf(NetworkRequestEntity(dims, "SUCCESS", 3, 9, 6, 1)),
            listOf(NoRequestEntity(dims, "DNS", 4)),
            listOf(NetworkPeakEntity(hour, versionCode, "FORUM", 2, 3, 1)),
        )
    }
}
