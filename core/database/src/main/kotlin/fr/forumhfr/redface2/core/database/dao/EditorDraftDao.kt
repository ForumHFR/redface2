package fr.forumhfr.redface2.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import fr.forumhfr.redface2.core.database.entities.EditorDraftEntity
import fr.forumhfr.redface2.core.database.entities.EditorDraftOfferEntity

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
    suspend fun saveLegacy(draft: EditorDraftEntity, offer: EditorDraftOfferEntity? = null) {
        val previous = get(draft.draftKey)
        if (previous?.attemptState == null) {
            preserveOffer(previous, offer)
            upsert(draft.copy(
                contentVersion = (previous?.contentVersion ?: 0L) + 1,
                pendingInlineQuoteRequest = previous?.pendingInlineQuoteRequest,
            ))
        }
    }

    /** ED-170: compare and write within one Room transaction, including the absent-row case. */
    @Transaction
    suspend fun saveVersioned(
        draft: EditorDraftEntity, expectedVersion: Long, newComposition: Boolean,
        offer: EditorDraftOfferEntity? = null,
    ): Boolean {
        val previous = get(draft.draftKey)
        val versionMatches = (previous?.contentVersion ?: 0L) == expectedVersion
        val attemptState = previous?.attemptState
        val writable = attemptState == null || (newComposition && attemptState == "PUBLISHED")
        if (versionMatches && writable) {
            preserveOffer(previous, offer)
            upsert(draft)
        }
        return versionMatches && writable
    }

    /** ED-170: content has its own revision; editing never turns uncertainty into an ordinary draft. */
    @Transaction
    suspend fun saveUnresolved(
        draft: EditorDraftEntity, expectedVersion: Long, offer: EditorDraftOfferEntity? = null,
    ): Boolean {
        val previous = get(draft.draftKey)
        val matches = previous != null && previous.contentVersion == expectedVersion &&
            previous.attemptId == draft.attemptId && previous.attemptVersion == draft.attemptVersion &&
            previous.attemptState in listOf("IN_PROGRESS", "UNCERTAIN")
        if (matches) {
            preserveOffer(previous, offer)
            upsert(draft.copy(attemptState = "UNCERTAIN"))
        }
        return matches
    }

    /** ED-170: snapshot plus attempt are committed together; unresolved live sends are exclusive. */
    @Transaction
    suspend fun beginAttempt(
        draft: EditorDraftEntity, expectedVersion: Long, offer: EditorDraftOfferEntity? = null,
    ): Boolean {
        val previous = get(draft.draftKey)
        val versionMatches = (previous?.contentVersion ?: 0L) == expectedVersion
        val attemptState = previous?.attemptState
        val writable = attemptState == null
        if (versionMatches && writable) {
            preserveOffer(previous, offer)
            upsert(draft)
        }
        return versionMatches && writable
    }

    /**
     * #1553: dedicated entry/consumption CAS. Comparing the complete read also detects a concurrent
     * metadata-only publication (which intentionally leaves contentVersion unchanged). The store
     * prepares the merged request and preserves fields/status without using saveUnresolved.
     */
    @Transaction
    suspend fun mutateInlineQuoteRequest(
        draft: EditorDraftEntity, expected: EditorDraftEntity?, offer: EditorDraftOfferEntity? = null,
    ): Boolean {
        val previous = get(draft.draftKey)
        val matches = previous == expected && draft.contentVersion == (previous?.contentVersion ?: 0L) + 1
        if (matches) {
            preserveOffer(previous, offer)
            upsert(draft)
        }
        return matches
    }

    /**
     * ED-121/132: the delivered C, the consumption of Q and the resolution of a D already merged into
     * C commit together. No partial state (quote durable with Q pending, or D still offered) survives
     * a failure or a process death inside this transaction.
     */
    @Transaction
    suspend fun consumeInlineQuoteRequestResolvingOffer(
        draft: EditorDraftEntity, expected: EditorDraftEntity?, resolved: EditorDraftOfferEntity,
        protection: EditorDraftOfferEntity? = null,
    ): Boolean {
        val previous = get(draft.draftKey)
        val stored = offers(resolved.ownerId, resolved.contextKey).any { it == resolved }
        val source = previous?.contentVersion == resolved.sourceVersion && previous.body == resolved.body &&
            previous.subject == resolved.subject && previous.recipients == resolved.recipients
        val available = stored || (source && previous?.attemptState == null)
        val current = previous == expected && draft.contentVersion == (previous?.contentVersion ?: 0L) + 1
        val matches = current && available
        if (matches) {
            if (protection?.offerId != resolved.offerId) preserveOffer(previous, protection)
            upsert(draft)
            deleteOffer(resolved.ownerId, resolved.contextKey, resolved.offerId)
        }
        return matches
    }

    /** ED-170: only the current unresolved attempt may record its outcome. */
    @Query(
        """
        UPDATE editor_drafts SET attemptState = :outcome, updatedAt = :updatedAt,
            attemptId = CASE WHEN :outcome IS NULL THEN NULL ELSE attemptId END,
            attemptVersion = CASE WHEN :outcome IS NULL THEN NULL ELSE attemptVersion END,
            pendingInlineQuoteRequest = CASE WHEN :outcome = 'PUBLISHED' THEN NULL ELSE pendingInlineQuoteRequest END
        WHERE draftKey = :draftKey AND attemptVersion = :version
            AND attemptId = :attemptId AND attemptState IN ('IN_PROGRESS', 'UNCERTAIN')
        """,
    )
    suspend fun finishAttempt(
        draftKey: String, attemptId: String, version: Long, outcome: String?, updatedAt: Long,
    ): Int

    /** Outcome acknowledges the attempt, while returning the latest edited content revision atomically. */
    @Transaction
    suspend fun finishVersioned(
        draftKey: String, attemptId: String, version: Long, outcome: String?, updatedAt: Long,
    ): Long? = if (finishAttempt(draftKey, attemptId, version, outcome, updatedAt) == 1) {
        get(draftKey)?.contentVersion
    } else {
        null
    }

    /** ED-161: keep the published row so old autosaves cannot resurrect its body. */
    @Query(
        """
        UPDATE editor_drafts SET body = '', subject = NULL, recipients = NULL,
            pendingInlineQuoteRequest = NULL, bodyProvenance = NULL, updatedAt = :updatedAt
        WHERE draftKey = :draftKey AND contentVersion = :version AND attemptVersion = :version
            AND attemptId = :attemptId AND attemptState = 'PUBLISHED'
        """,
    )
    suspend fun erasePublishedContent(draftKey: String, attemptId: String, version: Long, updatedAt: Long): Int

    /**
     * Logout/account switch: erase every private content field for this owner. Keep only durable
     * attempt metadata (including PUBLISHED), never readable as another account's draft (#1080).
     * Ordinary private drafts are deleted. Retention later expires the PUBLISHED markers; an erased
     * IN_PROGRESS/UNCERTAIN marker stays until its explicit resolution.
     */
    @Transaction
    suspend fun deletePrivateForUser(ownerId: String) {
        deletePrivateOffers(ownerId)
        deletePrivateWithoutAttempt(ownerId)
        erasePrivateAttemptContent(ownerId)
    }

    @Query("DELETE FROM editor_drafts WHERE ownerId = :ownerId AND isPrivate = 1 AND attemptState IS NULL")
    suspend fun deletePrivateWithoutAttempt(ownerId: String)

    @Query(
        """
        UPDATE editor_drafts SET body = '', subject = NULL, recipients = NULL, pendingInlineQuoteRequest = NULL,
            bodyProvenance = NULL
        WHERE ownerId = :ownerId AND isPrivate = 1 AND attemptState IN ('IN_PROGRESS', 'UNCERTAIN', 'PUBLISHED')
        """,
    )
    suspend fun erasePrivateAttemptContent(ownerId: String)

    /**
     * Retention purge run on app start: drops drafts and offers last touched before [cutoffMillis].
     * A row whose send is unresolved (IN_PROGRESS or UNCERTAIN) is never aged out: its text and attempt
     * marker are the only protection against a duplicate send, so it lives until an explicit resolution.
     */
    @Transaction
    suspend fun deleteOlderThan(cutoffMillis: Long) {
        deleteOldOffers(cutoffMillis)
        deleteOldContent(cutoffMillis)
    }

    // `NOT IN` alone is NULL for an ordinary draft (attemptState IS NULL) and would keep it forever.
    @Query(
        """
        DELETE FROM editor_drafts WHERE updatedAt < :cutoffMillis
            AND (attemptState IS NULL OR attemptState NOT IN ('IN_PROGRESS', 'UNCERTAIN'))
        """,
    )
    suspend fun deleteOldContent(cutoffMillis: Long)

    @Query("DELETE FROM editor_draft_offers WHERE updatedAt < :cutoffMillis")
    suspend fun deleteOldOffers(cutoffMillis: Long)

    @Query("DELETE FROM editor_draft_offers WHERE ownerId = :ownerId AND isPrivate = 1")
    suspend fun deletePrivateOffers(ownerId: String)

    @Query(
        """SELECT * FROM editor_draft_offers WHERE ownerId = :ownerId AND contextKey = :key
        ORDER BY updatedAt DESC, sourceVersion DESC, offerId DESC""",
    )
    suspend fun offers(ownerId: String, key: String): List<EditorDraftOfferEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOffer(offer: EditorDraftOfferEntity)

    /** The first replacement copies the exact source revision. Later saves never recreate an expired offer. */
    @Suppress("ComplexCondition") // Copy only the exact owner/context/revision/fields, never an unresolved attempt.
    suspend fun preserveOffer(previous: EditorDraftEntity?, offer: EditorDraftOfferEntity?) {
        if (previous != null && offer != null && previous.contentVersion == offer.sourceVersion &&
            previous.ownerId == offer.ownerId && previous.draftKey == "${offer.ownerId}|${offer.contextKey}" &&
            previous.body == offer.body && previous.subject == offer.subject &&
            previous.recipients == offer.recipients &&
            previous.attemptState == null
        ) insertOffer(offer)
    }

    @Query("DELETE FROM editor_draft_offers WHERE ownerId = :ownerId AND contextKey = :key AND offerId = :offerId")
    suspend fun deleteOffer(ownerId: String, key: String, offerId: String)

    /**
     * C + consumption commit together. A second invocation cannot restore the same offer twice.
     * Attempt status is part of the CAS: publication does not advance the content revision.
     */
    @Transaction
    suspend fun resolveOffer(
        draft: EditorDraftEntity, expectedVersion: Long, offer: EditorDraftOfferEntity,
        expectedAttemptState: String?,
        protection: EditorDraftOfferEntity? = null,
    ): Boolean {
        val previous = get(draft.draftKey)
        val stored = offers(offer.ownerId, offer.contextKey).any { it == offer }
        val source = previous?.contentVersion == offer.sourceVersion && previous.body == offer.body &&
            previous.subject == offer.subject && previous.recipients == offer.recipients
        val offerUnavailable = !stored && !(source && previous?.attemptState == null)
        val versionMatches = (previous?.contentVersion ?: 0L) == expectedVersion
        val attemptMatches = previous?.attemptState == expectedAttemptState
        return if (!versionMatches || !attemptMatches || offerUnavailable) {
            false
        } else {
            if (protection?.offerId != offer.offerId) preserveOffer(previous, protection)
            upsert(draft.resolvingOffer(previous))
            deleteOffer(offer.ownerId, offer.contextKey, offer.offerId)
            true
        }
    }
}

/** Start a composition after publication; unresolved sends keep their identity as with saveUnresolved. */
private fun EditorDraftEntity.resolvingOffer(previous: EditorDraftEntity?): EditorDraftEntity {
    val attempt = previous?.takeIf { it.attemptState in listOf("IN_PROGRESS", "UNCERTAIN") }
    return copy(
        attemptId = attempt?.attemptId,
        attemptVersion = attempt?.attemptVersion,
        attemptState = attempt?.let { "UNCERTAIN" },
        pendingInlineQuoteRequest = previous?.takeUnless { it.attemptState == "PUBLISHED" }
            ?.pendingInlineQuoteRequest,
    )
}
