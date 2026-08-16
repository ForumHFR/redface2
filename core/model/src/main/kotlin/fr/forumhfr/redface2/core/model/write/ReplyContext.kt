package fr.forumhfr.redface2.core.model.write

/**
 * Identifies the topic page the user is about to reply to. Built from a loaded
 * `Topic` (#213: only opens once `Topic.canReply` is true) and passed to the
 * reply repository so the repository can build the HFR `message.php` GET URL.
 *
 * All four ids come from the same parsed HTML page — there is no implicit default.
 * The repository will refuse to operate if [subcat] is negative. `subcat = 0` is a
 * valid, postable value : a category without sub-category (e.g. cat IA) posts with
 * `subcat=0`. Only the SUBCAT_UNKNOWN sentinel (-1) is rejected.
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
     * `ref` query parameter HFR includes in the « quote+ » link of each post. It is
     * the 1-based rank inside the page (`0` for the page-2+ recap row), so the model
     * carries whatever the topic page HTML gave us and forbids recomputing it from
     * a local list index. `null` is accepted for obfuscated toolbar rows: HFR identifies the
     * cited post by `numrep={quotedNumreponse}` alone, and the network layer simply
     * omits `&ref=` when this value is absent.
     */
    val quoteRef: Int? = null,
) {
    init {
        require(cat >= 0) { "cat must be >= 0, was $cat" }
        // #213 — refuse only the SUBCAT_UNKNOWN sentinel (-1, no reply form was
        // present). `subcat = 0` is postable (cat without sub-category, e.g. IA).
        // See `Topic.subcat` / `Topic.canReply` KDoc.
        require(subcat >= 0) { "subcat must be >= 0 (SUBCAT_UNKNOWN sentinel rejected), was $subcat" }
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
        // `quoteRef` is the per-page positional id of the *cited* post — only
        // meaningful in conjunction with `quotedNumreponse`. The reverse shape
        // (`quotedNumreponse != null && quoteRef == null`) is the expected fallback
        // for obfuscated toolbar rows where the quote link could not be parsed ; see
        // `HfrClient.getReplyForm` KDoc.
        require(quotedNumreponse != null || quoteRef == null) {
            "quoteRef requires quotedNumreponse"
        }
    }

    val isQuote: Boolean get() = quotedNumreponse != null
}
