package fr.forumhfr.redface2.feature.topic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tracing.Trace
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.topic.TopicRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 1D-2 (#107) ViewModel for `:feature:topic`. Holds the cache-aside collection
 * for the current `(cat, post, page)` triple and emits a one-shot
 * [TopicEffect.ScrollToPost] when a deep link asks the screen to scroll to a
 * `numreponse` and that post is present in the loaded page.
 *
 * Pagination remains route-driven : tapping a page button goes through `onOpenPage`
 * which `RedfaceNavigation` translates into a back-stack `replace` (not a `push`),
 * so the back button climbs back to the caller (forum / flags), not through a
 * synthetic chain of pages.
 */
@HiltViewModel(assistedFactory = TopicViewModel.Factory::class)
class TopicViewModel @AssistedInject constructor(
    @Assisted private val request: TopicRequest,
    private val topicRepository: TopicRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TopicUiState.initial(request))
    val state: StateFlow<TopicUiState> = _state.asStateFlow()

    private val _effects: Channel<TopicEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<TopicEffect> = _effects.receiveAsFlow()

    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    private var prefetchedPage: Int? = null

    /**
     * Tracks whether the current value of [TopicRequest.scrollTo] has already been
     * dispatched to the screen as a [TopicEffect.ScrollToPost]. Once true, a subsequent
     * refresh of the same page will not re-scroll — the user may have scrolled away
     * and re-snapping would steal focus. A new TopicRoute (different page) creates a
     * new ViewModel so the flag resets naturally.
     */
    private var scrollEffectEmitted: Boolean = false

    /**
     * Async-trace cookie for the `rf2.topic.first_content` section. The section starts when
     * [loadCurrentPage] kicks off and ends on the first emission that lands in
     * [TopicUiState.Mode.Loaded] (cache hit or first network reply, whichever comes first).
     * The cookie is monotonically incremented on each retry / re-load so a previous in-flight
     * section can be ended cleanly before a new one starts; without this the trace would show
     * an open-ended section that never closes when the user retries mid-load.
     */
    private var firstContentCookie: Int = 0
    private var firstContentInFlight: Boolean = false

    init {
        loadCurrentPage()
    }

    fun send(intent: TopicIntent) {
        when (intent) {
            TopicIntent.Retry -> loadCurrentPage()
        }
    }

    override fun onCleared() {
        // Close the in-flight async trace section if the ViewModel dies before the first
        // emission lands — leaving an open async section in the trace would skew the next
        // measurement window and Perfetto would draw a never-ending sliver.
        endFirstContentSectionIfNeeded()
        super.onCleared()
    }

    private fun beginFirstContentSection() {
        endFirstContentSectionIfNeeded()
        firstContentCookie++
        Trace.beginAsyncSection(FIRST_CONTENT_SECTION, firstContentCookie)
        firstContentInFlight = true
    }

    private fun endFirstContentSectionIfNeeded() {
        if (firstContentInFlight) {
            Trace.endAsyncSection(FIRST_CONTENT_SECTION, firstContentCookie)
            firstContentInFlight = false
        }
    }

    private fun loadCurrentPage() {
        loadJob?.cancel()
        prefetchJob?.cancel()
        prefetchedPage = null
        beginFirstContentSection()
        _state.update { it.copy(mode = TopicUiState.Mode.Loading) }
        loadJob = viewModelScope.launch {
            topicRepository
                .observeTopicPage(request.cat, request.post, request.page)
                .catch { error ->
                    if (error is CancellationException) throw error
                    // Cache-first UX: if we already showed a cached page, keep it on screen
                    // and swallow the refresh failure. The user won't see broken UI just because
                    // the network blip after the cache emission. A surface for that error
                    // (Snackbar / banner) is deferred to Phase 1D.
                    _state.update { current ->
                        if (current.mode is TopicUiState.Mode.Loaded) current
                        else current.copy(mode = TopicUiState.Mode.Error(error.message ?: "Unknown error"))
                    }
                    // Close the async trace section even on the error path so the trace
                    // still draws a bounded sliver from intent to terminal state.
                    endFirstContentSectionIfNeeded()
                }
                .collect { topic ->
                    _state.update {
                        it.copy(
                            mode = TopicUiState.Mode.Loaded(topic),
                            availablePages = (1..topic.totalPages).toList(),
                        )
                    }
                    // First content visible — close the async section. Subsequent emissions
                    // (stale-cache then refresh) already see `firstContentInFlight = false`
                    // and the helper short-circuits.
                    endFirstContentSectionIfNeeded()
                    maybeEmitScroll(topic.posts.map { it.numreponse })
                    maybeSchedulePrefetch(totalPages = topic.totalPages)
                }
        }
    }

    private suspend fun maybeEmitScroll(visiblePosts: List<Int>) {
        val target = request.scrollTo
        val shouldEmit = !scrollEffectEmitted && target != null && target in visiblePosts
        if (shouldEmit) {
            _effects.send(TopicEffect.ScrollToPost(checkNotNull(target)))
            scrollEffectEmitted = true
        }
    }

    /**
     * Fires off an anonymous prefetch of `currentPage + 1` once per loaded
     * page. We deliberately:
     *
     * - Run **only one** page ahead — multi-page prefetching is explicitly out
     *   of scope (§ "Ne pas prefetch plusieurs pages d'avance" in the PR 4
     *   prompt). Two-page-ahead would scale costs without matching real
     *   reading patterns.
     * - Skip the call when already at the last page.
     * - Track [prefetchedPage] so we don't re-issue a request if the same
     *   page emits twice (cache + refresh emission of the stale path, or any
     *   future intermediate emission). The marker is set **before** the
     *   coroutine runs, so a transient prefetch failure (network glitch) is
     *   **not** retried for the same emission — prefetch is best-effort by
     *   design and the next user-driven `observeTopicPage` on `page+1` will
     *   re-fetch through the normal cache-miss path.
     * - Hang the job off [viewModelScope] so leaving the screen cancels the
     *   in-flight request — the structured-concurrency cancel propagates down
     *   to OkHttp.
     */
    private fun maybeSchedulePrefetch(totalPages: Int) {
        val nextPage = request.page + 1
        if (nextPage > totalPages) return
        if (prefetchedPage == nextPage) return
        prefetchedPage = nextPage
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            topicRepository.prefetch(request.cat, request.post, nextPage)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(request: TopicRequest): TopicViewModel
    }

    private companion object {
        // Keep the section-name catalogue in lockstep with `docs/guides/profiling.md` so a
        // `TraceSectionMetric("rf2.topic.first_content")` consumer (future macrobenchmark
        // under #117 follow-up) keeps matching after refactors.
        private const val FIRST_CONTENT_SECTION = "rf2.topic.first_content"
    }
}
