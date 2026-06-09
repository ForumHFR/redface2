package fr.forumhfr.redface2.core.model.write

/**
 * Identifies the private-message conversation page the user is about to reply to (#301). Built from
 * a loaded `PrivateMessageThread` (only opens once `PrivateMessageThread.canReply` is true) and
 * passed to the private-message write repository.
 *
 * Unlike [ReplyContext] there is **no** `cat: Int` here: a private conversation lives under HFR's
 * `cat=prive` (a String, not a numeric category id), and the whole routing tuple — `cat=prive`,
 * `post={threadId}`, `subcat=0`, `numrep`, `pseudo`, `sujet` — is carried verbatim inside the
 * `bddpost.php` form HFR embeds in the thread page (cf. fixture `private_message_thread.html`). The
 * repository forwards those hidden fields untouched; this context only tells it which thread page to
 * fetch the form from.
 */
data class PrivateMessageReplyContext(
    /** HFR `post` id of the conversation (the thread id). */
    val threadId: Int,
    /** 1-based page of the conversation the user is viewing — the page whose form HFR pre-fills. */
    val page: Int,
) {
    init {
        require(threadId > 0) { "threadId must be > 0, was $threadId" }
        require(page >= 1) { "page must be >= 1, was $page" }
    }
}
