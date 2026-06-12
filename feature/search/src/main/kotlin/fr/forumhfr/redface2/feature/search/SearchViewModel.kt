package fr.forumhfr.redface2.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.domain.error.classifyHfrError
import fr.forumhfr.redface2.core.domain.search.SearchRepository
import fr.forumhfr.redface2.core.model.search.SearchCategoryScope
import fr.forumhfr.redface2.core.model.search.SearchPivotCategory
import fr.forumhfr.redface2.core.model.search.SearchRequest
import fr.forumhfr.redface2.core.model.search.SearchTextScope
import fr.forumhfr.redface2.core.model.search.SearchTopicResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 2G-A/B (#150 partiel) — ViewModel for [SearchScreen].
 *
 * One [searchJob] is in flight at a time : a new submit/retry/category-change
 * cancels the previous one so an older response cannot overwrite a newer
 * query. The submit pipeline is :
 *
 *  1. Clear any prior error, flip `isLoading=true`.
 *  2. Call [SearchRepository.search] with the user's query + scope.
 *  3. On success, populate `results`/`pivotCategories`/`selectedCategory`.
 *  4. On failure, classify the exception into an [HfrErrorKind] (#324) and
 *     surface a retry button.
 *
 * The repository already redacts the query from `IOException` messages, so
 * nothing in this layer logs it either — we map error kinds purely on the
 * exception type.
 *
 * #277 — result opening is asynchronous : [SearchIntent.OpenResult] resolves the
 * result's real topic page (HFR's search hrefs always carry `page=1`) and emits a
 * one-shot [SearchEffect.NavigateToTopic] through [effects] (Channel +
 * `receiveAsFlow`, same pattern as `TopicViewModel`).
 *
 * [initialPseudo] supports the profile's « Derniers messages » entry point : a
 * non-blank value pre-fills the author field and fires the search immediately
 * (author-only, all categories — cf. [SearchRequest.pseudo]). `@AssistedInject`
 * (same pattern as `ProfileViewModel`) because the value comes from the nav
 * route at construction time ; the search tab passes `null` and starts idle.
 */
@HiltViewModel(assistedFactory = SearchViewModel.Factory::class)
class SearchViewModel @AssistedInject constructor(
    private val searchRepository: SearchRepository,
    @Assisted private val initialPseudo: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState(pseudo = initialPseudo.orEmpty().trim()))
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private val _effects: Channel<SearchEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<SearchEffect> = _effects.receiveAsFlow()

    /** The query that was last actually submitted, used by [retry]. */
    private var lastSubmittedQuery: String? = null

    /** The category that was last actually submitted, used by [retry]. */
    private var lastSubmittedCategory: SearchCategoryScope = SearchCategoryScope.All

    /** The text scope that was last actually submitted, used by [retry] and pivot changes. */
    private var lastSubmittedTextScope: SearchTextScope = SearchTextScope.TitlesAndPosts

    /** The author filter that was last actually submitted, used by [retry] and pivot changes. */
    private var lastSubmittedPseudo: String? = null

    private var searchJob: Job? = null

    /**
     * Monotonic guard against stale completions. Cancelling a coroutine is the
     * normal path, but the generation also protects us if a fake/test repository
     * or a future dispatcher resumes after cancellation.
     */
    private var searchGeneration = 0L

    /**
     * #277 — anti double-tap guard. While a page resolution is in flight, further
     * [SearchIntent.OpenResult] intents are IGNORED (no queue) : the resolution is
     * sub-second, and queueing taps would fire a burst of navigations afterwards.
     */
    private var isOpeningResult = false

    init {
        // Profile entry point : land directly on the author's recent posts. The
        // init-time submit mirrors what the user would do by hand (fill the author
        // field, tap « Rechercher ») so retry/pivot/scope flows behave identically.
        val prefilled = _state.value.pseudo
        if (prefilled.isNotEmpty()) {
            launchSearch(
                query = "",
                scope = SearchCategoryScope.All,
                textScope = _state.value.textScope,
                pseudo = prefilled,
            )
        }
    }

    fun submit(intent: SearchIntent) {
        when (intent) {
            is SearchIntent.QueryChanged -> onQueryChanged(intent.query)
            is SearchIntent.PseudoChanged -> onPseudoChanged(intent.pseudo)
            is SearchIntent.TextScopeSelected -> onTextScopeSelected(intent.scope)
            SearchIntent.Submit -> onSubmit()
            SearchIntent.Retry -> onRetry()
            is SearchIntent.CategorySelected -> onCategorySelected(intent.category)
            is SearchIntent.OpenResult -> onOpenResult(intent.result)
            SearchIntent.EditCriteria -> _state.update { it.copy(formCollapsed = false) }
        }
    }

    private fun onQueryChanged(query: String) {
        // A typed-but-not-submitted query invalidates the currently displayed
        // search. Keeping the old list under the new field value is misleading
        // and can let an in-flight response land as stale results.
        invalidateDisplayedSearch { it.copy(query = query) }
    }

    private fun onPseudoChanged(pseudo: String) {
        // Same invalidation contract as onQueryChanged : the author filter is part
        // of the search identity, so editing it orphans the displayed results.
        invalidateDisplayedSearch { it.copy(pseudo = pseudo) }
    }

    private inline fun invalidateDisplayedSearch(transform: (SearchUiState) -> SearchUiState) {
        searchGeneration += 1
        searchJob?.cancel()
        _state.update {
            transform(it).copy(
                selectedCategory = null,
                pivotCategories = emptyList(),
                results = emptyList(),
                isLoading = false,
                errorMessage = null,
                hasSearched = false,
            )
        }
    }

    private fun onSubmit() {
        val trimmedQuery = _state.value.query.trim()
        val trimmedPseudo = _state.value.pseudo.trim()
        // HFR accepts query-only, author-only, and combined searches — but at least
        // one of the two must be set (cf. SearchRequest.pseudo).
        if (trimmedQuery.isEmpty() && trimmedPseudo.isEmpty()) return
        launchSearch(
            query = trimmedQuery,
            scope = SearchCategoryScope.All,
            textScope = _state.value.textScope,
            pseudo = trimmedPseudo.takeIf { it.isNotEmpty() },
        )
    }

    private fun onTextScopeSelected(scope: SearchTextScope) {
        val current = _state.value
        if (current.textScope == scope) return
        _state.update { it.copy(textScope = scope) }
        val query = current.query.trim()
        val pseudo = current.pseudo.trim()
        if (current.hasSearched && (query.isNotEmpty() || pseudo.isNotEmpty())) {
            launchSearch(query, lastSubmittedCategory, scope, pseudo.takeIf { it.isNotEmpty() })
        }
    }

    private fun onRetry() {
        val query = lastSubmittedQuery ?: return
        launchSearch(query, lastSubmittedCategory, lastSubmittedTextScope, lastSubmittedPseudo)
    }

    private fun onCategorySelected(category: SearchPivotCategory) {
        // `lastSubmittedQuery` can legitimately be "" after an author-only search ;
        // re-scope with whatever (query, pseudo) pair the listing on screen came from.
        val query = lastSubmittedQuery ?: _state.value.query.trim()
        val pseudo = lastSubmittedPseudo
        if (query.isEmpty() && pseudo.isNullOrEmpty()) return
        launchSearch(
            query = query,
            scope = SearchCategoryScope.Category(id = category.id, name = category.label),
            textScope = lastSubmittedTextScope,
            pseudo = pseudo,
        )
    }

    /**
     * #277 — opening a search result. HFR's result hrefs ALWAYS carry `page=1`, so a
     * content match navigated blindly lands on the first page and the `scrollTo`
     * target is nowhere on it. When the row has a matched [SearchTopicResult.numreponse],
     * the real page is resolved through HFR's server-side redirect
     * ([SearchRepository.resolveSearchResultPage]) before emitting the navigation
     * effect ; a failed resolution falls back to the href page (`?: 1`) — the exact
     * pre-#277 behaviour, never worse. Title-only rows (no numreponse) navigate to
     * page 1 immediately, without any network round-trip.
     */
    private fun onOpenResult(result: SearchTopicResult) {
        if (isOpeningResult) return
        val numreponse = result.numreponse
        if (numreponse == null) {
            // Title-search row : nothing to resolve, nothing to scroll to. The guard
            // is armed for branch symmetry with the resolution path below. Under
            // Main.immediate the whole launch body runs inline (send is non-suspending
            // on a buffered channel), so the guarded window is a single dispatch —
            // a second physical tap lands a frame later, after the collector navigated.
            isOpeningResult = true
            viewModelScope.launch {
                try {
                    _effects.send(
                        SearchEffect.NavigateToTopic(
                            cat = result.cat,
                            post = result.topicId,
                            page = 1,
                            scrollTo = null,
                        ),
                    )
                } finally {
                    isOpeningResult = false
                }
            }
            return
        }
        isOpeningResult = true
        viewModelScope.launch {
            try {
                val outcome = runCatching {
                    // Cap the resolution probe well below the shared OkHttp call timeout
                    // (30 s) : on a degraded network the guard would otherwise silently
                    // swallow every tap for the whole call. Timing out degrades to the
                    // href-page fallback below — never worse than pre-#277.
                    withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                        searchRepository.resolveSearchResultPage(
                            cat = result.cat,
                            post = result.topicId,
                            numreponse = numreponse,
                        )
                    }
                }
                (outcome.exceptionOrNull() as? CancellationException)?.let { throw it }
                // Resolution failure (null or exception) degrades to the href page —
                // the user lands where the pre-#277 app would have landed.
                val page = outcome.getOrNull() ?: result.page ?: 1
                _effects.send(
                    SearchEffect.NavigateToTopic(
                        cat = result.cat,
                        post = result.topicId,
                        page = page,
                        scrollTo = numreponse,
                    ),
                )
            } finally {
                isOpeningResult = false
            }
        }
    }

    private fun launchSearch(
        query: String,
        scope: SearchCategoryScope,
        textScope: SearchTextScope,
        pseudo: String? = null,
    ) {
        // Cancel any in-flight search ; a newer query must take precedence.
        searchJob?.cancel()
        val generation = searchGeneration + 1
        searchGeneration = generation
        lastSubmittedQuery = query
        lastSubmittedCategory = scope
        lastSubmittedTextScope = textScope
        lastSubmittedPseudo = pseudo
        _state.update {
            it.copy(
                query = query,
                pseudo = pseudo.orEmpty(),
                textScope = textScope,
                isLoading = true,
                errorMessage = null,
                hasSearched = true,
                // #433 — every launched search collapses the form ; the single
                // launch point keeps submit/retry/pivot/profile-entry consistent.
                formCollapsed = true,
            )
        }
        searchJob = viewModelScope.launch {
            val outcome = runCatching {
                searchRepository.search(
                    SearchRequest(query = query, category = scope, textScope = textScope, pseudo = pseudo),
                )
            }
            val cancellation = outcome.exceptionOrNull() as? CancellationException
            if (cancellation != null) {
                throw cancellation
            }
            if (generation != searchGeneration) return@launch
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
                    // #324 — shared type-derived classification. The repository lets
                    // HfrServerException traverse (URL redacted) so a 5xx maps to
                    // ServerDown instead of being mistaken for a network cut; a redacted
                    // SessionExpiredException lands in Other (search has no dedicated
                    // session treatment).
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = classifyHfrError(error),
                            // Keep the previous results untouched on retry-friendly errors —
                            // wiping the list on a transient network blip is more disruptive
                            // than helpful.
                        )
                    }
                },
            )
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(initialPseudo: String?): SearchViewModel
    }

    private companion object {
        /**
         * Upper bound for the #277 page-resolution probe. The shared OkHttp client
         * allows calls up to 30 s ; holding the open-result guard that long would
         * make the whole result list feel frozen on a degraded network. Past this
         * deadline the resolution degrades to the href-page fallback.
         */
        const val RESOLVE_TIMEOUT_MS: Long = 3_000
    }
}
