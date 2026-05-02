package fr.forumhfr.redface2.feature.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.model.SubCategory
import fr.forumhfr.redface2.core.model.TopicListPage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the per-category screen. Uses [CategoryRequest] (assisted-injected by
 * Hilt) to receive the route arguments, mirroring the pattern in
 * [fr.forumhfr.redface2.feature.topic.TopicViewModel] which also takes a route-bound
 * request object.
 *
 * Subscribed to:
 * - the subcategories list of `cat` (cached in the repository, rare to change),
 * - the topic list for the current `(cat, subcat, page)` triple.
 *
 * Switching subcategories is a local UI state change — it does not push a new nav entry,
 * so the deep link `(cat, initialSubcat)` survives across switches and the back button
 * still brings the user up to the parent stack.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = CategoryViewModel.Factory::class)
class CategoryViewModel @AssistedInject constructor(
    @Assisted private val request: CategoryRequest,
    private val forumRepository: ForumRepository,
) : ViewModel() {

    private val selectedSubcat: MutableStateFlow<Int?> = MutableStateFlow(request.initialSubcat)
    private val page: MutableStateFlow<Int> = MutableStateFlow(1)

    private val subcategoriesState: StateFlow<SubcategoriesUiState> =
        forumRepository.observeSubcategories(request.cat)
            .map { it.toSubcategoriesUiState() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = SubcategoriesUiState.Loading,
            )

    private val topicsState: StateFlow<TopicsUiState> = combine(
        selectedSubcat,
        page,
    ) { subcat, currentPage -> subcat to currentPage }
        .flatMapLatest { (subcat, currentPage) ->
            forumRepository.observeTopicList(cat = request.cat, subcat = subcat, page = currentPage)
                .map { it.toTopicsUiState() }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = TopicsUiState.Loading,
        )

    val uiState: StateFlow<CategoryUiState> = combine(
        selectedSubcat,
        page,
        subcategoriesState,
        topicsState,
    ) { subcat, currentPage, subcategories, topics ->
        CategoryUiState(
            cat = request.cat,
            initialSubcat = request.initialSubcat,
            selectedSubcat = subcat,
            page = currentPage,
            subcategories = subcategories,
            topics = topics,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = CategoryUiState(
            cat = request.cat,
            initialSubcat = request.initialSubcat,
            selectedSubcat = request.initialSubcat,
            page = 1,
            subcategories = SubcategoriesUiState.Loading,
            topics = TopicsUiState.Loading,
        ),
    )

    fun selectSubcategory(subcat: Int?) {
        if (selectedSubcat.value == subcat) return
        selectedSubcat.value = subcat
        page.value = 1
    }

    fun selectPage(newPage: Int) {
        if (newPage < 1 || newPage == page.value) return
        page.value = newPage
    }

    fun refresh() {
        viewModelScope.launch {
            forumRepository.refreshSubcategories(request.cat)
            forumRepository.refreshTopicList(
                cat = request.cat,
                subcat = selectedSubcat.value,
                page = page.value,
            )
        }
    }

    private fun ForumResult<List<SubCategory>>.toSubcategoriesUiState(): SubcategoriesUiState = when (this) {
        ForumResult.Loading -> SubcategoriesUiState.Loading
        is ForumResult.Success -> SubcategoriesUiState.Content(value)
        is ForumResult.Failure -> SubcategoriesUiState.Error(cause.message)
    }

    private fun ForumResult<TopicListPage>.toTopicsUiState(): TopicsUiState = when (this) {
        ForumResult.Loading -> TopicsUiState.Loading
        is ForumResult.Success -> TopicsUiState.Content(value)
        is ForumResult.Failure -> TopicsUiState.Error(cause.message)
    }

    @AssistedFactory
    interface Factory {
        fun create(request: CategoryRequest): CategoryViewModel
    }

    private companion object {
        const val STOP_TIMEOUT_MS: Long = 5_000L
    }
}

/**
 * Route arguments for [CategoryViewModel]. Plain data class so route values pass through
 * Hilt assisted injection without going through [androidx.lifecycle.SavedStateHandle].
 */
data class CategoryRequest(
    val cat: Int,
    val initialSubcat: Int?,
)
