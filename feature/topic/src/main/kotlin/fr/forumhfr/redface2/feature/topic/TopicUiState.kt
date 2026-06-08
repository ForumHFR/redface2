package fr.forumhfr.redface2.feature.topic

import fr.forumhfr.redface2.core.model.Topic

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
        ) : Mode

        data class Error(
            val message: String,
        ) : Mode
    }

    companion object {
        fun initial(request: TopicRequest): TopicUiState =
            TopicUiState(
                request = request,
                mode = Mode.Loading,
                availablePages = emptyList(),
            )
    }
}

sealed interface TopicIntent {
    data object Retry : TopicIntent

    /**
     * #292 — confirmed deletion of one of the user's own (normal) posts. The screen shows a
     * confirmation dialog first; this intent is only sent once the user confirms. [numreponse]
     * identifies the post to delete (unique per category).
     */
    data class DeletePost(val numreponse: Int) : TopicIntent
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
}
