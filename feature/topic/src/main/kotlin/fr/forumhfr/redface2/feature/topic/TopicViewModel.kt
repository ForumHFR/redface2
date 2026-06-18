package fr.forumhfr.redface2.feature.topic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tracing.Trace
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.blacklist.BlacklistRepository
import fr.forumhfr.redface2.core.domain.blacklist.canonicalizePseudo
import fr.forumhfr.redface2.core.domain.error.classifyHfrError
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.topic.TopicRepository
import fr.forumhfr.redface2.core.domain.topic.TopicSearchRepository
import fr.forumhfr.redface2.core.domain.write.DeletePostRepository
import fr.forumhfr.redface2.core.domain.write.DeletePostResult
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.model.TopicSearchRequest
import fr.forumhfr.redface2.core.model.write.EditPostContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
@Suppress("LongParameterList") // ViewModel aggregates its injected repositories; one per concern.
class TopicViewModel @AssistedInject constructor(
    @Assisted private val request: TopicRequest,
    private val topicRepository: TopicRepository,
    private val authRepository: AuthRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val deletePostRepository: DeletePostRepository,
    private val blacklistRepository: BlacklistRepository,
    private val topicSearchRepository: TopicSearchRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TopicUiState.initial(request))
    val state: StateFlow<TopicUiState> = _state.asStateFlow()

    /**
     * #509 — latest set of blacklisted canonical pseudos. Kept fresh by the [loadCurrentPage] combine
     * so the refresh / post-submit / post-delete paths (which bypass `observeTopicPage`) can recompute
     * the hidden set without re-subscribing to the blacklist.
     */
    private var blockedCanonicals: Set<String> = emptySet()

    private val _effects: Channel<TopicEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<TopicEffect> = _effects.receiveAsFlow()

    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    private var prefetchedPage: Int? = null

    /** Chantier C (#546) — at most one intra-topic search POST in flight at a time. */
    private var searchJob: Job? = null

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
        if (request.submitSignal != null) {
            // Issue #200 — the user just published a reply / quote / edit / edit-FP and the
            // navigation host signalled us to skip the cache so the freshly-published post
            // is actually visible. Without this short-circuit, `observeTopicPage` would
            // emit a stale cached page that doesn't contain the new post (it was created
            // server-side after the cache was populated).
            forceRefreshCurrentPage()
        } else {
            loadCurrentPage()
        }
        // #220 — gate the write affordances on the live auth state, not just `canReply`.
        // A logged-out user may still hold a stale cached `canReply = true` row (the topic
        // cache is intentionally not purged on logout, cf. CacheInvalidator), so the gate
        // must consult auth explicitly to avoid opening a reply editor that can only fail
        // at submit. Symmetric with the « Créer topic » FAB (CategoryViewModel.canCreateTopic).
        authRepository.observeAuthState()
            .onEach { authState ->
                _state.update { it.copy(isAuthenticated = authState is AuthState.Authenticated) }
            }
            .launchIn(viewModelScope)
        // Build 89 follow-up — mirror the top-bar auto-hide preference into state so the screen
        // can switch between a pinned and an `enterAlways` scroll behaviour without a refetch.
        userPreferencesRepository.observeTopicTopBarAutoHide()
            .onEach { autoHide ->
                _state.update { it.copy(topBarAutoHide = autoHide) }
            }
            .launchIn(viewModelScope)
        // #383 — mirror the page-FABs preference so the screen can hide the ‹/› cluster
        // without a refetch, same pattern as the top-bar auto-hide above.
        userPreferencesRepository.observeTopicPageFabs()
            .onEach { enabled ->
                _state.update { it.copy(showPageFabs = enabled) }
            }
            .launchIn(viewModelScope)
        // #456 — mirror the polls-expanded preference: seeds the poll card's initial revealed
        // state (the in-card toggle stays per-topic on top of it).
        userPreferencesRepository.observeTopicPollsExpanded()
            .onEach { expanded ->
                _state.update { it.copy(pollsExpandedDefault = expanded) }
            }
            .launchIn(viewModelScope)
        // #330 — mirror the signatures preference so the post cards can show/hide the author
        // signature without a refetch (it is always parsed and cached on the Post).
        userPreferencesRepository.observeTopicSignatures()
            .onEach { show ->
                _state.update { it.copy(showSignatures = show) }
            }
            .launchIn(viewModelScope)
    }

    fun send(intent: TopicIntent) {
        when (intent) {
            // Retry goes through the cache-aside path even after a post-submit force
            // refresh — by then the new post has been persisted and the user just wants
            // to recover from a transient error.
            TopicIntent.Retry -> loadCurrentPage()
            is TopicIntent.DeletePost -> deletePost(intent.numreponse)
            TopicIntent.Refresh -> refresh()
            is TopicIntent.SetAuthorBlocked -> setAuthorBlocked(intent.author, intent.blocked)
            TopicIntent.OpenSearch -> openSearch()
            TopicIntent.CloseSearch -> closeSearch()
            is TopicIntent.SearchWordChanged ->
                _state.update { it.copy(search = it.search.copy(word = intent.word)) }
            is TopicIntent.SearchPseudoChanged ->
                _state.update { it.copy(search = it.search.copy(spseudo = intent.pseudo)) }
            is TopicIntent.SearchOnlyMatchesChanged ->
                _state.update { it.copy(search = it.search.copy(onlyMatches = intent.onlyMatches)) }
            TopicIntent.SubmitSearch -> submitSearch()
        }
    }

    /**
     * #509 — block / unblock [author] from the post menu. This launch is on [viewModelScope], but the
     * DataStore commit still survives the sheet/ViewModel going away mid-write: the repository's
     * `persist {}` offloads the actual write to the application scope (the #507 pattern), so only the
     * `await` is cancelled, not the commit. The page re-filters live via the [loadCurrentPage] combine,
     * so no manual state poke is needed here.
     */
    private fun setAuthorBlocked(author: String, blocked: Boolean) {
        viewModelScope.launch {
            if (blocked) blacklistRepository.block(author) else blacklistRepository.unblock(author)
        }
    }

    /**
     * #335 — manual pull-to-refresh of the current page. Re-fetches over the network and replaces the
     * loaded page in place, WITHOUT the post-submit overflow redirect (#226) or any scroll effect, so
     * the user keeps their reading position. NO-OP unless a page is already loaded and no refresh is
     * in flight (guards a double pull). `isRefreshing` is cleared in `finally` so a cancellation —
     * e.g. a delete's `refreshAfterDelete` re-assigning `loadJob` mid-refresh — never leaves the
     * spinner stuck.
     *
     * konsist:bypass-prefetch-guard — cancels the in-flight prefetch and force-fetches the page; this
     * is an explicit user-initiated authenticated refresh, not an anonymous warmup escalating to
     * authenticated (same justification as `forceRefreshCurrentPage`).
     */
    private fun refresh() {
        if (_state.value.mode !is TopicUiState.Mode.Loaded || _state.value.isRefreshing) return
        loadJob?.cancel()
        prefetchJob?.cancel()
        prefetchedPage = null
        _state.update { it.copy(isRefreshing = true) }
        loadJob = viewModelScope.launch {
            try {
                val topic = topicRepository.refreshTopicPage(request.cat, request.post, request.page)
                _state.update {
                    it.copy(
                        mode = TopicUiState.Mode.Loaded(topic, computeHiddenNumreponses(topic, blockedCanonicals)),
                        availablePages = (1..topic.totalPages).toList(),
                    )
                }
                // Re-arm the page+1 warmup, like `loadCurrentPage` (l. ~219). Unlike the post-submit
                // `forceRefreshCurrentPage` (which deliberately skips it), a manual mid-page pull is
                // exactly when the user keeps reading forward, so re-warming page+1 restores the
                // prefetch benefit lost by the `prefetchedPage = null` reset above.
                maybeSchedulePrefetch(totalPages = topic.totalPages)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") refreshError: Exception) {
                // Cache-first: keep the page currently on screen and invite a retry via a Toast.
                android.util.Log.w(LOG_TAG, "Manual refresh failed", refreshError)
                _effects.send(TopicEffect.RefreshFailed)
            } finally {
                _state.update { it.copy(isRefreshing = false) }
            }
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
            combine(
                topicRepository
                    .observeTopicPage(request.cat, request.post, request.page, forceRefresh = request.forceRefresh),
                // #509 — gate the first Loaded on the blacklist being known so a blocked post never
                // flashes before it is hidden, and re-filter live when the blacklist changes.
                // observeBlockedCanonicals emits its current set immediately (its documented contract),
                // so combine never stalls waiting on the blacklist.
                blacklistRepository.observeBlockedCanonicals(),
            ) { topic, blocked -> topic to blocked }
                .catch { error ->
                    if (error is CancellationException) throw error
                    // Cache-first UX: if we already showed a cached page, keep it on screen
                    // and swallow the refresh failure. The user won't see broken UI just because
                    // the network blip after the cache emission. A surface for that error
                    // (Snackbar / banner) is deferred to Phase 1D.
                    _state.update { current ->
                        if (current.mode is TopicUiState.Mode.Loaded) {
                            current
                        } else {
                            current.copy(
                                mode = TopicUiState.Mode.Error(
                                    message = error.message ?: "Unknown error",
                                    // #324 — type-derived kind so the screen can tell an HFR
                                    // 5xx outage from a local network cut.
                                    kind = classifyHfrError(error),
                                ),
                            )
                        }
                    }
                    // Close the async trace section even on the error path so the trace
                    // still draws a bounded sliver from intent to terminal state.
                    endFirstContentSectionIfNeeded()
                }
                .collect { (topic, blocked) ->
                    blockedCanonicals = blocked
                    _state.update {
                        it.copy(
                            mode = TopicUiState.Mode.Loaded(topic, computeHiddenNumreponses(topic, blockedCanonicals)),
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

    /**
     * #509 — `numreponse` of the posts in [topic] whose author is blacklisted (canonical match). The
     * full [Topic.posts] list is kept intact; the screen renders a collapsed placeholder for these so
     * pagination, anchors and `numreponse` keys are unaffected. Fast-paths the common empty blacklist.
     */
    private fun computeHiddenNumreponses(topic: Topic, blocked: Set<String>): Set<Int> {
        if (blocked.isEmpty()) return emptySet()
        return topic.posts.asSequence()
            .filter { canonicalizePseudo(it.author) in blocked }
            .map { it.numreponse }
            .toSet()
    }

    private suspend fun maybeEmitScroll(visiblePosts: List<Int>) {
        if (scrollEffectEmitted) return
        val target = request.scrollTo
        when {
            target != null && target in visiblePosts -> {
                _effects.send(TopicEffect.ScrollToPost(target))
                scrollEffectEmitted = true
            }
            // Issue #200 — plain reply path: HFR anchors `#bas` and the parser leaves
            // `scrollTo` null. We still got told this is a post-submit reload via
            // `submitSignal`, so we scroll to the end of the (force-refreshed) page where
            // the freshly-published reply lives. Gate on `submitSignal != null` to avoid
            // any chance of stealing focus on a normal deep-link load that happens to
            // arrive with `scrollTo = null`.
            target == null && request.submitSignal != null -> {
                _effects.send(TopicEffect.ScrollToEndOfPage)
                scrollEffectEmitted = true
            }
        }
    }

    /**
     * Issue #200 — post-submit force fetch. Bypasses [TopicRepository.observeTopicPage]
     * (cache-aside) and calls [TopicRepository.refreshTopicPage] directly so the freshly
     * published post is in the emitted [Topic]. Falls back to the cache-aside path on
     * failure and emits [TopicEffect.PostSubmitRefreshFailed] so the user is told that
     * HFR accepted the post even though the local refresh blipped.
     *
     * Konsist guard: this function legitimately cancels the inflight prefetch AND calls
     * `refreshTopicPage` — the anti-anonymous-upgrade rule in `ArchitectureKonsistTest`
     * is bypassed via the literal marker `konsist:bypass-prefetch-guard` (see the test
     * for the allow-list mechanism). The bypass is intentional: this is a deliberate
     * authenticated refetch following an explicit submit signal from the navigation host,
     * not an anonymous warmup escalating to authenticated.
     */
    private fun forceRefreshCurrentPage() {
        loadJob?.cancel()
        prefetchJob?.cancel()
        prefetchedPage = null
        beginFirstContentSection()
        _state.update { it.copy(mode = TopicUiState.Mode.Loading) }
        loadJob = viewModelScope.launch {
            try {
                val topic = topicRepository.refreshTopicPage(request.cat, request.post, request.page)
                _state.update {
                    it.copy(
                        mode = TopicUiState.Mode.Loaded(topic, computeHiddenNumreponses(topic, blockedCanonicals)),
                        availablePages = (1..topic.totalPages).toList(),
                    )
                }
                endFirstContentSectionIfNeeded()
                // #226 — plain-reply overflow: the reply created a new page but HFR anchored the page
                // the form was on (request.page). The force-refreshed page reports the up-to-date
                // totalPages; if it now exceeds request.page (plain reply → scrollTo null; quote/edit
                // carry a #t{N} scrollTo and are excluded), the fresh post lives on the last page, not
                // here. Re-route there instead of scrolling this stale page. A same-page reply keeps
                // totalPages == request.page and falls through to the #200 ScrollToEndOfPage path.
                // Best-effort under concurrency: HFR's #bas success URL carries NO numreponse, so we
                // cannot tell our own overflow from a concurrent poster's new page — we send the user
                // to the last page either way (a reasonable landing). `postSubmitOverflowLanding`
                // guards re-entry: once the host re-routes us onto that last page it sets the flag (and
                // a fresh submitSignal so we STILL force-fetch it — no stale cache). On that landing we
                // must NOT redirect again, or a concurrent post bumping totalPages during our refresh
                // would start a moving-tail chase. The flagged landing falls through to
                // ScrollToEndOfPage below.
                if (request.scrollTo == null &&
                    topic.totalPages > request.page &&
                    !request.postSubmitOverflowLanding
                ) {
                    _effects.send(TopicEffect.NavigateToLastPage(topic.totalPages))
                    return@launch
                }
                maybeEmitScroll(topic.posts.map { it.numreponse })
                // Skip the page+1 warmup here — the user just submitted and is unlikely to need
                // page+1 immediately; the next normal navigation will trigger the warmup through
                // `loadCurrentPage` as usual.
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") refreshError: Exception) {
                // Force-refresh failed — log, tell the user HFR did accept the post even though
                // the local view may be stale (Toast in the screen, cf. TopicScreen.kt), and short-circuit the
                // scroll-effect machinery so the cache-aside fallback we hand off to does NOT
                // re-trigger `ScrollToEndOfPage` on a stale page (which would scroll the user to
                // some pre-submit "last post" and confuse them into thinking they're looking at
                // their fresh reply). Then hand off to the cache-aside path so the user at least
                // sees a previously-cached page with a Retry affordance.
                android.util.Log.w(LOG_TAG, "Force refresh failed for post-submit reload", refreshError)
                _effects.send(TopicEffect.PostSubmitRefreshFailed)
                scrollEffectEmitted = true
                endFirstContentSectionIfNeeded()
                loadCurrentPage()
            }
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

    /**
     * #292 — deletes one of the user's own posts. The screen confirms first; this runs only after
     * the user accepts. We resolve `subcat` from the loaded topic (the route carries only
     * `cat`/`post`/`page`) and guard the [EditPostContext] invariants before calling the repository.
     * On success we force-refresh the current page so the removed post disappears (unless HFR
     * removed the whole topic — a defensive branch the UI doesn't reach today, since delete is only
     * offered on normal posts). `deletingNumreponse` gates the affordance and blocks a double-submit.
     */
    @Suppress("ComplexCondition") // one conjunction guarding a destructive action; each clause distinct.
    private fun deletePost(numreponse: Int) {
        if (_state.value.deletingNumreponse != null) return
        val topic = (_state.value.mode as? TopicUiState.Mode.Loaded)?.topic ?: return
        // Re-validate server-side, never trust the UI gate alone for a HFR-mutating delete: a stale
        // or buggy intent could carry the first post, a non-editable / foreign post, or arrive after
        // logout. We refuse to POST unless the post is present on the page, editable, the topic is
        // postable, the session is authenticated, and it is NOT the first post (deleting that removes
        // the whole topic — out of scope today). `subcat == 0` is a valid HFR value (cat without a
        // sub-category, cf. EditPostContext), so only the SUBCAT_UNKNOWN sentinel (-1) is rejected.
        val post = topic.posts.firstOrNull { it.numreponse == numreponse }
        val isFirstPost = topic.page == 1 && numreponse == topic.posts.firstOrNull()?.numreponse
        if (post != null && !isFirstPost && post.isEditable && topic.canReply &&
            _state.value.isAuthenticated && topic.subcat >= 0
        ) {
            runDeletion(numreponse, topic.subcat)
        }
        // else: stale/invalid request the UI gate should have prevented — silently no-op, no POST.
    }

    private fun runDeletion(numreponse: Int, subcat: Int) {
        val context = EditPostContext(
            cat = request.cat,
            subcat = subcat,
            topicId = request.post,
            page = request.page,
            numreponse = numreponse,
        )
        _state.update { it.copy(deletingNumreponse = numreponse) }
        viewModelScope.launch {
            val result = deletePostRepository.deletePost(context)
            _state.update { it.copy(deletingNumreponse = null) }
            when (result) {
                is DeletePostResult.Success -> {
                    _effects.send(TopicEffect.PostDeleted)
                    // A normal-post delete keeps the topic alive → refresh so the post vanishes. A
                    // whole-topic delete (first post) would 404 on reload, so we skip the refetch and
                    // leave the page; the UI never offers delete on the first post today.
                    if (!result.deletedWholeTopic) refreshAfterDelete()
                }
                is DeletePostResult.Failure ->
                    _effects.send(TopicEffect.PostDeleteFailed(result.reason.toDeleteFailureReason()))
            }
        }
    }

    /**
     * Network-first reload of the current page after a successful deletion, so the removed post is
     * gone immediately (a cache-aside reload would flash the stale cached page that still contains
     * it). On failure we fall back to the cache-aside path: the delete already succeeded, so the
     * worst case is a briefly-stale list that the normal refresh reconciles.
     *
     * Konsist guard: this function cancels the inflight prefetch AND calls `refreshTopicPage`, so it
     * carries the `konsist:bypass-prefetch-guard` marker (same allow-list as `forceRefreshCurrentPage`).
     * The bypass is intentional: this is a deliberate authenticated refetch following an explicit
     * user-confirmed deletion, not an anonymous warmup escalating to authenticated.
     */
    private fun refreshAfterDelete() {
        loadJob?.cancel()
        prefetchJob?.cancel()
        prefetchedPage = null
        _state.update { it.copy(mode = TopicUiState.Mode.Loading) }
        loadJob = viewModelScope.launch {
            try {
                val topic = topicRepository.refreshTopicPage(request.cat, request.post, request.page)
                _state.update {
                    it.copy(
                        mode = TopicUiState.Mode.Loaded(topic, computeHiddenNumreponses(topic, blockedCanonicals)),
                        availablePages = (1..topic.totalPages).toList(),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") refreshError: Exception) {
                android.util.Log.w(LOG_TAG, "Post-delete refresh failed", refreshError)
                loadCurrentPage()
            }
        }
    }

    // ─── intra-topic search (#546) ───────────────────────────────────────────────

    /**
     * Open the search bar. No-op unless the loaded page exposes a usable search form
     * ([TopicUiState.canSearchInTopic]) — the screen only shows the affordance in that case, but we
     * re-check here so a stale tap can never open an inert bar.
     */
    private fun openSearch() {
        if (!_state.value.canSearchInTopic) return
        _state.update { it.copy(search = it.search.copy(isActive = true)) }
    }

    /**
     * Close the search bar and drop any active filtered view by reloading the normal current page.
     * The typed criteria are kept so re-opening restores them. We only reload when a search was
     * actually applied (`status == Done`) to avoid a needless refetch on a cancel-before-submit.
     */
    private fun closeSearch() {
        searchJob?.cancel()
        val hadResults = _state.value.search.status == TopicSearchStatus.Done
        _state.update {
            it.copy(search = it.search.copy(isActive = false, status = TopicSearchStatus.Idle))
        }
        if (hadResults) loadCurrentPage()
    }

    /**
     * Submit the intra-topic search : `POST transsearch.php` (authenticated) with the parsed form +
     * the typed criteria, then render the returned topic page in [TopicUiState.Mode.Loaded].
     *
     * Best-effort by contract : the `transsearch` response was never observed live, so we trust it
     * is a topic page and parse it as one — no result counter is promised. Server-side result
     * navigation (HFR `currentnum`) is intentionally NOT wired yet : the cursor value is only known
     * from a live response we have never captured, so navigating would guess. We surface the
     * filtered page HFR returns and stop there (documented in the brief / model KDoc).
     */
    private fun submitSearch() {
        val current = _state.value
        val form = (current.mode as? TopicUiState.Mode.Loaded)?.topic?.searchForm
        // Re-validate the gate server-side: never POST without a usable (authenticated) form.
        if (form == null || !form.canSearch || !current.search.canSubmit) return
        val request = TopicSearchRequest(
            form = form,
            word = current.search.word.trim(),
            spseudo = current.search.spseudo.trim(),
            onlyMatches = current.search.onlyMatches,
        )
        if (!request.isMeaningful) return
        searchJob?.cancel()
        loadJob?.cancel()
        prefetchJob?.cancel()
        _state.update {
            it.copy(
                mode = TopicUiState.Mode.Loading,
                search = it.search.copy(status = TopicSearchStatus.Loading),
            )
        }
        searchJob = viewModelScope.launch {
            try {
                val topic = topicSearchRepository.searchInTopic(request)
                _state.update {
                    it.copy(
                        mode = TopicUiState.Mode.Loaded(topic, computeHiddenNumreponses(topic, blockedCanonicals)),
                        // The transsearch response is a filtered/anchored view of the topic — its
                        // pager is not the canonical topic pager, so we do NOT touch availablePages
                        // (leaving the previously-known set) and never schedule a prefetch off it.
                        search = it.search.copy(status = TopicSearchStatus.Done),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") searchError: Exception) {
                android.util.Log.w(LOG_TAG, "Intra-topic search failed", searchError)
                _effects.send(TopicEffect.SearchFailed)
                // Restore the normal page so the user is not stranded on a Loading screen.
                _state.update { it.copy(search = it.search.copy(status = TopicSearchStatus.Error)) }
                loadCurrentPage()
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(request: TopicRequest): TopicViewModel
    }

    private fun ReplyFailureReason.toDeleteFailureReason(): DeleteFailureReason = when (this) {
        ReplyFailureReason.LoginRequired -> DeleteFailureReason.LoginRequired
        ReplyFailureReason.TopicLocked -> DeleteFailureReason.TopicLocked
        ReplyFailureReason.EmptyMessage,
        ReplyFailureReason.InvalidHashCheck,
        ReplyFailureReason.AntiFlood,
        ReplyFailureReason.Unknown,
        -> DeleteFailureReason.Generic
    }

    private companion object {
        // Keep the section-name catalogue in lockstep with `docs/guides/profiling.md` so a
        // `TraceSectionMetric("rf2.topic.first_content")` consumer (future macrobenchmark
        // under #117 follow-up) keeps matching after refactors.
        private const val FIRST_CONTENT_SECTION = "rf2.topic.first_content"
        private const val LOG_TAG = "TopicViewModel"
    }
}
