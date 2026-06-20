package fr.forumhfr.redface2.core.model.mpstorage

/**
 * MPStorage (#6, ADR-014) — domain models for the cross-userscript storage document.
 *
 * The storage is the FIRST post of a dedicated HFR private message (subject = the fixed
 * hash `a2bcc09b796b8c6fab77058ff8446c34`, recipient = the third-party `MultiMP` account).
 * Its body is one JSON document shared by every tool (DTCloud's `mpFlags`, HFR4K's `hfr4k`,
 * …) inside a weakly-namespaced v0.1 envelope :
 * `{ data: [ { version: '0.1', <tool keys> } ], sourceName, lastUpdate }`.
 *
 * Redface 2 adopts the envelope AS-IS (ADR-014) : reads project only the keys it consumes,
 * and keep the raw JSON intact for the future read-modify-write — writing is a full
 * overwrite (last-write-wins, no lock), so third-party keys must survive any round-trip.
 */

/** Outcome of a storage lookup. The account having NO storage is the first-class case. */
sealed interface MpStorageResult {
    /** No dedicated MP found — the account never used a MPStorage-based userscript. */
    data object NotFound : MpStorageResult

    /** The dedicated MP exists and its first post parsed into a [MpStorageDocument]. */
    data class Found(val document: MpStorageDocument) : MpStorageResult

    /**
     * The dedicated MP exists but its first post is NOT a readable v0.1 envelope.
     * Surfaced explicitly (never "repaired" : ADR-014 forbids the original library's
     * destructive reset-to-default on invalid content).
     */
    data object Unreadable : MpStorageResult
}

/**
 * Projection of the storage document. [rawEnvelope] carries the verbatim JSON (the whole
 * `content_form` body) so the future write path can re-emit unknown keys untouched.
 */
data class MpStorageDocument(
    /** Last WRITING TOOL (`sourceName`), not a per-tool property. Null when absent. */
    val sourceName: String?,
    /** DTCloud's `mpFlags.list` section — empty when the section is absent. */
    val mpFlags: List<MpStorageFlagEntry>,
    /** The verbatim JSON document. Never rebuilt field-by-field. */
    val rawEnvelope: String,
)

/**
 * One DTCloud entry : the READING-RESUME POSITION of a DT conversation (≥ 3 pseudos).
 * It is NOT a read/unread state (that is the server-side dot, cf. #361/ADR-013) and NOT
 * a pinned marker.
 */
data class MpStorageFlagEntry(
    /** HFR thread id of the conversation (`post` on the wire). */
    val threadId: Int,
    /** 1-based page the user last stood on. */
    val page: Int,
    /** Post anchor on that page (`href` = `"t<numreponse>"` on the wire). Null when absent/odd. */
    val numreponse: Int?,
    /** Desktop-format URI, relayed verbatim (DTCloud rebuilds its links from it). */
    val uri: String?,
)

/**
 * Outcome of an MPStorage WRITE attempt (#6, ADR-014 §4 — opt-in, OFF by default).
 *
 * The write path is the REAL read-modify-write of the storage document, gated by the
 * `syncPrivateMessagesWriteEnabled` preference (default `false`) AND a verify-after-write guard
 * (re-read the first post after the POST and confirm it matches the mutated body ; restore the
 * verbatim backup on a mismatch). NOT OBSERVED LIVE : the `bdd.php cat=prive` write contract has
 * never been captured against a real document — these variants describe what the gated path returns.
 */
sealed interface MpStorageWriteResult {

    /**
     * The opt-in preference is OFF (the default). NO request hit the wire — the path returns before
     * reading anything. This is the nominal case for every user who has not explicitly opted in.
     */
    data object DisabledByPreference : MpStorageWriteResult

    /**
     * UPDATE-ONLY path only (#597) : the storage document was located and read, but its
     * `mpFlags.list[]` holds NO entry for this `threadId`, so the auto reading-position trigger
     * declined to ADD a new one. This is the anti-pollution guarantee of the AUTO hook — a shared
     * cross-userscript document (DTCloud / HFR4K) must never gain a Redface-2-invented entry from a
     * mere page land (a 1-to-1 MP wrongly recorded as a DT would corrupt the storage). No POST was
     * sent. The MANUAL / preview path never returns this (it upserts add-or-update).
     */
    data object SkippedNotPresent : MpStorageWriteResult

    /**
     * The target storage document could not be located (ADR-014 §3 : NEVER create or overwrite a
     * fresh document — that would fork the cross-userscript storage / spawn a duplicate). The
     * caller must surface this, not "repair" it.
     */
    data object TargetNotFound : MpStorageWriteResult

    /** The located document is not a readable v0.1 envelope — surfaced, never repaired (ADR-014 §3). */
    data object Unreadable : MpStorageWriteResult

    /**
     * The mutated `content_form` exceeds the hard size cap
     * ([fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository.MAX_CONTENT_FORM_BYTES]).
     * The real HFR MP body limit is unknown (never observed) ; this cap fails CLOSED rather than
     * risk an uncontrolled / truncated POST. [sizeBytes] is the offending UTF-8 size. No POST is sent.
     */
    data class TooLarge(val sizeBytes: Int) : MpStorageWriteResult

    /**
     * The mutation was POSTed and the verify-after-write re-read confirmed the stored first post now
     * equals the mutated body. [verified] is `true` here (it is the only success variant — there is no
     * "success without verification" for the real path) ; the no-op case (the target position did not
     * change, so nothing was written) ALSO returns [verified] = `true` with no POST.
     */
    data class Success(val verified: Boolean) : MpStorageWriteResult

    /**
     * The verify-after-write re-read did NOT match the mutated body, so the verbatim backup was
     * re-POSTed and the subsequent re-read confirmed the document is back to its pre-write state.
     * The user's storage is intact ; the write simply did not take. [expectedBytes] / [actualBytes]
     * are the mutated vs the read-back UTF-8 sizes (diagnostics only — never the content).
     */
    data class VerificationFailedRestored(val expectedBytes: Int, val actualBytes: Int) : MpStorageWriteResult

    /**
     * CRITICAL : the verify-after-write mismatched AND the restore re-POST could NOT bring the document
     * back to its backup. The storage may be in an inconsistent state — logged at the highest level.
     * [expectedBytes] / [actualBytes] are the backup vs the read-back UTF-8 sizes (diagnostics only).
     */
    data class VerificationFailedRestoreFailed(val expectedBytes: Int, val actualBytes: Int) : MpStorageWriteResult
}
