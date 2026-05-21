package fr.forumhfr.redface2.feature.editor

/**
 * Plain request payload assisted-injected into [TopicFormViewModel]. Mirrors the
 * `TopicFormRoute` NavKey from `:app/navigation` without leaking Navigation 3
 * types into the feature module.
 *
 * Phase 2D #148 ([TopicFormMode.EditFirstPost]) requires the full
 * `(cat, subcat, topicId, page, numreponse)` tuple : `numreponse` identifies
 * the first post HFR is going to rewrite, and the GET URL needs every other id
 * to build a valid `message.php?…` link. [TopicFormMode.New] (Phase 2E #149)
 * will rely on a subset (`cat`, optional `subcat`, no `topicId` / `numreponse`).
 */
data class TopicFormRequest(
    val mode: TopicFormMode,
    val cat: Int?,
    val subcat: Int?,
    val topicId: Int?,
    val page: Int?,
    val numreponse: Int?,
)
