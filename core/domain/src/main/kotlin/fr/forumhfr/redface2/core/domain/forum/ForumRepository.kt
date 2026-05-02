package fr.forumhfr.redface2.core.domain.forum

import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.SubCategory
import fr.forumhfr.redface2.core.model.TopicListPage
import kotlinx.coroutines.flow.Flow

/**
 * Read access to the HFR forum browsing layer (categories, subcategories, topic lists).
 * Phase 1C-A is REST-first per ADR-003 — the implementation in `:core:data` calls the
 * `/webservices/rest_api.php` endpoints. The repository keeps a small in-memory cache
 * for the categories list (rare to change); topic lists are not cached in memory.
 *
 * Flow semantics mirror [fr.forumhfr.redface2.core.domain.flags.FlagRepository]:
 * - [observeCategories] / [observeTopicList] emit [ForumResult.Loading] then a single
 *   [ForumResult.Success] or [ForumResult.Failure] for the initial fetch, then any
 *   subsequent payload broadcast by an explicit [refreshCategories] / [refreshTopicList].
 * - Switching tabs / pages cancels the previous flow via the consumer's `flatMapLatest`.
 */
interface ForumRepository {

    fun observeCategories(): Flow<ForumResult<List<Category>>>

    suspend fun refreshCategories()

    fun observeSubcategories(cat: Int): Flow<ForumResult<List<SubCategory>>>

    suspend fun refreshSubcategories(cat: Int)

    fun observeTopicList(
        cat: Int,
        subcat: Int?,
        page: Int,
    ): Flow<ForumResult<TopicListPage>>

    suspend fun refreshTopicList(cat: Int, subcat: Int?, page: Int)
}

/**
 * Tri-state outcome of a forum browsing fetch. The domain layer stays Compose-free so
 * ViewModels translate this to a UI state on their own terms.
 */
sealed class ForumResult<out T> {
    data object Loading : ForumResult<Nothing>()
    data class Success<T>(val value: T) : ForumResult<T>()
    data class Failure(val cause: Throwable) : ForumResult<Nothing>()
}
