package fr.forumhfr.redface2.feature.forum

import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.SubCategory
import fr.forumhfr.redface2.core.model.TopicListPage

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
 */
data class CategoryUiState(
    val cat: Int,
    val initialSubcat: Int?,
    val selectedSubcat: Int?,
    val page: Int,
    val subcategories: SubcategoriesUiState,
    val topics: TopicsUiState,
)

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
