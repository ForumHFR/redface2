package fr.forumhfr.redface2.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import fr.forumhfr.redface2.core.database.entities.UploadedImageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Read/write access to the per-account uploaded-image history (#459). One row per
 * `(userId, provider, picId)`; see [UploadedImageEntity] for the privacy contract.
 *
 * As with [FlagDao], the DAO does NOT normalise [UploadedImageEntity.userId] — callers MUST
 * `.lowercase()` the pseudo before invoking any method, so the byte-exact Room equality matches.
 */
@Dao
interface UploadedImageDao {

    @Query("SELECT * FROM uploaded_images WHERE userId = :userId ORDER BY uploadedAt DESC")
    fun observeForUser(userId: String): Flow<List<UploadedImageEntity>>

    @Upsert
    suspend fun upsert(image: UploadedImageEntity)

    @Query(
        "DELETE FROM uploaded_images WHERE userId = :userId AND provider = :provider AND picId = :picId",
    )
    suspend fun delete(userId: String, provider: String, picId: String)

    /** Logout / account-switch purge of every image owned by [userId] (CacheInvalidator). */
    @Query("DELETE FROM uploaded_images WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)
}
