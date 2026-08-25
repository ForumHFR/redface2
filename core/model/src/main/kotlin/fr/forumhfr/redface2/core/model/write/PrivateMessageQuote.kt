package fr.forumhfr.redface2.core.model.write

/**
 * Server identifiers required to fetch one HFR private-message quote form (#1074).
 *
 * [numreponse] is the cited message id. [ref] is the 1-based rank HFR exposed for that message
 * inside its source page; it is forwarded unchanged and is never recomputed from the local list.
 * Unlike topic quotes, the measured MP contract has no fallback without `ref`: callers must hide
 * the quote action when the rank is unavailable rather than guessing or silently omitting it.
 */
data class PrivateMessageQuote(
    val numreponse: Int,
    val ref: Int,
) {
    init {
        require(numreponse > 0) { "numreponse must be > 0, was $numreponse" }
        require(ref >= 1) { "ref must be >= 1 for a private-message quote, was $ref" }
    }
}
