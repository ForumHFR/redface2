package fr.forumhfr.redface2.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.search.SearchRepository
import fr.forumhfr.redface2.core.model.search.SearchCategoryScope
import fr.forumhfr.redface2.core.model.search.SearchPivotCategory
import fr.forumhfr.redface2.core.model.search.SearchRequest
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 2G-A (#150 partiel) — ViewModel for [SearchScreen].
 *
 * One [searchJob] is in flight at a time : a new submit/retry/category-change
 * cancels the previous one so an older response cannot overwrite a newer
 * query. The submit pipeline is :
 *
 *  1. Clear any prior error, flip `isLoading=true`.
 *  2. Call [SearchRepository.search] with the user's query + scope.
 *  3. On success, populate `results`/`pivotCategories`/`selectedCategory`.
 *  4. On failure, map the exception kind to [SearchErrorKind] and surface
 *     a retry button.
 *
 * The repository already redacts the query from `IOException` messages, so
 * nothing in this layer logs it either — we map error kinds purely on the
 * exception type.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /** The query that was last actually submitted, used by [retry]. */
    private var lastSubmittedQuery: String? = null

    /** The category that was last actually submitted, used by [retry]. */
    private var lastSubmittedCategory: SearchCategoryScope = SearchCategoryScope.All

    private var searchJob: Job? = null

    fun submit(intent: SearchIntent) {
        when (intent) {
            is SearchIntent.QueryChanged -> onQueryChanged(intent.query)
            SearchIntent.Submit -> onSubmit()
            SearchIntent.Retry -> onRetry()
            is SearchIntent.CategorySelected -> onCategorySelected(intent.category)
        }
    }

    private fun onQueryChanged(query: String) {
        // Just track the field's value ; clear the error banner so the user isn't
        // left staring at a stale failure while typing a new query.
        _state.update { it.copy(query = query, errorMessage = null) }
    }

    private fun onSubmit() {
        val trimmed = _state.value.query.trim()
        if (trimmed.isEmpty()) return
        launchSearch(trimmed, SearchCategoryScope.All)
    }

    private fun onRetry() {
        val query = lastSubmittedQuery ?: return
        launchSearch(query, lastSubmittedCategory)
    }

    private fun onCategorySelected(category: SearchPivotCategory) {
        val query = lastSubmittedQuery ?: _state.value.query.trim().takeIf { it.isNotEmpty() } ?: return
        launchSearch(query, SearchCategoryScope.Category(id = category.id, name = category.label))
    }

    private fun launchSearch(query: String, scope: SearchCategoryScope) {
        // Cancel any in-flight search ; a newer query must take precedence.
        searchJob?.cancel()
        lastSubmittedQuery = query
        lastSubmittedCategory = scope
        _state.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                hasSearched = true,
            )
        }
        searchJob = viewModelScope.launch {
            val outcome = runCatching {
                searchRepository.search(SearchRequest(query = query, category = scope))
            }
            outcome.fold(
                onSuccess = { page ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            results = page.topics,
                            pivotCategories = page.pivotCategories,
                            selectedCategory = page.selectedCategory,
                            errorMessage = null,
                        )
                    }
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    val kind = when (error) {
                        is IOException -> SearchErrorKind.Network
                        else -> SearchErrorKind.Unknown
                    }
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = kind,
                            // Keep the previous results untouched on retry-friendly errors —
                            // wiping the list on a transient network blip is more disruptive
                            // than helpful.
                        )
                    }
                },
            )
        }
    }
}
