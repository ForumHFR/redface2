package fr.forumhfr.redface2.core.domain.mpstorage

import fr.forumhfr.redface2.core.model.mpstorage.MpStorageFlagEntry
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageResult
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageWriteResult

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

    /**
     * WRITE path (#6, ADR-014 §4 — deferred / opt-in). Read-modify-write of the storage document :
     * locate the dedicated MP, mutate its `mpFlags.list[]` to upsert [entry] (by [MpStorageFlagEntry.threadId])
     * **in place on the raw JSON tree** so every third-party namespace survives the round-trip, then
     * build the `bdd.php cat=prive` POST body.
     *
     * GUARDED BY DESIGN — NOT OBSERVED LIVE. The `bdd.php cat=prive` write contract was never captured
     * (device down). This public method is **DRY-RUN ONLY** : it locates, mutates, validates and builds the
     * body, then returns [MpStorageWriteResult.Prepared] with `posted = false` — **no request ever hits the
     * wire**, and there is no parameter to make it. The actual POST lives behind a module-internal,
     * test-only path on the implementation (NOT on this public interface) so it is **structurally
     * impossible to trigger from app/prod code** (Codex review) ; the ADR-014 §4 trigger is NOT wired.
     *
     * Target selection (Codex decision) is DETERMINISTIC : the first MP whose subject equals EXACTLY
     * [STORAGE_SUBJECT_HASH]. A miss is [MpStorageWriteResult.TargetNotFound] — NEVER a creation /
     * overwrite (ADR-014 §3 forbids the destructive reset ; creating a fresh doc would fork the
     * cross-userscript storage).
     *
     * The mutated `content_form` is capped at [MAX_CONTENT_FORM_BYTES] ([MpStorageWriteResult.TooLarge]
     * past it) : the real HFR MP body limit is unknown, so the cap fails CLOSED.
     *
     * @param entry the DT reading-resume position to upsert.
     */
    suspend fun writeBackFlag(entry: MpStorageFlagEntry): MpStorageWriteResult

    companion object {
        /** Fixed storage subject — the de-facto v0.1 contract's discriminator (#6). */
        const val STORAGE_SUBJECT_HASH: String = "a2bcc09b796b8c6fab77058ff8446c34"

        /**
         * Hard cap (Codex decision) on the mutated `content_form` UTF-8 size, 256 KiB. The real HFR
         * private-message body limit has never been observed ; 256 KiB comfortably exceeds any
         * realistic DTCloud `mpFlags.list` (the list is never pruned by the original library, hence
         * the risk) while bounding an uncontrolled POST. Crossing it fails CLOSED — NOT OBSERVED LIVE.
         */
        const val MAX_CONTENT_FORM_BYTES: Int = 256 * 1024
    }
}
