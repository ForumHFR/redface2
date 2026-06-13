package fr.forumhfr.redface2.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import fr.forumhfr.redface2.core.database.entities.MpReadPositionEntity

/**
 * Read/write access to the per-account MP reading positions (#430). One row per
 * `(userId, threadId)`; see [MpReadPositionEntity] for the privacy contract.
 */
@Dao
interface MpReadPositionDao {

    @Query("SELECT page FROM mp_read_positions WHERE userId = :userId AND threadId = :threadId")
    suspend fun readPage(userId: String, threadId: Int): Int?

    @Upsert
    suspend fun upsert(position: MpReadPositionEntity)

    /** Wipes every position owned by [userId] — logout / account-switch purge (CacheInvalidator). */
    @Query("DELETE FROM mp_read_positions WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)
}
