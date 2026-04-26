package fr.forumhfr.redface2.core.domain.topic

import fr.forumhfr.redface2.core.model.Topic
import kotlinx.coroutines.flow.Flow

interface TopicRepository {
    /**
     * Emits the cached page first if available, then a fresh copy fetched from HFR. The
     * second emission may be the same instance as the first if the cache and the network
     * agree. Errors during the network refresh are surfaced as exceptions on the flow.
     */
    fun observeTopicPage(cat: Int, post: Int, page: Int): Flow<Topic>

    /** Forces a network fetch and caches the result, ignoring any existing cache. */
    suspend fun refreshTopicPage(cat: Int, post: Int, page: Int): Topic
}
