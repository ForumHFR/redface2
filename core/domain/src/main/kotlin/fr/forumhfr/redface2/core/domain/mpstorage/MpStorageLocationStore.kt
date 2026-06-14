package fr.forumhfr.redface2.core.domain.mpstorage

/**
 * Per-account cache of the MPStorage conversation's location (#6, ADR-014).
 *
 * The storage MP is discovered by an inbox scan that can walk many pages (the storage subject is a
 * fixed hash, never bumped to the top of the inbox). That scan is expensive, so its result is
 * cached and subsequent reads go straight to the first post's edit form — exactly how
 * `MPStorage.user.js` / DTCloud cache `mpId`/`mpRepId` after the first discovery instead of
 * re-scanning on every page load.
 *
 * Rows are private per account (they reveal the account owns a storage MP, and at which
 * conversation) and are wiped on logout / account switch by `CacheInvalidator`, same contract as
 * the MP reading positions ([fr.forumhfr.redface2.core.domain.messages.PrivateMessageReadPositionStore]).
 */
interface MpStorageLocationStore {

    /**
     * The cached location for [owner], or `null` when nothing was ever discovered — or when
     * [owner] is not (or no longer) the active session (a save delayed across an account switch
     * must not leak the previous account's location).
     */
    suspend fun read(owner: String?): MpStorageLocation?

    /** Caches the discovered [threadId]/[numreponse] for [owner]; no-op when [owner] is not active. */
    suspend fun save(owner: String?, threadId: Int, numreponse: Int)

    /** Drops the cached location for [owner] — the conversation moved or vanished (stale read). */
    suspend fun clear(owner: String?)
}

/**
 * Where the storage document lives: the dedicated conversation [threadId] (`post` on the wire) and
 * the [numreponse] of its FIRST post, whose edit form carries the raw JSON document.
 */
data class MpStorageLocation(val threadId: Int, val numreponse: Int)
