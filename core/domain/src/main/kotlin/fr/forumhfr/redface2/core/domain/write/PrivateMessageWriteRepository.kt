package fr.forumhfr.redface2.core.domain.write

import fr.forumhfr.redface2.core.model.write.PrivateMessageReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult

/**
 * Repository surface for replying to an HFR private-message conversation (#301).
 *
 * The wire shape is the same family as a topic reply — HFR embeds a `bddpost.php` form in the
 * conversation page (`forum2.php?cat=prive&post={threadId}`) and the POST goes to the same
 * `bddpost.php` endpoint — so this reuses the generic [ReplyForm] / [ReplySubmitResult] models and
 * the shared form / response parsers. It is kept as a **separate** interface from [ReplyRepository]
 * because a private conversation has no numeric `cat` / `subcat` / quote semantics: the routing
 * lives entirely in the form's hidden fields (`cat=prive`, `post`, `numrep`, `subcat=0`, …), which
 * the implementation forwards verbatim rather than re-asserting from a typed context.
 *
 * - [fetchReplyForm] GETs the conversation page and parses the embedded `bddpost.php` form (fresh
 *   `hash_check`). It returns a [ReplyForm] even when HFR served the anonymous composer (caller
 *   inspects [ReplyForm.isAnonymous]); transport / session-expiry failures are raised.
 * - [submitReply] POSTs the reply and classifies the response into a [ReplySubmitResult]. The
 *   private-message POST success sentence is not pinned by a live fixture (a real send to a third
 *   party was intentionally avoided), so callers must treat an unrecognised
 *   ([fr.forumhfr.redface2.core.model.write.ReplyFailureReason.Unknown]) response as non-destructive
 *   — keep the draft and let the user verify the conversation — rather than asserting a hard failure.
 */
interface PrivateMessageWriteRepository {

    suspend fun fetchReplyForm(context: PrivateMessageReplyContext): ReplyForm

    suspend fun submitReply(
        context: PrivateMessageReplyContext,
        form: ReplyForm,
        bbcodeContent: String,
        options: ReplyFormOptions = ReplyFormOptions(),
    ): ReplySubmitResult
}
