package fr.forumhfr.redface2.core.domain.messages

/**
 * Per-account local store of the last page the user displayed in each private-message
 * conversation (#430, ADR-013 stage 1 — the server keeps no MP position at all, cf. #361).
 *
 * Implementations resolve the active account themselves and MUST be a no-op when no session is
 * active: positions are private per-account data, wiped on logout/account switch. Only page
 * NUMBERS ever reach the store — no subject, correspondent or content.
 */
interface PrivateMessageReadPositionStore {

    /** Last page displayed for [threadId] by the active account, or `null` when unknown. */
    suspend fun readPage(threadId: Int): Int?

    /** Records [page] as the last displayed page of [threadId] for the active account. */
    suspend fun savePage(threadId: Int, page: Int)
}
