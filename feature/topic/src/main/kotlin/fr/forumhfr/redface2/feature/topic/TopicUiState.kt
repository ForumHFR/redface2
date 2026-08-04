package fr.forumhfr.redface2.feature.topic

import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.model.editor.WritingSurfacePreset

data class TopicUiState(
    val request: TopicRequest,
    val mode: Mode,
    val availablePages: List<Int>,
    /**
     * #220 — whether the current HFR session is authenticated. Drives the write
     * affordances (Répondre / Citer / Modifier / Modifier-FP) so they are not offered
     * to a logged-out user, symmetric with the « Créer topic » FAB
     * (`CategoryViewModel.canCreateTopic`). Defaults `false` and flips on the first
     * auth emission. The conservative default means an already-authenticated user may
     * see a brief disabled→enabled transient on a cold open (we never momentarily
     * *offer* a write action to a not-yet-known session) — same trade-off as the FAB;
     * in practice the cookie jar is primed at nav-host start so the window rarely shows.
     */
    val isAuthenticated: Boolean = false,
    /**
     * #545 — pseudo of the authenticated session, `null` while anonymous. Feeds the ownership
     * fallback [isOwnPostBySession] : profiles with `affichoutils=0` get no post toolbar from
     * HFR, so `Post.isEditable`/`Post.isOwnPost` are blind there and the gates need the session
     * pseudo to recognise the user's own posts by author instead.
     */
    val connectedPseudo: String? = null,
    /**
     * #292 — `numreponse` of the post whose deletion is currently in flight, or `null` when no
     * delete is running. Drives the per-post « Supprimer » affordance (disabled / busy) and guards
     * against a double-submit. Cleared when the delete settles (success or failure).
     */
    val deletingNumreponse: Int? = null,
    /**
     * Build 89 follow-up — mirrors `UserPreferencesRepository.observeTopicTopBarAutoHide()`.
     * When `true`, the screen wires a Material3 `enterAlways` scroll behaviour on the top app
     * bar (title + page counter) so it collapses while scrolling down through the posts and
     * re-appears on the first upward scroll. Default `false` keeps the bar pinned (the prior,
     * always-visible behaviour). Flips on the first preference emission.
     */
    val topBarAutoHide: Boolean = false,
    /**
     * #383 — mirrors `UserPreferencesRepository.observeTopicPageFabs()`. When `false`, the
     * floating ‹/› page mini-FABs (#283) are hidden — the page swipe (#282) and the header
     * pager still cover page-change. The « Répondre » FAB is not governed by this flag.
     * Default `true` keeps the historical cluster until the first preference emission.
     */
    val showPageFabs: Boolean = true,
    /**
     * #456 — mirrors `UserPreferencesRepository.observeTopicPollsExpanded()`. Seeds the poll
     * card's initial revealed state; the in-card « afficher / masquer » toggle stays per-topic.
     * Default `false`: polls start collapsed.
     */
    val pollsExpandedDefault: Boolean = false,
    /**
     * #330 — mirrors `UserPreferencesRepository.observeTopicSignatures()`. When `true`, each post
     * card renders the author's signature (`Post.signature`) beneath the body, in a subdued style
     * separated by a divider. Default `false`: signatures are noisy and opt-in. Flips on the first
     * preference emission; the field is always parsed/cached so toggling never refetches.
     */
    val showSignatures: Boolean = false,
    /**
     * #884 — mirrors `UserPreferencesRepository.observeTopicFullWidthPosts()`. When `true`, the
     * post cards render edge-to-edge (full width, without the card inset). Default `false` keeps
     * the historical inset card. Flips on the first preference emission; pure render-time switch
     * (consumed by the screen in a later wave), toggling never refetches.
     */
    val fullWidthPosts: Boolean = false,
    /**
     * #806 — mirrors `UserPreferencesRepository.observeWritingSurfacePreset()`. Feeds
     * [writingSurfaceFor] AT TAP TIME on the three write entry points (reply FAB, « Citer »,
     * « Citer N ») to pick the quick-reply sheet or the full-screen editor. Default
     * [WritingSurfacePreset.FULL_EDITOR] since the sheet is experimental opt-in (#951). A preset
     * change never migrates an already-open sheet (the decision is only ever taken on the next tap).
     */
    val writingSurfacePreset: WritingSurfacePreset = WritingSurfacePreset.FULL_EDITOR,
    /**
     * #335 — `true` while a manual pull-to-refresh of the current page is in flight. Drives the
     * Material3 `PullToRefreshBox` spinner. Set on the `Refresh` intent, cleared in the refresh
     * coroutine's `finally` (so a cancellation — e.g. a delete starting mid-refresh — never leaves
     * the indicator stuck).
     */
    val isRefreshing: Boolean = false,
    /**
     * Chantier C (#546) — intra-topic search (HFR `transsearch.php`), a MODE of this screen. Holds
     * the search bar visibility, the typed criteria and the search lifecycle. The matching topic
     * page returned by HFR is surfaced through [Mode.Loaded] like any other page (`transsearch`
     * answers a topic page), so there is no separate "results list" model here.
     */
    val search: TopicSearchUiState = TopicSearchUiState(),
    /**
     * #782 / #895 étape 4 — `true` while the in-VM quote-jump chain is non-empty, i.e. the next
     * back gesture should unwind one jump ([TopicViewModel.returnFromJump]) instead of leaving
     * the topic. Drives the screen's `BackHandler(enabled = …)` — the interception moved from
     * `:app` (route-replace era) into the screen, next to the ViewModel that owns the chain.
     * Kept in lock-step with every jump-stack mutation (push / pop / clear).
     */
    val canReturnFromJump: Boolean = false,
) {
    /**
     * Helper used by the screen / ViewModel : `true` when the user has navigated to a
     * page > 1, i.e. when "Previous" should be enabled. Mirrors the symmetric helper
     * `canGoNext()` below.
     */
    val canGoPrevious: Boolean get() = request.page > 1

    val canGoNext: Boolean
        get() = when (mode) {
            is Mode.Loaded -> request.page < mode.topic.totalPages
            else -> request.page < (availablePages.lastOrNull() ?: 1)
        }

    sealed interface Mode {
        data object Loading : Mode

        data class Loaded(
            val topic: Topic,
            /**
             * #509 — `numreponse` of the posts whose author is blacklisted, computed from the
             * blacklist combined with `topic.posts` so it is always coherent with [topic]. The screen
             * renders these as a collapsed "post masqué" placeholder (never removed from the list, so
             * pagination/anchors/`numreponse` keys stay intact). Empty = nothing hidden. Gated into the
             * first emission (combine with the blacklist) so a blocked post never flashes before hiding.
             */
            val hiddenNumreponses: Set<Int> = emptySet(),
            /**
             * #785 — canonical pseudos (cf. `canonicalizePseudo`) of the black-listed authors — the
             * same blacklist snapshot [hiddenNumreponses] was computed from. The screen provides it
             * to the post renderers (`LocalBlockedQuoteAuthors`) so a citation OF a blocked author
             * inside another user's post is masked too. Kept in lock-step with [hiddenNumreponses]
             * by `TopicViewModel.loadedMode` — the single construction seam for this mode — so the
             * post-level and quote-level masks can never diverge across the load / refresh /
             * force-refresh / post-delete / search / live-refilter paths.
             */
            val blockedQuoteAuthors: Set<String> = emptySet(),
            /**
             * #877 — `true` while this page is the instant cache emission that a network refresh
             * will supersede on the same load (cf. `TopicPageEmission.provisional`). The posts
             * render normally (cache-first snappiness) but the top-bar pill keeps « Chargement… »
             * instead of a possibly stale « page X / Y ». The repository guarantees a terminal
             * `provisional = false` emission on every path (network page, TTL skip, failed
             * refresh), so this can never strand the pill.
             */
            val provisional: Boolean = false,
        ) : Mode

        data class Error(
            val message: String,
            /**
             * #324 — coarse classification (HFR 5xx / coupure réseau / autre) derived from
             * the exception TYPE by `classifyHfrError`. The screen swaps the raw [message]
             * for the shared `:core:ui` string on [HfrErrorKind.ServerDown] /
             * [HfrErrorKind.Network]; [HfrErrorKind.Other] keeps rendering [message].
             */
            val kind: HfrErrorKind = HfrErrorKind.Other,
        ) : Mode
    }

    /**
     * `true` when the loaded topic page exposes a usable intra-topic search form (authenticated,
     * non-empty `hash_check`). The form is transient (never cached) — a cache emission carries
     * none, which is why the ICON affordance is no longer gated on it (cf. [canOpenSearch], #877) :
     * this gate keeps protecting the actual POST paths (submit / step).
     */
    val canSearchInTopic: Boolean
        get() = (mode as? Mode.Loaded)?.topic?.searchForm?.canSearch == true

    /**
     * #877 — search ICON affordance : authenticated + a page on screen. Deliberately decoupled
     * from the transient [canSearchInTopic] : cache emissions (and the TTL-skip path, which never
     * refetches) carry no `searchForm`, and gating the icon on it made the Loupe vanish between
     * pages — deterministic on a fresh authenticated cache, perceived as intermittent. Opening
     * the bar without a form triggers a fresh form fetch (`TopicViewModel.ensureSearchForm`) ;
     * a submit that still has no form fails explicitly (Toast), never silently.
     */
    val canOpenSearch: Boolean
        get() = isAuthenticated && mode is Mode.Loaded

    companion object {
        fun initial(request: TopicRequest): TopicUiState =
            TopicUiState(
                request = request,
                mode = Mode.Loading,
                availablePages = emptyList(),
            )
    }
}

/**
 * Chantier C (#546) — UI state for the intra-topic search mode.
 *
 * @property isActive whether the search bar is open. When `false`, [word] / [spseudo] are kept so
 *   re-opening restores the last criteria, but no search is in effect.
 * @property word the term field (HFR `word`).
 * @property spseudo the author field (HFR `spseudo`).
 * @property onlyMatches the « Filtrer » toggle — HFR's `filter` checkbox (only show matching posts).
 * @property status the search lifecycle. Idle before the first submit ; Loading while the POST is in
 *   flight ; Done once a `transsearch` page has been loaded into [TopicUiState.Mode.Loaded] (the
 *   page itself carries the matches) ; NoResults when HFR found nothing ; Error on failure.
 * @property canGoPreviousResult whether a « previous result » step is available (Chantier B / #546).
 *   Mirrors the ViewModel's client-side cursor history (HFR's search is forward-only) : `true` once the
 *   user has navigated past the first match. Only meaningful in non-filtered mode (`!onlyMatches`).
 * @property canGoNextResult whether a « next result » step is available. `true` while HFR has not yet
 *   reported the end of results. Only meaningful in non-filtered mode (`!onlyMatches`).
 */
data class TopicSearchUiState(
    val isActive: Boolean = false,
    val word: String = "",
    val spseudo: String = "",
    val onlyMatches: Boolean = true,
    /**
     * #894 — the « Chercher depuis le début » opt-in : a fresh submit sends `firstnum=0` (whole
     * topic) instead of the session anchor (HFR's default « from the current page onwards »).
     * EPHEMERAL by design (cadrage F4) : plain bar state, no persisted preference.
     */
    val fromStart: Boolean = false,
    /**
     * #894 — the search anchor of the topic page the user is READING : the form `firstnum` of the
     * last REAL topic page rendered (a transsearch response carries none, so it never overwrites
     * this). A fresh default-mode submit sends it ; a fresh submit from a results page reuses it
     * (the on-screen response form has no anchor of its own).
     */
    val sessionAnchor: Int? = null,
    val status: TopicSearchStatus = TopicSearchStatus.Idle,
    val canGoPreviousResult: Boolean = false,
    val canGoNextResult: Boolean = false,
    /**
     * #879 — `true` while the page ON SCREEN is a FILTERED transsearch result list. Set by the
     * filtered render, cleared whenever a normal-load path takes the page back
     * (`takeOverFromSearch`). Gates the search-results footer (« résultats suivants ») and
     * SUPPRESSES the canonical PageBoundary/EndOfTopic cards, whose `onOpenPage` would silently
     * leave the search.
     */
    val showingFilteredResults: Boolean = false,
    /**
     * #894 — resume cursor of the displayed FILTERED result list. HFR truncates its scan window
     * (~200 matches observed) : a truncated response advertises the resume point in its form's
     * `currentnum` (⇒ « Résultats suivants » available), a COMPLETE response carries none.
     * `null` = no further batch. The continuation re-submits the FROZEN criteria below with
     * `currentnum = resumeCursor` (and NO anchor) ; the next batch REPLACES the list (web parity).
     */
    val resumeCursor: Int? = null,
    /**
     * #879 (gate finding 1) + #894 — the criteria the displayed results were actually SUBMITTED
     * with. The continuation, the non-filtered steps and the backward replay re-submit THESE,
     * never the live editable fields : editing the bar after a render can never fetch « the next
     * batch of a different search ». [resultAnchor] is the `firstnum` actually sent (`0` when
     * « depuis le début » was checked) — the backward replay re-anchors on it, a response form
     * carrying no anchor of its own.
     */
    val resultWord: String = "",
    val resultSpseudo: String = "",
    val resultAnchor: Int? = null,
) {
    /**
     * #894 — a further batch of filtered results is reachable. Deliberately CURSOR-based only
     * (gate #879 finding 3, carried over) : during Loading the footer is simply hidden by the
     * screen, and after a failed continuation the cursor is untouched — the card stays and
     * doubles as the retry affordance. `EndOfSearchResultsCard` is only truthful on
     * `Done && !hasMore`.
     */
    val hasMoreFilteredResults: Boolean
        get() = showingFilteredResults && resumeCursor != null

    /** HFR needs at least a term or an author ; the submit button is disabled otherwise. */
    val canSubmit: Boolean get() = word.isNotBlank() || spseudo.isNotBlank()
}

enum class TopicSearchStatus {
    Idle,
    Loading,
    Done,

    /** Chantier B (#546) — the search round-trip succeeded but HFR found no matching message. */
    NoResults,
    Error,
}

sealed interface TopicIntent {
    data object Retry : TopicIntent

    /**
     * #335 — manual pull-to-refresh of the currently open page. Re-fetches over the network and
     * updates the loaded page in place, without the post-submit overflow redirect (#226) or any
     * scroll effect, so the user keeps their reading position.
     */
    data object Refresh : TopicIntent

    /** #879 — filtered search : fetch the next page of the result list (footer card). */
    data object SearchNextResultsPage : TopicIntent

    /**
     * #292 — confirmed deletion of one of the user's own (normal) posts. The screen shows a
     * confirmation dialog first; this intent is only sent once the user confirms. [numreponse]
     * identifies the post to delete (unique per category).
     */
    data class DeletePost(val numreponse: Int) : TopicIntent

    /**
     * #509 — blacklist (or un-blacklist) [author] from the post menu. [blocked] = true blocks (their
     * posts collapse to the « masqué » placeholder), false unblocks. The ViewModel delegates to
     * `BlacklistRepository`; the topic re-filters live through the page combine.
     */
    data class SetAuthorBlocked(val author: String, val blocked: Boolean) : TopicIntent

    /**
     * #809 — a long-press on the top-bar title requests removing THIS topic's drapeau. Carries no
     * payload : the ViewModel already knows the topic from its [TopicRequest] and resolves the full
     * [fr.forumhfr.redface2.core.model.Flag] through `FlagRepository.findFlag` before confirming.
     */
    data object RequestRemoveTopicFlag : TopicIntent

    // ─── intra-topic search (#546) ───────────────────────────────────────────────

    /** Open the search bar. No-op if the loaded page has no usable search form. */
    data object OpenSearch : TopicIntent

    /**
     * Close the search bar AND clear any active search by reloading the normal current page. Keeps
     * the typed criteria so re-opening restores them.
     */
    data object CloseSearch : TopicIntent

    data class SearchWordChanged(val word: String) : TopicIntent

    data class SearchPseudoChanged(val pseudo: String) : TopicIntent

    data class SearchOnlyMatchesChanged(val onlyMatches: Boolean) : TopicIntent

    /** #894 — toggle « Chercher depuis le début » (fresh submits send `firstnum=0`). Ephemeral. */
    data class SearchFromStartChanged(val fromStart: Boolean) : TopicIntent

    /** Submit the intra-topic search (`POST transsearch.php`). */
    data object SubmitSearch : TopicIntent

    /**
     * Chantier B (#546) — jump to the NEXT search result (non-filtered mode). Steps HFR's
     * `currentnum` cursor forward and scrolls to the new match. No-op past the last result.
     */
    data object NextResult : TopicIntent

    /**
     * Chantier B (#546) — jump to the PREVIOUS search result (non-filtered mode). HFR's search is
     * forward-only, so this replays the client-side cursor history. No-op on the first result.
     */
    data object PrevResult : TopicIntent
}

/**
 * #292 — UI-facing classification of a delete failure, mapped from the domain `ReplyFailureReason`
 * so the screen can pick a specific message without depending on the write model directly.
 */
enum class DeleteFailureReason {
    /** Session not (or no longer) authenticated — surface a login CTA. */
    LoginRequired,

    /** The topic was closed/locked, so HFR refuses the deletion. */
    TopicLocked,

    /** Any other refusal (invalid token, unrecognised response, network) — generic message. */
    Generic,
}

/**
 * One-shot side effects produced by [TopicViewModel]. The screen consumes these via
 * `LaunchedEffect(Unit)` collecting from the effects channel — exactly once per
 * effect, never replayed across recompositions or process death (intentional : if
 * the user scrolled away, replaying a scroll on rotation would steal focus).
 */
sealed interface TopicEffect {
    /**
     * Ask the screen to scroll to a specific `numreponse` once the topic page is
     * rendered. The ViewModel only emits this when the post is present in the loaded
     * page, so the screen can blindly trust `numreponse` and resolve the index from
     * the current `Topic.posts` list.
     */
    data class ScrollToPost(val numreponse: Int) : TopicEffect

    /**
     * #879 (gate finding 2) — a NEW page of filtered search results replaced the list content in
     * place : without an explicit reposition the LazyListState keeps page N's end offset and the
     * first results of page N+1 open off-screen. Sent on every filtered render (fresh + next).
     */
    data object ScrollToTopOfResults : TopicEffect

    /**
     * Issue #200 — emitted after a plain reply submit when HFR's success URL anchors
     * `#bas` instead of `#t{numreponse}`. The screen scrolls to the last post on the
     * (force-refreshed) page so the user can see their freshly-published reply at the
     * bottom. Distinct from [ScrollToPost] because we don't know the new numreponse
     * — the parser couldn't extract it from the `#bas` fragment.
     *
     * Gate #895 r3 — [page] scopes the landing : the screen re-validates it against the CURRENT
     * `state.request.page` and DROPS a stale effect (a buffered landing consumed after a page
     * switch must never scroll the new page — the ViewModel's atomicity cannot cover the channel
     * and the UI consumer). [ScrollToPost] needs no scope : its numreponse lives on exactly one
     * page, so a stale one simply resolves to « absent » on the new page.
     */
    data class ScrollToEndOfPage(val page: Int) : TopicEffect

    /**
     * #895 (étape 4) — land back on a previously visited page at the exact reading position the
     * user left it (raw `LazyListState` primitives, cf. [TopicScrollAnchor]). Emitted by the
     * in-ViewModel page engine when a page switch resolves its landing to a saved anchor
     * (revisit / #782 jump return). Unwired until the navigation switch-over (PR 2) : the
     * route-replace paths never emit it. [page] : same stale-drop contract as [ScrollToEndOfPage].
     */
    data class ScrollToAnchor(val anchor: TopicScrollAnchor, val page: Int) : TopicEffect

    /**
     * #895 (étape 4) — land at the top of a freshly-switched page (no scrollTo, no saved anchor,
     * not a `page - 1` reading step). The explicit default landing of the in-ViewModel page
     * engine — without it a page switch inside one entry would keep the previous page's scroll
     * offset (the entry, and its `LazyListState`, now survive the switch). [page] : same
     * stale-drop contract as [ScrollToEndOfPage].
     */
    data class ScrollToTop(val page: Int) : TopicEffect

    /**
     * Issue #200 — emitted when the post-submit force refresh (`refreshTopicPage`) fails.
     * HFR has already accepted the post (the editor only emits its `SubmitSucceeded`
     * effect on a `ReplySubmitResult.Success`), but the local refetch could not land,
     * so the screen falls back to the stale cache. The screen surfaces a Toast (see
     * `TopicScreen.kt`) telling the user that the post is published but the local view
     * may not reflect it yet — pull-to-refresh fixes that. Without this effect, the
     * user lands on a stale page that does not contain their fresh post and assumes
     * the submit silently failed.
     */
    data object PostSubmitRefreshFailed : TopicEffect

    /**
     * #335 — emitted when a manual pull-to-refresh (`Refresh` intent) failed to reach HFR. The
     * current page stays on screen (cache-first); the screen surfaces a Toast inviting a retry.
     */
    data object RefreshFailed : TopicEffect

    /**
     * #292 — emitted after a post was successfully deleted. The screen surfaces a confirmation
     * toast; the ViewModel separately force-refreshes the current page so the removed post
     * disappears (unless the deletion removed the whole topic, an out-of-scope path today).
     */
    data object PostDeleted : TopicEffect

    /**
     * #292 — emitted when HFR refused the deletion. The screen surfaces a [reason]-specific toast
     * and leaves the post in place.
     */
    data class PostDeleteFailed(val reason: DeleteFailureReason) : TopicEffect

    /**
     * Chantier C (#546) — emitted when the intra-topic search (`transsearch.php`) failed to reach
     * HFR or returned an unparsable page. The current page stays on screen ; the screen surfaces a
     * Toast inviting a retry.
     */
    data object SearchFailed : TopicEffect

    /**
     * Chantier B (#546) — emitted when a « next result » step ran past the last match (HFR returned a
     * sentinel cursor / a no-result page). The current match stays on screen ; the screen surfaces a
     * sober Toast (« Aucun résultat suivant ») and the next arrow disables.
     */
    data object SearchResultsEnd : TopicEffect

    // ─── #809 — one-shot outcomes of the title long-press flag removal. They ride THIS channel
    // (the screen's single effects collector + Toast surface, like PostDeleted) rather than a
    // parallel consumable StateFlow — one one-shot mechanism per screen (review finding).

    /** #809 — `delflag.php` confirmed the removal ; the Drapeaux caches are already reconciled. */
    data object TopicFlagRemoved : TopicEffect

    /** #809 — the removal failed (refused, transport, session) ; nothing was touched. */
    data object TopicFlagRemovalFailed : TopicEffect

    /**
     * #986 — a favourite was placed on a specific post position (`addflag.php` anchors on
     * `numreponse`/`page`/`ref`, not on the topic). One-shot feedback; HFR offers no undo, so the
     * message must not promise one.
     */
    data object PostFavoriteAdded : TopicEffect

    /** #986 — the favourite could not be placed (network, session, or unrecognised HFR page). */
    data object PostFavoriteAddFailed : TopicEffect

    /**
     * #809 — the long-press resolved to no removable drapeau : topic not flagged, anonymous
     * session, or an unresolvable lookup (resolve failure folds here — cf. TopicViewModel).
     */
    data object TopicFlagNotFound : TopicEffect
}

/**
 * #809 — drives the « Retirer le drapeau » long-press interaction on the topic top bar. MVI-style
 * explicit state so the confirmation gates the network call. Mirrors FlagsViewModel's
 * `RemoveFlagState`, plus a [Resolving] step the Drapeaux view never needs : that screen already
 * holds the [Flag], whereas the topic screen must first resolve it through `FlagRepository.findFlag`
 * (which may fan out the network on a cold cache).
 *
 * - [Idle] — nothing pending.
 * - [Resolving] — the long-press fired ; the flag lookup is in flight. Blocks a second long-press.
 * - [Confirming] — a drapeau was found ; the screen shows the confirmation dialog ([flag] feeds its
 *   title). Absent this state, no dialog.
 * - [Removing] — the user confirmed ; the `delflag.php` call is in flight (anti double-tap).
 */
sealed interface RemoveTopicFlagState {
    data object Idle : RemoveTopicFlagState
    data object Resolving : RemoveTopicFlagState
    data class Confirming(val flag: Flag) : RemoveTopicFlagState
    data class Removing(val flag: Flag) : RemoveTopicFlagState
}

