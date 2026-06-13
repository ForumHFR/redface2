package fr.forumhfr.redface2.core.domain.messages

/**
 * Per-account local store of the last page the user displayed in each private-message
 * conversation (#430, ADR-013 stage 1 — the server keeps no MP position at all, cf. #361).
 *
 * The caller passes the [owner] pseudo it captured when the conversation was opened/read, so a row
 * is always attributed to the session that actually read the page — never to whoever happens to be
 * active when a delayed IO write lands. Implementations MUST be a no-op when [owner] is `null` or no
 * longer the active session: positions are private per-account data, wiped on logout/account switch
 * (an `A → B` switch must not misattribute A's position to B, Codex review of the 0.11.0 beta).
 * Only page NUMBERS ever reach the store — no subject, correspondent or content.
 */
interface PrivateMessageReadPositionStore {

    /** Last page displayed for [threadId] by [owner], or `null` when unknown / not the active account. */
    suspend fun readPage(owner: String?, threadId: Int): Int?

    /** Records [page] as the last displayed page of [threadId] for [owner] (no-op if not active). */
    suspend fun savePage(owner: String?, threadId: Int, page: Int)
}
