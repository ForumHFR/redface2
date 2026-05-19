package fr.forumhfr.redface2.core.domain.write

import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult

/**
 * Repository surface for posting a reply to an HFR topic. Phase 2C-A (#145) only
 * covers the simple-reply path (no quote pre-fill, no edit, no FP); the same shape
 * will be reused later for `:feature:editor`'s edit and create-topic flows.
 *
 * Implementations live in `:core:data` and chain a GET on `message.php` (to grab
 * the per-session `hash_check`) followed by a POST on `bddpost.php`. The contract
 * is intentionally narrow:
 *
 * - [fetchReplyForm] returns a [ReplyForm] even when HFR served the anonymous
 *   composer (caller inspects [ReplyForm.isAnonymous] before calling [submitReply]).
 *   Transport / session-expiry failures are raised as exceptions.
 * - [submitReply] returns a [ReplySubmitResult] for every recognised response
 *   (success + the documented [ReplyFailureReason] modes). It raises only on
 *   transport-level issues.
 */
interface ReplyRepository {

    suspend fun fetchReplyForm(context: ReplyContext): ReplyForm

    suspend fun submitReply(
        context: ReplyContext,
        form: ReplyForm,
        bbcodeContent: String,
        options: ReplyFormOptions = ReplyFormOptions(),
    ): ReplySubmitResult
}
