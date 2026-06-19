package fr.forumhfr.redface2.core.domain.mpstorage

import fr.forumhfr.redface2.core.model.mpstorage.MpStorageFlagEntry
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageResult
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageWriteResult

/**
 * MPStorage read access (#6, ADR-014) — the cross-userscript storage document living in the
 * first post of a dedicated HFR private message (subject = fixed hash, recipient = the
 * third-party `MultiMP` account).
 *
 * [fetchStorage] performs the full discovery + read pipeline (authenticated) :
 * subject search → conversation first post → edit form → `content_form` JSON. The
 * « account has no storage » outcome ([MpStorageResult.NotFound]) is the first-class
 * nominal case, not an error. Transport / session failures are raised as exceptions.
 *
 * Reading marks the storage conversation itself as read server-side (a GET of any page of a
 * `cat=prive` conversation clears its whole-conversation dot, cf. #361/ADR-013) — acceptable :
 * the storage MP is machinery, not user correspondence ; DTCloud behaves identically.
 *
 * The WRITE path ([writeBackFlag], full-overwrite `bdd.php`, last-write-wins) is the REAL,
 * opt-in path (ADR-014 §4), gated by `syncPrivateMessagesWriteEnabled` (default OFF) and a
 * verify-after-write guard. Implementations must NEVER "repair" an unreadable document (the
 * original library's destructive-reset trap) and NEVER create a fresh storage MP.
 */
interface MpStorageRepository {

    suspend fun fetchStorage(): MpStorageResult

    /**
     * WRITE path (#6, ADR-014 §4 — opt-in, OFF by default). Read-modify-write of the storage document :
     * locate the dedicated MP, mutate its `mpFlags.list[]` to upsert [entry] (by [MpStorageFlagEntry.threadId])
     * **in place on the raw JSON tree** so every third-party namespace survives the round-trip, POST the
     * mutated `content_form` back to `bdd.php cat=prive`, then VERIFY the write by re-reading the first post
     * (restoring the verbatim backup on a mismatch).
     *
     * GATED BY THE OPT-IN PREFERENCE : when `syncPrivateMessagesWriteEnabled` is `false` (the default) this
     * returns [MpStorageWriteResult.DisabledByPreference] WITHOUT touching the network — the absence of a
     * write trigger is therefore harmless. NOT OBSERVED LIVE : the `bdd.php cat=prive` write contract has
     * never been captured against a real document.
     *
     * Target selection is DETERMINISTIC : the first MP whose subject equals EXACTLY [STORAGE_SUBJECT_HASH].
     * A miss is [MpStorageWriteResult.TargetNotFound] — NEVER a creation / overwrite (ADR-014 §3). An
     * unreadable located document is [MpStorageWriteResult.Unreadable], never repaired. A target whose
     * position does not actually change is a NO-OP : [MpStorageWriteResult.Success] with no POST.
     *
     * The mutated `content_form` is capped at [MAX_CONTENT_FORM_BYTES] ([MpStorageWriteResult.TooLarge]
     * past it, fail-closed). After the POST the first post is re-read : a match is
     * [MpStorageWriteResult.Success] ; a mismatch triggers a restore POST of the backup, surfaced as
     * [MpStorageWriteResult.VerificationFailedRestored] (backup re-read OK) or
     * [MpStorageWriteResult.VerificationFailedRestoreFailed] (the backup could not be restored — critical).
     *
     * @param entry the DT reading-resume position to upsert.
     */
    suspend fun writeBackFlag(entry: MpStorageFlagEntry): MpStorageWriteResult

    /**
     * DRY-RUN of [writeBackFlag] for tests / dev tooling : locates, mutates and validates the body but
     * NEVER hits the wire, NEVER consults the opt-in preference, and NEVER verifies. Returns the verbatim
     * mutated body that the real path WOULD POST, or a typed failure (not-found / unreadable / oversize).
     * This is the inspectable counterpart — the production trigger uses [writeBackFlag], never this.
     */
    suspend fun previewWriteBackFlag(entry: MpStorageFlagEntry): MpStorageWritePreview

    companion object {
        /** Fixed storage subject — the de-facto v0.1 contract's discriminator (#6). */
        const val STORAGE_SUBJECT_HASH: String = "a2bcc09b796b8c6fab77058ff8446c34"

        /**
         * Hard cap (Codex decision) on the mutated `content_form` UTF-8 size, 64 KiB. The real HFR
         * private-message body limit has never been observed ; 64 KiB comfortably exceeds any
         * realistic DTCloud `mpFlags.list` (a few hundred small entries) while bounding an uncontrolled
         * POST. Crossing it fails CLOSED — NOT OBSERVED LIVE.
         */
        const val MAX_CONTENT_FORM_BYTES: Int = 64 * 1024
    }
}

/**
 * Outcome of the dry-run [MpStorageRepository.previewWriteBackFlag] : it never POSTs, so it cannot
 * report a verification result — only the prepared body or a typed pre-POST failure.
 */
sealed interface MpStorageWritePreview {
    /** The body the real path WOULD POST as `content_form`. */
    data class Prepared(val body: String) : MpStorageWritePreview

    /** No storage MP located (ADR-014 §3 : never created). */
    data object TargetNotFound : MpStorageWritePreview

    /** The located document is not a readable v0.1 envelope (never repaired). */
    data object Unreadable : MpStorageWritePreview

    /** The mutated body exceeds [MpStorageRepository.MAX_CONTENT_FORM_BYTES]. */
    data class TooLarge(val sizeBytes: Int) : MpStorageWritePreview
}
