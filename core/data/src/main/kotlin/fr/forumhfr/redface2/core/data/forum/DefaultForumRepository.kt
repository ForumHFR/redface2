package fr.forumhfr.redface2.core.data.forum

import android.util.Log
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.SubCategory
import fr.forumhfr.redface2.core.model.TopicListPage
import fr.forumhfr.redface2.core.network.HfrApiClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Phase 1C-A REST-first implementation of [ForumRepository] (cf. ADR-003).
 *
 * Caching policy:
 * - Categories list is cached in memory after the first successful fetch — the public
 *   list is small (19 entries) and very rarely changes. [refreshCategories] clears the
 *   cache and refetches; the next observer gets the fresh value.
 * - Subcategory lists are cached per parent category id, same rationale.
 * - Topic lists are **not** cached: pagination + freshness expectations make a memory
 *   cache more confusing than helpful in 1C-A. Re-opening a category screen refetches.
 *
 * No Room persistence in 1C-A — disk persistence for browsing is out of scope per the
 * ADR's "no fallback runtime global" + the smallness of the payloads at stake.
 */
@Singleton
class DefaultForumRepository @Inject constructor(
    private val apiClient: HfrApiClient,
    private val json: Json,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ForumRepository {

    @Volatile
    private var cachedCategories: List<Category>? = null
    private val cachedSubcategories: MutableMap<Int, List<SubCategory>> = HashMap()
    private val subcategoriesLock = Any()

    private val categoriesRefresh: MutableSharedFlow<ForumResult<List<Category>>> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val subcategoriesRefresh: MutableMap<Int, MutableSharedFlow<ForumResult<List<SubCategory>>>> = HashMap()
    private val topicListRefresh: MutableMap<TopicListKey, MutableSharedFlow<ForumResult<TopicListPage>>> = HashMap()
    private val refreshFlowsLock = Any()

    override fun observeCategories(): Flow<ForumResult<List<Category>>> = flow {
        val cached = cachedCategories
        if (cached != null) {
            emit(ForumResult.Success(cached))
        } else {
            emit(ForumResult.Loading)
            emit(fetchCategories())
        }
        emitAll(categoriesRefresh.asSharedFlow())
    }

    override suspend fun refreshCategories() {
        categoriesRefresh.emit(ForumResult.Loading)
        categoriesRefresh.emit(fetchCategories())
    }

    override fun observeSubcategories(cat: Int): Flow<ForumResult<List<SubCategory>>> = flow {
        val cached = synchronized(subcategoriesLock) { cachedSubcategories[cat] }
        if (cached != null) {
            emit(ForumResult.Success(cached))
        } else {
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

    private suspend fun fetchCategories(): ForumResult<List<Category>> = withContext(ioDispatcher) {
        runCatching {
            val body = apiClient.getCategories(useAuth = false)
            val envelope = json.decodeFromString<RestListEnvelope<RestCategory>>(body)
            RestForumMappers.toCategories(envelope)
        }.fold(
            onSuccess = { list ->
                cachedCategories = list
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
                synchronized(subcategoriesLock) { cachedSubcategories[cat] = list }
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

    private companion object {
        const val LOG_TAG = "ForumRepository"
        const val DEFAULT_RESULTS_PER_PAGE = 50
    }
}
