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
