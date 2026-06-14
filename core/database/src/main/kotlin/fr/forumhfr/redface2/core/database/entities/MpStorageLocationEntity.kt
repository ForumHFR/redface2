package fr.forumhfr.redface2.core.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached location of the per-account MPStorage conversation (#6, ADR-014): the dedicated MP whose
 * subject is the fixed hash and whose first post carries the cross-userscript JSON document. Caching
 * it lets the repository read the document directly instead of re-scanning the whole MP inbox on
 * every fetch — the same optimisation `MPStorage.user.js` / DTCloud apply by remembering `mpId`
 * after the first discovery.
 *
 * One row per account ([userId] is the PK: an account has at most one storage MP). Like
 * [MpReadPositionEntity] the row is private — it reveals the account owns a storage MP and at which
 * conversation — so it is wiped on logout / account switch (cf. `CacheInvalidator`). Only ids are
 * stored, never document content.
 */
@Entity(tableName = "mp_storage_locations")
data class MpStorageLocationEntity(
    /** Lowercased HFR pseudo of the account that owns this row. */
    @PrimaryKey val userId: String,
    /** HFR `post` id of the storage conversation (unique within `cat=prive`). */
    val threadId: Int,
    /** `numreponse` of the conversation's FIRST post (the post holding the JSON document). */
    val numreponse: Int,
)
