package fr.forumhfr.redface2.core.model.write

/**
 * Identifies the exact HFR topic position where `/user/addflag.php` must place a
 * favourite (#986).
 *
 * `addflag.php` only creates favourites : cyan/read drapeaux are HFR side-effects, not a
 * caller-selected type. The full `(cat, subcat, topicId, page, numreponse, ref)` tuple
 * must come from the same parsed topic page. In particular, [ref] is the 1-based rank of
 * the post inside [page], not a `numreponse` and not a global counter.
 */
data class FlagAddContext(
    val cat: Int,
    val subcat: Int?,
    val topicId: Int,
    val page: Int,
    val numreponse: Int,
    val ref: Int,
) {
    init {
        require(cat >= 0) { "cat must be >= 0, was $cat" }
        require(subcat == null || subcat >= 0) {
            "subcat must be null or >= 0 (SUBCAT_UNKNOWN sentinel rejected), was $subcat"
        }
        require(topicId > 0) { "topicId must be > 0, was $topicId" }
        require(page >= 1) { "page must be >= 1, was $page" }
        require(numreponse > 0) { "numreponse must be > 0, was $numreponse" }
        require(ref >= 1) { "ref must be >= 1, was $ref" }
    }
}
