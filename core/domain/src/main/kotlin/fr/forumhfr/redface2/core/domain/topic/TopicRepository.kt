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
}
