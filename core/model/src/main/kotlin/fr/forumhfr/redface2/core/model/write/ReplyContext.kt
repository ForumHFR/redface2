package fr.forumhfr.redface2.core.model.write

/**
 * Identifies the topic page the user is about to reply to. Built from a loaded
 * `Topic` (Phase 2C: only opens once `Topic.hasSubcat` is true) and passed to the
 * reply repository so the repository can build the HFR `message.php` GET URL.
 *
 * All four ids come from the same parsed HTML page — there is no implicit default.
 * The repository will refuse to operate if [subcat] is negative.
 */
data class ReplyContext(
    val cat: Int,
    val subcat: Int,
    val topicId: Int,
    val page: Int,
) {
    init {
        require(cat >= 0) { "cat must be >= 0, was $cat" }
        require(subcat >= 0) { "subcat must be >= 0 (sentinel reached), was $subcat" }
        require(topicId >= 0) { "topicId must be >= 0, was $topicId" }
        require(page >= 1) { "page must be >= 1, was $page" }
    }
}
