package fr.forumhfr.redface2.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
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

    @Query("DELETE FROM editor_drafts WHERE draftKey = :draftKey AND attemptState IS NULL")
    suspend fun deleteByKey(draftKey: String)

    /** Legacy autosaves cannot overwrite a durable attempt (ED-161/170). */
    @Transaction
    suspend fun saveLegacy(draft: EditorDraftEntity) {
        val previous = get(draft.draftKey)
        if (previous?.attemptState == null) {
            upsert(draft.copy(contentVersion = (previous?.contentVersion ?: 0L) + 1))
        }
    }

    /** ED-170: compare and write within one Room transaction, including the absent-row case. */
    @Transaction
    suspend fun saveVersioned(draft: EditorDraftEntity, expectedVersion: Long, newComposition: Boolean): Boolean {
        val previous = get(draft.draftKey)
        val versionMatches = (previous?.contentVersion ?: 0L) == expectedVersion
        val attemptState = previous?.attemptState
        val writable = attemptState == null || (newComposition && attemptState == "PUBLISHED")
        if (versionMatches && writable) upsert(draft)
        return versionMatches && writable
    }

    /** ED-170: content has its own revision; editing never turns uncertainty into an ordinary draft. */
    @Transaction
    suspend fun saveUnresolved(draft: EditorDraftEntity, expectedVersion: Long): Boolean {
        val previous = get(draft.draftKey)
        val matches = previous != null && previous.contentVersion == expectedVersion &&
            previous.attemptId == draft.attemptId && previous.attemptVersion == draft.attemptVersion &&
            previous.attemptState in listOf("IN_PROGRESS", "UNCERTAIN")
        if (matches) upsert(draft.copy(attemptState = "UNCERTAIN"))
        return matches
    }

    /** ED-170: snapshot plus attempt are committed together; unresolved live sends are exclusive. */
    @Transaction
    suspend fun beginAttempt(draft: EditorDraftEntity, expectedVersion: Long): Boolean {
        val previous = get(draft.draftKey)
        val versionMatches = (previous?.contentVersion ?: 0L) == expectedVersion
        val attemptState = previous?.attemptState
        val writable = attemptState == null
        if (versionMatches && writable) upsert(draft)
        return versionMatches && writable
    }

    /** ED-170: only the current unresolved attempt may record its outcome. */
    @Query(
        """
        UPDATE editor_drafts SET attemptState = :outcome,
            attemptId = CASE WHEN :outcome IS NULL THEN NULL ELSE attemptId END,
            attemptVersion = CASE WHEN :outcome IS NULL THEN NULL ELSE attemptVersion END
        WHERE draftKey = :draftKey AND attemptVersion = :version
            AND attemptId = :attemptId AND attemptState IN ('IN_PROGRESS', 'UNCERTAIN')
        """,
    )
    suspend fun finishAttempt(draftKey: String, attemptId: String, version: Long, outcome: String?): Int

    /** Outcome acknowledges the attempt, while returning the latest edited content revision atomically. */
    @Transaction
    suspend fun finishVersioned(draftKey: String, attemptId: String, version: Long, outcome: String?): Long? =
        if (finishAttempt(draftKey, attemptId, version, outcome) == 1) get(draftKey)?.contentVersion else null

    /** ED-161: keep the published row so old autosaves cannot resurrect its body. */
    @Query(
        """
        UPDATE editor_drafts SET body = '', subject = NULL, recipients = NULL
        WHERE draftKey = :draftKey AND contentVersion = :version AND attemptVersion = :version
            AND attemptId = :attemptId AND attemptState = 'PUBLISHED'
        """,
    )
    suspend fun erasePublishedContent(draftKey: String, attemptId: String, version: Long): Int

    /**
     * Logout/account switch: erase every private content field for this owner. Keep only durable
     * attempt metadata (including PUBLISHED), never readable as another account's draft (#1080).
     * Ordinary private drafts are deleted; retention remains responsible for the surviving markers.
     */
    @Transaction
    suspend fun deletePrivateForUser(ownerId: String) {
        deletePrivateWithoutAttempt(ownerId)
        erasePrivateAttemptContent(ownerId)
    }

    @Query("DELETE FROM editor_drafts WHERE ownerId = :ownerId AND isPrivate = 1 AND attemptState IS NULL")
    suspend fun deletePrivateWithoutAttempt(ownerId: String)

    @Query(
        """
        UPDATE editor_drafts SET body = '', subject = NULL, recipients = NULL
        WHERE ownerId = :ownerId AND isPrivate = 1 AND attemptState IN ('IN_PROGRESS', 'UNCERTAIN', 'PUBLISHED')
        """,
    )
    suspend fun erasePrivateAttemptContent(ownerId: String)

    /** Retention purge run on app start: drops drafts last touched before [cutoffMillis]. */
    @Query("DELETE FROM editor_drafts WHERE updatedAt < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)
}
