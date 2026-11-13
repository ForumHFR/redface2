package fr.forumhfr.redface2.core.domain.write

import fr.forumhfr.redface2.core.model.write.EditPostContext
import fr.forumhfr.redface2.core.model.write.PrivateMessageEditContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult

/**
 * Repository surface for editing an existing HFR post the current authenticated
 * user owns, in a public topic (#147) or a private conversation (#1625). Public first-post editing
 * (#148, with `sujet` + `subcat` + poll mutation) and post deletion live elsewhere.
 *
 * Implementations live in `:core:data` and chain a GET on `message.php?…
 * &numreponse={N}` (to grab `hash_check` + the post's current BBCode in
 * `<textarea name=content_form>`) followed by a POST on `bdd.php` — a sibling
 * endpoint of `bddpost.php`, distinct on the wire even though the form shape is
 * the same. The two repositories ([ReplyRepository] / [EditPostRepository])
 * deliberately share `ReplyForm` / `ReplyFormOptions` / `ReplySubmitResult` /
 * [ReplyFailureReason] : the domain types describe the **form shape and the
 * wire outcome**, not the operation that produced them.
 */
interface EditPostRepository {

    suspend fun fetchEditPostForm(context: EditPostContext): ReplyForm

    suspend fun submitEditPost(
        context: EditPostContext,
        form: ReplyForm,
        bbcodeContent: String,
        options: ReplyFormOptions = ReplyFormOptions(),
    ): ReplySubmitResult

    /** Loads and validates an owned MP edit form, never a reply composer. */
    suspend fun fetchEditPostForm(context: PrivateMessageEditContext): ReplyForm

    /**
     * Edits an MP without changing its recipients or deleting it. A first-message subject can be
     * changed through `form.copy(sujet = ...)` only when [ReplyForm.isSubjectEditable] is true.
     * Unknown responses remain [ReplyFailureReason.Unknown]: callers must keep the durable attempt
     * unresolved and surface uncertainty, without automatic resubmission.
     *
     * PENDING(#1625): MP edit success response not yet observed live.
     */
    suspend fun submitEditPost(
        context: PrivateMessageEditContext,
        form: ReplyForm,
        bbcodeContent: String,
        options: ReplyFormOptions = ReplyFormOptions(),
    ): ReplySubmitResult
}
