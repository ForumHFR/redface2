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
     * mark-as-read side effect, cf. ADR-003 § Prefetch).
     *
     * **Persists an `ANONYMOUS` row** in Room when [page] is not already cached
     * as `AUTHENTICATED`. The next [observeTopicPage] call on the same `(cat, post,
     * page)` reads that anon row immediately for snappy first paint, then triggers
     * a foreground authenticated re-fetch to surface per-user fields (`isOwnPost`,
     * `isEditable`) and let HFR mark drapeaux as read. The anti-overwrite rule is
     * enforced atomically by the DAO `@Transaction` (cf.
     * `TopicDao.upsertTopicPageWithPostsUnlessAuthenticated`), so a concurrent
     * authenticated write cannot be clobbered by this prefetch.
     *
     * Note the asymmetry with [fr.forumhfr.redface2.core.domain.forum.ForumRepository.prefetchTopicList],
     * which warms HFR's CDN only and **does not** populate any client-side cache —
     * the listing payload would strip per-user fields the user-facing path needs.
     * Topic pages are persisted because the `ANONYMOUS` shape of a topic page is
     * still useful to render before the auth refresh lands ; listing pages are not.
     *
     * The caller's coroutine context controls cancellation: when the consumer
     * leaves the screen or moves on to a new page, the structured-concurrency
     * scope it lives in propagates the cancel and stops the in-flight fetch.
     *
     * Failures are swallowed: a prefetch is best-effort by design and must
     * never bubble up to the user-facing flow.
     */
    suspend fun prefetch(cat: Int, post: Int, page: Int)
}
