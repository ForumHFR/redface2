package fr.forumhfr.redface2.core.data.messages

import androidx.sqlite.db.SupportSQLiteDatabase
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal fun interface PrivateContentDatabaseScrubber {
    suspend fun scrub()
}

/** Removes post-purge private-content bytes from both the main SQLite file and its WAL. */
@Singleton
internal class RoomPrivateContentDatabaseScrubber @Inject constructor(
    private val database: RedfaceDatabase,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PrivateContentDatabaseScrubber {
    override suspend fun scrub() = withContext(ioDispatcher) {
        val sqliteDatabase = database.openHelper.writableDatabase
        // 1. Flush and truncate the WAL: page images predating the DELETE live there, and a WAL
        //    reset alone would not shrink the file.
        checkpointTruncate(sqliteDatabase)
        // 2. Rewrite the database without the freed pages.
        sqliteDatabase.execSQL(VACUUM)
        // 3. VACUUM is an ordinary transaction: in WAL mode it commits its clean image *into the
        //    WAL*, leaving the purged bytes in redface.db until the next checkpoint. Android's
        //    passive autocheckpoint often catches up, but never guarantees it (small database,
        //    concurrent reader), so the post-purge window this scrub closes requires an explicit
        //    second checkpoint. Measured: without it the sentinel survives in redface.db.
        checkpointTruncate(sqliteDatabase)
    }

    private fun checkpointTruncate(sqliteDatabase: SupportSQLiteDatabase) {
        val checkpointBusy = sqliteDatabase.query(WAL_CHECKPOINT_TRUNCATE).use { cursor ->
            check(cursor.moveToFirst()) { "SQLite returned no WAL checkpoint result" }
            cursor.getInt(CHECKPOINT_BUSY_COLUMN)
        }
        check(checkpointBusy == CHECKPOINT_COMPLETED) { "SQLite WAL checkpoint remained busy" }
    }

    private companion object {
        const val WAL_CHECKPOINT_TRUNCATE = "PRAGMA wal_checkpoint(TRUNCATE)"
        const val VACUUM = "VACUUM"
        const val CHECKPOINT_BUSY_COLUMN = 0
        const val CHECKPOINT_COMPLETED = 0
    }
}
