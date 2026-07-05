package fr.forumhfr.redface2.feature.topic

import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
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
     * #806 — mirrors `UserPreferencesRepository.observeWritingSurfacePreset()`. Feeds
     * [writingSurfaceFor] AT TAP TIME on the three write entry points (reply FAB, « Citer »,
     * « Citer N ») to pick the quick-reply sheet or the full-screen editor. Default
     * [WritingSurfacePreset.SHEET] = the 0.25.1 behaviour. A preset change never migrates an
     * already-open sheet (the decision is only ever taken on the next tap).
     */
    val writingSurfacePreset: WritingSurfacePreset = WritingSurfacePreset.SHEET,
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
     * Helper used by the screen : `true` when the loaded topic page exposes a usable intra-topic
     * search form (authenticated, non-empty `hash_check`). Drives the search icon affordance,
     * symmetric with the reply gate. The form is transient (never cached), so a cold cache row
     * keeps search disabled until a live authenticated load.
     */
    val canSearchInTopic: Boolean
        get() = (mode as? Mode.Loaded)?.topic?.searchForm?.canSearch == true

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
    val status: TopicSearchStatus = TopicSearchStatus.Idle,
    val canGoPreviousResult: Boolean = false,
    val canGoNextResult: Boolean = false,
) {
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
     * Issue #200 — emitted after a plain reply submit when HFR's success URL anchors
     * `#bas` instead of `#t{numreponse}`. The screen scrolls to the last post on the
     * (force-refreshed) page so the user can see their freshly-published reply at the
     * bottom. Distinct from [ScrollToPost] because we don't know the new numreponse
     * — the parser couldn't extract it from the `#bas` fragment.
     */
    data object ScrollToEndOfPage : TopicEffect

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
     * Issue #226 — emitted after a plain-reply submit when the reply overflowed the topic onto a
     * newly-created page but HFR's success URL anchored the OLD page (the one the form was on). The
     * ViewModel detects this in `forceRefreshCurrentPage`: the force-refreshed page reports a
     * `totalPages` greater than `request.page` while `scrollTo` is null (plain reply — quote/edit
     * carry a `#t{N}` scrollTo and are excluded). The navigation host re-routes to [page] (= the new
     * `totalPages`) with `scrollTo = null`, a **fresh `submitSignal`** AND
     * `postSubmitOverflowLanding = true` (cf. `TopicRequest`). The fresh `submitSignal` makes the new
     * ViewModel force-fetch that last page — never a stale cache-aside row — and the landing flag
     * makes it emit [ScrollToEndOfPage] (surfacing the freshly-published post) **without** re-emitting
     * `NavigateToLastPage`: if a concurrent post bumped `totalPages` further during the refresh, the
     * flag breaks the moving-tail chase. Defensive: works whether HFR anchored the old page (the bug)
     * or the new one.
     */
    data class NavigateToLastPage(val page: Int) : TopicEffect

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
}
