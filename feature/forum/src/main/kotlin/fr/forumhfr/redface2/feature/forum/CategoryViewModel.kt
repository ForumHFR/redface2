package fr.forumhfr.redface2.feature.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.error.classifyHfrError
import fr.forumhfr.redface2.core.domain.forum.FlagFilterBucket
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.domain.preferences.CategoryFlagFilter
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.model.AuthState
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    authRepository: AuthRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    // #1132 — the REAL auth state, seeded `null` (still unknown) so the persisted-filter seed can
    // wait for the FIRST genuine emission instead of racing a fake `Anonymous`. Both [isAuthenticated]
    // and the hydration in `init` derive from this single shared source.
    private val authState: StateFlow<AuthState?> = authRepository.observeAuthState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initialValue = null)

    private val isAuthenticated: StateFlow<Boolean> = authState
        .map { it is AuthState.Authenticated }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initialValue = false)

    private val selectedSubcat: MutableStateFlow<Int?> = MutableStateFlow(request.initialSubcat)
    private val page: MutableStateFlow<Int> = MutableStateFlow(request.initialPage.coerceAtLeast(1))
    // #1130 — the query AND its open/closed mode live in ONE flow so closeSearch (empty + close)
    // is a SINGLE emission and thus truly atomic: `combine` offers no multi-flow transaction, so
    // two separate MutableStateFlows could surface an intermediate (query="", active=true) before
    // the final state. Hosted here (not in the Composable) so the open/clear/close logic is
    // unit-testable without a Compose harness. `active` is NOT derived from the query — an open,
    // empty field is a valid state.
    private val search: MutableStateFlow<SearchSlice> = MutableStateFlow(SearchSlice(query = "", active = false))
    private val isRefreshing: MutableStateFlow<Boolean> = MutableStateFlow(false)

    // #455 — « Mes drapeaux » filter. Pushed state (not flatMapLatest) so refresh() can
    // await the bucket fetch and drive [isRefreshing] correctly, and so a refresh keeps the
    // current content instead of flashing Loading. ALL = the normal listing is the source;
    // the bucket fetch only runs for the three flag modes. Seeded ALL then REPLACED by the
    // persisted preference at hydration (#1132) — see [flagFilterHydrated] and the `init` block.
    private val flagFilter: MutableStateFlow<CategoryFlagFilter> = MutableStateFlow(CategoryFlagFilter.ALL)
    private val flagFilterTopics: MutableStateFlow<TopicsUiState> = MutableStateFlow(TopicsUiState.Loading)
    private var flagFilterJob: Job? = null

    // #1132 — `false` until the persisted filter has been resolved against the first real auth state.
    // [filterSlice] stays SILENT while this is false, so `uiState` cannot emit and thus cannot flash a
    // transient ALL selector before the persisted (possibly non-ALL) filter lands.
    private val flagFilterHydrated: MutableStateFlow<Boolean> = MutableStateFlow(false)

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

    // Fold the flag filter (#455) into a single slice so the final `combine` stays within
    // the 5-arg typed overload (coreState already aggregates the 5 core sources). #1132 — the slice
    // emits NOTHING until [flagFilterHydrated] flips true (map to `null` + filterNotNull), so the
    // final `uiState` combine holds its initial Loading seed and never surfaces a transient ALL
    // selector before the persisted filter is applied.
    private val filterSlice: Flow<FilterSlice> = combine(
        flagFilter,
        flagFilterTopics,
        flagFilterHydrated,
    ) { filter, topics, hydrated ->
        if (hydrated) FilterSlice(filter, topics) else null
    }.filterNotNull()

    // #1130 — [search] is already a single combined flow (query + active), so it feeds the final
    // `combine` directly and keeps the same 5 typed sources.
    val uiState: StateFlow<CategoryUiState> = combine(
        coreState,
        filterSlice,
        search,
        isRefreshing,
        isAuthenticated,
    ) { core, filter, searchState, refreshing, authenticated ->
        val filterActive = filter.flagFilter != CategoryFlagFilter.ALL
        CategoryUiState(
            cat = request.cat,
            categoryName = core.categoryName,
            initialSubcat = request.initialSubcat,
            selectedSubcat = core.subcat,
            page = core.page,
            // No pager in flag-filter mode: the buckets are not paginated server-side.
            pageCount = if (filterActive) 1 else core.topics.pageCount(),
            subcategories = core.subcategories,
            topics = core.topics,
            searchQuery = searchState.query,
            searchActive = searchState.active,
            // The text search applies to whichever listing is active (bucket or normal).
            filteredTopics = if (filterActive) {
                filter.flagFilterTopics.filterTopics(searchState.query)
            } else {
                core.topics.filterTopics(searchState.query)
            },
            isRefreshing = refreshing,
            canCreateTopic = authenticated,
            flagFilter = filter.flagFilter,
            flagFilterTopics = filter.flagFilterTopics,
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
        // Anonymous prefetch of `page + 1` whenever a Content lands. The trigger is
        // derived **directly from the [TopicListPage] inside `Content`** — not from
        // the separate `selectedSubcat` / `page` `MutableStateFlow`s. The earlier
        // shape `combine(selectedSubcat, page, topicsState)` had a race : when the
        // user switched subcat, the new `(subcat, page)` keys landed before the
        // upstream `flatMapLatest` had time to emit the new `Content`, so the
        // combine produced a trigger with **new keys + old Content payload** and
        // tried to prefetch the wrong (subcat, page+1).
        //
        // Reading from the Content guarantees the trigger fields are coherent. On
        // intermediate states (Loading / Error during a refresh or subcat switch)
        // we cancel any in-flight prefetch so we never warm a page the user has
        // moved past.
        topicsState
            .map { state -> state.toPrefetchTriggerOrNull() }
            .distinctUntilChanged()
            .onEach { trigger ->
                if (trigger == null) {
                    prefetchJob?.cancel()
                } else {
                    schedulePrefetch(trigger)
                }
            }
            .launchIn(viewModelScope)

        // #455 — re-fetch the active flag bucket for the new (sub)category when the user switches
        // subcat. #1132 — `drop(1)` skips the INITIAL emission: a persisted non-ALL seed already
        // launches its own fetch at hydration, so the initial subcat value must NOT fire a second
        // (duplicate) bucket fetch. Only a genuine subcat CHANGE with a filter active refetches.
        selectedSubcat
            .drop(1)
            .onEach {
                if (flagFilter.value != CategoryFlagFilter.ALL) {
                    launchFlagFilterFetch(keepContent = false)
                }
            }
            .launchIn(viewModelScope)

        // #455 — on logout / session expiry the flag selector is hidden (anonymous has no
        // flags); reset to ALL so the screen never stays stuck on a stale filtered list with
        // no visible control to leave it (review Codex #455). The initial emission is `false`
        // while the filter is already ALL, so it is a no-op until a real logout occurs.
        isAuthenticated
            .onEach { authenticated ->
                if (!authenticated && flagFilter.value != CategoryFlagFilter.ALL) {
                    flagFilterJob?.cancel()
                    flagFilter.value = CategoryFlagFilter.ALL
                    flagFilterTopics.value = TopicsUiState.Loading
                    // #1132 — a logout is a UI safety reset, NOT a user choice: never call the setter
                    // here, so the persisted preference survives the logout untouched.
                }
            }
            .launchIn(viewModelScope)

        // #1132 — hydrate the flag filter from the persisted preference, resolved against the FIRST
        // real auth state (not the `null` placeholder). Authenticated → the stored value; anonymous →
        // a LOCAL ALL that never touches DataStore (an anonymous session has no flags and must not
        // clobber the remembered preference). `take(1)` seeds exactly once: a later login on the same
        // screen does NOT restore the preference — the NEXT category does. Marking hydrated lets
        // [uiState] start emitting; a non-ALL seed launches the bucket fetch immediately so the first
        // VISIBLE state already shows the persisted filter's listing (no ALL flash).
        combine(
            userPreferencesRepository.observeForumCategoryFlagFilter(),
            authState.filterNotNull(),
        ) { persisted, auth -> persisted to auth }
            .take(1)
            .onEach { (persisted, auth) ->
                val seed = if (auth is AuthState.Authenticated) persisted else CategoryFlagFilter.ALL
                flagFilter.value = seed
                flagFilterHydrated.value = true
                if (seed != CategoryFlagFilter.ALL) {
                    launchFlagFilterFetch(keepContent = false)
                }
            }
            .launchIn(viewModelScope)
    }

    private fun schedulePrefetch(trigger: ContentTrigger) {
        val nextPage = trigger.currentPage + 1
        // pageCount unknown is not possible here — the trigger is built from a
        // Content payload that always carries a valid totalTopics / resultsPerPage.
        // We still skip when the user is on the last page (or beyond, defensive).
        if (nextPage > trigger.pageCount) return
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            forumRepository.prefetchTopicList(
                cat = request.cat,
                subcat = trigger.subcat,
                page = nextPage,
            )
        }
    }

    private fun TopicsUiState.toPrefetchTriggerOrNull(): ContentTrigger? = when (this) {
        is TopicsUiState.Content -> ContentTrigger(
            subcat = page.subcat,
            currentPage = page.page,
            pageCount = listingPageCount(
                totalTopics = page.totalTopics,
                resultsPerPage = page.resultsPerPage,
            ),
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
        search.update { it.copy(query = query) }
    }

    /** #1130 — opens the in-page search (autofocus is driven by the field entering composition). */
    fun openSearch() {
        search.update { it.copy(active = true) }
    }

    /**
     * #1130 — leaves the search mode atomically: empties the query AND closes the field in a SINGLE
     * write to the combined [search] flow, so no intermediate (empty, still-open) state can surface.
     * Target of both the leading back arrow and the system/gesture back. Distinct from the clear
     * cross, which only empties the query via [updateSearchQuery] and keeps the mode open.
     */
    fun closeSearch() {
        search.value = SearchSlice(query = "", active = false)
    }

    /**
     * #455 — selects the « Mes drapeaux » filter. [CategoryFlagFilter.ALL] restores the
     * normal listing; any other value fetches the matching bucket for the current
     * (sub)category. No-op when the value is unchanged.
     */
    fun selectFlagFilter(filter: CategoryFlagFilter) {
        if (flagFilter.value == filter) return
        flagFilter.value = filter
        launchFlagFilterFetch(keepContent = false)
        // #1132 — persist the user's explicit choice AFTER the optimistic local update + fetch, so the
        // UI reacts instantly and the write happens off the critical path. The early-return above makes
        // a re-selection of the same value a no-op (neither refetch nor rewrite).
        viewModelScope.launch { userPreferencesRepository.setForumCategoryFlagFilter(filter) }
    }

    private fun launchFlagFilterFetch(keepContent: Boolean) {
        flagFilterJob?.cancel()
        flagFilterJob = viewModelScope.launch { fetchFlagFilter(keepContent) }
    }

    /**
     * Fetches the active flag bucket for the current (sub)category and pushes it into
     * [flagFilterTopics]. ALL resets the slot to `Loading` (not consumed in that case).
     * [keepContent] `true` (pull-to-refresh of the same bucket) skips the intermediate
     * `Loading` so the list does not blank; `false` (filter change / subcat switch) shows it.
     */
    private suspend fun fetchFlagFilter(keepContent: Boolean) {
        // Snapshot the (filter, subcat) this fetch is for so the tuple stays atomic across the
        // suspension and a concurrent change can be detected before publishing (review #455).
        val filter = flagFilter.value
        val subcat = selectedSubcat.value
        val bucket = filter.toDomainBucket()
        if (bucket == null) {
            flagFilterTopics.value = TopicsUiState.Loading
            return
        }
        if (!keepContent || flagFilterTopics.value !is TopicsUiState.Content) {
            flagFilterTopics.value = TopicsUiState.Loading
        }
        val result = forumRepository.getFlagFilteredTopics(cat = request.cat, subcat = subcat, bucket = bucket)
        // Stale-guard: only publish if the active (filter, subcat) is still the one we fetched.
        if (flagFilter.value == filter && selectedSubcat.value == subcat) {
            flagFilterTopics.value = result.toTopicsUiState()
        }
    }

    private fun CategoryFlagFilter.toDomainBucket(): FlagFilterBucket? = when (this) {
        CategoryFlagFilter.ALL -> null
        CategoryFlagFilter.PARTICIPATED -> FlagFilterBucket.PARTICIPATED
        CategoryFlagFilter.READ -> FlagFilterBucket.READ
        CategoryFlagFilter.FAVORITES -> FlagFilterBucket.FAVORITES
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
                if (flagFilter.value == CategoryFlagFilter.ALL) {
                    forumRepository.refreshTopicList(
                        cat = request.cat,
                        subcat = selectedSubcat.value,
                        page = page.value,
                    )
                } else {
                    // #455 — re-fetch through [flagFilterJob] (NOT inline) so a concurrent
                    // selectFlagFilter / subcat change can cancel it: an inline fetch had no
                    // handle and could clobber flagFilterTopics out of order (review #455).
                    // join() keeps [isRefreshing] bracketing the round-trip.
                    flagFilterJob?.cancel()
                    val job = viewModelScope.launch { fetchFlagFilter(keepContent = true) }
                    flagFilterJob = job
                    job.join()
                }
            } finally {
                isRefreshing.value = false
            }
        }
    }

    private fun ForumResult<List<SubCategory>>.toSubcategoriesUiState(): SubcategoriesUiState = when (this) {
        ForumResult.Loading -> SubcategoriesUiState.Loading
        is ForumResult.Success -> SubcategoriesUiState.Content(value)
        // #324 — kind derives from the exception TYPE (5xx outage vs network cut vs other).
        is ForumResult.Failure -> SubcategoriesUiState.Error(cause.message, classifyHfrError(cause))
    }

    private fun ForumResult<TopicListPage>.toTopicsUiState(): TopicsUiState = when (this) {
        ForumResult.Loading -> TopicsUiState.Loading
        is ForumResult.Success -> TopicsUiState.Content(value)
        // #324 — kind derives from the exception TYPE (5xx outage vs network cut vs other).
        is ForumResult.Failure -> TopicsUiState.Error(cause.message, classifyHfrError(cause))
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

    private data class FilterSlice(
        val flagFilter: CategoryFlagFilter,
        val flagFilterTopics: TopicsUiState,
    )

    private data class SearchSlice(
        val query: String,
        val active: Boolean,
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
    /**
     * #206 workaround (« Exact post-création »). Non-null only when this listing is reached
     * right after a successful create-topic POST : carries the exact posted title so the
     * screen highlights the matching row (HFR never returns the created topic id — #214 — so
     * direct navigation is impossible). `null` on every normal nav path → no highlight.
     */
    val highlightTitle: String? = null,
)
