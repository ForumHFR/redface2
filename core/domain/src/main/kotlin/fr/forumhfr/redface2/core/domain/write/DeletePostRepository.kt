package fr.forumhfr.redface2.core.domain.write

import fr.forumhfr.redface2.core.model.write.EditPostContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason

/**
 * Deletes one of the user's own posts (#292). HFR has no dedicated delete endpoint: deletion
 * reuses the **edit form** (`bdd.php?config=hfr.inc`) with an extra `delete=1` field
 * (cf. `docs/specs/protocol-hfr.md` § « Suppression post/topic »). The implementation therefore
 * fetches a fresh edit form (for a current `hash_check`) and immediately POSTs it back with
 * `delete=1` — a single fetch-then-submit so the token is never stale (unlike the editor, where the
 * form is loaded, edited for a while, then submitted).
 *
 * Scope (#292 MVP): **deleting a normal post**. Deleting the *first* post deletes the **entire
 * topic** on HFR (the success response redirects to the sub-category listing instead of the topic);
 * that destructive whole-topic path is intentionally **not offered in the UI yet** (the first post
 * is excluded from the delete affordance). [DeletePostResult.Success.deletedWholeTopic] is still
 * surfaced as a defensive signal so the caller never tries to reload a topic HFR just removed.
 */
interface DeletePostRepository {
    suspend fun deletePost(context: EditPostContext): DeletePostResult
}

/** Outcome of a [DeletePostRepository.deletePost] call. */
sealed interface DeletePostResult {
    /**
     * HFR accepted the deletion (« Message effacé avec succès ! »). [deletedWholeTopic] is `true`
     * when HFR's success redirect points at the sub-category listing (`liste_sujet-*.htm`) rather
     * than the topic page (`sujet_{id}_{page}.htm`) — i.e. the deleted post was the first post and
     * the whole topic is now gone. For a normal post it is `false` and the caller refreshes the
     * current topic page so the removed post disappears.
     */
    data class Success(val deletedWholeTopic: Boolean) : DeletePostResult

    /** HFR refused the deletion and surfaced one of the known reasons (reused from the reply flow). */
    data class Failure(val reason: ReplyFailureReason) : DeletePostResult
}
