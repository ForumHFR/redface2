package fr.forumhfr.redface2.core.model.write

/**
 * Identifies the private-message conversation page the user is about to reply to (#301). Built from
 * a loaded `PrivateMessageThread` (only opens once `PrivateMessageThread.canReply` is true) and
 * passed to the private-message write repository.
 *
 * Unlike [ReplyContext] there is **no** `cat: Int` here: a private conversation lives under HFR's
 * `cat=prive` (a String, not a numeric category id). For a simple reply, [quote] is null and the
 * repository follows the real reply link exposed by the thread page so it preserves server-owned
 * fields such as `newdest`. For a quote, [quote] is non-null and the repository builds the measured
 * typed `message.php` URL instead; the parsed form still owns the complete POST routing tuple.
 */
data class PrivateMessageReplyContext(
    /** HFR `post` id of the conversation (the thread id). */
    val threadId: Int,
    /** 1-based page of the conversation the user is viewing — the page whose form HFR pre-fills. */
    val page: Int,
    /** Null for a simple reply; server-provided target and page rank for a citation. */
    val quote: PrivateMessageQuote? = null,
) {
    init {
        require(threadId > 0) { "threadId must be > 0, was $threadId" }
        require(page >= 1) { "page must be >= 1, was $page" }
    }
}
