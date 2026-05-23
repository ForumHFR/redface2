package fr.forumhfr.redface2.feature.search

import fr.forumhfr.redface2.core.model.search.SearchPivotCategory
import fr.forumhfr.redface2.core.model.search.SearchTextScope
import fr.forumhfr.redface2.core.model.search.SearchTopicResult

/**
 * Phase 2G-A/B (#150 partiel) — MVI state for [SearchScreen].
 *
 * `hasSearched` flips to `true` after the first non-blank submit so the screen can
 * distinguish the « initial idle state » from a no-result outcome. Without it the
 * « Aucun résultat » empty state would flash before the user has even searched.
 *
 * `errorMessage` is a localized string already resolved by the ViewModel ; the
 * screen surfaces it verbatim with a retry button.
 */
data class SearchUiState(
    val query: String = "",
    val textScope: SearchTextScope = SearchTextScope.TitlesAndPosts,
    val selectedCategory: SearchPivotCategory? = null,
    val pivotCategories: List<SearchPivotCategory> = emptyList(),
    val results: List<SearchTopicResult> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: SearchErrorKind? = null,
    val hasSearched: Boolean = false,
)

/**
 * Localized failure kinds the screen can render. Kept as a discrete sum so the
 * Compose layer can pick the right string resource (network vs unexpected
 * response vs « no cat context » parser error) without leaking technical
 * exception messages to the user.
 */
sealed interface SearchErrorKind {
    data object Network : SearchErrorKind
    data object Unknown : SearchErrorKind
}

/**
 * Intents emitted by [SearchScreen]. `Retry` reuses the last submitted query
 * (NOT the field's current value) so the user can re-fire a failed search
 * even if they have started typing a new one. `CategorySelected` re-issues
 * the search scoped to the picked pivot.
 */
sealed interface SearchIntent {
    data class QueryChanged(val query: String) : SearchIntent
    data class TextScopeSelected(val scope: SearchTextScope) : SearchIntent
    data object Submit : SearchIntent
    data object Retry : SearchIntent
    data class CategorySelected(val category: SearchPivotCategory) : SearchIntent
}
