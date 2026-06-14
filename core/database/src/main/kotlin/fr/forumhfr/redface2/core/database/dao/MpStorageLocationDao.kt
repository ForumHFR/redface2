package fr.forumhfr.redface2.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import fr.forumhfr.redface2.core.database.entities.MpStorageLocationEntity

/**
 * Read/write access to the per-account cached MPStorage location (#6, ADR-014). One row per
 * `userId`; see [MpStorageLocationEntity] for the privacy contract.
 */
@Dao
interface MpStorageLocationDao {

    @Query("SELECT * FROM mp_storage_locations WHERE userId = :userId")
    suspend fun get(userId: String): MpStorageLocationEntity?

    @Upsert
    suspend fun upsert(location: MpStorageLocationEntity)

    /** Wipes the location owned by [userId] — logout / account switch (CacheInvalidator) or a stale read. */
    @Query("DELETE FROM mp_storage_locations WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)
}
