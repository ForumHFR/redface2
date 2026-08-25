package fr.forumhfr.redface2.core.data.messages

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
        val checkpointBusy = sqliteDatabase.query(WAL_CHECKPOINT_TRUNCATE).use { cursor ->
            check(cursor.moveToFirst()) { "SQLite returned no WAL checkpoint result" }
            cursor.getInt(CHECKPOINT_BUSY_COLUMN)
        }
        check(checkpointBusy == CHECKPOINT_COMPLETED) { "SQLite WAL checkpoint remained busy" }
        sqliteDatabase.execSQL(VACUUM)
    }

    private companion object {
        const val WAL_CHECKPOINT_TRUNCATE = "PRAGMA wal_checkpoint(TRUNCATE)"
        const val VACUUM = "VACUUM"
        const val CHECKPOINT_BUSY_COLUMN = 0
        const val CHECKPOINT_COMPLETED = 0
    }
}
