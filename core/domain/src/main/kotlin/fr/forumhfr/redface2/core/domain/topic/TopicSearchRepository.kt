package fr.forumhfr.redface2.core.domain.topic

import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.model.TopicSearchRequest

/**
 * Chantier C (#546) — domain entry point for the intra-topic search (`transsearch.php`).
 *
 * One-shot suspend `fun` rather than a `Flow` : the search is user-triggered, has no observable
 * upstream signal, and the ViewModel models its own loading/error/results state around the call
 * (same rationale as `SearchRepository`). It deliberately does NOT touch the topic-page cache : a
 * filtered/anchored `transsearch` page is a transient view, not the canonical `(cat, post, page)`
 * row, so persisting it would poison the cache the normal topic flow reads.
 *
 * Implementations must redact the user's [TopicSearchRequest.word] / [TopicSearchRequest.spseudo]
 * and the `hash_check` from any error message / persistent log before propagating.
 */
interface TopicSearchRepository {
    /**
     * Builds and POSTs the [request] to `transsearch.php` (authenticated), then re-parses the
     * returned topic page into a [Topic].
     *
     * @return the parsed [Topic] of the `transsearch` response. **The response shape was never
     *   observed live** : we trust the documented contract that it is a topic page and parse it
     *   with the existing topic-page parser. Navigation via [TopicSearchRequest.currentNum] is
     *   EXPERIMENTAL / best-effort for the same reason.
     *
     * Failures surface as exceptions (typed where the network layer typed them, e.g.
     * [fr.forumhfr.redface2.core.domain.auth.SessionExpiredException] /
     * [fr.forumhfr.redface2.core.domain.error.HfrServerException]).
     *
     * A successful round-trip that found no match raises [NoTopicSearchResultsException] (Chantier B
     * / #546) — HFR answers a « aucune réponse n'a été trouvée » page rather than an HTTP error, so the
     * caller must distinguish « no result » from a genuine failure.
     */
    suspend fun searchInTopic(request: TopicSearchRequest): Topic
}
