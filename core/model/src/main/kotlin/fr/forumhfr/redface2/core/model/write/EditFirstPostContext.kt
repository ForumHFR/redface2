package fr.forumhfr.redface2.core.model.write

/**
 * Identifies the first post of a topic the user wants to edit (Phase 2D #148).
 * Same wire endpoint as `EditPostContext` (HFR routes both flows through
 * `bdd.php?config=hfr.inc`), but the form is topic-level : subject, subcategory
 * and poll fields are mutable from the FP form. We keep the context type
 * separate so the call-sites cannot accidentally pretend a regular post edit is
 * a FP edit (the subject/subcat fields would silently fall back to defaults).
 *
 * `numreponse` is unique **per category** on HFR, never globally, so the full
 * `(cat, subcat, topicId, page, numreponse)` tuple is mandatory. The first
 * post always lives on page 1, but we still carry the page explicitly to
 * mirror the GET URL exactly.
 */
data class EditFirstPostContext(
    val cat: Int,
    val subcat: Int,
    val topicId: Int,
    val page: Int,
    val numreponse: Int,
) {
    init {
        require(cat >= 0) { "cat must be >= 0, was $cat" }
        // #213 — UNLIKE `ReplyContext` / `EditPostContext` (relaxed to `subcat >= 0`,
        // so a category without sub-category, subcat=0, is postable), FP edit keeps
        // `subcat > 0`: the FP recategorise flow is not relaxed for 0-subcat categories
        // (its sub-category dropdown contract is not captured yet). FP-in-0-subcat is a
        // #213 follow-up; the gate in `TopicScreen` mirrors this (`canReply && subcat > 0`).
        require(subcat > 0) { "subcat must be > 0 (FP edit needs a real sub-category), was $subcat" }
        require(topicId > 0) { "topicId must be > 0, was $topicId" }
        require(page == 1) {
            "page must be 1 for the first post — FP lives on page 1 by definition, was $page"
        }
        require(numreponse > 0) { "numreponse must be > 0, was $numreponse" }
    }
}
