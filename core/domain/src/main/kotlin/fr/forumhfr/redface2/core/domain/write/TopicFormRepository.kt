package fr.forumhfr.redface2.core.domain.write

import fr.forumhfr.redface2.core.model.write.EditFirstPostContext
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.model.write.TopicForm

/**
 * Repository surface for the topic-level write flows. Phase 2D #148 covers
 * « edit first post » only ; create-topic (#149) will land later as a second
 * operation on the same interface (same `bddpost.php` endpoint, different
 * context type).
 *
 * The wire endpoints for FP edit are identical to a regular post edit
 * (`message.php?…&numreponse=N` GET, `bdd.php?config=hfr.inc` POST), but the
 * form shape is topic-level — `sujet`, `subcat`, optional poll fields — which
 * is why this repository lives next to [EditPostRepository] rather than
 * extending it.
 */
interface TopicFormRepository {

    suspend fun fetchEditFirstPostForm(context: EditFirstPostContext): TopicForm

    @Suppress("LongParameterList") // Topic-level POST contract : 6 fields the VM must hand over
    // verbatim (context + form + subject + content + subcat + options) — collapsing them into a
    // wrapper would hide HFR's surface, not simplify it.
    suspend fun submitEditFirstPost(
        context: EditFirstPostContext,
        form: TopicForm,
        subject: String,
        bbcodeContent: String,
        selectedSubcat: Int,
        options: ReplyFormOptions = ReplyFormOptions(),
    ): ReplySubmitResult
}
