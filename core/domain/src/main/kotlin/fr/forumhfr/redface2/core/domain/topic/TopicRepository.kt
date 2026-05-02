package fr.forumhfr.redface2.core.domain.topic

import fr.forumhfr.redface2.core.model.Topic
import kotlinx.coroutines.flow.Flow

interface TopicRepository {
    /**
     * Emits the cached page first if available. A fresh network refresh follows
     * only when the cached row is stale relative to the implementation's TTL
     * (cf. `CachePolicy.topicPage`) — a hot revisit returns the cache and stops.
     *
     * Network errors after a cache emission are swallowed so the user keeps
     * seeing the last-known-good page; on a cold cache, errors are surfaced as
     * exceptions on the flow.
     */
    fun observeTopicPage(cat: Int, post: Int, page: Int): Flow<Topic>

    /**
     * Forces a network fetch, bypasses TTL, and writes the result to the cache.
     * The persisted row is tagged as authenticated.
     */
    suspend fun refreshTopicPage(cat: Int, post: Int, page: Int): Topic

    /**
     * Background prefetch — anonymous fetch (no HFR cookies, no
     * mark-as-read side effect, cf. ADR-003 § Prefetch) that warms the Room
     * cache for [page] without overwriting an existing authenticated row. The
     * caller's coroutine context controls cancellation: when the consumer
     * leaves the screen or moves on to a new page, the structured-concurrency
     * scope it lives in propagates the cancel and stops the in-flight fetch.
     *
     * Failures are swallowed: a prefetch is best-effort by design and must
     * never bubble up to the user-facing flow.
     */
    suspend fun prefetch(cat: Int, post: Int, page: Int)
}
