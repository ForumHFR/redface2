package fr.forumhfr.redface2.core.data.topic

import android.util.Log
import androidx.tracing.trace
import androidx.tracing.traceAsync
import fr.forumhfr.redface2.core.data.cache.CachePolicy
import fr.forumhfr.redface2.core.database.dao.TopicDao
import fr.forumhfr.redface2.core.database.entities.FetchMode
import fr.forumhfr.redface2.core.database.entities.PostEntity
import fr.forumhfr.redface2.core.database.entities.TopicEntity
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.topic.TopicPageEmission
import fr.forumhfr.redface2.core.domain.topic.TopicRepository
import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.HfrParser
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

@Singleton
class TopicRepositoryImpl @Inject constructor(
    private val client: HfrClient,
    private val parser: HfrParser,
    private val topicDao: TopicDao,
    private val clock: Clock,
    private val userPreferencesRepository: UserPreferencesRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TopicRepository {

    /**
     * Cache-first read with TTL-driven refresh.
     *
     * - **Alpha `ignoreTopicCache` toggle ON** → skip the Room read entirely and emit a
     *   fresh AUTHENTICATED fetch. The result is still persisted so toggling back OFF
     *   later finds a parser-coherent cache. Network failures propagate as a flow
     *   exception (no silent fallback to a potentially stale cache row).
     * - Cache hit, **AUTHENTICATED** + fresh → emit and stop. No network. This is the
     *   snappy back-nav case: returning to a page within `CachePolicy.topicPage` does
     *   not refetch and does not silently mark drapeaux as read.
     * - Cache hit, **ANONYMOUS** (warmed by [prefetch]) → always emit cache
     *   then re-fetch authenticated, regardless of TTL. The anon row is missing
     *   per-user fields (`isOwnPost`, `isEditable`, …) and reading without re-fetching
     *   would also skip the implicit "mark as read" the auth GET triggers server-side.
     * - Cache hit, AUTHENTICATED + stale → emit cache, then refresh in foreground. If
     *   the refresh fails (offline, HFR 502, …) we swallow the failure — keeping the
     *   stale page on screen is strictly better than wiping it.
     * - Cache miss → fetch directly. A failure here propagates so the UI can
     *   show its error state.
     */
    override fun observeTopicPage(
        cat: Int,
        post: Int,
        page: Int,
        forceRefresh: Boolean,
    ): Flow<TopicPageEmission> = flow {
        // Alpha "Ignorer le cache topic" toggle (Phase 2 finish): when enabled, skip the
        // Room read entirely and emit a fresh network fetch. The result is still persisted
        // so toggling back OFF later finds a cache coherent with the current parser. We
        // evaluate the preference once per `observeTopicPage` call — no long-lived collect.
        val ignoreTopicCache = withContext(ioDispatcher) {
            userPreferencesRepository.observeIgnoreTopicCache().first()
        }
        if (ignoreTopicCache) {
            emit(TopicPageEmission(fetchAndPersist(cat, post, page, FetchMode.AUTHENTICATED), provisional = false))
            return@flow
        }

        val cached = withContext(ioDispatcher) { loadFromCache(cat, post, page) }
        if (cached != null) {
            // #231 — `forceRefresh` (set when opening a topic from a flag, where the user
            // wants the latest posts) bypasses the snappy-cache TTL: the cached page is
            // emitted instantly, but we still always re-fetch below so a followed
            // topic that grew is never shown stale within the 60s window. The TTL skip
            // remains for ordinary back-nav (forceRefresh = false).
            val canSkipRefresh = !forceRefresh &&
                cached.authMode == FetchMode.AUTHENTICATED &&
                CachePolicy.isFresh(cached.fetchedAt, CachePolicy.topicPage, clock)
            // #877 — the cache page is provisional ONLY when a refresh follows on this very
            // flow. On the TTL-skip path it IS the settled page: flagging it provisional
            // would strand the pill on « Chargement… » with nothing left to confirm it.
            emit(TopicPageEmission(cached.topic, provisional = !canSkipRefresh))
            if (canSkipRefresh) {
                return@flow
            }
            // Stale or ANONYMOUS cache stays on screen if the background refresh fails.
            // UI does nothing — a snackbar/banner for refresh failures is the
            // responsibility of the caller (Phase 1D PR 2 added Retry; nothing else
            // needs a structural change here). CancellationException is rethrown to
            // keep structured concurrency semantics intact.
            try {
                emit(TopicPageEmission(fetchAndPersist(cat, post, page, FetchMode.AUTHENTICATED), provisional = false))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") refreshError: Exception) {
                Log.w(LOG_TAG, "Stale refresh failed for cat=$cat post=$post page=$page", refreshError)
                // #877 — terminal re-emission: the refresh is not coming, so the cache page is
                // now the settled one. Without this, a provisional-gated pill would show
                // « Chargement… » forever on an offline back-nav to a stale page.
                emit(TopicPageEmission(cached.topic, provisional = false))
            }
        } else {
            emit(TopicPageEmission(fetchAndPersist(cat, post, page, FetchMode.AUTHENTICATED), provisional = false))
        }
    }

    override suspend fun refreshTopicPage(cat: Int, post: Int, page: Int): Topic =
        fetchAndPersist(cat, post, page, FetchMode.AUTHENTICATED)

    /**
     * Phase 1D PR 4 — anonymous prefetch. Issues an unauthenticated fetch (cf.
     * ADR-003 § Prefetch) and persists the result *only if* it does not
     * overwrite an existing authenticated cache row. Failures are logged and
     * swallowed so a flaky prefetch never disturbs the user-facing flow.
     *
     * **No-op when the alpha `ignoreTopicCache` toggle is ON**: prefetching into Room
     * while the user explicitly asked to bypass it would re-fill the very cache they
     * want to skip, so the call returns early without hitting the network.
     *
     * **No-op when ANY cache row already exists** (#895 quick win 2) : the prefetch's declared
     * purpose is warming pages never visited. Re-fetching over an existing AUTHENTICATED row is
     * pure waste (the DAO guard below would refuse the write anyway), and over a stale ANONYMOUS
     * row it buys nothing either — reading an anon row always triggers the authenticated refetch.
     * The `upsertTopicPageWithPostsUnlessAuthenticated` transaction stays as the LAST line of
     * defense against the check-then-fetch race (a concurrent authenticated fetch landing between
     * this read and the persist).
     */
    override suspend fun prefetch(cat: Int, post: Int, page: Int) {
        val ignoreTopicCache = withContext(ioDispatcher) {
            userPreferencesRepository.observeIgnoreTopicCache().first()
        }
        if (ignoreTopicCache) {
            // The alpha toggle promises that "Ignorer le cache topic" suspends the prefetch as
            // well — prefetching into Room while the user wants to bypass it would re-fill the
            // very cache they asked us to skip. Cancellation semantics are preserved because we
            // do nothing.
            Log.d(LOG_TAG, "Skipped topic prefetch because ignore topic cache is enabled")
            return
        }
        if (withContext(ioDispatcher) { loadFromCache(cat, post, page) } != null) {
            Log.d(LOG_TAG, "Skipped topic prefetch: page already cached (cat=$cat post=$post page=$page)")
            return
        }
        try {
            fetchAndPersist(cat, post, page, FetchMode.ANONYMOUS)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Log.w(LOG_TAG, "Prefetch failed for cat=$cat post=$post page=$page", error)
        }
    }

    private suspend fun fetchAndPersist(
        cat: Int,
        post: Int,
        page: Int,
        authMode: FetchMode,
    ): Topic = withContext(ioDispatcher) {
        // The `rf2.topic.network` and `rf2.topic.body_read` sections live inside `HfrClient`
        // (same coroutine, same IO dispatcher). The remaining phases — parse, map, persist —
        // are wrapped here. Section names match `docs/guides/profiling.md`.
        //
        // `parse_html` and `map_domain` use the synchronous `trace { … }` helper because both
        // wrapped calls are non-suspend and run to completion on a single thread. `room_write`
        // wraps a `suspend` Room transaction that may resume on Room's executor thread, so the
        // synchronous per-thread `Trace.beginSection`/`endSection` would leak — we use the async
        // variant with a process-wide cookie instead.
        val html = client.getTopicPage(
            cat = cat,
            post = post,
            page = page,
            useAuth = authMode == FetchMode.AUTHENTICATED,
        )
        val topic = trace("rf2.topic.parse_html") { parser.parseTopicPage(html) }
        val (topicEntity, postEntities) = trace("rf2.topic.map_domain") {
            TopicMappers.toEntities(topic, clock.instant(), authMode)
        }
        traceAsync(ROOM_WRITE_SECTION, asyncCookie.incrementAndGet()) {
            persist(topicEntity, postEntities, authMode)
        }
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
     * fresh anon one without the per-user signal. The check runs inside a
     * Room `@Transaction` (`TopicDao.upsertTopicPageWithPostsUnlessAuthenticated`)
     * so a concurrent authenticated fetch cannot land between the read and the
     * write — the previous read-then-write outside the transaction had a narrow
     * TOCTOU window flagged by the multi-flavor reviews on PR #115.
     */
    private suspend fun persist(
        topicEntity: TopicEntity,
        postEntities: List<PostEntity>,
        authMode: FetchMode,
    ) {
        if (authMode == FetchMode.ANONYMOUS) {
            val written = topicDao.upsertTopicPageWithPostsUnlessAuthenticated(topicEntity, postEntities)
            if (!written) {
                Log.d(
                    LOG_TAG,
                    "Skipped anonymous overwrite of AUTHENTICATED cache for " +
                        "cat=${topicEntity.cat} post=${topicEntity.post} page=${topicEntity.page}",
                )
            }
        } else {
            topicDao.upsertTopicPageWithPosts(topicEntity, postEntities)
        }
    }

    private suspend fun loadFromCache(cat: Int, post: Int, page: Int): CachedTopic? =
        // Async section : the wrapped DAO calls are `suspend` and Room may resume the coroutine
        // on its own executor, breaking the same-thread invariant of synchronous trace sections.
        // `traceAsync` from `androidx.tracing` handles begin/end across suspend boundaries.
        traceAsync(ROOM_READ_SECTION, asyncCookie.incrementAndGet()) {
            val topicEntity = topicDao.getTopicPage(cat, post, page) ?: return@traceAsync null
            val postEntities = topicDao.getPostsByNumreponse(cat, topicEntity.numreponses)
            CachedTopic(
                topic = TopicMappers.toDomain(topicEntity, postEntities),
                fetchedAt = topicEntity.fetchedAt,
                authMode = topicEntity.authMode,
            )
        }

    private data class CachedTopic(
        val topic: Topic,
        val fetchedAt: java.time.Instant,
        val authMode: FetchMode,
    )

    private companion object {
        const val LOG_TAG = "TopicRepository"
        const val ROOM_READ_SECTION = "rf2.topic.room_read"
        const val ROOM_WRITE_SECTION = "rf2.topic.room_write"

        // Process-wide monotonic cookie source for async trace sections. AndroidX requires
        // cookies to be unique only among overlapping events sharing the same section name;
        // a single shared `AtomicInteger` is enough and survives `@Singleton` boundaries
        // (same JVM = same counter). Wraps around at `Int.MAX_VALUE` after ~2B sections,
        // which is several orders of magnitude above any realistic process lifetime.
        private val asyncCookie = AtomicInteger(0)
    }
}
