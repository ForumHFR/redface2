package fr.forumhfr.redface2.feature.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.SubCategory
import fr.forumhfr.redface2.core.model.TopicListPage
import fr.forumhfr.redface2.core.model.TopicSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the per-category screen. Uses [CategoryRequest] (assisted-injected by
 * Hilt) to receive the route arguments, mirroring the pattern in
 * [fr.forumhfr.redface2.feature.topic.TopicViewModel] which also takes a route-bound
 * request object.
 *
 * Subscribed to:
 * - the categories list (memory-cached in the repository) — used to derive the
 *   display name for [CategoryRequest.cat] so the screen shows "Technologies Mobiles"
 *   instead of the raw "Catégorie 23",
 * - the subcategories list of `cat` (cached in the repository, rare to change),
 * - the topic list for the current `(cat, subcat, page)` triple.
 *
 * Switching subcategories is a local UI state change — it does not push a new nav entry,
 * so the deep link `(cat, initialSubcat)` survives across switches and the back button
 * still brings the user up to the parent stack.
 *
 * `refresh()` toggles [isRefreshing] for the duration of the network round-trip so a
 * PullToRefresh indicator stays anchored over the existing content. While a refresh is
 * in flight the [TopicsUiState] / [SubcategoriesUiState] sub-states keep showing the
 * previous `Content` (via [keepContentDuringRefresh]) instead of bouncing through
 * `Loading` and wiping the list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = CategoryViewModel.Factory::class)
class CategoryViewModel @AssistedInject constructor(
    @Assisted private val request: CategoryRequest,
    private val forumRepository: ForumRepository,
) : ViewModel() {

    private val selectedSubcat: MutableStateFlow<Int?> = MutableStateFlow(request.initialSubcat)
    private val page: MutableStateFlow<Int> = MutableStateFlow(request.initialPage.coerceAtLeast(1))
    private val searchQuery: MutableStateFlow<String> = MutableStateFlow("")
    private val isRefreshing: MutableStateFlow<Boolean> = MutableStateFlow(false)

    // scan keeps the last non-null name across `Loading` / `Failure` re-emissions so a
    // global refreshCategories() round-trip does not reflash the screen title back to
    // "Catégorie <id>". The seed `null` lets the screen render its fallback before the
    // first successful fetch.
    private val categoryNameState: StateFlow<String?> =
        forumRepository.observeCategories()
            .scan(null as String?) { previous, result ->
                result.toCategoryName(request.cat) ?: previous
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = null,
            )

    private val subcategoriesState: StateFlow<SubcategoriesUiState> =
        forumRepository.observeSubcategories(request.cat)
            .map { it.toSubcategoriesUiState() }
            .keepContentDuringRefresh(
                isLoading = { it is SubcategoriesUiState.Loading },
                isContent = { it is SubcategoriesUiState.Content },
            )
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = SubcategoriesUiState.Loading,
            )

    private val topicsState: StateFlow<TopicsUiState> = combine(
        selectedSubcat,
        page,
    ) { subcat, currentPage -> subcat to currentPage }
        // flatMapLatest gives us a fresh upstream every time (cat, subcat, page) shifts —
        // the new flow restarts at Loading, which is the right semantics: the topics
        // we were showing are for a different (subcat, page) tuple. The refresh-keeps-
        // content trick (keepContentDuringRefresh) is applied *inside* the new upstream
        // so that a refresh of the same key keeps showing the prior topics.
        .flatMapLatest { (subcat, currentPage) ->
            forumRepository.observeTopicList(cat = request.cat, subcat = subcat, page = currentPage)
                .map { it.toTopicsUiState() }
                .keepContentDuringRefresh(
                    isLoading = { it is TopicsUiState.Loading },
                    isContent = { it is TopicsUiState.Content },
                )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = TopicsUiState.Loading,
        )

    // 7 source flows total (5 core + searchQuery + isRefreshing). Kotlin's typed
    // `combine` overloads cap at 5, so we layer a second `combine` on top of the core
    // one to fold in the search query and refresh indicator. Keeps the lambda typed
    // and avoids `Array<*>` casts at the use site.
    private val coreState: Flow<CoreCategoryState> = combine(
        selectedSubcat,
        page,
        categoryNameState,
        subcategoriesState,
        topicsState,
    ) { subcat, currentPage, categoryName, subcategories, topics ->
        CoreCategoryState(subcat, currentPage, categoryName, subcategories, topics)
    }

    val uiState: StateFlow<CategoryUiState> = combine(
        coreState,
        searchQuery,
        isRefreshing,
    ) { core, query, refreshing ->
        CategoryUiState(
            cat = request.cat,
            categoryName = core.categoryName,
            initialSubcat = request.initialSubcat,
            selectedSubcat = core.subcat,
            page = core.page,
            pageCount = core.topics.pageCount(),
            subcategories = core.subcategories,
            topics = core.topics,
            searchQuery = query,
            filteredTopics = core.topics.filterTopics(query),
            isRefreshing = refreshing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = CategoryUiState(
            cat = request.cat,
            categoryName = null,
            initialSubcat = request.initialSubcat,
            selectedSubcat = request.initialSubcat,
            page = request.initialPage.coerceAtLeast(1),
            pageCount = 1,
            subcategories = SubcategoriesUiState.Loading,
            topics = TopicsUiState.Loading,
            searchQuery = "",
            filteredTopics = emptyList(),
            isRefreshing = false,
        ),
    )

    private var prefetchJob: Job? = null

    init {
        // Anonymous prefetch of `page + 1` whenever a content emission lands —
        // see ADR-003 § Prefetch and the PR 4 prompt. The job is cancelled on
        // every (subcat, page) change so we never have two prefetches in
        // flight at once and never warm a page the user has already moved
        // past. Using `is ContentTrigger` ignores Loading / Error transitions
        // — only firm Content triggers a warm-up.
        combine(selectedSubcat, page, topicsState) { subcat, currentPage, topics ->
            ContentTrigger(subcat, currentPage, topics.contentPageCount() ?: -1)
        }
            .distinctUntilChanged()
            .onEach { trigger -> schedulePrefetch(trigger) }
            .launchIn(viewModelScope)
    }

    private fun schedulePrefetch(trigger: ContentTrigger) {
        val nextPage = trigger.currentPage + 1
        // pageCount unknown (no Content yet) → -1 sentinel, skip without touching
        // the in-flight prefetch. The previous shape cancelled the job before this
        // guard, which would lose an already-running prefetch when a Loading or
        // intermediate emission landed during a pull-to-refresh — the prefetch was
        // dropped and not relaunched, leaving the user with a cold next page.
        if (trigger.pageCount <= 0 || nextPage > trigger.pageCount) return
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            forumRepository.prefetchTopicList(
                cat = request.cat,
                subcat = trigger.subcat,
                page = nextPage,
            )
        }
    }

    private fun TopicsUiState.contentPageCount(): Int? = when (this) {
        is TopicsUiState.Content -> listingPageCount(
            totalTopics = page.totalTopics,
            resultsPerPage = page.resultsPerPage,
        )
        else -> null
    }

    fun selectSubcategory(subcat: Int?) {
        if (selectedSubcat.value == subcat) return
        selectedSubcat.value = subcat
        page.value = 1
        // searchQuery preserved deliberately — see CategoryUiState.searchQuery KDoc.
    }

    fun selectPage(newPage: Int) {
        if (newPage < 1 || newPage == page.value) return
        page.value = newPage
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    /**
     * Triggers a network refresh of the subcategories AND the current topic list.
     * Toggles [isRefreshing] for the duration so the PullToRefresh indicator stays
     * anchored. The repository's refresh* methods broadcast `Loading` then the
     * fresh result through their respective shared flows; [keepContentDuringRefresh]
     * collapses the transient `Loading` so the list does not blank.
     */
    fun refresh() {
        viewModelScope.launch {
            isRefreshing.value = true
            try {
                forumRepository.refreshSubcategories(request.cat)
                forumRepository.refreshTopicList(
                    cat = request.cat,
                    subcat = selectedSubcat.value,
                    page = page.value,
                )
            } finally {
                isRefreshing.value = false
            }
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

    private fun ForumResult<List<Category>>.toCategoryName(cat: Int): String? = when (this) {
        is ForumResult.Success -> value.firstOrNull { it.id == cat }?.name
        // Loading / Failure → keep the previous name (null on first emission). The
        // screen renders "Catégorie <id>" while we wait, instead of flashing an error.
        else -> null
    }

    private fun TopicsUiState.pageCount(): Int = when (this) {
        is TopicsUiState.Content -> listingPageCount(
            totalTopics = page.totalTopics,
            resultsPerPage = page.resultsPerPage,
        )
        else -> 1
    }

    private fun TopicsUiState.filterTopics(query: String): List<TopicSummary> = when (this) {
        is TopicsUiState.Content -> page.topics.filter { matchesTopicQuery(it, query) }
        else -> emptyList()
    }

    @AssistedFactory
    interface Factory {
        fun create(request: CategoryRequest): CategoryViewModel
    }

    private data class CoreCategoryState(
        val subcat: Int?,
        val page: Int,
        val categoryName: String?,
        val subcategories: SubcategoriesUiState,
        val topics: TopicsUiState,
    )

    private data class ContentTrigger(
        val subcat: Int?,
        val currentPage: Int,
        val pageCount: Int,
    )

    private companion object {
        const val STOP_TIMEOUT_MS: Long = 5_000L
    }
}

/**
 * Route arguments for [CategoryViewModel]. Plain data class so route values pass through
 * Hilt assisted injection without going through [androidx.lifecycle.SavedStateHandle].
 *
 * `initialPage` lets a deep link such as `forum1.php?cat=23&subcat=550&page=2` land on
 * page 2 instead of silently resetting to 1. The user-driven [CategoryViewModel.selectPage]
 * still mutates the page from there; switching subcategories resets to 1 by design.
 */
data class CategoryRequest(
    val cat: Int,
    val initialSubcat: Int?,
    val initialPage: Int = 1,
)
