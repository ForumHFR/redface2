package fr.forumhfr.redface2.core.domain.mpstorage

import fr.forumhfr.redface2.core.model.mpstorage.MpStorageResult

/**
 * MPStorage read access (#6, ADR-014) — the cross-userscript storage document living in the
 * first post of a dedicated HFR private message (subject = fixed hash, recipient = the
 * third-party `MultiMP` account).
 *
 * READ-ONLY by design for Phase 3 : the write path (full-overwrite `bdd.php`, last-write-wins)
 * is deferred to a dedicated opt-in follow-up (ADR-014 §4). Implementations must NEVER write
 * anything — in particular never "repair" an unreadable document (the original library's
 * destructive-reset trap).
 *
 * [fetchStorage] performs the full discovery + read pipeline (authenticated) :
 * subject search → conversation first post → edit form → `content_form` JSON. The
 * « account has no storage » outcome ([MpStorageResult.NotFound]) is the first-class
 * nominal case, not an error. Transport / session failures are raised as exceptions.
 *
 * Reading marks the storage conversation itself as read server-side (a GET of any page of a
 * `cat=prive` conversation clears its whole-conversation dot, cf. #361/ADR-013) — acceptable :
 * the storage MP is machinery, not user correspondence ; DTCloud behaves identically.
 */
interface MpStorageRepository {

    suspend fun fetchStorage(): MpStorageResult

    companion object {
        /** Fixed storage subject — the de-facto v0.1 contract's discriminator (#6). */
        const val STORAGE_SUBJECT_HASH: String = "a2bcc09b796b8c6fab77058ff8446c34"
    }
}
