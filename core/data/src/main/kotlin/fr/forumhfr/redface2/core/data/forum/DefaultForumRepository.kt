package fr.forumhfr.redface2.core.data.forum

import android.util.Log
import fr.forumhfr.redface2.core.data.cache.CachePolicy
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.forum.FlagFilterBucket
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.SubCategory
import fr.forumhfr.redface2.core.model.TopicListPage
import fr.forumhfr.redface2.core.network.HfrApiClient
import fr.forumhfr.redface2.core.network.HfrRestFlagBucket
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Phase 1C-A REST-first implementation of [ForumRepository] (cf. ADR-003).
 *
 * Caching policy:
 * - Categories list is cached in memory after the first successful fetch — the public
 *   list is small (19 entries) and very rarely changes. [refreshCategories] re-emits
 *   `Loading` then a fresh result through the broadcast flow, and replaces the cache
 *   on success; on failure the previous cached value stays in place so the next
 *   observer can still render last-known-good data without bouncing through an Error
 *   state.
 * - Subcategory lists are cached per parent category id, same semantics:
 *   [refreshSubcategories] replaces the cache on success, keeps it on failure.
 * - Topic lists are **not** cached: pagination + freshness expectations make a memory
 *   cache more confusing than helpful in 1C-A. Re-opening a category screen refetches.
 *
 * No Room persistence in 1C-A — disk persistence for browsing is out of scope per the
 * ADR's "no fallback runtime global" + the smallness of the payloads at stake.
 */
@Singleton
class DefaultForumRepository @Inject constructor(
    private val apiClient: HfrApiClient,
    @param:ForumJson private val json: Json,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock,
) : ForumRepository {

    @Volatile
    private var cachedCategories: CachedEntry<List<Category>>? = null
    private val cachedSubcategories: MutableMap<Int, CachedEntry<List<SubCategory>>> = HashMap()
    private val subcategoriesLock = Any()

    // Single-flight guard for the cold categories fetch. `observeCategories()` is a cold
    // `flow {}` builder with no in-flight coalescing, so two concurrent first-collectors
    // (e.g. `FlagsViewModel` grouping #179 + `DefaultFlagRepository.loadCategories()`, both
    // observing on an authenticated cold start before the Forum tab has populated the cache)
    // would each see `cachedCategories == null` and each fire `getCategories()` — two
    // redundant public REST calls. The mutex serialises the fetch path and the second
    // collector double-checks the now-warm cache instead of re-fetching.
    private val categoriesFetchMutex = Mutex()

    private val categoriesRefresh: MutableSharedFlow<ForumResult<List<Category>>> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val subcategoriesRefresh: MutableMap<Int, MutableSharedFlow<ForumResult<List<SubCategory>>>> = HashMap()
    private val topicListRefresh: MutableMap<TopicListKey, MutableSharedFlow<ForumResult<TopicListPage>>> = HashMap()
    private val refreshFlowsLock = Any()

    override fun observeCategories(): Flow<ForumResult<List<Category>>> = flow {
        val cached = cachedCategories
        val now = clock.instant()
        if (cached != null && CachePolicy.isFresh(cached.fetchedAt, CachePolicy.categories, now)) {
            emit(ForumResult.Success(cached.value))
        } else {
            if (cached != null) emit(ForumResult.Success(cached.value))
            emit(ForumResult.Loading)
            emit(fetchCategoriesSingleFlight(now))
        }
        emitAll(categoriesRefresh.asSharedFlow())
    }

    override suspend fun refreshCategories() {
        categoriesRefresh.emit(ForumResult.Loading)
        categoriesRefresh.emit(fetchCategories())
    }

    override fun observeSubcategories(cat: Int): Flow<ForumResult<List<SubCategory>>> = flow {
        val cached = synchronized(subcategoriesLock) { cachedSubcategories[cat] }
        val now = clock.instant()
        if (cached != null && CachePolicy.isFresh(cached.fetchedAt, CachePolicy.subcategories, now)) {
            emit(ForumResult.Success(cached.value))
        } else {
            if (cached != null) emit(ForumResult.Success(cached.value))
            emit(ForumResult.Loading)
            emit(fetchSubcategories(cat))
        }
        emitAll(subcategoriesFlow(cat).asSharedFlow())
    }

    override suspend fun refreshSubcategories(cat: Int) {
        val flow = subcategoriesFlow(cat)
        flow.emit(ForumResult.Loading)
        flow.emit(fetchSubcategories(cat))
    }

    override fun observeTopicList(
        cat: Int,
        subcat: Int?,
        page: Int,
    ): Flow<ForumResult<TopicListPage>> = flow {
        emit(ForumResult.Loading)
        emit(fetchTopicList(cat, subcat, page))
        emitAll(topicListFlow(cat, subcat, page).asSharedFlow())
    }

    override suspend fun refreshTopicList(cat: Int, subcat: Int?, page: Int) {
        val flow = topicListFlow(cat, subcat, page)
        flow.emit(ForumResult.Loading)
        flow.emit(fetchTopicList(cat, subcat, page))
    }

    /**
     * #455 — one-shot fetch of the flagged topics of a (sub)category for [bucket]. Not
     * cached (same stance as topic lists) and not paginated server-side: we request the
     * max page size so a busy category's whole bucket comes back in one call (the flag
     * buckets ignore real pagination — see [HfrApiClient.getCategoryFlagTopics]). Reuses
     * the forum mapper [RestForumMappers.toTopicListPage] so the rows are [TopicSummary],
     * NOT [fr.forumhfr.redface2.core.model.Flag] — the category screen needs the listing
     * row model (author / sticky / locked) and the same "resume at last read page" path.
     */
    override suspend fun getFlagFilteredTopics(
        cat: Int,
        subcat: Int?,
        bucket: FlagFilterBucket,
    ): ForumResult<TopicListPage> = withContext(ioDispatcher) {
        try {
            val body = apiClient.getCategoryFlagTopics(
                cat = cat,
                bucket = bucket.toRestBucket(),
                subcat = subcat,
                resultsPerPage = FLAG_FILTER_RESULTS_PER_PAGE,
                useAuth = true,
            )
            val envelope = json.decodeFromString<RestListEnvelope<RestTopic>>(body)
            ForumResult.Success(RestForumMappers.toTopicListPage(envelope, cat = cat, subcat = subcat))
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // The flag-filter fetch runs inside a ViewModel job cancelled on filter / subcat
            // change; let cancellation propagate rather than mapping it to a Failure that would
            // clobber flagFilterTopics with an Error (review #455). Same stance as prefetchTopicList.
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Log.w(LOG_TAG, "Flag-filter fetch failed for cat=$cat subcat=$subcat bucket=$bucket", error)
            ForumResult.Failure(error)
        }
    }

    private fun FlagFilterBucket.toRestBucket(): HfrRestFlagBucket = when (this) {
        FlagFilterBucket.PARTICIPATED -> HfrRestFlagBucket.PARTICIPATED
        FlagFilterBucket.READ -> HfrRestFlagBucket.READ
        FlagFilterBucket.FAVORITES -> HfrRestFlagBucket.FAVORITES
    }

    /**
     * Fires an unauthenticated `getTopicList` request — no cookie sent — to
     * warm HFR's edge cache for the next page the user is likely to visit.
     * The response is intentionally **discarded**: writing it into the
     * authenticated [topicListRefresh] flow would silently strip per-user
     * fields like `is_read` and `last_post_read_id` that the screen relies on,
     * which is exactly the failure mode ADR-003 § Prefetch warns against.
     *
     * Failures are swallowed (best-effort prefetch). [CancellationException]
     * is rethrown so the caller's coroutine cancellation propagates.
     */
    override suspend fun prefetchTopicList(cat: Int, subcat: Int?, page: Int) {
        withContext(ioDispatcher) {
            try {
                apiClient.getTopicList(
                    cat = cat,
                    subcat = subcat,
                    page = page,
                    resultsPerPage = DEFAULT_RESULTS_PER_PAGE,
                    useAuth = false,
                )
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                Log.w(LOG_TAG, "Prefetch failed for cat=$cat subcat=$subcat page=$page", error)
            }
        }
    }

    /**
     * Coalesces concurrent cold category fetches behind [categoriesFetchMutex]. Whoever wins
     * the lock first performs the single [fetchCategories] round-trip and warms
     * [cachedCategories]; any collector that was waiting on the lock then double-checks the
     * (now fresh) cache and returns it without a second network call. A still-cold cache after
     * the lock (the previous fetch failed and left no entry) falls through to its own fetch.
     *
     * Note: only the **cold** fetch path is serialised. [refreshCategories] deliberately
     * bypasses this guard — it is an explicit user/forced refresh and must always re-hit HFR.
     */
    private suspend fun fetchCategoriesSingleFlight(now: Instant): ForumResult<List<Category>> =
        categoriesFetchMutex.withLock {
            val cached = cachedCategories
            if (cached != null && CachePolicy.isFresh(cached.fetchedAt, CachePolicy.categories, now)) {
                ForumResult.Success(cached.value)
            } else {
                fetchCategories()
            }
        }

    private suspend fun fetchCategories(): ForumResult<List<Category>> = withContext(ioDispatcher) {
        runCatching {
            val body = apiClient.getCategories(useAuth = false)
            val envelope = json.decodeFromString<RestListEnvelope<RestCategory>>(body)
            RestForumMappers.toCategories(envelope)
        }.fold(
            onSuccess = { list ->
                cachedCategories = CachedEntry(list, clock.instant())
                ForumResult.Success(list)
            },
            onFailure = { throwable ->
                Log.w(LOG_TAG, "Categories fetch failed", throwable)
                ForumResult.Failure(throwable)
            },
        )
    }

    private suspend fun fetchSubcategories(cat: Int): ForumResult<List<SubCategory>> = withContext(ioDispatcher) {
        runCatching {
            val body = apiClient.getSubcategories(cat = cat)
            val envelope = json.decodeFromString<RestListEnvelope<RestSubcategory>>(body)
            RestForumMappers.toSubcategories(envelope, parentCategoryId = cat)
        }.fold(
            onSuccess = { list ->
                synchronized(subcategoriesLock) { cachedSubcategories[cat] = CachedEntry(list, clock.instant()) }
                ForumResult.Success(list)
            },
            onFailure = { throwable ->
                Log.w(LOG_TAG, "Subcategories fetch failed for cat=$cat", throwable)
                ForumResult.Failure(throwable)
            },
        )
    }

    private suspend fun fetchTopicList(
        cat: Int,
        subcat: Int?,
        page: Int,
    ): ForumResult<TopicListPage> = withContext(ioDispatcher) {
        runCatching {
            // `useAuth = true` is intentional: the topic-listing UI surfaces per-user
            // fields (`is_read`, `last_post_read_id`, the page extracted from
            // `links.posts.href`). A future prefetch path that warms a cache off
            // `/topics/last/` must instead pass `useAuth = false` — see ADR-003 §
            // "Prefetch" — otherwise it would silently mark drapeaux as read.
            val body = apiClient.getTopicList(
                cat = cat,
                subcat = subcat,
                page = page,
                resultsPerPage = DEFAULT_RESULTS_PER_PAGE,
                useAuth = true,
            )
            val envelope = json.decodeFromString<RestListEnvelope<RestTopic>>(body)
            RestForumMappers.toTopicListPage(envelope, cat = cat, subcat = subcat)
        }.fold(
            onSuccess = { ForumResult.Success(it) },
            onFailure = { throwable ->
                Log.w(LOG_TAG, "Topic list fetch failed for cat=$cat subcat=$subcat page=$page", throwable)
                ForumResult.Failure(throwable)
            },
        )
    }

    private fun subcategoriesFlow(cat: Int): MutableSharedFlow<ForumResult<List<SubCategory>>> =
        synchronized(refreshFlowsLock) {
            subcategoriesRefresh.getOrPut(cat) {
                MutableSharedFlow(
                    replay = 0,
                    extraBufferCapacity = 1,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
            }
        }

    private fun topicListFlow(
        cat: Int,
        subcat: Int?,
        page: Int,
    ): MutableSharedFlow<ForumResult<TopicListPage>> = synchronized(refreshFlowsLock) {
        topicListRefresh.getOrPut(TopicListKey(cat, subcat, page)) {
            MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }
    }

    private data class TopicListKey(val cat: Int, val subcat: Int?, val page: Int)

    private data class CachedEntry<T>(val value: T, val fetchedAt: Instant)

    private companion object {
        const val LOG_TAG = "ForumRepository"
        const val DEFAULT_RESULTS_PER_PAGE = 50

        // #455 — flag buckets are not paginated server-side; request the max page size so a
        // busy (sub)category's whole bucket comes back in one call (HfrApiClient caps at 100).
        const val FLAG_FILTER_RESULTS_PER_PAGE = 100
    }
}
