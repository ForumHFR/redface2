package fr.forumhfr.redface2.core.domain.topic

import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.Topic
import kotlinx.coroutines.flow.Flow

interface TopicRepository {
    /**
     * Emits the cached page first if available. A fresh network refresh follows
     * only when the cached row is stale relative to the implementation's TTL
     * (cf. `CachePolicy.topicPage`) — a hot revisit returns the cache and stops.
     *
     * [forceRefresh] (#231) bypasses that TTL skip: the cached page is still emitted
     * instantly for a snappy first paint, but a network refresh **always** follows.
     * Set it when the user's intent is to catch up on new posts (e.g. opening a topic
     * from a drapeau/flag), so a followed topic that grew is never shown stale within
     * the snappy-cache window.
     *
     * Network errors after a cache emission are swallowed so the user keeps
     * seeing the last-known-good page; on a cold cache, errors are surfaced as
     * exceptions on the flow.
     *
     * #877 — each emission carries its provenance ([TopicPageEmission.provisional]) so the
     * UI can tell a cache page that a network refresh will supersede from a settled page.
     * Only this repository knows whether a refresh follows (TTL skip, refresh failure), so
     * the flag is decided here, never inferred downstream.
     */
    fun observeTopicPage(cat: Int, post: Int, page: Int, forceRefresh: Boolean = false): Flow<TopicPageEmission>

    /**
     * Forces a network fetch, bypasses TTL, and writes the result to the cache.
     * The persisted row is tagged as authenticated.
     */
    suspend fun refreshTopicPage(cat: Int, post: Int, page: Int): Topic

    /**
     * #783 — returns the distinct post rows HFR exposes for its native reverse citation index.
     * The server-side badge counts citation occurrences while `quote_only=1` may deduplicate
     * citing posts, so callers must never equate this list's size with `Post.citedCount`.
     *
     * The result is deliberately uncached: this is volatile, user-triggered detail data rather
     * than part of the durable topic-page snapshot.
     */
    suspend fun getCitingPosts(cat: Int, post: Int, numreponse: Int): Result<List<Post>>

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

/**
 * #877 — one emission of [TopicRepository.observeTopicPage], tagged with its provenance.
 *
 * [provisional] is `true` only when this page comes from the cache AND a network refresh is
 * still expected to follow on the same flow. It is `false` for network pages, for cache pages
 * the TTL skip settles on (no refresh coming), and for the terminal re-emission after a failed
 * refresh — so `provisional` always terminates: the UI can safely hold back derived affordances
 * (the « page X / Y » pill) while it is `true` without ever getting stuck.
 */
data class TopicPageEmission(
    val topic: Topic,
    val provisional: Boolean,
)
