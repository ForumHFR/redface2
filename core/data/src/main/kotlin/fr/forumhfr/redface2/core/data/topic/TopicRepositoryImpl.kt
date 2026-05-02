package fr.forumhfr.redface2.core.data.topic

import android.util.Log
import fr.forumhfr.redface2.core.data.cache.CachePolicy
import fr.forumhfr.redface2.core.database.dao.TopicDao
import fr.forumhfr.redface2.core.database.entities.FetchMode
import fr.forumhfr.redface2.core.database.entities.PostEntity
import fr.forumhfr.redface2.core.database.entities.TopicEntity
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.topic.TopicRepository
import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.HfrParser
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

@Singleton
class TopicRepositoryImpl @Inject constructor(
    private val client: HfrClient,
    private val parser: HfrParser,
    private val topicDao: TopicDao,
    private val clock: Clock,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TopicRepository {

    /**
     * Cache-first read with TTL-driven refresh.
     *
     * - Cache hit, fresh → emit and stop. No network. This is the snappy back-nav
     *   case: returning to a page within `CachePolicy.topicPage` does not refetch
     *   and does not silently mark drapeaux as read.
     * - Cache hit, stale → emit cache, then refresh in foreground. If the refresh
     *   fails (offline, HFR 502, …) we swallow the failure — keeping the stale
     *   page on screen is strictly better than wiping it.
     * - Cache miss → fetch directly. A failure here propagates so the UI can
     *   show its error state.
     */
    override fun observeTopicPage(cat: Int, post: Int, page: Int): Flow<Topic> = flow {
        val cached = withContext(ioDispatcher) { loadFromCache(cat, post, page) }
        if (cached != null) {
            emit(cached.topic)
            if (CachePolicy.isFresh(cached.fetchedAt, CachePolicy.topicPage, clock)) {
                return@flow
            }
            // Stale cache stays on screen if the background refresh fails. UI does
            // nothing — a snackbar/banner for refresh failures is the responsibility
            // of the caller (Phase 1D PR 2 added Retry; nothing else needs a
            // structural change here). CancellationException is rethrown to keep
            // structured concurrency semantics intact.
            try {
                emit(fetchAndPersist(cat, post, page, FetchMode.AUTHENTICATED))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") refreshError: Exception) {
                Log.w(LOG_TAG, "Stale refresh failed for cat=$cat post=$post page=$page", refreshError)
            }
        } else {
            emit(fetchAndPersist(cat, post, page, FetchMode.AUTHENTICATED))
        }
    }

    override suspend fun refreshTopicPage(cat: Int, post: Int, page: Int): Topic =
        fetchAndPersist(cat, post, page, FetchMode.AUTHENTICATED)

    /**
     * Background prefetch path used by the prefetch service (Phase 1D PR 4).
     * Issues an unauthenticated fetch — see ADR-003 § Prefetch — and persists
     * the result *only if* it does not overwrite an existing authenticated
     * cache row. Returns the parsed [Topic] for the caller's discretion (the
     * service typically discards it).
     *
     * Not declared on [TopicRepository] because consumers in `:feature:*`
     * never need the anonymous variant; only the data-layer prefetch service
     * does.
     */
    suspend fun prefetchAnonymous(cat: Int, post: Int, page: Int): Topic =
        fetchAndPersist(cat, post, page, FetchMode.ANONYMOUS)

    private suspend fun fetchAndPersist(
        cat: Int,
        post: Int,
        page: Int,
        authMode: FetchMode,
    ): Topic = withContext(ioDispatcher) {
        val html = client.getTopicPage(
            cat = cat,
            post = post,
            page = page,
            useAuth = authMode == FetchMode.AUTHENTICATED,
        )
        val topic = parser.parseTopicPage(html)
        val (topicEntity, postEntities) = TopicMappers.toEntities(topic, clock.instant(), authMode)
        persist(topicEntity, postEntities, authMode)
        topic
    }

    /**
     * Anti-overwrite rule: an anonymous prefetch must never replace an
     * authenticated row. The auth row carries per-user fields (`isOwnPost`,
     * `isEditable`, …) that an anonymous request cannot reproduce; clobbering
     * them silently with anonymous values would silently downgrade the page
     * the user sees on next load.
     *
     * If [authMode] is `ANONYMOUS` and an `AUTHENTICATED` row already exists,
     * we drop the write entirely — better a slightly stale auth row than a
     * fresh anon one without the per-user signal.
     */
    private suspend fun persist(
        topicEntity: TopicEntity,
        postEntities: List<PostEntity>,
        authMode: FetchMode,
    ) {
        if (authMode == FetchMode.ANONYMOUS) {
            val existing = topicDao.getTopicPage(topicEntity.cat, topicEntity.post, topicEntity.page)
            if (existing != null && existing.authMode == FetchMode.AUTHENTICATED) {
                Log.d(
                    LOG_TAG,
                    "Skipping anonymous overwrite of AUTHENTICATED cache for " +
                        "cat=${topicEntity.cat} post=${topicEntity.post} page=${topicEntity.page}",
                )
                return
            }
        }
        topicDao.upsertTopicPageWithPosts(topicEntity, postEntities)
    }

    private suspend fun loadFromCache(cat: Int, post: Int, page: Int): CachedTopic? {
        val topicEntity = topicDao.getTopicPage(cat, post, page) ?: return null
        val postEntities = topicDao.getPostsByNumreponse(cat, topicEntity.numreponses)
        return CachedTopic(
            topic = TopicMappers.toDomain(topicEntity, postEntities),
            fetchedAt = topicEntity.fetchedAt,
        )
    }

    private data class CachedTopic(val topic: Topic, val fetchedAt: java.time.Instant)

    private companion object {
        const val LOG_TAG = "TopicRepository"
    }
}
