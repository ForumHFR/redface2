package fr.forumhfr.redface2.core.domain.write

import fr.forumhfr.redface2.core.model.write.EditFirstPostContext
import fr.forumhfr.redface2.core.model.write.NewTopicContext
import fr.forumhfr.redface2.core.model.write.NewTopicSubmitResult
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.model.write.TopicForm

/**
 * Repository surface for the topic-level write flows. Phase 2D #148 covers
 * « edit first post » and Phase 2E #149 covers « create topic ». They live on
 * the same interface because both expose the topic-level form shape (`sujet`,
 * `subcat`, optional poll fields), even though the POST endpoint differs.
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

    /**
     * Phase 2E (#149) — fetch the create-topic form for the given context.
     * `context.entrySubcat` is forwarded as the URL `subcat=` query (or `0`
     * when the user is on the « Toutes » view) so HFR knows which chip the
     * composer was opened from. The returned [TopicForm] has
     * `selectedSubcat = null` because HFR ships the new-topic `<select>`
     * without any `selected` attribute — the user picks one in the dropdown.
     */
    suspend fun fetchNewTopicForm(context: NewTopicContext): TopicForm

    /**
     * Phase 2E (#149) — submit the create-topic payload. `selectedSubcat`
     * comes from the dropdown choice (always `> 0`) and lands in the POST
     * `subcat=` field ; the d'arrivée chip (`context.entrySubcat`) lands in
     * `from_subcat=`, sourced from `form.hiddenFields["from_subcat"]` when
     * present.
     */
    @Suppress("LongParameterList") // Same topic-level POST shape as Edit FP, 6 fields verbatim.
    suspend fun submitNewTopic(
        context: NewTopicContext,
        form: TopicForm,
        subject: String,
        bbcodeContent: String,
        selectedSubcat: Int,
        options: ReplyFormOptions = ReplyFormOptions(),
    ): NewTopicSubmitResult
}
