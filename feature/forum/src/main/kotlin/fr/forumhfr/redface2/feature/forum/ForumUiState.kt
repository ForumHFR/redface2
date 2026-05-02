package fr.forumhfr.redface2.feature.forum

import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.SubCategory
import fr.forumhfr.redface2.core.model.TopicListPage
import kotlin.math.ceil

/**
 * UI state for the Forum home screen (list of HFR top-level categories). The state
 * machine mirrors [fr.forumhfr.redface2.core.domain.forum.ForumResult] without exposing
 * domain types directly, so the screen stays Compose-only and the VM stays easy to
 * unit-test.
 */
sealed interface ForumUiState {
    data object Loading : ForumUiState
    data class Content(val categories: List<Category>) : ForumUiState
    data class Error(val message: String?) : ForumUiState
}

/**
 * UI state for a category detail screen. We keep subcategories and topic list as
 * independent sub-states so the screen can render the topic list under a loading
 * skeleton while the subcategories are already shown (or vice-versa).
 *
 * `categoryName` is the HFR display name for [cat] when known (sourced from the
 * cached categories list); `null` when categories haven't loaded yet, in which case
 * the screen falls back to the bare numeric category id.
 *
 * `pageCount` is the number of listing pages computed from
 * `TopicListPage.totalTopics / resultsPerPage` — used by the pager to disable
 * "Suivant" on the last page. Defaults to `1` until topics finish loading.
 */
data class CategoryUiState(
    val cat: Int,
    val categoryName: String?,
    val initialSubcat: Int?,
    val selectedSubcat: Int?,
    val page: Int,
    val pageCount: Int,
    val subcategories: SubcategoriesUiState,
    val topics: TopicsUiState,
)

/**
 * Pure helper kept top-level so it can be exercised in isolation. Falls back to `1`
 * when either input is non-positive or when the math underflows — the pager renders
 * a "Page 1 / 1" cell in that case which is what we want for empty listings.
 */
internal fun listingPageCount(totalTopics: Int, resultsPerPage: Int): Int {
    if (totalTopics <= 0 || resultsPerPage <= 0) return 1
    return ceil(totalTopics.toDouble() / resultsPerPage).toInt().coerceAtLeast(1)
}

sealed interface SubcategoriesUiState {
    data object Loading : SubcategoriesUiState
    data class Content(val subcategories: List<SubCategory>) : SubcategoriesUiState
    data class Error(val message: String?) : SubcategoriesUiState
}

sealed interface TopicsUiState {
    data object Loading : TopicsUiState
    data class Content(val page: TopicListPage) : TopicsUiState
    data class Error(val message: String?) : TopicsUiState
}
