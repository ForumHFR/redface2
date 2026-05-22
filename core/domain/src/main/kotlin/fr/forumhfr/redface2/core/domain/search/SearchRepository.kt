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
}
