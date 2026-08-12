package fr.forumhfr.redface2.feature.topic

import androidx.lifecycle.SavedStateHandle
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
import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.domain.error.classifyHfrError
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.search.SearchRepository
import fr.forumhfr.redface2.core.domain.topic.NoTopicSearchResultsException
import fr.forumhfr.redface2.core.domain.topic.TopicRepository
import fr.forumhfr.redface2.core.domain.topic.TopicSearchRepository
import fr.forumhfr.redface2.core.domain.write.DeletePostRepository
import fr.forumhfr.redface2.core.domain.write.DeletePostResult
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.write.FlagAddContext
import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.model.TopicSearchForm
import fr.forumhfr.redface2.core.model.TopicSearchRequest
import fr.forumhfr.redface2.core.model.write.EditPostContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * #895 (étape 4) — this ViewModel OWNS intra-topic pagination. `TopicRoute.page` is only the
 * initial entry ; `state.request.page` is the canonical current page, restored from
 * [SavedStateHandle] across process death. Page changes go through the internal switch engine
 * ([switchToPage] / [goToPost] / [returnFromJump] / [applySubmitResult]) : departure anchor saved,
 * owner generation bumped (latest-wins), in-flight work cancelled, LRU memory snapshot activated
 * atomically when available (no Loading flash), landing resolved by priority (explicit post >
 * post-submit bottom > saved anchor > `page - 1` bottom step (#412) > top) and dispatched on the
 * first matching Loaded. Since PR 2 (`4248c22d`), `RedfaceNavigation` routes every in-topic page
 * change through this engine — the `TopicRoute` is frozen at entry (single nav entry).
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
    // #895 (étape 4) — canonical current page + one-shot consumption markers, so a process death
    // neither loses the page the user had switched to nor replays an already-consumed entry
    // intention (scrollTo / forceRefresh / resolveScrollToPage). Plain Hilt
    // dependency : @HiltViewModel supports SavedStateHandle alongside assisted params.
    private val savedStateHandle: SavedStateHandle,
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

    // #986 — account-scoped owner token for the resolve → optional confirm → add interaction.
    // A logout/account switch cancels both jobs, advances the token and resets the visible state,
    // so a late result can never describe or mutate the next account's UI state.
    private var favoriteAuthGeneration: Int = 0
    private var favoriteResolveJob: Job? = null
    private var favoriteAddJob: Job? = null
    private val _favoriteAtPostState = MutableStateFlow<FavoriteAtPostState>(FavoriteAtPostState.Unknown)
    val favoriteAtPostState: StateFlow<FavoriteAtPostState> = _favoriteAtPostState.asStateFlow()

    /**
     * Chantier C (#546), generalized by #895 étape 4 — monotonic token guarding against ANY stale
     * async write on the page. Incremented whenever a flow that owns the page (a normal load,
     * refresh, force-refresh, post-delete refetch, a new search — and now a PAGE SWITCH) takes
     * over. Every async producer ([submitSearch], the form fetch, the page-load collect, the
     * submit refresh, the landing dispatch) snapshots it before suspending and only applies its
     * result while the token is still current — latest-wins strict, per the #895 cadrage (F4 :
     * one common generation for page changes, `loadJob.cancel()` alone is not enough).
     */
    private var ownerGeneration: Int = 0

    // ─── #895 étape 4 — in-ViewModel pagination engine ───────────────────────────

    /**
     * F2 — LRU memory snapshots of TERMINAL page emissions (raw [Topic], the blacklist filter is
     * recomputed at activation through [loadedMode]). Bounded to [MAX_PAGE_SNAPSHOTS] terminal
     * pages ; access-ordered so revisits refresh recency. Provisional emissions are displayed but
     * never recorded (they must not replace a terminal snapshot) ; `transsearch` result pages are
     * never recorded either (they are a search VIEW of the topic, not a canonical page).
     */
    private val pageSnapshots = object : LinkedHashMap<Int, Topic>(
        SNAPSHOTS_INITIAL_CAPACITY,
        SNAPSHOTS_LOAD_FACTOR,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Topic>): Boolean =
            size > MAX_PAGE_SNAPSHOTS
    }

    /**
     * F3 — reading anchors per visited page (raw LazyListState primitives, [TopicScrollAnchor]).
     * The screen reports the current page's anchor through [reportPageAnchor] and hands the
     * departure anchor to the switch intents ; a revisit whose landing resolves to a saved anchor
     * emits [TopicEffect.ScrollToAnchor]. RAM map for visited pages ; the CURRENT page's anchor is
     * mirrored to [SavedStateHandle] (primitives only) so a process death restores the position.
     */
    private val pageAnchors = mutableMapOf<Int, TopicScrollAnchor>()

    /**
     * #782 — quote-jump return stack, now owned by the ViewModel (F1) : each [goToPost] pushes the
     * departure `{page, tap-time anchor}`, [returnFromJump] pops and lands back on it. Transient by
     * design (never saved — a serialized chain would replay stale returns, PR #420 stance) ; a
     * MANUAL page switch clears it (browser-like, same rule as today's `onOpenPage`).
     */
    private val jumpStack = ArrayDeque<TopicJumpFrame>()

    /**
     * F3/F4 — the landing owed to the page on screen, dispatched on the first matching Loaded
     * then cleared (one landing per switch/entry). A [PendingLanding.Post] whose target is not on
     * the page stays pending for the next emission (historical scrollTo retry) ; any newer switch
     * REPLACES it through [armLanding]. Gate Sol PR1 : the armed landing CARRIES its owner
     * `(generation, page)` — [dispatchPendingLanding] refuses a stale pair, and the
     * post-suspension clear is a COMPARE-and-clear, so a landing armed by a newer owner while
     * `_effects.send` was suspended is never blindly erased. A same-page re-own (Retry / refresh /
     * search takeover) re-tags the landing instead of dropping it ([becomePageOwner]).
     * [ArmedLanding.initialScrollTo] marks the INITIAL route `scrollTo` : its dispatch or its
     * supersession persists [KEY_SCROLL_TO_CONSUMED] (Sol point 5 : `route.scrollTo` is
     * exclusively an ENTRY intention, never replayed after process death).
     */
    private var pendingLanding: ArmedLanding? = null

    private data class ArmedLanding(
        val landing: PendingLanding,
        val generation: Int,
        val page: Int,
        val initialScrollTo: Boolean = false,
    )

    /**
     * #226 anti-chase as an internal budget (Sol point 3) : [applySubmitResult] arms ONE redirect ;
     * the overflow detection consumes it BEFORE switching to the freshly-created last page, and
     * that landing is terminal — a concurrent post bumping `totalPages` during the refresh can
     * never start a moving-tail chase.
     */
    private var postSubmitRedirectBudget: Int = 0

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
     * #895 étape 4 — the landing model of the switch engine (F3 priority made explicit). One value
     * per switch/entry, dispatched by [dispatchPendingLanding] on the first matching Loaded :
     * - [Post] → [TopicEffect.ScrollToPost] once the numreponse is on the page (stays pending
     *   through emissions of the same generation otherwise — historical scrollTo behaviour) ;
     * - [Anchor] → [TopicEffect.ScrollToAnchor] (revisit / #782 return / process restore) ;
     * - [Bottom] → [TopicEffect.ScrollToEndOfPage] (post-submit `#bas`, #412 `page - 1` step) ;
     * - [Top] → [TopicEffect.ScrollToTop] (default landing of a fresh page).
     */
    private sealed interface PendingLanding {
        data class Post(val numreponse: Int) : PendingLanding
        data class Anchor(val anchor: TopicScrollAnchor) : PendingLanding
        data object Bottom : PendingLanding
        data object Top : PendingLanding
    }

    /** #782 — one frame of the quote-jump return stack : the departure page + tap-time anchor. */
    private data class TopicJumpFrame(val page: Int, val anchor: TopicScrollAnchor?)

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
        // #895 F1 — the canonical current page survives process death : the SavedState page
        // (written by every switch and at init) or an already-resolved #750 page wins over the
        // route's initial page. A config change never reaches this code (the ViewModel survives).
        val canonicalPage = savedStateHandle.get<Int>(KEY_CURRENT_PAGE)
            ?: savedStateHandle.get<Int>(KEY_RESOLVED_PAGE)
        if (canonicalPage != null && canonicalPage != request.page) {
            request = request.copy(page = canonicalPage)
            _state.update { it.copy(request = request) }
        }
        // F3 — the current page's reading anchor is the one primitive pair persisted ; it seeds
        // the RAM anchor map so the restore landing (below) can resolve to it.
        savedStateHandle.get<Int>(KEY_ANCHOR_INDEX)?.let { index ->
            pageAnchors[request.page] =
                TopicScrollAnchor(index, savedStateHandle.get<Int>(KEY_ANCHOR_OFFSET) ?: 0)
        }
        // Sol points 4-5 — ENTRY intentions are consumable one-shots : an already-consumed
        // scrollTo never replays after process death ; an interrupted one resumes.
        val initialScrollTo = request.scrollTo
            ?.takeIf { savedStateHandle.get<Boolean>(KEY_SCROLL_TO_CONSUMED) != true }
        val untrustedPageTarget = request.resolveScrollToPage && initialScrollTo != null &&
            canonicalPage == null
        // Gate Sol PR1 (bloquant 1) — the canonical page is only persisted once it is TRUSTED :
        // never before an unresolved #750 probe, or a process death mid-resolution would freeze
        // the untrusted route page as canonical and the resolution would never replay.
        if (!untrustedPageTarget) {
            savedStateHandle[KEY_CURRENT_PAGE] = request.page
        }
        // The entry landing (armed by the chosen load path AFTER it takes ownership — arming
        // before the ownership bump would tag it with a stale generation).
        val entryLanding: PendingLanding? = when {
            initialScrollTo != null -> PendingLanding.Post(initialScrollTo)
            // Process restore : land back on the persisted reading position, if any.
            canonicalPage != null -> pageAnchors[request.page]?.let { PendingLanding.Anchor(it) }
            else -> null
        }
        val entryLandingIsScrollTo = initialScrollTo != null
        when {
            // #750 — email deep link: `page` is a lie (always 1), resolve the real one first.
            untrustedPageTarget -> resolveScrollToPageThenLoad(entryLanding, entryLandingIsScrollTo)
            else -> loadCurrentPage(entryLanding, entryLandingIsScrollTo)
        }
        // #220 — gate the write affordances on the live auth state, not just `canReply`.
        // A logged-out user may still hold a stale cached `canReply = true` row (the topic
        // cache is intentionally not purged on logout, cf. CacheInvalidator), so the gate
        // must consult auth explicitly to avoid opening a reply editor that can only fail
        // at submit. Symmetric with the « Créer topic » FAB (CategoryViewModel.canCreateTopic).
        authRepository.observeAuthState()
            .onEach { authState ->
                val connectedPseudo = (authState as? AuthState.Authenticated)?.pseudo
                if (!_state.value.connectedPseudo.equals(connectedPseudo, ignoreCase = true)) {
                    favoriteAuthGeneration++
                    favoriteResolveJob?.cancel()
                    favoriteAddJob?.cancel()
                    _favoriteAtPostState.value = FavoriteAtPostState.Unknown
                }
                _state.update {
                    it.copy(
                        isAuthenticated = authState is AuthState.Authenticated,
                        // #545 — carry the session pseudo for the ownership fallback (profiles
                        // with affichoutils=0 get no toolbar : isEditable/isOwnPost are blind).
                        connectedPseudo = connectedPseudo,
                    )
                }
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
        // #884 — mirror the full-width-posts preference so the screen can switch the post cards
        // between the inset card and an edge-to-edge layout without a refetch (rendered in a
        // later wave).
        userPreferencesRepository.observeTopicFullWidthPosts()
            .onEach { fullWidth ->
                _state.update { it.copy(fullWidthPosts = fullWidth) }
            }
            .launchIn(viewModelScope)
        // #874 Q4/P1 — render-only Ego switches. They remain independent so an own post can keep
        // its blue container while an auto-citation inside it independently keeps the purple one.
        userPreferencesRepository.observeTopicEgoQuoteEnabled()
            .onEach { enabled ->
                _state.update { it.copy(egoQuoteEnabled = enabled) }
            }
            .launchIn(viewModelScope)
        userPreferencesRepository.observeTopicEgoPostEnabled()
            .onEach { enabled ->
                _state.update { it.copy(egoPostEnabled = enabled) }
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

    @Suppress("CyclomaticComplexMethod") // MVI dispatcher : one branch per intent, no logic here —
    // splitting the when by domain would only scatter the single entry point (same rationale as the
    // class-level LargeClass suppress, #809/#879).
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
            is TopicIntent.SearchFromStartChanged ->
                _state.update { it.copy(search = it.search.copy(fromStart = intent.fromStart)) }
            TopicIntent.SubmitSearch -> submitSearch()
            TopicIntent.SearchNextResultsPage -> searchNextResultsPage()
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
     * authenticated (same justification as `performSubmitRefresh`).
     */
    private fun refresh() {
        val displayed = _state.value.mode as? TopicUiState.Mode.Loaded ?: return
        // #910 — during a cold-switch grace the DISPLAYED page is the departed one while the
        // canonical page is already the target : a pull here would refresh a page the user is
        // leaving (and fight the in-flight switch load). The switch resolves within the grace.
        if (displayed.topic.page != request.page || _state.value.isRefreshing) return
        becomePageOwner()
        _state.update { it.copy(isRefreshing = true) }
        // Gate Sol PR1 r2 (bloquant 1) — same ownership guard as every other async producer :
        // a reply landing after a page switch must never write over the new owner's page.
        val generation = ownerGeneration
        loadJob = viewModelScope.launch {
            try {
                val topic = topicRepository.refreshTopicPage(request.cat, request.post, request.page)
                if (generation != ownerGeneration) return@launch
                _state.update {
                    it.copy(
                        mode = loadedMode(topic),
                        availablePages = (1..topic.totalPages).toList(),
                        search = it.search.capturingAnchor(topic),
                    )
                }
                recordSnapshot(topic)
                // Re-arm the page+1 warmup, like `loadCurrentPage` (l. ~219). Unlike the post-submit
                // `performSubmitRefresh` (which deliberately skips it), a manual mid-page pull is
                // exactly when the user keeps reading forward, so re-warming page+1 restores the
                // prefetch benefit lost by the `prefetchedPage = null` reset above.
                maybeSchedulePrefetch(totalPages = topic.totalPages)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") refreshError: Exception) {
                // Cache-first: keep the page currently on screen and invite a retry via a Toast —
                // unless a newer owner took the page meanwhile (stale toast suppressed).
                android.util.Log.w(LOG_TAG, "Manual refresh failed", refreshError)
                if (generation == ownerGeneration) _effects.trySend(TopicEffect.RefreshFailed)
            } finally {
                // Gate Sol PR1 r3 (bloquant 3) — only the still-current owner clears its spinner :
                // a superseded refresh must not cut a NEWER refresh's indicator (the takeover
                // itself already reset the stale one in becomePageOwner).
                if (generation == ownerGeneration) {
                    _state.update { it.copy(isRefreshing = false) }
                }
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
     * ViewModel, a new topic nav entry builds a fresh one without the flag (in-topic page changes
     * keep this ViewModel — #895 étape 4).
     */
    private fun resolveScrollToPageThenLoad(
        entryLanding: PendingLanding?,
        entryLandingIsScrollTo: Boolean,
    ) {
        val scrollTo = requireNotNull(request.scrollTo) { "resolveScrollToPageThenLoad requires scrollTo" }
        _state.update { it.copy(mode = TopicUiState.Mode.Loading) }
        // Gate Sol PR1 r2 (bloquant 1) — a switch / submit arriving DURING the probe owns the
        // page : the late resolution must neither adopt its page nor restart the load.
        val generation = ownerGeneration
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
            if (generation != ownerGeneration) return@launch
            val resolved = outcome.getOrNull()
            if (resolved != null) {
                // Sol point 4 — #750 is consumed by STORING the resolved canonical page : a
                // process death after this point restores the real page and never re-probes ;
                // an interrupted probe (no stored page yet — gate Sol PR1 bloquant 1, the init
                // deliberately did NOT persist the untrusted route page) re-runs, never worse.
                savedStateHandle[KEY_RESOLVED_PAGE] = resolved
                savedStateHandle[KEY_CURRENT_PAGE] = resolved
                if (resolved != request.page) {
                    request = request.copy(page = resolved)
                    _state.update { it.copy(request = request) }
                }
            }
            // A FAILED probe deliberately persists nothing : the untrusted page loads as-is
            // (pre-#750 behaviour, never worse) but a later process restore re-probes — the
            // degraded outcome is not frozen as canonical. Any subsequent switch persists.
            loadCurrentPage(entryLanding, entryLandingIsScrollTo)
        }
    }

    private fun loadCurrentPage(
        entryLanding: PendingLanding? = null,
        entryLandingIsScrollTo: Boolean = false,
    ) {
        becomePageOwner()
        if (entryLanding != null) {
            armLanding(entryLanding, initialScrollTo = entryLandingIsScrollTo)
        }
        startPageLoad(showLoading = true)
    }

    /**
     * #895 étape 4 — the takeover prologue every path that (re)claims the page runs first : cancel
     * the in-flight search / form fetch / load / prefetch and bump [ownerGeneration] (via
     * [takeOverFromSearch]) so any late reply is dropped. Extracted so [internalSwitch] can take
     * ownership BEFORE arming its landing and activating a snapshot — the collect it then starts
     * must not bump the generation a second time.
     */
    private fun becomePageOwner() {
        takeOverFromSearch()
        loadJob?.cancel()
        prefetchJob?.cancel()
        prefetchedPage = null
        reclaimRefreshIndicator()
        // Gate Sol PR1 — a SAME-PAGE re-own (Retry, refresh, search takeover) keeps the
        // not-yet-dispatched landing alive by re-tagging it to the new generation (historical
        // scrollTo retry across Retry) ; a page change drops it — the switch paths re-arm
        // explicitly right after.
        pendingLanding = pendingLanding
            ?.takeIf { it.page == request.page }
            ?.copy(generation = ownerGeneration)
    }

    /**
     * Gate Sol PR1 r3/r4 — the pull-to-refresh spinner belongs to the OUTGOING owner : EVERY
     * takeover (page switch, normal load, AND a search claiming the page through [launchSearch])
     * resets it immediately ; the superseded refresh's finally (generation-guarded) then rightly
     * refuses to touch a newer owner's indicator. Without the search path a spinner could stay
     * stuck forever — `refresh()` guards on `isRefreshing`, blocking every future pull (gate r4).
     */
    private fun reclaimRefreshIndicator() {
        if (_state.value.isRefreshing) {
            _state.update { it.copy(isRefreshing = false) }
        }
    }

    /**
     * Gate Sol PR1 — the ONLY writer of [pendingLanding] besides [becomePageOwner]'s re-tag :
     * tags the landing with the CURRENT `(generation, page)` (callers arm AFTER taking ownership
     * and updating the canonical page) and persists the consumption of a superseded initial
     * scrollTo (any newer navigation supersedes the entry intention, Sol point 5).
     */
    private fun armLanding(landing: PendingLanding?, initialScrollTo: Boolean = false) {
        val previous = pendingLanding
        if (previous?.initialScrollTo == true) {
            savedStateHandle[KEY_SCROLL_TO_CONSUMED] = true
        }
        pendingLanding = landing?.let {
            ArmedLanding(it, ownerGeneration, request.page, initialScrollTo)
        }
    }

    /**
     * #895 étape 4 — shared cache-aside collection of the CURRENT canonical page. Callers run
     * [becomePageOwner] first. [showLoading] is `false` when the switch engine already activated a
     * memory snapshot : the collect then refreshes the page IN PLACE (provisional pill + hairline,
     * quick win 3) instead of flashing the skeleton — removing that flash is the point of #895.
     */
    private fun startPageLoad(showLoading: Boolean, graceLoading: Boolean = false) {
        beginFirstContentSection()
        if (showLoading) {
            _state.update { it.copy(mode = TopicUiState.Mode.Loading) }
        }
        // #231 made consumable (Sol point 4) : the TTL bypass applies until its first TERMINAL
        // fresh emission, then never replays across process death ; interrupted → resumes.
        val effectiveForceRefresh = request.forceRefresh &&
            savedStateHandle.get<Boolean>(KEY_FORCE_REFRESH_DONE) != true
        // F4 — belt over the job cancellation : a page switch bumps the owner generation, so an
        // emission raced from the previous owner can never write over the new page.
        val generation = ownerGeneration
        loadJob = viewModelScope.launch {
            // #910 — grace timer for a cold switch that kept the departed page on screen : post
            // the skeleton only if NO emission arrived within the grace. `received` (cadrage Sol :
            // the cancel alone cannot outrace a timer that already crossed its delay — both run
            // on the Main-confined scope, so the flag read/write cannot interleave) keeps a
            // late-firing timer from stomping a Loaded that just landed ; the generation guard
            // keeps a takeover's timer from stomping the next owner.
            var received = false
            val grace = if (graceLoading) {
                launch {
                    delay(SWITCH_GRACE_MS)
                    if (generation == ownerGeneration && !received) {
                        _state.update { it.copy(mode = TopicUiState.Mode.Loading) }
                    }
                }
            } else {
                null
            }
            topicRepository
                .observeTopicPage(request.cat, request.post, request.page, forceRefresh = effectiveForceRefresh)
                // #509 — the blacklist is owned by the independent observeBlockedCanonicals collector
                // (launched in init), NOT combined here: it has already seeded blockedCanonicals by the
                // time this load runs (its first emission is synchronous, documented contract), so the
                // initial hidden set below is correct and there is no double-source / clignotement
                // between this load and the live re-filter — the init collector is the sole re-filter.
                .catch { error ->
                    if (error is CancellationException) throw error
                    // #910 — a failed load must never leave a dangling grace (it would post a
                    // skeleton over the error state, or an endless hairline).
                    grace?.cancel()
                    terminalizeLoadFailure(error)
                }
                .collect { emission ->
                    // F4 — drop an emission raced from a previous owner (page switch / takeover).
                    if (generation != ownerGeneration) return@collect
                    // #910 — the target arrived within the grace : the skeleton never shows.
                    received = true
                    grace?.cancel()
                    val topic = emission.topic
                    _state.update {
                        it.copy(
                            // #877 — the provenance flag rides into Mode.Loaded so the top-bar
                            // pill can hold « Chargement… » through the cache emission and only
                            // show « page X / Y » once the page is settled (network / TTL skip /
                            // terminal after a failed refresh).
                            mode = loadedMode(topic, provisional = emission.provisional),
                            availablePages = (1..topic.totalPages).toList(),
                            // #894 — a REAL topic page refreshes the search session anchor
                            // (a transsearch response has no form anchor : no-op there).
                            search = it.search.capturingAnchor(topic),
                        )
                    }
                    // First content visible — close the async section. Subsequent emissions
                    // (stale-cache then refresh) already see `firstContentInFlight = false`
                    // and the helper short-circuits.
                    endFirstContentSectionIfNeeded()
                    if (!emission.provisional) {
                        // F2 — only TERMINAL emissions become memory snapshots ; a provisional
                        // page may be displayed but must never replace a terminal snapshot.
                        recordSnapshot(topic)
                        if (effectiveForceRefresh) {
                            savedStateHandle[KEY_FORCE_REFRESH_DONE] = true
                        }
                    }
                    dispatchPendingLanding(topic)
                    maybeSchedulePrefetch(totalPages = topic.totalPages)
                }
            // #910 gate r1 — a NORMAL completion with NO emission would otherwise leave either
            // the grace's skeleton or the provisional hold on screen forever (nobody cancels the
            // timer, nothing terminal ever lands).
            grace?.cancel()
            if (!received && generation == ownerGeneration) {
                terminalizeEmptyCompletion()
            }
        }
    }

    /**
     * #910 — terminal state of a load whose flow FAILED. Cache-first UX : a Loaded already on
     * screen OF THIS PAGE stays (the refresh failure is swallowed) ; anything else — grace hold
     * of the departed page, entry skeleton — surfaces Error (a durable « displayed ≠ canonical »
     * state is exactly what the #907 gates forbid, and Retry reloads the target).
     */
    private fun terminalizeLoadFailure(error: Throwable) {
        _state.update { current ->
            val displayed = current.mode as? TopicUiState.Mode.Loaded
            if (displayed != null && displayed.topic.page == request.page) {
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

    /**
     * #910 gate r1 — terminal state of a load whose flow completed NORMALLY with no emission :
     * an on-screen Loaded OF THIS PAGE stays (refresh flavour), an Error stays (a failed flow
     * also completes — the catch's typed Error must not be overwritten), anything else surfaces
     * a generic Error (Retry reloads the target).
     */
    private fun terminalizeEmptyCompletion() {
        _state.update { current ->
            val displayed = current.mode as? TopicUiState.Mode.Loaded
            when {
                current.mode is TopicUiState.Mode.Error -> current
                displayed != null && displayed.topic.page == request.page -> current
                else -> current.copy(
                    mode = TopicUiState.Mode.Error(
                        message = "Page load completed without content",
                        kind = HfrErrorKind.Other,
                    ),
                )
            }
        }
        endFirstContentSectionIfNeeded()
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

    /**
     * #895 étape 4 — dispatch the landing owed to the page on screen, once, on the first Loaded
     * of the owning generation. A [PendingLanding.Post] whose numreponse is not on [topic] stays
     * pending (the next emission of the SAME generation may contain it — the historical scrollTo
     * retry) ; every other landing dispatches unconditionally. A landing armed by a superseded
     * owner is dropped without effect.
     */
    private fun dispatchPendingLanding(topic: Topic) {
        // Gate Sol PR1 (bloquant 2) — refuse a stale pair : a dispatch reached from an untagged
        // path (same-page jump, snapshot activation) may run after a newer navigation replaced
        // the owner ; the armed landing knows who it belongs to.
        val armed = pendingLanding
            ?.takeIf { it.generation == ownerGeneration && it.page == request.page }
            ?: return
        val effect = when (val landing = armed.landing) {
            // A Post target absent from the page stays pending : the next emission of the same
            // owner may contain it (historical scrollTo retry) — hence the nullable effect.
            is PendingLanding.Post ->
                TopicEffect.ScrollToPost(landing.numreponse)
                    .takeIf { topic.posts.any { post -> post.numreponse == landing.numreponse } }
            is PendingLanding.Anchor -> TopicEffect.ScrollToAnchor(landing.anchor, armed.page)
            PendingLanding.Bottom -> TopicEffect.ScrollToEndOfPage(armed.page)
            PendingLanding.Top -> TopicEffect.ScrollToTop(armed.page)
        } ?: return
        // Gate Sol PR1 r2 (bloquant 2) — the validity check and the delivery must be ATOMIC : a
        // suspending `send` opens a window where a newer owner supersedes the landing while the
        // stale effect is still delivered on the new page. `trySend` never suspends (the channel
        // is BUFFERED), so on the Main-confined ViewModel nothing can interleave between the
        // `(generation, page)` check above and the delivery. A full buffer (never observed : the
        // screen collects eagerly) keeps the landing pending for the next emission.
        if (_effects.trySend(effect).isSuccess) {
            clearLanding()
        }
    }

    /**
     * Clear the pending landing ; when it was the INITIAL route `scrollTo`, persist the
     * consumption so a process death never replays the deep-link scroll (Sol point 5).
     */
    private fun clearLanding() {
        val armed = pendingLanding ?: return
        pendingLanding = null
        if (armed.initialScrollTo) {
            savedStateHandle[KEY_SCROLL_TO_CONSUMED] = true
        }
    }

    /**
     * F2 — record a TERMINAL emission of the CURRENT canonical page into the LRU snapshot map.
     * The page guard drops late replies raced from a previous page ; `transsearch` renders never
     * call this (a search view is not a canonical page).
     */
    private fun recordSnapshot(topic: Topic) {
        if (topic.page != request.page) return
        pageSnapshots[topic.page] = topic
    }

    // ─── #895 étape 4 — page switch engine (unbranched until the navigation PR) ──

    /**
     * #782/#895 étape 4 — mirror the jump chain's availability into state after EVERY mutation
     * (push / pop / clear), so the screen's `BackHandler(enabled = canReturnFromJump)` always
     * reflects the chain the next back gesture would unwind. StateFlow conflation makes the
     * no-change case free.
     */
    private fun syncJumpAvailability() {
        val available = jumpStack.isNotEmpty()
        _state.update { it.copy(canReturnFromJump = available) }
    }

    /**
     * F1/F3 — the screen reports the CURRENT page's reading anchor (on scroll settle / departure).
     * Feeds the RAM anchor map (revisit landings) and mirrors the primitives into the
     * [SavedStateHandle] so a process death restores the position of the page being read.
     */
    fun reportPageAnchor(anchor: TopicScrollAnchor) {
        pageAnchors[request.page] = anchor
        savedStateHandle[KEY_ANCHOR_INDEX] = anchor.index
        savedStateHandle[KEY_ANCHOR_OFFSET] = anchor.offset
    }

    /**
     * F1 — MANUAL page change (pager, ‹/› FABs, swipe, boundary cards, page picker). Clears the
     * #782 jump chain (browser-like, same rule as today's `onOpenPage`). Landing : saved anchor
     * of the target if any, else bottom on a strict « page - 1 » reading step (#412), else top.
     */
    fun switchToPage(target: Int, departureAnchor: TopicScrollAnchor? = null) {
        if (target == request.page || target < 1) return
        jumpStack.clear()
        syncJumpAvailability()
        internalSwitch(
            target = target,
            departureAnchor = departureAnchor,
            landing = null,
            bottomLandingEligible = target == request.page - 1,
        )
    }

    /**
     * #699/#782 — jump to a cited post : push the departure `{page, tap-time anchor}` on the jump
     * chain, switch when needed and land on [numreponse]. A same-page jump dispatches against the
     * page already on screen (no reload — the historical route replace rebuilt everything).
     */
    fun goToPost(targetPage: Int, numreponse: Int, departureAnchor: TopicScrollAnchor? = null) {
        if (jumpStack.size >= JUMP_STACK_MAX) jumpStack.removeFirst()
        jumpStack.addLast(TopicJumpFrame(request.page, departureAnchor))
        syncJumpAvailability()
        if (targetPage == request.page) {
            departureAnchor?.let { reportPageAnchor(it) }
            armLanding(PendingLanding.Post(numreponse))
            dispatchLandingAgainstScreen()
            return
        }
        internalSwitch(targetPage, departureAnchor, landing = PendingLanding.Post(numreponse))
    }

    /**
     * #782 — unwind ONE quote jump : land back on the departure page at the tap-time anchor.
     * Returns `false` when the chain is empty — the caller (screen back handling) then lets the
     * system back leave the topic. A return is never a « page - 1 » reading step (no bottom).
     */
    fun returnFromJump(departureAnchor: TopicScrollAnchor? = null): Boolean {
        val frame = jumpStack.removeLastOrNull() ?: return false
        syncJumpAvailability()
        val landing = frame.anchor?.let { PendingLanding.Anchor(it) }
        if (frame.page == request.page) {
            departureAnchor?.let { reportPageAnchor(it) }
            armLanding(
                landing
                    ?: pageAnchors[frame.page]?.let { PendingLanding.Anchor(it) }
                    ?: PendingLanding.Top,
            )
            dispatchLandingAgainstScreen()
        } else {
            internalSwitch(frame.page, departureAnchor, landing = landing)
        }
        return true
    }

    /**
     * Post-submit result delivered to THIS retained ViewModel (Sol GO, option A) : the editor /
     * quick-reply sheet publishes `{targetPage, scrollTo}` after a successful POST ; the target
     * falls back on the CANONICAL current page (never the route page). Arms the single #226
     * redirect budget, dirties the target snapshot and force-fetches it — landing `scrollTo`
     * (quote / edit) or bottom (`#bas`, plain reply).
     */
    fun applySubmitResult(targetPage: Int?, scrollTo: Int?) {
        postSubmitRedirectBudget = 1
        val target = targetPage ?: request.page
        // F2 — the submitted-to page is dirty : its snapshot must never serve again as terminal.
        pageSnapshots.remove(target)
        jumpStack.clear()
        syncJumpAvailability()
        performSubmitRefresh(target, scrollTo)
    }

    /**
     * F4 — the shared switch machinery : anchor saved, ownership taken (generation bump + cancels),
     * entry intentions superseded, canonical page updated, landing armed by priority, memory
     * snapshot activated atomically (no Loading) else skeleton, then the cache-aside collect.
     */
    private fun internalSwitch(
        target: Int,
        departureAnchor: TopicScrollAnchor?,
        landing: PendingLanding?,
        bottomLandingEligible: Boolean = false,
    ) {
        // F4 step 1 — save the departure anchor before anything else.
        departureAnchor?.let { pageAnchors[request.page] = it }
        // Steps 2-3 — new owner : generation bump + cancel search / form fetch / load / prefetch.
        becomePageOwner()
        // A navigation supersedes the #231 catch-up (entry intention of the ENTRY page) ; the
        // initial scrollTo supersession is handled by armLanding below (Sol points 4-5).
        savedStateHandle[KEY_FORCE_REFRESH_DONE] = true
        // Step 4 — the canonical current page (route stays untouched, F1).
        updateCanonicalPage(target)
        // Step 5 — landing by priority (F3) : explicit > saved anchor > page-1 bottom > top.
        // Armed AFTER the ownership bump and the page update, so it carries the right tags.
        armLanding(
            landing
                ?: pageAnchors[target]?.let { PendingLanding.Anchor(it) }
                ?: if (bottomLandingEligible) PendingLanding.Bottom else PendingLanding.Top,
        )
        // Steps 6-8 — activate the terminal memory snapshot atomically (blacklist recomputed
        // through loadedMode, F2) and refresh in place ; a miss keeps the CURRENT page on screen
        // under a grace timer (#910 stale-while-switching) — the skeleton only appears when the
        // target genuinely takes longer than the grace, or when there is nothing to keep showing.
        val snapshot = pageSnapshots[target]
        val displayed = _state.value.mode as? TopicUiState.Mode.Loaded
        when {
            snapshot != null -> {
                _state.update {
                    it.copy(
                        mode = loadedMode(snapshot),
                        availablePages = (1..snapshot.totalPages).toList(),
                        search = it.search.capturingAnchor(snapshot),
                    )
                }
                dispatchLandingAgainstScreen()
                startPageLoad(showLoading = false)
            }
            displayed != null -> {
                // #910 — the departed page stays visible, flagged provisional : the pill keeps
                // describing the displayed content (#877 rule) and the 2 dp hairline signals the
                // switch. A fast target (Room hit ~30 ms, quick network) swaps Loaded→Loaded
                // with zero flash ; the grace timer inside startPageLoad posts the skeleton
                // only if nothing arrived in time.
                _state.update { it.copy(mode = displayed.copy(provisional = true)) }
                startPageLoad(showLoading = false, graceLoading = true)
            }
            else -> startPageLoad(showLoading = true)
        }
    }

    /**
     * Dispatch the pending landing against the page already on screen (same-page / snapshot).
     * Synchronous since the r2 gate : [dispatchPendingLanding] no longer suspends, so there is no
     * untagged coroutine racing a newer navigation.
     */
    private fun dispatchLandingAgainstScreen() {
        val loaded = _state.value.mode as? TopicUiState.Mode.Loaded ?: return
        dispatchPendingLanding(loaded.topic)
    }

    /**
     * Sol point 3 — the post-submit force fetch of the retained ViewModel, #226 anti-chase
     * included : the freshly-created-last-page redirect happens INTERNALLY (one switch, budget
     * consumed BEFORE it) and its landing is terminal. The current content stays on screen while
     * the same page refreshes (zero-flash) ; switching to another page without a snapshot shows
     * the skeleton (its content is unknown).
     *
     * konsist:bypass-prefetch-guard — deliberate authenticated refetch following an explicit
     * submit result ; ownership taken via [becomePageOwner] (which cancels the anonymous warmup),
     * never an anonymous prefetch escalating to authenticated.
     */
    private fun performSubmitRefresh(initialTarget: Int, scrollTo: Int?) {
        becomePageOwner()
        savedStateHandle[KEY_FORCE_REFRESH_DONE] = true
        adoptSubmitTarget(initialTarget, scrollTo)
        val generation = ownerGeneration
        loadJob = viewModelScope.launch {
            // Gate Sol PR1 (récursion fragile → ITÉRATIF) : the #226 redirect continues INSIDE
            // this job under the SAME ownership — no self-cancelling re-own mid-coroutine.
            var target = initialTarget
            while (true) {
                try {
                    val topic = topicRepository.refreshTopicPage(request.cat, request.post, target)
                    if (generation != ownerGeneration) return@launch
                    _state.update {
                        it.copy(
                            mode = loadedMode(topic),
                            availablePages = (1..topic.totalPages).toList(),
                            search = it.search.capturingAnchor(topic),
                        )
                    }
                    // #226 — plain-reply overflow : the reply created a page past the target.
                    // Consume the single redirect budget and land on the real last page ; that
                    // landing can never redirect again (anti-chase), whatever a concurrent
                    // poster does.
                    if (scrollTo == null && topic.totalPages > target && postSubmitRedirectBudget > 0) {
                        postSubmitRedirectBudget = 0
                        pageSnapshots.remove(target)
                        pageSnapshots.remove(topic.totalPages)
                        target = topic.totalPages
                        adoptSubmitTarget(target, scrollTo = null)
                        continue
                    }
                    recordSnapshot(topic)
                    dispatchPendingLanding(topic)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (@Suppress("TooGenericExceptionCaught") refreshError: Exception) {
                    // Same contract as the historical post-submit failure : HFR accepted the POST,
                    // tell the user, drop the landing (no scroll on a stale page) and fall back to
                    // the cache-aside path with a Retry affordance.
                    android.util.Log.w(LOG_TAG, "Submit-result refresh failed", refreshError)
                    if (generation != ownerGeneration) return@launch
                    _effects.trySend(TopicEffect.PostSubmitRefreshFailed)
                    clearLanding()
                    startPageLoad(showLoading = _state.value.mode !is TopicUiState.Mode.Loaded)
                }
                return@launch
            }
        }
    }

    /**
     * Adopt [target] as the canonical submit-landing page : canonical page updated, content kept
     * on screen when it already shows something (zero-flash on the common same-page reply), the
     * skeleton only when the target's content is unknown, landing re-armed for the new page.
     */
    private fun adoptSubmitTarget(target: Int, scrollTo: Int?) {
        if (target != request.page) {
            updateCanonicalPage(target)
            // A cross-page submit landing shows the skeleton unless a snapshot can bridge the
            // fetch — the dirty eviction in [applySubmitResult] only removed the TARGET page.
            if (pageSnapshots[target] == null) {
                _state.update { it.copy(mode = TopicUiState.Mode.Loading) }
            }
        } else if (_state.value.mode !is TopicUiState.Mode.Loaded) {
            _state.update { it.copy(mode = TopicUiState.Mode.Loading) }
        }
        armLanding(scrollTo?.let { PendingLanding.Post(it) } ?: PendingLanding.Bottom)
    }

    /**
     * F1 — the single writer of the canonical current page outside init : `request`/state,
     * [KEY_CURRENT_PAGE], and the persisted anchor keys (they described the DEPARTED page).
     */
    private fun updateCanonicalPage(target: Int) {
        request = request.copy(page = target)
        savedStateHandle[KEY_CURRENT_PAGE] = target
        // Gate Sol PR1 r2 (réserve) — the persisted anchor must describe the NEW current page :
        // a known RAM anchor is copied immediately (a process death before the screen's next
        // reportPageAnchor would otherwise lose the restorable position) ; unknown → cleared.
        val known = pageAnchors[target]
        if (known != null) {
            savedStateHandle[KEY_ANCHOR_INDEX] = known.index
            savedStateHandle[KEY_ANCHOR_OFFSET] = known.offset
        } else {
            savedStateHandle.remove<Int>(KEY_ANCHOR_INDEX)
            savedStateHandle.remove<Int>(KEY_ANCHOR_OFFSET)
        }
        _state.update { it.copy(request = request) }
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
        val ownedPost = post != null &&
            (post.isEditable || isOwnPostBySession(post, _state.value.connectedPseudo))
        if (ownedPost && !isFirstPost && topic.canReply &&
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
                    _effects.trySend(TopicEffect.PostDeleted)
                    // A normal-post delete keeps the topic alive → refresh so the post vanishes. A
                    // whole-topic delete (first post) would 404 on reload, so we skip the refetch and
                    // leave the page; the UI never offers delete on the first post today.
                    if (!result.deletedWholeTopic) refreshAfterDelete()
                }
                is DeletePostResult.Failure ->
                    _effects.trySend(TopicEffect.PostDeleteFailed(result.reason.toDeleteFailureReason()))
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
     * carries the `konsist:bypass-prefetch-guard` marker (cf. `ArchitectureKonsistTest`).
     * The bypass is intentional: this is a deliberate authenticated refetch following an explicit
     * user-confirmed deletion, not an anonymous warmup escalating to authenticated.
     */
    private fun refreshAfterDelete() {
        becomePageOwner()
        // F2 — a deletion can shift the whole pagination : every memory snapshot is suspect.
        pageSnapshots.clear()
        _state.update { it.copy(mode = TopicUiState.Mode.Loading) }
        // Gate Sol PR1 r2 (bloquant 1) — ownership guard, like every other async producer.
        val generation = ownerGeneration
        loadJob = viewModelScope.launch {
            try {
                val topic = topicRepository.refreshTopicPage(request.cat, request.post, request.page)
                if (generation != ownerGeneration) return@launch
                _state.update {
                    it.copy(
                        mode = loadedMode(topic),
                        availablePages = (1..topic.totalPages).toList(),
                    )
                }
                recordSnapshot(topic)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") refreshError: Exception) {
                android.util.Log.w(LOG_TAG, "Post-delete refresh failed", refreshError)
                if (generation == ownerGeneration) loadCurrentPage()
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
                _effects.trySend(TopicEffect.TopicFlagNotFound)
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
                _effects.trySend(
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

    /**
     * #986 — refresh the topic-level HFR favourite state when an eligible post menu opens. The
     * repository performs a bounded-fresh category-scoped lookup; a failure stays visibly
     * unavailable and can never degrade to the destructive `Ready(false)` path.
     */
    fun resolveFavoriteAtPostState() {
        val state = _state.value
        val pseudo = state.connectedPseudo
        val interactionInProgress = _favoriteAtPostState.value is FavoriteAtPostState.Adding ||
            _favoriteAtPostState.value is FavoriteAtPostState.ConfirmingMove
        val eligiblePseudo = pseudo?.takeIf { state.isAuthenticated }
        val canResolve = state.mode is TopicUiState.Mode.Loaded && !interactionInProgress
        if (eligiblePseudo != null && canResolve) {
            val authGeneration = favoriteAuthGeneration
            favoriteResolveJob?.cancel()
            _favoriteAtPostState.value = FavoriteAtPostState.Resolving
            favoriteResolveJob = viewModelScope.launch {
                val result = runCatching {
                    flagRepository.resolveFavorite(cat = request.cat, topicId = request.post)
                }.getOrElse { raised ->
                    if (raised is CancellationException) throw raised
                    Result.failure(raised)
                }
                (result.exceptionOrNull() as? CancellationException)?.let { throw it }
                val stillOwned = authGeneration == favoriteAuthGeneration &&
                    _state.value.connectedPseudo.equals(eligiblePseudo, ignoreCase = true)
                if (stillOwned) {
                    _favoriteAtPostState.value = result.fold(
                        onSuccess = { FavoriteAtPostState.Ready(topicHasFavorite = it) },
                        onFailure = { FavoriteAtPostState.Unavailable },
                    )
                }
            }
        }
    }

    /**
     * #986 — first favourite adds directly; an existing favourite raises the confirmation state
     * because HFR will replace its unknown current position and offers no undo.
     */
    fun requestAddFavoriteAtPost(post: Post) {
        val ready = _favoriteAtPostState.value as? FavoriteAtPostState.Ready ?: return
        if (ready.topicHasFavorite) {
            _favoriteAtPostState.value = FavoriteAtPostState.ConfirmingMove(post)
        } else {
            startFavoriteAdd(post = post, topicHadFavorite = false)
        }
    }

    fun cancelMoveFavorite() {
        if (_favoriteAtPostState.value is FavoriteAtPostState.ConfirmingMove) {
            _favoriteAtPostState.value = FavoriteAtPostState.Ready(topicHasFavorite = true)
        }
    }

    fun confirmMoveFavorite() {
        val confirming = _favoriteAtPostState.value as? FavoriteAtPostState.ConfirmingMove ?: return
        startFavoriteAdd(post = confirming.post, topicHadFavorite = true)
    }

    /**
     * Captures the raw HFR tuple from the post currently displayed, then validates it and performs
     * the mutation inside the coroutine. [FlagAddContext] deliberately validates every field with
     * `require`; keeping its construction under [runCatching] prevents malformed cached/route data
     * from escaping synchronously through the Compose click handler.
     */
    private fun startFavoriteAdd(post: Post, topicHadFavorite: Boolean) {
        val request = favoriteAddRequest(post)
        if (request == null) {
            _favoriteAtPostState.value = FavoriteAtPostState.Ready(topicHadFavorite)
            return
        }

        val authGeneration = favoriteAuthGeneration
        _favoriteAtPostState.value = FavoriteAtPostState.Adding(topicHadFavorite)
        favoriteAddJob = viewModelScope.launch {
            try {
                val result = runCatching {
                    flagRepository.addFlag(request.toContext()).getOrThrow()
                }
                (result.exceptionOrNull() as? CancellationException)?.let { throw it }
                val stillOwned = authGeneration == favoriteAuthGeneration &&
                    _state.value.connectedPseudo.equals(request.pseudo, ignoreCase = true)
                if (stillOwned) {
                    _favoriteAtPostState.value = FavoriteAtPostState.Ready(
                        topicHasFavorite = result.isSuccess || topicHadFavorite,
                    )
                    _effects.trySend(
                        if (result.isSuccess) TopicEffect.PostFavoriteAdded else TopicEffect.PostFavoriteAddFailed,
                    )
                }
            } finally {
                if (
                    authGeneration == favoriteAuthGeneration &&
                    _favoriteAtPostState.value is FavoriteAtPostState.Adding
                ) {
                    _favoriteAtPostState.value = FavoriteAtPostState.Ready(topicHadFavorite)
                }
            }
        }
    }

    private data class FavoriteAddRequest(
        val pseudo: String,
        val cat: Int,
        val subcat: Int?,
        val topicId: Int,
        val page: Int,
        val numreponse: Int,
        val ref: Int,
    ) {
        fun toContext(): FlagAddContext = FlagAddContext(
            cat = cat,
            subcat = subcat,
            topicId = topicId,
            page = page,
            numreponse = numreponse,
            ref = ref,
        )
    }

    private fun favoriteAddRequest(post: Post): FavoriteAddRequest? {
        val state = _state.value
        val topic = (state.mode as? TopicUiState.Mode.Loaded)?.topic ?: return null
        val displayed = topic.posts.firstOrNull { it.numreponse == post.numreponse }
        return displayed?.quoteRef?.takeIf { it >= 1 }?.let { ref ->
            state.connectedPseudo?.takeIf { state.isAuthenticated }?.let { pseudo ->
                FavoriteAddRequest(
                    pseudo = pseudo,
                    cat = state.request.cat,
                    subcat = topic.subcat.takeIf { it >= 0 },
                    topicId = state.request.post,
                    // The displayed page wins during provisional switches and search.
                    page = topic.page,
                    numreponse = displayed.numreponse,
                    ref = ref,
                )
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
        // owner change bumps `ownerGeneration` (takeOverFromSearch / launchSearch), so a stale
        // form-fetch reply is dropped exactly like a stale transsearch reply (gate finding, #877).
        val fetchedFor = request
        val generation = ownerGeneration
        searchFormJob = viewModelScope.launch {
            runCatching {
                topicRepository.refreshTopicPage(fetchedFor.cat, fetchedFor.post, fetchedFor.page)
            }.onSuccess { fresh ->
                // Latest-wins : same page still on screen AND no newer owner took over.
                val stillCurrent = _state.value.mode is TopicUiState.Mode.Loaded &&
                    request == fetchedFor &&
                    generation == ownerGeneration
                if (stillCurrent) {
                    _state.update { state ->
                        state.copy(
                            mode = loadedMode(fresh),
                            availablePages = (1..fresh.totalPages).toList(),
                        )
                    }
                    // F2 — a fresh authenticated render of the current page is terminal : record it.
                    recordSnapshot(fresh)
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
        val current = _state.value
        val hadResults = current.search.status == TopicSearchStatus.Done
        // #913 (verdict Sol loupe/#910) — a search submitted during a page transition took over
        // the switch load ; closing WITHOUT results used to skip the reload, leaving the departed
        // page displayed while the canonical page is the target with NO load in flight — the
        // durable « displayed ≠ canonical » state the #907 gates forbid. Reload whenever the
        // displayed page is not the canonical one (a missing Loaded — skeleton — reloads too).
        val displayedPage = (current.mode as? TopicUiState.Mode.Loaded)?.topic?.page
        val mustReload = hadResults || displayedPage != request.page
        _state.update {
            it.copy(search = it.search.copy(isActive = false, status = TopicSearchStatus.Idle))
        }
        // loadCurrentPage already cancels searchJob + bumps the generation (takeOverFromSearch).
        // Reload on results (Done) OR on a displayed/canonical mismatch ; only the aligned
        // no-result close skips it — then drop the in-flight search ourselves so a late reply
        // can't write.
        if (mustReload) loadCurrentPage() else takeOverFromSearch()
    }

    /**
     * Cancel any in-flight intra-topic search and bump [ownerGeneration] so a `transsearch` reply
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
        ownerGeneration++
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
                    // #879/#894 — a normal load owns the page again : the filtered-results footer,
                    // its resume cursor and the frozen criteria are stale, reset them (transverse
                    // risk : state de résultats mal remis). `sessionAnchor` deliberately SURVIVES —
                    // it describes the page being read, and the incoming load refreshes it.
                    showingFilteredResults = false,
                    resumeCursor = null,
                    resultWord = "",
                    resultSpseudo = "",
                    resultAnchor = null,
                ),
            )
        }
    }

    /**
     * #894 — refresh [TopicSearchUiState.sessionAnchor] from a rendered REAL topic page. A
     * `transsearch` response ships its form without `firstnum`, so it can never overwrite the
     * anchor of the page the user was actually reading — exactly the intended no-op.
     */
    private fun TopicSearchUiState.capturingAnchor(topic: Topic): TopicSearchUiState =
        topic.searchForm?.firstnum?.let { copy(sessionAnchor = it) } ?: this

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
        // #894 — resolve the anchor of a FRESH search : « depuis le début » sends an explicit 0,
        // the default sends the anchor of the page the search starts from — the on-screen form's
        // `firstnum` (a real topic page) or, from a results page (whose form carries none), the
        // frozen session anchor. A default-mode submit with NO anchor available must fail
        // explicitly (same recovery as a missing form) — silently omitting `firstnum` would run a
        // whole-topic search the user did not ask for (cadrage F1).
        val anchor = if (current.search.fromStart) 0 else form?.firstnum ?: current.search.sessionAnchor
        if (form == null || !form.canSearch || anchor == null) {
            if (current.search.canSubmit) {
                // Gate r5 — SearchFailed is a GUARANTEED functional effect : own coroutine whose
                // suspending send is its terminal (and only) operation ; the form re-fetch below
                // runs independently and never races the delivery.
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
            // Fresh search : no cursor (HFR re-anchors on the first match at-or-after the anchor).
            anchor = anchor,
        )
        if (!current.search.canSubmit || !request.isMeaningful) return
        resetSearchCursors()
        launchSearch(request, isFresh = true)
    }

    /**
     * #894 — fetch the NEXT batch of a FILTERED result list (« Résultats suivants » footer, web
     * parity) : HFR's scan window truncated and its response advertised a resume cursor. The
     * continuation re-POSTs the FROZEN criteria with `currentnum = resumeCursor` and NO anchor
     * (cadrage F3) ; latest-wins via the same generation token as every search. The form is
     * re-read from the page on screen (a transsearch reply carries its own form).
     */
    private fun searchNextResultsPage() {
        val current = _state.value
        val form = (current.mode as? TopicUiState.Mode.Loaded)?.topic?.searchForm?.takeIf { it.canSearch }
        // One combined gate : a batch announced (hasMore ⇒ cursor non-null) + a usable form on the
        // rendered result page.
        val cursor = current.search.resumeCursor?.takeIf { current.search.hasMoreFilteredResults }
        if (cursor == null || form == null) return
        val request = TopicSearchRequest(
            form = form,
            // Gate #879 finding 1 — the cursor belongs to the SUBMITTED search : the next batch is
            // fetched with the frozen criteria, whatever the (editable) bar currently shows.
            word = current.search.resultWord,
            spseudo = current.search.resultSpseudo,
            onlyMatches = true,
            currentNum = cursor.toString(),
        )
        if (!request.isMeaningful) return
        launchSearch(request, isFresh = false)
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
            // #894 (cadrage F5) — the replay to the FIRST result re-issues the fresh search with
            // the FROZEN criteria and the FROZEN anchor of the displayed search session : the
            // on-screen response form carries no anchor, and the editable bar may have changed.
            TopicSearchRequest(
                form = form,
                word = current.search.resultWord,
                spseudo = current.search.resultSpseudo,
                onlyMatches = false,
                anchor = current.search.resultAnchor,
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

    /**
     * Chantier B (#546) — a next/previous STEP request : cursor set, NO anchor (re-sending one
     * re-anchors HFR on the first match). #894 (cadrage F5) — steps re-submit the FROZEN criteria
     * of the displayed search, never the live editable bar.
     */
    private fun stepRequest(
        form: TopicSearchForm,
        search: TopicSearchUiState,
        cursor: Int,
    ): TopicSearchRequest = TopicSearchRequest(
        form = form,
        word = search.resultWord,
        spseudo = search.resultSpseudo,
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
        // Gate r4 (bloquant 1) — a search takeover supersedes an in-flight pull-to-refresh too.
        reclaimRefreshIndicator()
        val generation = ++ownerGeneration
        _state.update { it.copy(search = it.search.copy(status = TopicSearchStatus.Loading)) }
        searchJob = viewModelScope.launch {
            try {
                val topic = topicSearchRepository.searchInTopic(request)
                // Drop a stale reply: a newer normal load / refresh / search bumped the generation.
                if (generation != ownerGeneration) return@launch
                applySearchResult(
                    topic,
                    isFresh = isFresh,
                    rewindToIndex = rewindToIndex,
                    submitted = request,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (noResults: NoTopicSearchResultsException) {
                android.util.Log.i(LOG_TAG, "Intra-topic search returned no result", noResults)
                if (generation != ownerGeneration) return@launch
                if (request.onlyMatches && !isFresh) {
                    // #894 — an empty FILTERED CONTINUATION (matches deleted since the previous
                    // batch advertised the cursor) is the END of the results, never « Aucun
                    // résultat » (cadrage F6) : keep the displayed batch, drop the cursor.
                    _state.update {
                        it.copy(
                            search = it.search.copy(
                                status = TopicSearchStatus.Done,
                                resumeCursor = null,
                            ),
                        )
                    }
                    return@launch
                }
                // Same dispatch as a parsed reply with no usable landing (fresh→no results,
                // rewind→replay failure ≠ end, forward→end of results).
                signalNoLanding(isFresh, rewindToIndex)
            } catch (@Suppress("TooGenericExceptionCaught") searchError: Exception) {
                android.util.Log.w(LOG_TAG, "Intra-topic search failed", searchError)
                if (generation != ownerGeneration) return@launch
                // The current page is still on screen — just mark the search as failed so the user
                // can retry or close it. Status FIRST : the suspending send must be the terminal
                // operation (gate r4 — no vulnerable write after a suspension).
                _state.update { it.copy(search = it.search.copy(status = TopicSearchStatus.Error)) }
                _effects.send(TopicEffect.SearchFailed)
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
        // #894 — the request this reply answers : its criteria are frozen into the state (gate
        // #879 finding 1 — the footer/steps must never mix new bar content with old results), its
        // `onlyMatches` snapshot dispatches the branch (never the live toggle, which the user may
        // have flipped mid-flight — Codex review), and its cursor drives the anti-loop guard.
        submitted: TopicSearchRequest,
    ) {
        if (submitted.onlyMatches) {
            // #894 — the filtered page is ONE batch of the result list. HFR advertises a further
            // batch through the response form's `currentnum` (truncated scan) ; a COMPLETE list
            // carries none. Anti-loop guard (cadrage F3) : a continuation cursor that did not
            // STRICTLY advance past the one we sent would re-serve the same batch forever — treat
            // it as the end instead.
            val sentCursor = submitted.currentNum?.toIntOrNull()
            val resumeCursor = topic.searchForm?.currentNum
                ?.takeIf { sentCursor == null || it > sentCursor }
            renderSearchPage(
                topic,
                TopicSearchStatus.Done,
                canPrev = false,
                canNext = false,
                filteredResumeCursor = resumeCursor,
                showingFiltered = true,
                submitted = submitted,
            )
            // Gate #879 finding 2 — the list content was replaced in place : reposition at the top
            // so the first results of this batch are visible (fresh AND continuation alike).
            _effects.send(TopicEffect.ScrollToTopOfResults)
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
            // #894 (cadrage F5) — freeze the criteria + anchor for the steps and the backward
            // replay, which must never read the live editable bar.
            submitted = submitted,
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
    @Suppress("LongParameterList") // single render seam of the search state : one param per facet.
    private fun renderSearchPage(
        topic: Topic,
        status: TopicSearchStatus,
        canPrev: Boolean,
        canNext: Boolean,
        // #894 — resume cursor advertised by a FILTERED response (null = complete list).
        filteredResumeCursor: Int? = null,
        // #894 — `true` ONLY for a filtered render (the batch replaces the list on screen).
        showingFiltered: Boolean = false,
        // #879 finding 1 + #894 F5 — the request this render answers : criteria + anchor frozen
        // with the results they produced, for the continuation / steps / backward replay.
        submitted: TopicSearchRequest? = null,
    ) {
        _state.update {
            it.copy(
                mode = loadedMode(topic),
                search = it.search.copy(
                    status = status,
                    canGoPreviousResult = canPrev,
                    canGoNextResult = canNext,
                    showingFilteredResults = showingFiltered,
                    resumeCursor = filteredResumeCursor,
                    resultWord = submitted?.word ?: "",
                    resultSpseudo = submitted?.spseudo ?: "",
                    // A step/continuation carries no anchor : keep the one frozen by the fresh
                    // submit — the backward replay re-anchors on it.
                    resultAnchor = submitted?.anchor ?: it.search.resultAnchor,
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

        // ─── #895 étape 4 — page engine ──────────────────────────────────────────

        // F2 — LRU bound : « toutes les pages est une fuite lente ; 3 est trop agressif pour les
        // allers-retours et citations » (cadrage Sol). 5 terminal pages ≈ a reading window of
        // two back-and-forths plus a quote jump.
        private const val MAX_PAGE_SNAPSHOTS = 5
        private const val SNAPSHOTS_INITIAL_CAPACITY = 8
        private const val SNAPSHOTS_LOAD_FACTOR = 0.75f

        // #782 — same cap as the historical `:app` TopicJumpStack (TOPIC_JUMP_STACK_MAX).
        private const val JUMP_STACK_MAX = 8

        /**
         * #910 — grace before a cold page switch shows the skeleton : the departed page stays on
         * screen (provisional) and a target arriving within the grace swaps Loaded→Loaded with no
         * flash. ~15 frames at 60 Hz — long enough for a Room hit or a fast network round-trip,
         * short enough that a genuinely slow load still gets its skeleton promptly (cadrage Sol :
         * initial default, to be tuned by feel/telemetry).
         */
        private const val SWITCH_GRACE_MS = 250L

        // SavedStateHandle keys (F1/F3 + Sol points 4-5) — primitives only, never a Kotlin map.
        private const val KEY_CURRENT_PAGE = "topic.currentPage"
        private const val KEY_RESOLVED_PAGE = "topic.resolvedPage"
        private const val KEY_ANCHOR_INDEX = "topic.anchorIndex"
        private const val KEY_ANCHOR_OFFSET = "topic.anchorOffset"
        private const val KEY_SCROLL_TO_CONSUMED = "topic.scrollToConsumed"
        private const val KEY_FORCE_REFRESH_DONE = "topic.forceRefreshDone"
    }
}
