package fr.forumhfr.redface2.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import fr.forumhfr.redface2.core.database.entities.EditorDraftEntity

/**
 * Read/write access to the per-account editor drafts (#405). See [EditorDraftEntity] for the
 * privacy contract.
 */
@Dao
interface EditorDraftDao {

    @Query("SELECT * FROM editor_drafts WHERE draftKey = :draftKey")
    suspend fun get(draftKey: String): EditorDraftEntity?

    @Upsert
    suspend fun upsert(draft: EditorDraftEntity)

    @Query("DELETE FROM editor_drafts WHERE draftKey = :draftKey")
    suspend fun deleteByKey(draftKey: String)

    /** Logout / account-switch purge of MP drafts owned by [ownerId] (CacheInvalidator). */
    @Query("DELETE FROM editor_drafts WHERE ownerId = :ownerId AND isPrivate = 1")
    suspend fun deletePrivateForUser(ownerId: String)

    /** Retention purge run on app start: drops drafts last touched before [cutoffMillis]. */
    @Query("DELETE FROM editor_drafts WHERE updatedAt < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)
}
