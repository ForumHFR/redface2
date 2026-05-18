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
    /**
     * `numreponse` of the post the user is quoting (Phase 2C, #146). `null` for a
     * simple reply. When non-null, the repository switches to the HFR « quote »
     * form: GET `message.php?...&numrep={quotedNumreponse}&ref={quoteRef}...` and
     * POST `bddpost.php` with `numrep={quotedNumreponse}`. The `numreponse` form
     * field stays empty in both reply and quote — it's only used for edit.
     */
    val quotedNumreponse: Int? = null,
    /**
     * `ref` query parameter HFR includes in the « quote+ » link of each post. Phase
     * 2C (#146) ships it through as-is: the value is opaque (it correlates with
     * post position on the topic page but the exact contract is undocumented), so
     * the model carries whatever the topic page HTML gave us and forbids guessing
     * a default. `null` when the source post had no quote link — in that case the
     * UI suppresses the « Citer » action upstream and we never reach this code path.
     */
    val quoteRef: Int? = null,
) {
    init {
        require(cat >= 0) { "cat must be >= 0, was $cat" }
        // Refuse both the SUBCAT_UNKNOWN sentinel (-1) and the moderator-space wire
        // shape (0). Mirrors `Topic.hasSubcat` — see its KDoc.
        require(subcat > 0) { "subcat must be > 0 (sentinel or moderator space), was $subcat" }
        require(topicId >= 0) { "topicId must be >= 0, was $topicId" }
        require(page >= 1) { "page must be >= 1, was $page" }
        // Quote requires a positive cited numreponse (the HFR `numrep` query param
        // does not have a documented sentinel meaning). `quoteRef` may legitimately
        // travel as null even on a quote when a future HFR change drops `ref` —
        // we only assert its sign when present.
        quotedNumreponse?.let {
            require(it > 0) { "quotedNumreponse must be > 0 when present, was $it" }
        }
        quoteRef?.let {
            require(it >= 0) { "quoteRef must be >= 0 when present, was $it" }
        }
    }

    val isQuote: Boolean get() = quotedNumreponse != null
}
