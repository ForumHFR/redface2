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
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.search.SearchRepository
import fr.forumhfr.redface2.core.domain.topic.NoTopicSearchResultsException
import fr.forumhfr.redface2.core.domain.topic.TopicRepository
import fr.forumhfr.redface2.core.domain.topic.TopicSearchRepository
import fr.forumhfr.redface2.core.domain.write.DeletePostRepository
import fr.forumhfr.redface2.core.domain.write.DeletePostResult
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.model.TopicSearchForm
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
// LongParameterList: the ViewModel aggregates its injected repositories, one per concern.
// LargeClass (#809): the topic ViewModel is the reading surface's single MVI hub — load / pagination /
// refresh / delete / intra-topic search / flag removal all live here by design (same aggregator shape
// as FlagsViewModel). Adding the #809 flow tipped it over the threshold; splitting the hub is a
// separate refactor, tracked rather than forced by this feature.
@Suppress("LongParameterList", "LargeClass")
class TopicViewModel @AssistedInject constructor(
    // #750 — `var`, not `val`: when [TopicRequest.resolveScrollToPage] is set the real target page
    // is only known after the resolution probe; the resolved request then REPLACES this one (and
    // `state.request`) so every later read — loads, retry, refresh, page indicator, highlight —
    // sees the actual page. Mutated in exactly one place ([resolveScrollToPageThenLoad]).
    @Assisted private var request: TopicRequest,
    private val topicRepository: TopicRepository,
    private val authRepository: AuthRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val deletePostRepository: DeletePostRepository,
    private val blacklistRepository: BlacklistRepository,
    private val topicSearchRepository: TopicSearchRepository,
    private val searchRepository: SearchRepository,
    // #809 — plain Hilt dependency (the assisted Factory is unchanged): resolves + removes THIS
    // topic's drapeau for the top-bar long-press.
    private val flagRepository: FlagRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TopicUiState.initial(request))
    val state: StateFlow<TopicUiState> = _state.asStateFlow()

    /**
     * #509 — latest set of blacklisted canonical pseudos. Kept fresh by the independent
     * [observeBlockedCanonicals] collector launched in [init] (NOT by a load job), so EVERY page that
     * is on screen — whatever path put it there (cache-aside load, pull-to-refresh, post-submit force
     * refresh, post-delete refetch, intra-topic search) — re-filters live when the blacklist changes.
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
     * #877 — at most one background « fetch a fresh search form » in flight (cf. [ensureSearchForm]).
     * Fired when the search bar opens over a page whose transient `searchForm` is absent — the
     * TTL-skip cache path never refetches, so without this the form (and thus submit) would stay
     * unavailable until an unrelated reload.
     */
    private var searchFormJob: Job? = null

    /**
     * Chantier C (#546) — monotonic token guarding against a stale `transsearch` write.
     * Incremented whenever ANY flow that owns the page (a normal load, refresh, force-refresh,
     * post-delete refetch or a new search) takes over. [submitSearch] snapshots it before the POST
     * and only applies the returned page + final `search.status` while the token is still current —
     * so a slow `transsearch` reply can never clobber a more recent normal page (latest-wins strict).
     * Symmetric with cancelling [searchJob] at the head of every normal-load path.
     */
    private var searchGeneration: Int = 0

    /**
     * Chantier B (#546) — client-side cursor history for next/previous result navigation in
     * NON-FILTERED mode. HFR's `transsearch` is forward-only (it has no « previous » endpoint), so we
     * record the `numreponse` of every visited match in order and replay the list to go back.
     * `searchCursors[0]` is the first match (reached by a FRESH search) ; subsequent entries are
     * reached by stepping. [searchCursorIndex] points at the entry currently shown (-1 when no search
     * is active / filtered mode). Reset on every fresh submit and on closing the search.
     */
    private val searchCursors = mutableListOf<Int>()
    private var searchCursorIndex = -1

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
        // #509 — own the blacklist as a single independent collector (NOT tied to a load job). It
        // seeds [blockedCanonicals] and re-filters the page on screen live, so a block / unblock
        // applies on every load path (refresh / force-refresh / search) and not just the cache-aside
        // load. Launched FIRST so observeBlockedCanonicals' immediate first emission (its documented
        // contract) seeds [blockedCanonicals] before the load below computes the initial hidden set —
        // a blocked post therefore never flashes before it is hidden, even on the force-refresh path.
        observeBlockedCanonicals()
        val untrustedPageTarget = request.resolveScrollToPage && request.scrollTo != null
        if (request.submitSignal != null) {
            // Issue #200 — the user just published a reply / quote / edit / edit-FP and the
            // navigation host signalled us to skip the cache so the freshly-published post
            // is actually visible. Without this short-circuit, `observeTopicPage` would
            // emit a stale cached page that doesn't contain the new post (it was created
            // server-side after the cache was populated).
            forceRefreshCurrentPage()
        } else if (untrustedPageTarget) {
            // #750 — email deep link: `page` is a lie (always 1), resolve the real one first.
            resolveScrollToPageThenLoad()
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
        // #806 — mirror the writing-surface preset so the screen can route each write tap
        // (reply FAB / « Citer » / « Citer N ») to the sheet or the full-screen editor.
        userPreferencesRepository.observeWritingSurfacePreset()
            .onEach { preset ->
                _state.update { it.copy(writingSurfacePreset = preset) }
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
            TopicIntent.RequestRemoveTopicFlag -> requestRemoveTopicFlag()
            TopicIntent.OpenSearch -> openSearch()
            TopicIntent.CloseSearch -> closeSearch()
            is TopicIntent.SearchWordChanged ->
                _state.update { it.copy(search = it.search.copy(word = intent.word)) }
            is TopicIntent.SearchPseudoChanged ->
                _state.update { it.copy(search = it.search.copy(spseudo = intent.pseudo)) }
            is TopicIntent.SearchOnlyMatchesChanged ->
                _state.update { it.copy(search = it.search.copy(onlyMatches = intent.onlyMatches)) }
            TopicIntent.SubmitSearch -> submitSearch()
            TopicIntent.NextResult -> nextResult()
            TopicIntent.PrevResult -> prevResult()
        }
    }

    /**
     * #509 — block / unblock [author] from the post menu. This launch is on [viewModelScope], but the
     * DataStore commit still survives the sheet/ViewModel going away mid-write: the repository's
     * `persist {}` offloads the actual write to the application scope (the #507 pattern), so only the
     * `await` is cancelled, not the commit. The page re-filters live through the independent
     * [observeBlockedCanonicals] collector (launched in [init]), so the write here applies on whatever
     * page is currently on screen, regardless of how it was loaded — no manual state poke is needed.
     */
    private fun setAuthorBlocked(author: String, blocked: Boolean) {
        viewModelScope.launch {
            if (blocked) blacklistRepository.block(author) else blacklistRepository.unblock(author)
        }
    }

    /**
     * #509 — single, load-independent owner of the blacklist. Collected once on [viewModelScope] (NOT
     * inside [loadJob], which the refresh / force-refresh / post-delete / search paths cancel and
     * replace), so a block / unblock applies live on the page currently on screen whatever path put it
     * there. On each emission it (a) caches the set in [blockedCanonicals] (so every load path can
     * compute the initial hidden set with the up-to-date blacklist) and (b) recomputes
     * [TopicUiState.Mode.Loaded.hiddenNumreponses] on the current loaded page and re-emits it. A
     * non-loaded state (Loading / Error) is left untouched; the next load reads the freshest
     * [blockedCanonicals].
     *
     * Launched FIRST in [init] so its emission usually seeds [blockedCanonicals] before the initial
     * page renders. But `observeBlockedCanonicals` is a COLD DataStore flow (not a StateFlow), so that
     * first emission is asynchronous — the ordering vs the initial load is NOT guaranteed (Codex
     * review). The (b) re-filter is the real guarantee: even if a load renders before the blacklist
     * lands, this collector immediately re-hides the blocked posts. The residual is at most a one-frame
     * flash on a cold open whose page-1 already contains a blocked author — and in practice the network
     * page load is slower than the (memory-cached) DataStore read, so it rarely shows.
     */
    private fun observeBlockedCanonicals() {
        blacklistRepository.observeBlockedCanonicals()
            .onEach { blocked ->
                blockedCanonicals = blocked
                _state.update { current ->
                    val loaded = current.mode as? TopicUiState.Mode.Loaded ?: return@update current
                    // #785 — rebuild the WHOLE loaded mode through the single seam (loadedMode) so
                    // the post-level mask (hiddenNumreponses) and the quote-level canonical set
                    // (blockedQuoteAuthors) re-filter together on a live blacklist change.
                    // #877 — this is a LOCAL transformation of the page already on screen : it must
                    // carry the provenance over, or a re-filter landing between the provisional
                    // cache emission and the settled one would fake-settle the pill (gate finding).
                    current.copy(mode = loadedMode(loaded.topic, provisional = loaded.provisional))
                }
            }
            // This collector is independent of any load job, so an unhandled error here would tear
            // down viewModelScope and kill the screen. A DataStore read hiccup on the blacklist must
            // not do that — keep the last known set; the next emission recovers (Codex review; mirrors
            // the catch the former loadCurrentPage combine carried).
            .catch { error -> android.util.Log.w(LOG_TAG, "Blacklist observe failed", error) }
            .launchIn(viewModelScope)
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
        takeOverFromSearch()
        loadJob?.cancel()
        prefetchJob?.cancel()
        prefetchedPage = null
        _state.update { it.copy(isRefreshing = true) }
        loadJob = viewModelScope.launch {
            try {
                val topic = topicRepository.refreshTopicPage(request.cat, request.post, request.page)
                _state.update {
                    it.copy(
                        mode = loadedMode(topic),
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

    /**
     * #750 — resolves the REAL page of [TopicRequest.scrollTo] through HFR's server-side redirect
     * (same probe + timeout + fallback contract as the search results, #277:
     * [SearchRepository.resolveSearchResultPage]) BEFORE the first load, while the screen shows the
     * loading skeleton. On success the resolved request replaces [request] AND `state.request`, so
     * the page becomes the real target everywhere (loads, retry, page indicator, highlight) — not a
     * presentational patch. On timeout / failure the untrusted page loads as-is: the pre-#750
     * behaviour, never worse. Runs at most once (init-only branch); a config change keeps the
     * ViewModel, a route replace builds a fresh one without the flag.
     */
    private fun resolveScrollToPageThenLoad() {
        val scrollTo = requireNotNull(request.scrollTo) { "resolveScrollToPageThenLoad requires scrollTo" }
        _state.update { it.copy(mode = TopicUiState.Mode.Loading) }
        viewModelScope.launch {
            val outcome = runCatching {
                withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                    searchRepository.resolveSearchResultPage(
                        cat = request.cat,
                        post = request.post,
                        numreponse = scrollTo,
                    )
                }
            }
            (outcome.exceptionOrNull() as? CancellationException)?.let { throw it }
            val resolved = outcome.getOrNull()
            if (resolved != null && resolved != request.page) {
                request = request.copy(page = resolved)
                _state.update { it.copy(request = request) }
            }
            loadCurrentPage()
        }
    }

    private fun loadCurrentPage() {
        takeOverFromSearch()
        loadJob?.cancel()
        prefetchJob?.cancel()
        prefetchedPage = null
        beginFirstContentSection()
        _state.update { it.copy(mode = TopicUiState.Mode.Loading) }
        loadJob = viewModelScope.launch {
            topicRepository
                .observeTopicPage(request.cat, request.post, request.page, forceRefresh = request.forceRefresh)
                // #509 — the blacklist is owned by the independent observeBlockedCanonicals collector
                // (launched in init), NOT combined here: it has already seeded blockedCanonicals by the
                // time this load runs (its first emission is synchronous, documented contract), so the
                // initial hidden set below is correct and there is no double-source / clignotement
                // between this load and the live re-filter — the init collector is the sole re-filter.
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
                .collect { emission ->
                    val topic = emission.topic
                    _state.update {
                        it.copy(
                            // #877 — the provenance flag rides into Mode.Loaded so the top-bar
                            // pill can hold « Chargement… » through the cache emission and only
                            // show « page X / Y » once the page is settled (network / TTL skip /
                            // terminal after a failed refresh).
                            mode = loadedMode(topic, provisional = emission.provisional),
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
     * #785 — SINGLE construction seam for [TopicUiState.Mode.Loaded]. Every path that puts a page on
     * screen (cache-aside load, manual refresh, post-submit force refresh, post-delete refetch,
     * intra-topic search, live blacklist re-filter) builds the mode here, from the same
     * [blockedCanonicals] snapshot, so the post-level mask ([TopicUiState.Mode.Loaded.hiddenNumreponses])
     * and the quote-level canonical set ([TopicUiState.Mode.Loaded.blockedQuoteAuthors]) can never
     * diverge — a path bypassing this seam would make masked citations flicker across
     * refresh/search/delete (Codex framing reservation on #785).
     */
    private fun loadedMode(topic: Topic, provisional: Boolean = false): TopicUiState.Mode.Loaded =
        TopicUiState.Mode.Loaded(
            topic = topic,
            hiddenNumreponses = computeHiddenNumreponses(topic, blockedCanonicals),
            blockedQuoteAuthors = blockedCanonicals,
            // #877 — default false : every other caller (refresh, force-refresh, post-delete,
            // search, live re-filter) renders a settled network page ; only the cache-aside
            // collect above forwards the repository's provenance.
            provisional = provisional,
        )

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
        takeOverFromSearch()
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
                        mode = loadedMode(topic),
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
        takeOverFromSearch()
        loadJob?.cancel()
        prefetchJob?.cancel()
        prefetchedPage = null
        _state.update { it.copy(mode = TopicUiState.Mode.Loading) }
        loadJob = viewModelScope.launch {
            try {
                val topic = topicRepository.refreshTopicPage(request.cat, request.post, request.page)
                _state.update {
                    it.copy(
                        mode = loadedMode(topic),
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

    // ─── remove topic flag (#809) ─────────────────────────────────────────────────

    /**
     * #809 — drives the top-bar long-press « Retirer le drapeau » interaction. Explicit MVI state so
     * the confirmation gates the delflag call and the anti double-press guard is observable. The
     * [RemoveTopicFlagState.Resolving] step (which FlagsViewModel lacks — it already holds the Flag)
     * covers the async lookup that may fan out the network on a cold flag cache.
     */
    private val _removeTopicFlagState = MutableStateFlow<RemoveTopicFlagState>(RemoveTopicFlagState.Idle)
    val removeTopicFlagState: StateFlow<RemoveTopicFlagState> = _removeTopicFlagState.asStateFlow()

    /**
     * #809 — user long-pressed the title : resolve THIS topic's drapeau, then either raise the
     * confirmation dialog ([RemoveTopicFlagState.Confirming]) or emit [TopicEffect.TopicFlagNotFound]
     * (topic not flagged, anonymous, or an unresolvable lookup — see below). The outcome rides the
     * screen's single [effects] channel like every other one-shot Toast (review finding : no parallel
     * consumable StateFlow). No-op while a lookup or a removal is already running, so a second
     * long-press during the (possibly network-bound) resolve cannot launch a duplicate.
     */
    @Suppress("TooGenericExceptionCaught") // gate #809 — any resolve failure folds to NotFound below;
    // cancellation is rethrown after releasing the state.
    fun requestRemoveTopicFlag() {
        val current = _removeTopicFlagState.value
        if (current is RemoveTopicFlagState.Resolving || current is RemoveTopicFlagState.Removing) return
        _removeTopicFlagState.value = RemoveTopicFlagState.Resolving
        viewModelScope.launch {
            // Gate Codex #809 — findFlag can die mid-resolve (an in-flight fetch cancelled by an
            // account switch, an unexpected runtime failure) : fold it to « unresolvable » instead of
            // leaving the state wedged in Resolving with no event — the long-press would otherwise be
            // dead until the VM is recreated. CancellationException is rethrown (the scope is going
            // away) AFTER releasing the state.
            val flag = try {
                flagRepository.findFlag(cat = request.cat, topicId = request.post)
            } catch (cancelled: CancellationException) {
                _removeTopicFlagState.value = RemoveTopicFlagState.Idle
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (flag != null) {
                _removeTopicFlagState.value = RemoveTopicFlagState.Confirming(flag)
            } else {
                _effects.send(TopicEffect.TopicFlagNotFound)
                _removeTopicFlagState.value = RemoveTopicFlagState.Idle
            }
        }
    }

    /** #809 — user dismissed the confirmation dialog without confirming. */
    fun cancelRemoveTopicFlag() {
        if (_removeTopicFlagState.value is RemoveTopicFlagState.Confirming) {
            _removeTopicFlagState.value = RemoveTopicFlagState.Idle
        }
    }

    /**
     * #809 — user confirmed in the dialog : move to [RemoveTopicFlagState.Removing] (disables the
     * action), call the repository, then emit the one-shot outcome on [effects]. The repository owns
     * the cache reconciliation, so nothing optimistic happens here.
     */
    fun confirmRemoveTopicFlag() {
        val confirming = _removeTopicFlagState.value as? RemoveTopicFlagState.Confirming ?: return
        val flag = confirming.flag
        _removeTopicFlagState.value = RemoveTopicFlagState.Removing(flag)
        viewModelScope.launch {
            try {
                // Review #809 — removeFlag CAN throw outside its Result (evictFlagFromCaches runs in
                // `.onSuccess`, past the internal runCatching) : fold a raw throw to Failure so the
                // user still gets feedback instead of a crash. Cancellation propagates untouched
                // (the finally below releases the lock either way).
                val result = runCatching { flagRepository.removeFlag(flag) }
                    .getOrElse { raised ->
                        if (raised is CancellationException) throw raised
                        Result.failure(raised)
                    }
                _effects.send(
                    if (result.isSuccess) TopicEffect.TopicFlagRemoved else TopicEffect.TopicFlagRemovalFailed,
                )
            } finally {
                // #603 audit fix (fork #5) — always release the Removing lock, even if removeFlag
                // throws outside its Result (or the coroutine is cancelled). Otherwise the state stays
                // Removing forever and the anti double-tap guard wedges until the VM is recreated.
                _removeTopicFlagState.value = RemoveTopicFlagState.Idle
            }
        }
    }

    // ─── intra-topic search (#546) ───────────────────────────────────────────────

    /**
     * Open the search bar. Gated on [TopicUiState.canOpenSearch] (authenticated + page on screen,
     * #877) — NOT on the transient `searchForm`, whose absence on a cache emission made the Loupe
     * vanish. When the form is missing (TTL-skip cache, provisional page), [ensureSearchForm]
     * fetches a fresh one in the background so the submit gate is usually satisfied by the time
     * the user finished typing ; a submit that still has no form fails explicitly (Toast).
     */
    private fun openSearch() {
        if (!_state.value.canOpenSearch) return
        _state.update { it.copy(search = it.search.copy(isActive = true)) }
        ensureSearchForm()
    }

    /**
     * #877 — background fetch of a fresh, authenticated page for the sole purpose of harvesting
     * its transient `searchForm` (hash_check). Never persisted beyond the normal page cache — the
     * session token itself is NEVER written to Room (cadrage : périssable + sensible). The fresh
     * page replaces the on-screen one through the single [loadedMode] seam (same page, newer
     * posts — an acceptable, even desirable, side effect). Failures are silent : the submit path
     * owns the explicit error surface.
     */
    private fun ensureSearchForm() {
        val loaded = _state.value.mode as? TopicUiState.Mode.Loaded ?: return
        // No fetch when : the form is already usable ; the page is provisional (the cache-aside
        // network refresh is in flight and will carry the form — if it fails, the terminal
        // re-emission drops `provisional` and a re-open of the bar or the submit failure path
        // retries from here) ; or a fetch is already running.
        val shouldFetch = loaded.topic.searchForm?.canSearch != true &&
            !loaded.provisional &&
            searchFormJob?.isActive != true
        if (!shouldFetch) return
        // Snapshot the request AND the generation token : `request` is a var (page changes mutate
        // it), and `request == fetchedFor` alone cannot tell two successive owners of the SAME
        // page apart (a refresh or a search taking over between our launch and our landing). Every
        // owner change bumps `searchGeneration` (takeOverFromSearch / launchSearch), so a stale
        // form-fetch reply is dropped exactly like a stale transsearch reply (gate finding, #877).
        val fetchedFor = request
        val generation = searchGeneration
        searchFormJob = viewModelScope.launch {
            runCatching {
                topicRepository.refreshTopicPage(fetchedFor.cat, fetchedFor.post, fetchedFor.page)
            }.onSuccess { fresh ->
                _state.update { state ->
                    // Latest-wins : same page still on screen AND no newer owner took over.
                    if (state.mode is TopicUiState.Mode.Loaded &&
                        request == fetchedFor &&
                        generation == searchGeneration
                    ) {
                        state.copy(
                            mode = loadedMode(fresh),
                            availablePages = (1..fresh.totalPages).toList(),
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    /**
     * Close the search bar and drop any active filtered view by reloading the normal current page.
     * The typed criteria are kept so re-opening restores them. We only reload when a search was
     * actually applied (`status == Done`) to avoid a needless refetch on a cancel-before-submit.
     */
    private fun closeSearch() {
        val hadResults = _state.value.search.status == TopicSearchStatus.Done
        _state.update {
            it.copy(search = it.search.copy(isActive = false, status = TopicSearchStatus.Idle))
        }
        // loadCurrentPage already cancels searchJob + bumps the generation (takeOverFromSearch). When
        // there is nothing to reload, drop the in-flight search ourselves so a late reply can't write.
        if (hadResults) loadCurrentPage() else takeOverFromSearch()
    }

    /**
     * Cancel any in-flight intra-topic search and bump [searchGeneration] so a `transsearch` reply
     * that is still on the wire is dropped on arrival. Called at the head of every normal-load path
     * (load / refresh / force-refresh / post-delete) so a stale search result can never overwrite a
     * more recent normal page (latest-wins strict).
     *
     * Also clears a `status == Loading` left dangling by the cancelled search: bumping the generation
     * drops the reply, but [submitSearch]'s own `status = Done/Error` write is then guarded out, so
     * without this reset the search bar would spin forever after a normal load took over. We reset to
     * [TopicSearchStatus.Idle] only the Loading state — the bar stays open (`isActive` kept) with the
     * typed criteria preserved so the user can retry; we never close the bar from here.
     */
    private fun takeOverFromSearch() {
        searchJob?.cancel()
        // #877 — a form fetch tied to the outgoing page is moot (its inject guard would drop the
        // result anyway) : cancel it so it does not waste a network round-trip.
        searchFormJob?.cancel()
        searchGeneration++
        // A normal-load path owns the page now, so the result cursor history is stale: a later
        // next/prev would step from a position that no longer matches what is on screen. Reset it
        // and clear the navigation affordances. (Covers closeSearch, which routes through here.)
        resetSearchCursors()
        // Clear the prev/next affordances UNCONDITIONALLY — not just on a mid-flight Loading search:
        // after a completed (Done) search, a refresh / page change / normal load takes over the page,
        // and lingering arrows would become no-op clicks over a non-search page (Codex review). Also
        // reset a dangling Loading to Idle so the bar doesn't spin forever after the takeover.
        _state.update {
            val s = it.search
            it.copy(
                search = s.copy(
                    status = if (s.status == TopicSearchStatus.Loading) TopicSearchStatus.Idle else s.status,
                    canGoPreviousResult = false,
                    canGoNextResult = false,
                ),
            )
        }
    }

    /** Chantier B (#546) — drop the client-side result cursor history (no search position active). */
    private fun resetSearchCursors() {
        searchCursors.clear()
        searchCursorIndex = -1
    }

    /**
     * Submit a FRESH intra-topic search : `POST transsearch.php` (authenticated) with the parsed form +
     * the typed criteria, then render the returned topic page in [TopicUiState.Mode.Loaded].
     *
     * The current page STAYS visible while the POST is in flight — only `search.status` flips to
     * [TopicSearchStatus.Loading] (the search bar shows the progress), per the documented contract
     * ([TopicEffect.SearchFailed]'s KDoc : « the current page stays on screen »). We never switch the
     * whole screen to [TopicUiState.Mode.Loading], which would blank the posts the user is reading.
     *
     * Latest-wins : a generation token is snapshotted before the POST (incremented by every normal-load
     * path AND by a fresh submit through [searchJob]'s cancellation). The result page + final status
     * are applied ONLY while that token is still current, so a slow `transsearch` reply that lost the
     * race to a more recent normal page (refresh / page change / new search) is dropped, never written.
     *
     * Chantier B (#546) — in NON-FILTERED mode (`!onlyMatches`) HFR returns the FULL page of the first
     * match anchored on `#t<currentnum>` (no highlight), so we seed the result cursor history with that
     * match and scroll to it. Filtered mode (`onlyMatches`) keeps the prior behaviour : HFR returns the
     * matches-only page, which IS the result list, so there is no per-result navigation or scroll. A
     * « no result » page surfaces as [TopicSearchStatus.NoResults] (≠ Error), never a failure Toast.
     */
    private fun submitSearch() {
        val current = _state.value
        val form = (current.mode as? TopicUiState.Mode.Loaded)?.topic?.searchForm
        // Re-validate the gate server-side: never POST without a usable (authenticated) form.
        // #877 — with the icon decoupled from the transient form, this path is reachable when the
        // fresh-form fetch has not landed yet (or failed) : fail EXPLICITLY (same Toast as a
        // network search failure) and retry the form fetch, never a silent no-op tap. The toast
        // only fires on a submittable bar — a tap with empty criteria stays inert either way.
        if (form == null || !form.canSearch) {
            if (current.search.canSubmit) {
                viewModelScope.launch { _effects.send(TopicEffect.SearchFailed) }
                ensureSearchForm()
            }
            return
        }
        val request = TopicSearchRequest(
            form = form,
            word = current.search.word.trim(),
            spseudo = current.search.spseudo.trim(),
            onlyMatches = current.search.onlyMatches,
            // Fresh search: no cursor, keep firstnum (isStep = false). HFR re-anchors on the first match.
        )
        if (!current.search.canSubmit || !request.isMeaningful) return
        resetSearchCursors()
        launchSearch(request, isFresh = true)
    }

    /**
     * Chantier B (#546) — jump to the NEXT search result (non-filtered mode). Steps HFR's `currentnum`
     * cursor forward from the current match, OMITTING `firstnum` (the repository does, keyed on
     * [TopicSearchRequest.isStep]) so HFR truly advances instead of re-anchoring on the first match.
     */
    private fun nextResult() {
        val current = _state.value
        val form = navigableSearchForm(current).takeIf { searchCursorIndex >= 0 } ?: return
        launchSearch(stepRequest(form, current.search, cursor = searchCursors[searchCursorIndex]), isFresh = false)
    }

    /**
     * Chantier B (#546) — jump to the PREVIOUS search result (non-filtered mode). HFR is forward-only,
     * so we replay the cursor history : re-issue a FRESH search to reach the first match again, or a
     * STEP from the match just before the target, then scroll to the now-current cursor.
     */
    private fun prevResult() {
        val current = _state.value
        val form = navigableSearchForm(current).takeIf { searchCursorIndex > 0 } ?: return
        val targetIndex = searchCursorIndex - 1
        val request = if (targetIndex == 0) {
            TopicSearchRequest(
                form = form,
                word = current.search.word.trim(),
                spseudo = current.search.spseudo.trim(),
                onlyMatches = false,
            )
        } else {
            stepRequest(form, current.search, cursor = searchCursors[targetIndex - 1])
        }
        launchSearch(request, isFresh = false, rewindToIndex = targetIndex)
    }

    /**
     * Chantier B (#546) — the usable (authenticated) search form of the loaded page IF result
     * navigation applies, i.e. NON-filtered mode and a loaded page whose form `canSearch`. Returns
     * `null` to short-circuit a no-op next/previous, keeping those single-exit (detekt ReturnCount).
     */
    private fun navigableSearchForm(current: TopicUiState): TopicSearchForm? {
        if (current.search.onlyMatches) return null
        return (current.mode as? TopicUiState.Mode.Loaded)?.topic?.searchForm?.takeIf { it.canSearch }
    }

    /** Chantier B (#546) — a next/previous STEP request (cursor set, firstnum omitted via isStep). */
    private fun stepRequest(
        form: TopicSearchForm,
        search: TopicSearchUiState,
        cursor: Int,
    ): TopicSearchRequest = TopicSearchRequest(
        form = form,
        word = search.word.trim(),
        spseudo = search.spseudo.trim(),
        onlyMatches = false,
        currentNum = cursor.toString(),
        isStep = true,
    )

    /**
     * Chantier B (#546) — shared launcher for a fresh search, a forward step or a backward replay.
     * Snapshots the generation (latest-wins), keeps the current page on screen while the POST is in
     * flight and dispatches the result to [applySearchResult]. [rewindToIndex] is non-null only for a
     * « previous » replay : it tells [applySearchResult] which existing history slot we are landing on
     * instead of appending a new one.
     */
    private fun launchSearch(request: TopicSearchRequest, isFresh: Boolean, rewindToIndex: Int? = null) {
        // Take over from any previous search; cancel the in-flight normal load / prefetch so a late
        // observeTopicPage emission cannot land on top of the page this search is about to render.
        searchJob?.cancel()
        loadJob?.cancel()
        prefetchJob?.cancel()
        val generation = ++searchGeneration
        _state.update { it.copy(search = it.search.copy(status = TopicSearchStatus.Loading)) }
        searchJob = viewModelScope.launch {
            try {
                val topic = topicSearchRepository.searchInTopic(request)
                // Drop a stale reply: a newer normal load / refresh / search bumped the generation.
                if (generation != searchGeneration) return@launch
                applySearchResult(
                    topic,
                    isFresh = isFresh,
                    rewindToIndex = rewindToIndex,
                    onlyMatches = request.onlyMatches,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (noResults: NoTopicSearchResultsException) {
                android.util.Log.i(LOG_TAG, "Intra-topic search returned no result", noResults)
                if (generation != searchGeneration) return@launch
                // Same dispatch as a parsed reply with no usable landing (fresh→no results,
                // rewind→replay failure ≠ end, forward→end of results).
                signalNoLanding(isFresh, rewindToIndex)
            } catch (@Suppress("TooGenericExceptionCaught") searchError: Exception) {
                android.util.Log.w(LOG_TAG, "Intra-topic search failed", searchError)
                if (generation != searchGeneration) return@launch
                _effects.send(TopicEffect.SearchFailed)
                // The current page is still on screen — just mark the search as failed so the user can
                // retry or close it.
                _state.update { it.copy(search = it.search.copy(status = TopicSearchStatus.Error)) }
            }
        }
    }

    /**
     * Chantier B (#546) — apply a `transsearch` result page. In FILTERED mode the page IS the result
     * list (no per-result navigation), so we render it and stop. In NON-FILTERED mode HFR anchors a
     * single match : we only swap the page on screen once we know the match is USABLE (so a step past
     * the last result leaves the current match in place — « ne pas naviguer en fin »). The client
     * cursor history is maintained (fresh seeds it, forward appends/advances, backward rewinds) and we
     * scroll to the match. A cursor that is null OR no longer a post on the page means : fresh → no
     * results ; step → end of results.
     */
    private suspend fun applySearchResult(
        topic: Topic,
        isFresh: Boolean,
        rewindToIndex: Int?,
        // Snapshot of the MODE the request was launched with — NOT the live toggle, which the user
        // may have flipped mid-flight (Codex review : that would apply a non-filtered reply as
        // filtered or vice-versa).
        onlyMatches: Boolean,
    ) {
        if (onlyMatches) {
            // Matches-only page: it is the whole result set, no scroll / cursor navigation.
            renderSearchPage(topic, TopicSearchStatus.Done, canPrev = false, canNext = false)
            return
        }
        // `landed` = the returned cursor IF it is a real post on the page. A null cursor, or one
        // absent from the page (HFR's end sentinel is lastPost+1, never a real post), means no usable
        // match here.
        val cursor = topic.searchForm?.currentNum
        val landed = cursor?.takeIf { c -> topic.posts.any { it.numreponse == c } }
        // A forward step must ADVANCE — matches are in increasing numreponse order. A cursor that did
        // not move past the current one (re-anchor / stale parse) is the end of results, not a new hit.
        val forwardNoProgress = !isFresh && rewindToIndex == null && landed != null &&
            landed <= (searchCursors.getOrNull(searchCursorIndex) ?: Int.MIN_VALUE)
        if (landed == null || forwardNoProgress) {
            signalNoLanding(isFresh, rewindToIndex)
            return
        }
        recordLanding(landed, isFresh, rewindToIndex)
        renderSearchPage(
            topic,
            TopicSearchStatus.Done,
            canPrev = searchCursorIndex > 0,
            // Forward-only HFR never reports a count up front; keep « next » enabled until a step
            // actually reports the end (onStepEnd disables it).
            canNext = true,
        )
        _effects.send(TopicEffect.ScrollToPost(landed))
    }

    /**
     * Chantier B (#546) — record a usable match in the client cursor history. Fresh seeds it ; a
     * backward replay just repositions the index ; a forward step DROPS any history beyond the current
     * position before appending — a blind append corrupted the history when stepping back then forward
     * (e.g. `[100,200,300]` at index 1 → next would yield `[100,200,300,200]`) (Codex review).
     */
    private fun recordLanding(landed: Int, isFresh: Boolean, rewindToIndex: Int?) {
        when {
            rewindToIndex != null -> searchCursorIndex = rewindToIndex
            isFresh -> {
                searchCursors.clear()
                searchCursors += landed
                searchCursorIndex = 0
            }
            else -> {
                while (searchCursors.lastIndex > searchCursorIndex) {
                    searchCursors.removeAt(searchCursors.lastIndex)
                }
                searchCursors += landed
                searchCursorIndex = searchCursors.lastIndex
            }
        }
    }

    /**
     * Chantier B (#546) — a reply with no usable landing (null/absent cursor, end sentinel, or a
     * NoResults page). Fresh → no results ; a backward replay that failed → non-destructive failure
     * (≠ end) ; a forward step → end of results.
     */
    private suspend fun signalNoLanding(isFresh: Boolean, rewindToIndex: Int?) {
        when {
            isFresh -> onFreshNoResults()
            rewindToIndex != null -> onReplayFailed()
            else -> onStepEnd()
        }
    }

    /**
     * Chantier B (#546) — render the `transsearch` page into [TopicUiState.Mode.Loaded] with [status]
     * and the prev/next affordances, keeping the previously-known [TopicUiState.availablePages] (the
     * transsearch pager is not the canonical topic pager) and never scheduling a prefetch off it.
     */
    private fun renderSearchPage(topic: Topic, status: TopicSearchStatus, canPrev: Boolean, canNext: Boolean) {
        _state.update {
            it.copy(
                mode = loadedMode(topic),
                search = it.search.copy(
                    status = status,
                    canGoPreviousResult = canPrev,
                    canGoNextResult = canNext,
                ),
            )
        }
    }

    /** Chantier B (#546) — a fresh search that matched nothing : NoResults state, no navigation. */
    private fun onFreshNoResults() {
        resetSearchCursors()
        _state.update {
            it.copy(
                search = it.search.copy(
                    status = TopicSearchStatus.NoResults,
                    canGoPreviousResult = false,
                    canGoNextResult = false,
                ),
            )
        }
    }

    /**
     * Chantier B (#546) — a forward step ran past the last match. The current match STAYS on screen (we
     * never rendered the sentinel page), « next » disables, and a sober Toast is surfaced. The cursor
     * history is untouched so « previous » still walks back from the last real match.
     */
    private suspend fun onStepEnd() {
        _state.update {
            it.copy(
                search = it.search.copy(
                    status = TopicSearchStatus.Done,
                    canGoPreviousResult = searchCursorIndex > 0,
                    canGoNextResult = false,
                ),
            )
        }
        _effects.send(TopicEffect.SearchResultsEnd)
    }

    /**
     * Chantier B (#546) — a « previous » replay HFR could not honour (no-result / inconsistency).
     * Distinct from [onStepEnd] (the end of FORWARD results) : the current match stays on screen,
     * « next » is NOT disabled, and a generic search-failure Toast is surfaced (Codex review). The
     * cursor index is unchanged (the rewind never landed), so the affordances reflect the position
     * we are still on.
     */
    private suspend fun onReplayFailed() {
        _state.update {
            it.copy(
                search = it.search.copy(
                    status = TopicSearchStatus.Done,
                    canGoPreviousResult = searchCursorIndex > 0,
                    canGoNextResult = true,
                ),
            )
        }
        _effects.send(TopicEffect.SearchFailed)
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

        // #750 — cap on the page-resolution probe, same rationale and value as
        // SearchViewModel.RESOLVE_TIMEOUT_MS (#277): degrade to the untrusted page
        // rather than hold the first load hostage on a degraded network.
        private const val RESOLVE_TIMEOUT_MS: Long = 3_000
    }
}
