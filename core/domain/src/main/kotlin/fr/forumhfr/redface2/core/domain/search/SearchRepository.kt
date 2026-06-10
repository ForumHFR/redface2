package fr.forumhfr.redface2.core.domain.search

import fr.forumhfr.redface2.core.model.search.SearchRequest
import fr.forumhfr.redface2.core.model.search.SearchResultPage

/**
 * Phase 2G-A (#150 partiel) — domain entry point for the HFR forum search.
 *
 * One-shot suspend `fun` rather than a `Flow` : the search is user-triggered
 * (no observable upstream signal) and the ViewModel models its own
 * loading/error/results state around the call. A `Flow` would complicate the
 * cancellation story without buying anything (no replay, no refresh hook).
 *
 * Failures surface as exceptions. Implementations must redact the user's
 * query from any error message before propagating (the URL HFR returns in
 * `IOException("HFR returned ... for ...")` contains the `search=` parameter
 * verbatim).
 */
interface SearchRepository {
    suspend fun search(request: SearchRequest): SearchResultPage

    /**
     * Issue #277 — resolves the REAL topic page of a search result that carries a
     * matched `numreponse`.
     *
     * HFR's search hrefs always serialise `page=1`, so the page parsed from the
     * result row is NOT the page the post lives on. The actual page is resolved
     * server-side : probing `forum2.php?…&page=1&numreponse={numreponse}` returns a
     * redirect to the pretty URL of the right page (`…sujet_{post}_{page}.htm#t{N}`),
     * from which the page is extracted. [numreponse] is unique per **category**, not
     * globally — hence the full `(cat, post, numreponse)` tuple.
     *
     * @return the resolved page, or `null` when the probe failed (network error,
     * non-redirect response, unparsable target). Callers fall back to the page
     * carried by the search href — never worse than the pre-#277 behaviour.
     */
    suspend fun resolveSearchResultPage(cat: Int, post: Int, numreponse: Int): Int?
}
