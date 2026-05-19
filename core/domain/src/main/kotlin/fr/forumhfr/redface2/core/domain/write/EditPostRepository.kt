package fr.forumhfr.redface2.core.domain.write

import fr.forumhfr.redface2.core.model.write.EditPostContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult

/**
 * Repository surface for editing an existing HFR post the current authenticated
 * user owns. Phase 2D-A (#147) only — the « edit first post » flow (#148, with
 * `sujet` + `subcat` + poll mutation) and post deletion live elsewhere.
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
}
