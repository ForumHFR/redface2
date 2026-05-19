package fr.forumhfr.redface2.core.model.write

/**
 * Identifies the post the user is editing. Phase 2D (#147) only — Edit FP
 * (#148, with `sujet` + `subcat` + poll mutation) and post deletion stay out
 * of scope.
 *
 * `numreponse` is unique **per category**, not globally on HFR, so the full
 * `(cat, subcat, topicId, page, numreponse)` tuple is mandatory ; the
 * repository will refuse to operate with any invalid component. All ids must
 * come from the same parsed topic page — `TopicPageParser` exposes them
 * through `Topic.cat / .subcat / .post / .page` and `Post.numreponse`, and
 * the navigation layer must forward them verbatim (no defaults, no fall-back).
 */
data class EditPostContext(
    val cat: Int,
    val subcat: Int,
    val topicId: Int,
    val page: Int,
    val numreponse: Int,
) {
    init {
        require(cat >= 0) { "cat must be >= 0, was $cat" }
        // Same `subcat > 0` rule as `ReplyContext` : refuse both the
        // SUBCAT_UNKNOWN sentinel and HFR's `cat=0` / `cat=prive` moderator-
        // space wire shape — see `Topic.hasSubcat`.
        require(subcat > 0) { "subcat must be > 0 (sentinel or moderator space), was $subcat" }
        require(topicId > 0) { "topicId must be > 0, was $topicId" }
        require(page >= 1) { "page must be >= 1, was $page" }
        require(numreponse > 0) { "numreponse must be > 0, was $numreponse" }
    }
}
