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
        // Same `subcat > 0` rule as `ReplyContext` / `EditPostContext` : refuse
        // both the SUBCAT_UNKNOWN sentinel and HFR's `cat=0` / `cat=prive`
        // moderator-space wire shape. See `Topic.hasSubcat` for the rationale.
        require(subcat > 0) { "subcat must be > 0 (sentinel or moderator space), was $subcat" }
        require(topicId > 0) { "topicId must be > 0, was $topicId" }
        require(page == 1) {
            "page must be 1 for the first post — FP lives on page 1 by definition, was $page"
        }
        require(numreponse > 0) { "numreponse must be > 0, was $numreponse" }
    }
}
