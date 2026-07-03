package fr.forumhfr.redface2.feature.topic

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.domain.author.isRf2Creator
import fr.forumhfr.redface2.core.model.Poll
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.ui.RedfacePlaceholderScreen
import fr.forumhfr.redface2.core.ui.error.sharedLabelResOrNull
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon
import fr.forumhfr.redface2.core.ui.pager.pageSwipeEdgeHint
import fr.forumhfr.redface2.core.ui.post.PostCardShell
import fr.forumhfr.redface2.core.ui.post.PostIdentityBand
import fr.forumhfr.redface2.core.ui.post.PostIdentityHeader
import fr.forumhfr.redface2.core.ui.post.PostListScaffold
import fr.forumhfr.redface2.core.ui.post.PostRenderer
import fr.forumhfr.redface2.core.ui.theme.LocalDisplayMetrics
import fr.forumhfr.redface2.core.ui.theme.LocalIgnoreInlineColors
import fr.forumhfr.redface2.core.ui.theme.rememberCreatorPseudoBrush
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

@Composable
// LongParameterList : state-hoisted Composable : each callback has a distinct call-site
// (reply / quote / edit-post / edit-FP / openPage) and bundling them in a callbacks holder would
// hide the navigation surface rather than simplify it.
// CyclomaticComplexMethod : the one-shot effects `when` (scroll / refresh / delete / search) is a
// flat dispatch table — every branch is a distinct, independent side effect ; splitting it would
// scatter one logical sink (same stance as TopicContent / TopicLoadedContent).
@Suppress("LongParameterList", "CyclomaticComplexMethod")
fun TopicScreen(
    request: TopicRequest,
    /**
     * Open the reply editor for this topic. The lambda receives the topic's
     * sub-category id (parsed from the loaded page) and the current page number ;
     * cat and topicId are derived from [request]. Phase 2C-A only invokes this
     * callback when the topic carries a valid `subcat` (otherwise the reply button
     * stays disabled to avoid passing a sentinel value to the HFR write contract).
     */
    onReply: (subcat: Int, page: Int) -> Unit,
    /**
     * Open the editor in quote mode (Phase 2C, #146). Same destination as [onReply],
     * but the editor GETs HFR's quote form and hydrates the draft with the
     * `[quotemsg=…]` block HFR prefills. The call-site supplies
     * `quotedNumreponse = post.numreponse` (always known) and `quoteRef = post.quoteRef`
     * (forwarded when known, may be `null`). HFR identifies the cited post by
     * `numrep={numreponse}` alone — `ref` is positional/optional (#227, proven live;
     * `HfrClient.getReplyForm` omits `&ref=` when null) — so « Citer » is gated on
     * `Topic.canReply` (cf. the per-post gate below), never on the presence of a
     * parsed quote link. Obfuscated/cached rows with `quoteRef = null` are supported.
     */
    onQuote: (subcat: Int, page: Int, quotedNumreponse: Int, quoteRef: Int?) -> Unit,
    /**
     * Open the editor in edit mode (Phase 2D, #147). HFR exposes the edit link on
     * the post's left toolbar only when the post belongs to the current user and
     * the topic is not locked — `TopicPageParser` translates that into
     * `Post.isEditable = true`. The call-site supplies `numreponse = post.numreponse`
     * and the topic-wide `(subcat, page)`. Posts whose toolbar did not carry an
     * edit link keep the « Modifier » button hidden — we never reach this
     * callback for those.
     */
    onEdit: (subcat: Int, page: Int, numreponse: Int) -> Unit,
    /**
     * Open the topic-level editor for the first post (Phase 2D #148). Only
     * invoked when (a) we are on page 1, (b) `Topic.isFirstPostOwner == true`
     * (parsed from the FP toolbar's edit link), (c) the topic carries a valid
     * `subcat`. Receives `(subcat, page, numreponse)` of the FIRST post — never
     * the topic id.
     */
    onEditFirstPost: (subcat: Int, page: Int, numreponse: Int) -> Unit,
    onOpenPage: (Int) -> Unit,
    /**
     * #699 — jump to the post a quote header cites: `(page, numreponse)` parsed from the citation
     * href. `:app` wires it to the same in-place `TopicRoute` replace as [onOpenPage], but with
     * `scrollTo = numreponse` so the landing scrolls to and highlights the cited post (the #200
     * deep-link mechanism) — one uniform path whether the target is on this page or another.
     */
    onGoToPost: (page: Int, numreponse: Int) -> Unit,
    /**
     * #285 — leave the topic and go back to the screen that opened it (topic list / flags).
     * Wired to a back-stack pop in `:app`. Surfaced as an explicit back arrow in the top app
     * bar so the user never has to rely on the system / gesture back to exit a topic.
     */
    onBack: () -> Unit,
    /**
     * #226 — after a plain reply that overflowed onto a freshly created page, re-route to that
     * last page : the post the user just published lives there, not on the page the reply form
     * was anchored to. `:app` replaces the current TopicRoute in place with the target page.
     */
    onNavigateToLastPage: (page: Int) -> Unit,
    /**
     * Bug fix (build 89) — reports the loaded topic title up to `:app` so it can keep a per-topic
     * title cache. On a page change the screen is recreated and starts in `Loading`; `:app` feeds
     * the cached title back via [TopicRequest.titleHint] so the top app bar no longer flashes the
     * generic « Sujet » fallback between pages.
     */
    onTitleLoaded: (String) -> Unit = {},
    /**
     * Phase 2 finish (#208) — emitted when the user taps on a post avatar or author name.
     * Carries the numeric user id (canonical key for profile navigation) plus display hints
     * [pseudo] and [avatarUrl] that `:app` can show immediately while the profile loads.
     *
     * Only emitted when [Post.profileId] is non-null — posts that don't carry a HFR profile
     * link (e.g. « Publicité » rows, anonymous reads) never invoke this callback.
     *
     * `:feature:topic` does not depend on `:feature:profile` — the hoist lives in `:app`
     * (cf. `docs/specs/architecture.md` § Frontière feature:topic ↔ feature:profile).
     */
    onOpenProfile: (userId: Int, pseudo: String, avatarUrl: String?) -> Unit = { _, _, _ -> },
    /**
     * #307 — saved read position to restore for THIS `(cat, post, page)` landing, or `null` when
     * nothing should be restored. `:app` resolves the full priority chain
     * (`resolveTopicScrollRestoration`: route `scrollTo` > post-submit landing > saved anchor >
     * previous-page bottom (#412) > top) BEFORE threading the value here, so a non-null anchor
     * already means « the saved position won »
     * — the screen applies it once the first `Loaded` emission lands, exactly once per landing, and
     * it can never compete with the `ScrollToPost` / `ScrollToEndOfPage` effects (their routes
     * resolve to `null` here).
     */
    restoreScrollAnchor: TopicScrollAnchor? = null,
    /**
     * #412 — `true` when this landing is a « page précédente » navigation with no saved anchor
     * (resolved by `:app`, mutually exclusive with a non-null [restoreScrollAnchor]): once the
     * first `Loaded` emission lands, scroll to the LAST item of the page — reading backwards,
     * the next posts to read are at the bottom (HFR web's `#bas` landing). One-shot, same
     * contract as the anchor restore.
     */
    startAtBottom: Boolean = false,
    /**
     * #412 — invoked once the bottom landing for this page has been executed (or skipped on an
     * empty page), right after the first `Loaded` emission. `:app` uses it to clear the transient
     * « page précédente » marker so the landing can never replay on a later visit to the same page
     * (the marker is nav state, not a route field — cf. Codex review on PR #420).
     */
    onStartAtBottomConsumed: () -> Unit = {},
    /**
     * #307 — reports the read position when the screen leaves the composition, so `:app` can cache
     * it per `(cat, post, page)` (twin of [onTitleLoaded] / the title cache). Fired from a single
     * `DisposableEffect` — the unique save point covering EVERY departure (swipe, FAB, header pager,
     * back, tab switch) — and only after the page actually loaded, so a landing abandoned while
     * still `Loading` never clobbers a previously saved position with `(0, 0)`.
     */
    onScrollAnchorSaved: (TopicScrollAnchor) -> Unit = {},
    /**
     * #291 — numreponses currently selected for multi-quote in THIS topic, in selection order.
     * Owned by `:app` (the basket must survive the per-page entry swap, like the title cache);
     * the screen only renders the count and the per-post toggle state.
     */
    multiQuoteSelection: List<Int> = emptyList(),
    /**
     * #291 — toggles a post in the multi-quote basket. Only invoked under the same gate as
     * [onQuote] (`shouldShowQuoteAction`): a topic the user cannot reply to has nothing to quote.
     */
    onToggleMultiQuote: (numreponse: Int) -> Unit = {},
    /**
     * #291 — opens the editor pre-filled with every selected quote (same destination as
     * [onQuote]; `:app` rides the selection on the route and clears the basket). Receives the
     * topic's `(subcat, page)` like [onReply].
     */
    onMultiQuote: (subcat: Int, page: Int) -> Unit = { _, _ -> },
    /**
     * #465 — the user's MANUAL poll-expansion choice for THIS topic, owned by `:app` so it survives
     * the per-page TopicRoute swap (like the multi-quote basket / scroll anchors). `null` means « no
     * manual choice yet — follow the [TopicUiState.pollsExpandedDefault] setting »; `true` / `false`
     * mean the user explicitly expanded / collapsed the poll. The screen only renders it.
     */
    pollManualExpanded: Boolean? = null,
    /**
     * #465 — records a tap on the poll card (the resulting revealed state), so `:app` caches the
     * manual choice per `(cat, post)`. The next page of the same topic reads it back through
     * [pollManualExpanded], keeping the poll collapsed / expanded across page navigation.
     */
    onPollExpansionChanged: (Boolean) -> Unit = {},
    /**
     * #518 follow-up — `true` when `:app` wants this screen to report its scroll facts for the
     * immersive nav-bar reveal (immersive on AND a scroll-driven mode selected). When `false` the
     * reporter is a no-op and clears any stale facts. `:feature:topic` stays free of the reveal-mode
     * enum: it only reports raw `(atBottom, scrollingUp)` facts; `:app` applies the policy.
     */
    immersiveNavBarRevealActive: Boolean = false,
    /**
     * #518 follow-up — reports the topic's raw scroll facts UP so `:app` (single owner of the window
     * nav bar) can reveal the hidden bar per the chosen mode. Only fires while
     * [immersiveNavBarRevealActive] and only on a change.
     */
    onImmersiveNavBarScroll: (atBottom: Boolean, scrollingUp: Boolean) -> Unit = { _, _ -> },
) {
    val viewModel = hiltViewModel<TopicViewModel, TopicViewModel.Factory>(
        creationCallback = { factory -> factory.create(request) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lazyListState = rememberLazyListState()
    // #518 follow-up — report scroll facts up so `:app` can reveal the hidden system nav bar per the
    // chosen mode. No-op (and clears stale facts) when the feature is inactive.
    ImmersiveNavBarScrollReporter(
        listState = lazyListState,
        active = immersiveNavBarRevealActive,
        onScrollFacts = onImmersiveNavBarScroll,
    )
    val context = androidx.compose.ui.platform.LocalContext.current
    // Resolve the string at composition time, not inside the LaunchedEffect collect block.
    // Lint flags `context.getString(R.string.…)` inside a Compose call site (the call is in a
    // suspending lambda but the surrounding scope is still a Composable). Capturing the message
    // upfront keeps the rule happy and avoids re-resolving on every effect.
    val refreshFailedMsg = stringResource(R.string.topic_post_submit_refresh_failed)
    // #335 — manual pull-to-refresh failure message (resolved upfront, same rationale).
    val refreshManualFailedMsg = stringResource(R.string.topic_refresh_failed)
    // Chantier C (#546) — intra-topic search failure message (resolved upfront, same rationale).
    val searchFailedMsg = stringResource(R.string.topic_search_failed)
    // Chantier B (#546) — « no further result » Toast (resolved upfront, same rationale).
    val searchResultsEndMsg = stringResource(R.string.topic_search_results_end)
    // #292 — delete feedback messages, resolved upfront (same rationale as refreshFailedMsg).
    val deleteSuccessMsg = stringResource(R.string.topic_post_delete_success)
    val deleteFailedLoginMsg = stringResource(R.string.topic_post_delete_failed_login)
    val deleteFailedLockedMsg = stringResource(R.string.topic_post_delete_failed_locked)
    val deleteFailedGenericMsg = stringResource(R.string.topic_post_delete_failed_generic)
    // #292 — `numreponse` awaiting delete confirmation (null = no dialog). Local UI state: the
    // confirmation is a pure view concern, only the confirmed deletion reaches the ViewModel.
    var deleteCandidate by rememberSaveable { mutableStateOf<Int?>(null) }

    // Bug fix (build 89) — report the loaded title up so `:app` caches it per topic. The next page
    // (recreated screen) reads it back through `request.titleHint`, keeping the top bar title stable
    // instead of flashing « Sujet » during the load.
    val loadedTitle = (state.mode as? TopicUiState.Mode.Loaded)?.topic?.title
    LaunchedEffect(loadedTitle) {
        loadedTitle?.takeIf { it.isNotBlank() }?.let(onTitleLoaded)
    }

    // #307 — one-shot restore of the saved read position + the single central save point,
    // extracted to its own effect holder (also keeps TopicScreen under the detekt complexity cap).
    TopicScrollRestorationEffects(
        state = viewModel.state,
        lazyListState = lazyListState,
        request = request,
        restoreScrollAnchor = restoreScrollAnchor,
        startAtBottom = startAtBottom,
        onStartAtBottomConsumed = onStartAtBottomConsumed,
        onScrollAnchorSaved = onScrollAnchorSaved,
    )

    // Single-shot scroll : `effects` emits `ScrollToPost` exactly once per request,
    // when the ViewModel has loaded a page that contains the requested numreponse.
    // Once consumed, the user can scroll freely without the deep link snapping back.
    //
    // The `LaunchedEffect` lives here (next to `viewModel`) instead of inside
    // `TopicContent` because it must read the latest [TopicUiState] from the
    // [StateFlow], not the recomposition-captured `state` parameter. Reading the
    // captured `state` would race : the ViewModel always updates the state before
    // it sends the effect, but `collectAsStateWithLifecycle` may not have surfaced
    // the new value to the composition by the time the effect lands. Pulling
    // straight from `viewModel.state` and waiting for `Loaded` makes the invariant
    // impossible to break.
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is TopicEffect.ScrollToPost -> {
                    val loadedMode = viewModel.state.first { it.mode is TopicUiState.Mode.Loaded }.mode
                            as TopicUiState.Mode.Loaded
                    val index = loadedMode.topic.posts.indexOfFirst { it.numreponse == effect.numreponse }
                    if (index >= 0) {
                        // +1 because the LazyColumn header card occupies item 0.
                        val target = index + 1
                        lazyListState.scrollToItem(target)
                        // #197 — block images above the target grow from 160dp to up to 480dp once
                        // Coil decodes them, shifting the offset *after* this one-shot scroll and
                        // leaving the target off-screen on a cold image cache. Keep it pinned while
                        // the layout settles (bails on user scroll, bounded by a frame budget).
                        lazyListState.reanchorWhileMediaSettles(target)
                    }
                }
                TopicEffect.ScrollToEndOfPage -> {
                    // Issue #200 — post-reply landing : HFR anchored `#bas`, the parser couldn't
                    // extract a numreponse, so we land on the last item of the freshly-refreshed
                    // page. The new post is by definition the last one HFR served on this page.
                    val loadedMode = viewModel.state.first { it.mode is TopicUiState.Mode.Loaded }.mode
                            as TopicUiState.Mode.Loaded
                    if (loadedMode.topic.posts.isNotEmpty()) {
                        // +1 for the header card (same offset rationale as ScrollToPost above).
                        lazyListState.scrollToItem(loadedMode.topic.posts.size)
                    }
                }
                TopicEffect.PostSubmitRefreshFailed -> {
                    // Issue #200 — HFR accepted the post but the local force refresh failed.
                    // Surface a Toast so the user knows the submit went through and can
                    // re-trigger the refresh manually (pull-to-refresh / Retry) instead of
                    // assuming the post was silently lost. Toast picked over Snackbar to keep
                    // this composable a plain Surface — wrapping the existing TopicContent in
                    // a Scaffold + SnackbarHost is left for a follow-up if a richer feedback
                    // surface is needed.
                    android.widget.Toast.makeText(
                        context,
                        refreshFailedMsg,
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                TopicEffect.RefreshFailed -> {
                    // #335 — manual pull-to-refresh could not reach HFR; the page stays on screen
                    // (cache-first) and the Toast invites a retry.
                    android.widget.Toast.makeText(
                        context,
                        refreshManualFailedMsg,
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                is TopicEffect.NavigateToLastPage -> {
                    // #226 — the plain reply overflowed onto a freshly created last page. Hand the
                    // target page to `:app`, which replaces the current TopicRoute in place so the
                    // user lands on the page that actually holds their new post (the ViewModel for
                    // that route then anchors #bas → ScrollToEndOfPage as usual).
                    onNavigateToLastPage(effect.page)
                }
                TopicEffect.PostDeleted -> {
                    // #292 — HFR accepted the deletion; the ViewModel force-refreshes the page so the
                    // post is already gone by the time this lands. Confirm with a Toast.
                    android.widget.Toast.makeText(
                        context,
                        deleteSuccessMsg,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                is TopicEffect.PostDeleteFailed -> {
                    val message = when (effect.reason) {
                        DeleteFailureReason.LoginRequired -> deleteFailedLoginMsg
                        DeleteFailureReason.TopicLocked -> deleteFailedLockedMsg
                        DeleteFailureReason.Generic -> deleteFailedGenericMsg
                    }
                    android.widget.Toast.makeText(
                        context,
                        message,
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                TopicEffect.SearchFailed -> {
                    // Chantier C (#546) — the transsearch POST failed; the normal page is restored by
                    // the ViewModel and the Toast invites a retry.
                    android.widget.Toast.makeText(
                        context,
                        searchFailedMsg,
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                TopicEffect.SearchResultsEnd -> {
                    // Chantier B (#546) — a « next » step ran past the last match; the current match
                    // stays on screen and a sober Toast confirms there is nothing further.
                    android.widget.Toast.makeText(
                        context,
                        searchResultsEndMsg,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    TopicContent(
        state = state,
        listState = lazyListState,
        onIntent = viewModel::send,
        onBack = onBack,
        onReply = onReply,
        onQuote = onQuote,
        onEdit = onEdit,
        onEditFirstPost = onEditFirstPost,
        onOpenPage = onOpenPage,
        onGoToPost = onGoToPost,
        onOpenProfile = onOpenProfile,
        onDeleteRequest = { numreponse -> deleteCandidate = numreponse },
        multiQuoteSelection = multiQuoteSelection,
        onToggleMultiQuote = onToggleMultiQuote,
        onMultiQuote = onMultiQuote,
        pollManualExpanded = pollManualExpanded,
        onPollExpansionChanged = onPollExpansionChanged,
    )

    // #292 — confirmation before the (irreversible, no-undo) deletion. Only « Supprimer » sends the
    // intent; dismissing leaves the post untouched. Mirrors the « Vider le cache » confirm pattern.
    deleteCandidate?.let { numreponse ->
        DeletePostConfirmDialog(
            onConfirm = {
                viewModel.send(TopicIntent.DeletePost(numreponse))
                deleteCandidate = null
            },
            onDismiss = { deleteCandidate = null },
        )
    }
}

/**
 * #197 — keep [target] anchored at the top of the viewport while upstream block images settle.
 *
 * `PostBlock.Image` renders a `SubcomposeAsyncImage` that starts at `blockImageMinHeight` (160.dp)
 * while loading/erroring and grows to its decoded height (up to `blockImageMaxHeight`, 480.dp) once
 * Coil resolves the bitmap. Any block image in a post *above* the deep-link target shifts the
 * cumulative scroll offset by up to +320.dp *after* the initial one-shot `scrollToItem`, leaving the
 * target scrolled off-screen. A warm image cache decodes synchronously before the first measure,
 * which is why #197 only reproduces on a cold cache.
 *
 * Each frame we re-pin the target to the top (when it has drifted) and stop once the minimum
 * settle window has elapsed *and* its position has held still for [REANCHOR_STABLE_FRAMES]
 * consecutive frames — *settled*, not *pinned at offset 0*.
 * Keying the stop on stillness rather than `offset == 0` handles two cases the #197 review flagged:
 *  - a tail post the list cannot scroll all the way up (not enough content below) rests at a
 *    non-zero offset; an `offset == 0` criterion would never be met and would churn the whole frame
 *    budget on no-op re-pins;
 *  - block images above the target that decode at staggered times keep moving the position, so we
 *    must not declare victory in the gap between two growth pushes.
 * The [REANCHOR_MIN_FRAMES] guard prevents the opposite bug: declaring victory after only three
 * stable frames (~50 ms) before a cold Coil decode has even had time to finish, then letting the
 * target drift once the first image finally grows.
 * Bounded by [REANCHOR_MAX_FRAMES] so a never-resolving image cannot hold the list hostage, and we
 * bail the instant the user grabs the list (`isScrollInProgress`) so the settle window never fights
 * manual scrolling — extending the single-shot, no-focus-stealing contract on [TopicEffect].
 *
 * #175 note — this no longer covers every source of post-load geometry shift: inline smileys now use
 * intrinsic (measured) placeholder sizes (`:core:ui` `IntrinsicMediaSizeCache`), so a perso whose size
 * lands *after* this settle window — or after a manual scroll, which this loop deliberately does not
 * fight — can still nudge the geometry. The provisional fallbacks (pre-seeded builtin / dominant 70×50
 * perso) keep that nudge small in the common case; a dedicated warmup-before-reanchor is the follow-up
 * if a deep-link to a perso-heavy post drifts in practice (to validate on a cold-cache device).
 *
 * The per-frame decision is delegated to the pure [reanchorStep] so the state machine is unit-tested
 * without a frame clock or a live `LazyListState`.
 */
private suspend fun LazyListState.reanchorWhileMediaSettles(target: Int) {
    var stableFrames = 0
    var previous: ReanchorFrame? = null
    repeat(REANCHOR_MAX_FRAMES) { frame ->
        withFrameNanos { }
        if (isScrollInProgress) return // user took over — never fight a manual scroll
        val current = ReanchorFrame(firstVisibleItemIndex, firstVisibleItemScrollOffset)
        val stableThreshold = if (frame >= REANCHOR_MIN_FRAMES) {
            REANCHOR_STABLE_FRAMES
        } else {
            Int.MAX_VALUE
        }
        when (
            val step = reanchorStep(
                current = current,
                previous = previous,
                target = target,
                stableFrames = stableFrames,
                stableThreshold = stableThreshold,
            )
        ) {
            ReanchorStep.Stop -> return
            is ReanchorStep.Continue -> {
                stableFrames = step.stableFrames
                if (step.repin) scrollToItem(target)
            }
        }
        previous = current
    }
}

/**
 * #307 — one-shot restoration of the saved read position + the single central save point.
 *
 * RESTORE: waits for the FIRST `Loaded` emission (same timing as the `ScrollToPost` effect, and read
 * from the [state] flow — not a recomposition-captured snapshot — for the same race-free reason),
 * then applies the anchor — or the #412 bottom landing when `startAtBottom` won the resolution —
 * exactly once per route landing. Subsequent `Loaded` emissions (cache→network refresh of the stale
 * path, manual pull-to-refresh, post-delete reload) never re-scroll: the effect has already
 * completed, mirroring the one-shot contract of the scroll effects. The priority chain was resolved
 * by `:app` — see `restoreScrollAnchor` / `startAtBottom` on [TopicScreen].
 *
 * SAVE: `onDispose` is the ONE save point. `onOpenPage` is shared by swipe, header, pager and
 * FAB, so saving per trigger would multiply call sites (and race); disposal of this composition
 * covers every departure — swipe, FAB, back, tab switch, editor push — with a single write.
 * `scrollAnchorSettled` gates the save: a page abandoned while still Loading reads (0, 0) from
 * a list that never rendered, and must not clobber the real position saved by an earlier visit.
 */
@Suppress("LongParameterList") // Private effect holder: the params are TopicScreen's own
// restoration inputs threaded as-is; grouping them into a holder type would only add indirection.
@Composable
private fun TopicScrollRestorationEffects(
    state: StateFlow<TopicUiState>,
    lazyListState: LazyListState,
    request: TopicRequest,
    restoreScrollAnchor: TopicScrollAnchor?,
    startAtBottom: Boolean,
    onStartAtBottomConsumed: () -> Unit,
    onScrollAnchorSaved: (TopicScrollAnchor) -> Unit,
) {
    var scrollAnchorSettled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val loadedMode = state.first { it.mode is TopicUiState.Mode.Loaded }.mode
                as TopicUiState.Mode.Loaded
        when {
            restoreScrollAnchor != null ->
                lazyListState.scrollToItem(restoreScrollAnchor.index, restoreScrollAnchor.offset)
            // #412 — « page précédente » without a saved anchor: land on the last item (reading
            // direction). Same `posts.size` target as the ScrollToEndOfPage handler — the +1
            // header card at index 0 makes the last post index == posts.size.
            startAtBottom && loadedMode.topic.posts.isNotEmpty() ->
                lazyListState.scrollToItem(loadedMode.topic.posts.size)
        }
        if (startAtBottom) {
            // Consume even when the empty-page guard skipped the scroll: the landing decision for
            // this page is spent either way.
            onStartAtBottomConsumed()
        }
        scrollAnchorSettled = true
    }
    DisposableEffect(request.cat, request.post, request.page) {
        onDispose {
            // Deliberately captures THIS composition's `onScrollAnchorSaved` (keyed to this route's
            // (cat, post, page)) rather than a rememberUpdatedState latest-value: if the request
            // ever changed in place, the departing position must be saved under the OLD key.
            if (scrollAnchorSettled) {
                onScrollAnchorSaved(
                    TopicScrollAnchor(
                        index = lazyListState.firstVisibleItemIndex,
                        offset = lazyListState.firstVisibleItemScrollOffset,
                    ),
                )
            }
        }
    }
}

/** The target row's position within the viewport on a given frame. Cf. [reanchorStep]. */
internal data class ReanchorFrame(val index: Int, val offset: Int)

/** Outcome of one [reanchorStep] decision. */
internal sealed interface ReanchorStep {
    /** The layout has settled (or the frame budget is spent) — stop re-anchoring. */
    data object Stop : ReanchorStep

    /** Keep going: carry [stableFrames] to the next frame and re-pin to the top iff [repin]. */
    data class Continue(val stableFrames: Int, val repin: Boolean) : ReanchorStep
}

/**
 * Pure per-frame decision for [reanchorWhileMediaSettles] (#197), extracted so the state machine is
 * unit-testable without a frame clock or a live `LazyListState`.
 *
 * Stop once the target's position has held still ([current] equal to [previous]) for
 * [stableThreshold] consecutive frames. The caller passes `Int.MAX_VALUE` during the initial
 * cold-decode guard window so the helper keeps monitoring even if the first frames are stable.
 * Otherwise carry the updated stable count and ask for a re-pin whenever the target is not currently
 * at the very top ([ReanchorFrame.index] != [target] or a non-zero offset) — a no-op when it already
 * is, harmless when the list cannot scroll it higher.
 *
 * @param current the target row's position this frame
 * @param previous the same reading from the previous frame, or `null` on the first frame
 * @param target the item index we want pinned to the top
 * @param stableFrames consecutive still frames observed so far
 * @param stableThreshold still frames required to consider the layout settled
 */
internal fun reanchorStep(
    current: ReanchorFrame,
    previous: ReanchorFrame?,
    target: Int,
    stableFrames: Int,
    stableThreshold: Int,
): ReanchorStep {
    val moved = previous == null || current != previous
    val nextStableFrames = if (moved) 0 else stableFrames + 1
    if (nextStableFrames >= stableThreshold) return ReanchorStep.Stop
    val repin = current.index != target || current.offset != 0
    return ReanchorStep.Continue(stableFrames = nextStableFrames, repin = repin)
}

/**
 * ~2 s at 60 fps : long enough to cover a cold image decode on a typical page, short enough that a
 * stuck/never-resolving image cannot pin the list indefinitely. Cf. [reanchorWhileMediaSettles].
 */
private const val REANCHOR_MAX_FRAMES = 120

/**
 * ~1 s at 60 fps before stillness can stop the loop. This keeps the guard alive long enough for the
 * first cold Coil decodes to start moving layout; otherwise three stable frames immediately after
 * the initial scroll can stop the loop before any image above the target has resolved.
 */
private const val REANCHOR_MIN_FRAMES = 60

/** Frames the target position must hold still before we treat the layout as settled. */
private const val REANCHOR_STABLE_FRAMES = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList") // state-hoisted Composable : each param has a distinct call-site.
internal fun TopicContent(
    state: TopicUiState,
    listState: LazyListState,
    onIntent: (TopicIntent) -> Unit,
    onBack: () -> Unit,
    onReply: (subcat: Int, page: Int) -> Unit,
    onQuote: (subcat: Int, page: Int, quotedNumreponse: Int, quoteRef: Int?) -> Unit,
    onEdit: (subcat: Int, page: Int, numreponse: Int) -> Unit,
    onEditFirstPost: (subcat: Int, page: Int, numreponse: Int) -> Unit,
    onOpenPage: (Int) -> Unit,
    // #699 — quote-header tap, threaded down to the post cards (cf. TopicScreen KDoc).
    onGoToPost: (page: Int, numreponse: Int) -> Unit = { _, _ -> },
    onOpenProfile: (userId: Int, pseudo: String, avatarUrl: String?) -> Unit = { _, _, _ -> },
    // #292 — a per-post « Supprimer » tap; the screen owns the confirmation dialog, so this only
    // requests it (carrying the post's numreponse). Never invoked for the first post (excluded).
    onDeleteRequest: (numreponse: Int) -> Unit = {},
    // #291 — multi-quote selection (owned by :app) + its two actions, threaded to the post menu
    // (toggle) and the floating cluster (« Citer N »).
    multiQuoteSelection: List<Int> = emptyList(),
    onToggleMultiQuote: (numreponse: Int) -> Unit = {},
    onMultiQuote: (subcat: Int, page: Int) -> Unit = { _, _ -> },
    // #465 — the topic's manual poll choice (owned by :app, null = follow the global default) +
    // the callback recording a tap on the poll card. Threaded to the header card's poll.
    pollManualExpanded: Boolean? = null,
    onPollExpansionChanged: (Boolean) -> Unit = {},
) {
    // #285 — the topic title and #284 — the page counter live in a persistent top app bar so they
    // stay visible while the user scrolls (the in-card title/caption scrolls away). While loading,
    // the title falls back to the cached hint (or a generic label) and the counter to « Chargement… »
    // — never a page total that has not been parsed yet (#622).
    val loaded = state.mode as? TopicUiState.Mode.Loaded
    // #411 — bottom action cluster hides on scroll-down, re-appears on scroll-up (RF1 parity).
    val bottomActionsVisible = rememberBottomActionsVisible(listState)
    val fallbackTitle = stringResource(R.string.topic_topbar_fallback_title)
    // Honour TopicRequest.titleHint's contract: the cached hint is a LOADING-only stand-in. Once the
    // page is Loaded, the live Topic.title wins (or the generic fallback if it is somehow blank) — we
    // never reach back to the stale hint, so a loaded topic can never display another page's title.
    val barTitle = if (loaded != null) {
        loaded.topic.title.takeIf { it.isNotBlank() } ?: fallbackTitle
    } else {
        state.request.titleHint?.takeIf { it.isNotBlank() } ?: fallbackTitle
    }
    val barPageIndicator = topicBarPageIndicator(state, loaded)
    val backLabel = stringResource(R.string.topic_back)
    // Build 89 follow-up — when the user opted into auto-hide, give the top bar an `enterAlways`
    // scroll behaviour (collapses on scroll-down, snaps back on the first scroll-up). Otherwise
    // leave it `null` so the bar stays pinned (the prior, always-visible behaviour). Toggling the
    // preference re-enters the other branch, recreating the behaviour expanded — the desired reset.
    val scrollBehavior = if (state.topBarAutoHide) {
        TopAppBarDefaults.enterAlwaysScrollBehavior()
    } else {
        null
    }
    Scaffold(
        modifier = if (scrollBehavior != null) {
            Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
        } else {
            Modifier
        },
        topBar = {
            TopicTopBar(
                state = state,
                barTitle = barTitle,
                barPageIndicator = barPageIndicator,
                backLabel = backLabel,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                onIntent = onIntent,
                loaded = loaded,
                onOpenPage = onOpenPage,
            )
        },
        floatingActionButton = {
            TopicBottomActionsHost(
                state = state,
                loaded = loaded,
                bottomActionsVisible = bottomActionsVisible,
                multiQuoteSelection = multiQuoteSelection,
                onOpenPage = onOpenPage,
                onReply = onReply,
                onMultiQuote = onMultiQuote,
            )
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.surface,
        ) {
            when (val mode = state.mode) {
                TopicUiState.Mode.Loading -> {
                    // #604 — skeleton loading (mockup « Chargement A ») : loader centré + cartes
                    // fantômes, à la place de l'ancien spinner nu aligné en haut à gauche.
                    TopicLoadingSkeleton()
                }

                is TopicUiState.Mode.Error -> {
                    // #324 — ServerDown / Network swap the raw exception message for the
                    // shared :core:ui label; Other keeps the existing diagnostic detail.
                    // (`if` rather than `?.let {} ?:` keeps TopicContent under detekt's
                    // cyclomatic-complexity threshold.)
                    val sharedLabelRes = mode.kind.sharedLabelResOrNull()
                    val detail = if (sharedLabelRes != null) stringResource(sharedLabelRes) else mode.message
                    RedfacePlaceholderScreen(
                        title = stringResource(R.string.topic_error_title),
                        body = stringResource(R.string.topic_error_body, state.request.page, detail),
                    ) {
                        TopicPageNavigation(
                            currentPage = state.request.page,
                            availablePages = state.availablePages,
                            canGoPrevious = state.canGoPrevious,
                            canGoNext = state.canGoNext,
                            onOpenPage = onOpenPage,
                        )
                        OutlinedButton(onClick = { onIntent(TopicIntent.Retry) }) {
                            Text(text = stringResource(R.string.topic_retry))
                        }
                    }
                }

                is TopicUiState.Mode.Loaded -> {
                    // #335 — pull-to-refresh only wraps the loaded content (Loading/Error don't need
                    // it). PullToRefreshBox layers a vertical nested-scroll connection on top of the
                    // top-bar enterAlways behaviour (#338) and the horizontal page swipe (#282); the
                    // pull only engages on overscroll at the top of the list, so the read position is
                    // preserved on refresh (the ViewModel emits no scroll effect).
                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = { onIntent(TopicIntent.Refresh) },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // #300/#351 — the intra-page scrollbar now rides inside PostListScaffold
                        // (overlaying the list's right edge, outside the scrolled element), so the
                        // manual Box + LazyListScrollbar wrapper is gone. PullToRefreshBox stays the
                        // feature's wrapper (its refresh state belongs to the ViewModel).
                        TopicLoadedContent(
                            state = state,
                            topic = mode.topic,
                            hiddenNumreponses = mode.hiddenNumreponses,
                            onQuote = onQuote,
                            onEdit = onEdit,
                            onEditFirstPost = onEditFirstPost,
                            onOpenPage = onOpenPage,
                            onGoToPost = onGoToPost,
                            onOpenProfile = onOpenProfile,
                            onDeleteRequest = onDeleteRequest,
                            onDoubleTapRefresh = { onIntent(TopicIntent.Refresh) },
                            listState = listState,
                            multiQuoteSelection = multiQuoteSelection,
                            onToggleMultiQuote = onToggleMultiQuote,
                            onSetAuthorBlocked = { author, blocked ->
                                onIntent(TopicIntent.SetAuthorBlocked(author, blocked))
                            },
                            pollManualExpanded = pollManualExpanded,
                            onPollExpansionChanged = onPollExpansionChanged,
                        )
                    }
                }
            }
        }
    }
}

/**
 * #622 — subtitle of the persistent top bar. « page X / N » only once the response is PARSED: the
 * previous fallback chain used `availablePages.lastOrNull()` during Loading, which can be stale from
 * an earlier navigation (a wrong total displayed while the spinner runs, corrected on arrival).
 * `availablePages` itself stays untouched — the Error path deliberately keeps the last-known page
 * grid as context (see the ViewModel), so Error shows the requested page over that last-known total
 * while Loading shows a plain « Chargement… ».
 */
@Composable
private fun topicBarPageIndicator(state: TopicUiState, loaded: TopicUiState.Mode.Loaded?): String = when {
    loaded != null -> stringResource(
        R.string.topic_page_indicator,
        loaded.topic.page,
        loaded.topic.totalPages,
    )

    state.mode is TopicUiState.Mode.Error -> stringResource(
        R.string.topic_page_indicator,
        state.request.page,
        state.availablePages.lastOrNull() ?: state.request.page,
    )

    else -> stringResource(R.string.topic_loading)
}

/**
 * #285/#284 + Chantier C (#546) — the topic top app bar (title + page counter + back) plus the
 * intra-topic search affordance : a search icon in `actions` (only when the loaded page exposes a
 * usable, authenticated transsearch form) that opens the [TopicSearchBar] directly beneath the bar.
 * Extracted from `TopicContent` to keep that builder under detekt's cyclomatic-complexity cap.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList") // hoisted bar : title/page/back inputs + search sink + page picker.
private fun TopicTopBar(
    state: TopicUiState,
    barTitle: String,
    barPageIndicator: String,
    backLabel: String,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior?,
    onBack: () -> Unit,
    onIntent: (TopicIntent) -> Unit,
    // Vague 3 (#604) — the loaded page (null while loading / on error). Non-null makes the page
    // indicator a tappable pill opening the page-picker sheet — the header card's page navigation
    // (jump field + range row) now lives HERE, the reading surface's single page-change home
    // besides the ‹/› FABs and the horizontal swipe (#282).
    loaded: TopicUiState.Mode.Loaded? = null,
    onOpenPage: (Int) -> Unit = {},
) {
    val searchLabel = stringResource(R.string.topic_search_open)
    var pagePickerOpen by remember { mutableStateOf(false) }
    val pagePickerLabel = stringResource(R.string.topic_page_picker_open)
    Column {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = barTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = barPageIndicator,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (loaded != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = if (loaded != null) {
                            Modifier.clickable(onClickLabel = pagePickerLabel) { pagePickerOpen = true }
                        } else {
                            Modifier
                        },
                    )
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.semantics { contentDescription = backLabel },
                ) {
                    // #360 / ADR-015 — vector stroke unifié, dimensionné en dp (indépendant de la
                    // police et de la baseline, contrairement à l'ancien glyphe « ← »), via le
                    // primitive partagé :core:ui. L'étiquette a11y reste sur l'IconButton, donc
                    // l'icône est décorative (contentDescription = null par défaut).
                    RedfaceVectorIcon(
                        resId = fr.forumhfr.redface2.core.ui.R.drawable.ic_arrow_back,
                    )
                }
            },
            actions = {
                if (state.canSearchInTopic && !state.search.isActive) {
                    IconButton(
                        onClick = { onIntent(TopicIntent.OpenSearch) },
                        modifier = Modifier.semantics { contentDescription = searchLabel },
                    ) {
                        RedfaceVectorIcon(resId = fr.forumhfr.redface2.core.ui.R.drawable.ic_search)
                    }
                }
            },
            scrollBehavior = scrollBehavior,
        )
        if (state.search.isActive) {
            TopicSearchBar(search = state.search, onIntent = onIntent)
        }
    }
    // Vague 3 (#604) — page-picker sheet: the dissolved header card's TopicPageNavigation
    // (prev/next + jump field + compact range row), verbatim, in a bottom sheet anchored to the
    // top-bar pill. The Error path keeps its own inline TopicPageNavigation — recovery navigation
    // must not hide behind a sheet (cadrage Codex vague 3).
    if (pagePickerOpen && loaded != null) {
        ModalBottomSheet(onDismissRequest = { pagePickerOpen = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.topic_page_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TopicPageNavigation(
                    currentPage = loaded.topic.page,
                    availablePages = state.availablePages,
                    canGoPrevious = state.canGoPrevious,
                    canGoNext = state.canGoNext,
                    onOpenPage = { target ->
                        pagePickerOpen = false
                        onOpenPage(target)
                    },
                )
            }
        }
    }
}

/**
 * Chantier C (#546) — the intra-topic search bar, shown under the top app bar when search is active.
 *
 * Two fields (term / author) + a « Filtrer » toggle (HFR's `filter`, i.e. show only matching posts)
 * + submit + close. Submitting POSTs `transsearch.php` ; the response (a topic page) replaces the
 * loaded page.
 *
 * Chantier B (#546) — in NON-FILTERED mode, once a search is `Done`, a « précédent / suivant » pair of
 * arrows steps between matches (`currentnum`), enabled per the ViewModel's client-side cursor history.
 * A `NoResults` status shows a sober « Aucun résultat » line instead. Filtered mode keeps no per-result
 * navigation (the page already IS the matches list).
 */
@Composable
private fun TopicSearchBar(
    search: TopicSearchUiState,
    onIntent: (TopicIntent) -> Unit,
) {
    val closeLabel = stringResource(R.string.topic_search_close)
    Surface(
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = search.word,
                    onValueChange = { onIntent(TopicIntent.SearchWordChanged(it)) },
                    label = { Text(stringResource(R.string.topic_search_word_hint)) },
                    singleLine = true,
                    enabled = search.status != TopicSearchStatus.Loading,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = search.spseudo,
                    onValueChange = { onIntent(TopicIntent.SearchPseudoChanged(it)) },
                    label = { Text(stringResource(R.string.topic_search_pseudo_hint)) },
                    singleLine = true,
                    enabled = search.status != TopicSearchStatus.Loading,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = search.onlyMatches,
                    onCheckedChange = { onIntent(TopicIntent.SearchOnlyMatchesChanged(it)) },
                    enabled = search.status != TopicSearchStatus.Loading,
                )
                Text(
                    text = stringResource(R.string.topic_search_only_matches),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = search.status != TopicSearchStatus.Loading) {
                            onIntent(TopicIntent.SearchOnlyMatchesChanged(!search.onlyMatches))
                        },
                )
                IconButton(
                    onClick = { onIntent(TopicIntent.CloseSearch) },
                    modifier = Modifier.semantics { contentDescription = closeLabel },
                ) {
                    RedfaceVectorIcon(resId = fr.forumhfr.redface2.core.ui.R.drawable.ic_close)
                }
                Button(
                    onClick = { onIntent(TopicIntent.SubmitSearch) },
                    enabled = search.canSubmit && search.status != TopicSearchStatus.Loading,
                ) {
                    Text(stringResource(R.string.topic_search_submit))
                }
            }
            // Chantier B (#546) — per-result navigation (non-filtered) / « no result » feedback.
            TopicSearchResultNav(search = search, onIntent = onIntent)
        }
    }
}

/**
 * Chantier B (#546) — the result-navigation footer of [TopicSearchBar]. In NON-FILTERED mode, once the
 * search is `Done`, shows « précédent / suivant » arrows (enabled per the ViewModel's cursor history).
 * A `NoResults` status shows a sober « Aucun résultat » line. Renders nothing otherwise (filtered mode,
 * idle, loading), so the bar collapses back to its two-row shape.
 */
@Composable
private fun TopicSearchResultNav(
    search: TopicSearchUiState,
    onIntent: (TopicIntent) -> Unit,
) {
    when {
        search.status == TopicSearchStatus.NoResults -> {
            Text(
                text = stringResource(R.string.topic_search_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        search.status == TopicSearchStatus.Done && !search.onlyMatches -> {
            val prevLabel = stringResource(R.string.topic_search_prev_result)
            val nextLabel = stringResource(R.string.topic_search_next_result)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { onIntent(TopicIntent.PrevResult) },
                    enabled = search.canGoPreviousResult,
                    modifier = Modifier.semantics { contentDescription = prevLabel },
                ) {
                    RedfaceVectorIcon(resId = fr.forumhfr.redface2.core.ui.R.drawable.ic_chevron_left)
                }
                IconButton(
                    onClick = { onIntent(TopicIntent.NextResult) },
                    enabled = search.canGoNextResult,
                    modifier = Modifier.semantics { contentDescription = nextLabel },
                ) {
                    RedfaceVectorIcon(resId = fr.forumhfr.redface2.core.ui.R.drawable.ic_chevron_right)
                }
            }
        }
    }
}

@Composable
// LongParameterList : state-hoisted Composable, each param has a distinct call-site.
// CyclomaticComplexMethod : dense list builder (header + per-post action gates + #509 hidden branch);
// splitting it would scatter one visual unit across helpers (same stance as TopicPostCard).
@Suppress("LongParameterList", "CyclomaticComplexMethod")
private fun TopicLoadedContent(
    state: TopicUiState,
    topic: Topic,
    // #509 — `numreponse` of posts whose author is blacklisted; rendered as a collapsed
    // "post masqué" placeholder instead of the full card (the post stays in the list).
    hiddenNumreponses: Set<Int> = emptySet(),
    // Vague 3 (#604) — onReply dropped: the dissolved header card was its only consumer here
    // (the bottom FAB cluster replies from TopicContent's own callback).
    onQuote: (subcat: Int, page: Int, quotedNumreponse: Int, quoteRef: Int?) -> Unit,
    onEdit: (subcat: Int, page: Int, numreponse: Int) -> Unit,
    onEditFirstPost: (subcat: Int, page: Int, numreponse: Int) -> Unit,
    onOpenPage: (Int) -> Unit,
    // #699 — quote-header tap, forwarded into each TopicPostCard's PostRenderer.
    onGoToPost: (page: Int, numreponse: Int) -> Unit = { _, _ -> },
    onOpenProfile: (userId: Int, pseudo: String, avatarUrl: String?) -> Unit = { _, _, _ -> },
    onDeleteRequest: (numreponse: Int) -> Unit = {},
    /** #382 — double-tap anywhere on the list refreshes the current page (RF1 parity). */
    onDoubleTapRefresh: () -> Unit = {},
    listState: LazyListState,
    // #291 — selection state + toggle for the post menu's multi-quote entry.
    multiQuoteSelection: List<Int> = emptyList(),
    onToggleMultiQuote: (numreponse: Int) -> Unit = {},
    // #509 — block/unblock a post's author from the post menu (blacklist).
    onSetAuthorBlocked: (author: String, blocked: Boolean) -> Unit = { _, _ -> },
    // #465 — the topic's manual poll choice (owned by :app, null = follow the global default) + the
    // callback recording a tap on the poll card. Threaded down to the header card's poll.
    pollManualExpanded: Boolean? = null,
    onPollExpansionChanged: (Boolean) -> Unit = {},
) {
    // Scroll-anchor (#104 follow-up): the post the reader was sent to (quote link, deep link, last-read).
    // Marked by tinting ONLY its identity band with tertiaryContainer (XaTriX: the left-rail attempt was
    // ugly; the old card+band double tint stays removed) — one subtle band, no layout shift.
    val highlight = state.request.scrollTo
    // #239 — how many posts of THIS page cite each post, computed once per loaded post list. Drives
    // the « cité N fois » badge below. Pure + page-scoped (cf. citationCountsByNumreponse KDoc).
    val citationCounts = remember(topic.posts) { citationCountsByNumreponse(topic.posts) }
    // #362 — post whose contextual menu is open (null = closed). Plain local UI state at the
    // Loaded level: the menu carries no async data, so no ViewModel/hoisting is needed — the
    // sheet lives in :feature:topic (unlike ProfilePreviewSheet, hoisted in :app only because
    // it needs a Hilt ViewModel). Deliberately NOT rememberSaveable: Post is not Parcelable
    // and losing an open overflow menu across process death is acceptable.
    var menuPost by remember { mutableStateOf<Post?>(null) }
    // #509 — posts the reader chose to reveal despite the author being blacklisted. Temporary and
    // re-keyed on `topic.page`, not persisted: re-hiding on a page change is the intended "masqué by
    // default" behaviour (decision #6). This composable instance is bound to one (cat, post), so the
    // page is the only key dimension that matters here.
    var revealedHiddenPosts by remember(topic.page) { mutableStateOf(emptySet<Int>()) }
    // #509 — a post hidden (author blacklisted) while it sat in the multi-quote basket is dropped from
    // the selection: the placeholder exposes no deselect affordance (decision #1), so leaving it
    // selected would silently quote a masqué post. The basket is hoisted in :app; reuse its toggle.
    LaunchedEffect(hiddenNumreponses, multiQuoteSelection) {
        multiQuoteSelection.filter { it in hiddenNumreponses }.forEach(onToggleMultiQuote)
    }
    // #282 — shared offset between the gesture (drives translationX) and the edge glow. A plain
    // MutableFloatState: the gesture writes it synchronously per frame (no coroutine/alloc), the draw
    // phase reads it; an Animatable inside the gesture handles only release transitions. Lives in the
    // Loaded composition only, so a committed swipe (which recreates the screen) starts back at rest.
    val dragOffset = remember { mutableFloatStateOf(0f) }
    // #282 — hoisted so the gesture can tick on arming and confirm on commit.
    val haptics = LocalHapticFeedback.current
    // #282 — live page count for the swipe gesture, read through a lambda so the gesture sees the
    // latest value WITHOUT re-keying its `pointerInput` (which would cancel an in-flight commit
    // slide-out and drop the navigation — see `topicPageSwipe`). `rememberUpdatedState` keeps the
    // State identity stable while its value tracks `topic.totalPages` across recompositions.
    val currentTotalPages by rememberUpdatedState(topic.totalPages)
    // #282 — the swipe must be INERT while this nav entry is not yet settled (mid NavDisplay
    // transition, lifecycle < RESUMED). During the transition the incoming (cached) page is a fresh
    // composition that would otherwise accept a swipe and commit a second onOpenPage mid-flight,
    // interrupting the transition → frozen screen. The lambda reads `lifecycle.currentState` live, so
    // the gesture (whose pointerInput does not re-key on this) always sees the current state.
    val entryLifecycle = LocalLifecycleOwner.current.lifecycle
    val swipeEnabled: () -> Boolean = { entryLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
    // #282 (P2-b) — if the loaded page re-keys while we stay Loaded (a force-refresh or page jump that
    // lands the same screen on a new page), drop any residual translation so the page is never left
    // frozen off-centre. Keyed on `topic.page` ONLY — never `topic.totalPages`: a page-count change
    // landing during a commit's slide-out must not reset the offset mid-animation (it would yank the
    // sliding page back); the gesture reads the live count via `currentTotalPages` instead.
    LaunchedEffect(topic.page) {
        dragOffset.floatValue = 0f
    }
    // #351 — the list overlay (LazyColumn + auto-hiding scrollbar) is now the shared PostListScaffold
    // (#300/#351). The swipe machinery stays feature-owned and is threaded through `listModifier`,
    // applied to the LazyColumn itself (so the list follows the finger and the scrollbar overlay stays
    // fixed); the contentPadding / verticalArrangement / scrollbar gate are passed unchanged.
    PostListScaffold(
        listState = listState,
        // #283 — extra bottom padding so the last post's right-aligned actions clear the floating
        // bottom-action cluster (the Scaffold FAB slot floats over the content). Harmless extra
        // breathing room when the cluster is absent (anon + single page).
        // #398 — the nav host no longer pads screens by 8 dp/side, so the reader carries its own
        // 8 dp side gutter here (previously 0 + 8 host = 8). Same effective 8 dp, now owned locally.
        contentPadding = PaddingValues(start = 8.dp, top = 16.dp, end = 8.dp, bottom = 88.dp),
        // 8 dp vertical rhythm, matching the 8 dp effective side gutters — a uniform grid (and
        // a denser feed, cf. the #287 density feedback).
        verticalArrangement = Arrangement.spacedBy(8.dp),
        listModifier = Modifier
            // #285 — system-bar insets (status + navigation) are now consumed by the Scaffold/TopAppBar
            // in TopicContent and applied via the content Surface's padding(innerPadding); the list no
            // longer adds statusBarsPadding()/navigationBarsPadding() here to avoid double-insetting.
            // #282 — horizontal swipe changes page via the existing route-driven onOpenPage, with
            // drag-follow feedback: the page tracks the finger (graphicsLayer inside topicPageSwipe)
            // and pageSwipeEdgeHint (shared, :core:ui) paints an edge glow as the swipe arms. It must
            // precede topicPageSwipe so the glow draws in untranslated (screen) space.
            // Engages on horizontal slop only, so vertical scroll and the page-grid's own
            // horizontalScroll keep their gestures; edges are a damped no-op.
            .pageSwipeEdgeHint(
                currentPage = topic.page,
                totalPages = { currentTotalPages },
                dragOffset = dragOffset,
                // #282 — desaturated edge-glow tint: mostly neutral (onSurfaceVariant) with a touch of
                // primary, instead of full primary which read as an imposing pink/mauve panel.
                accent = lerp(
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    MaterialTheme.colorScheme.primary,
                    0.3f,
                ),
                enabled = swipeEnabled,
            )
            .topicPageSwipe(
                currentPage = topic.page,
                totalPages = { currentTotalPages },
                dragOffset = dragOffset,
                handlers = TopicSwipeHandlers(
                    haptics = haptics,
                    onOpenPage = onOpenPage,
                    enabled = swipeEnabled,
                ),
            )
            // #382 — double-tap anywhere on the list to refresh the page (RF1 parity). Child
            // clickables (links, buttons, avatar) consume their own up events, so taps on them
            // never count toward this detector; drags past slop cancel it, so scrolling and the
            // #282 page swipe are untouched. The PullToRefreshBox spinner gives the feedback
            // (isRefreshing is already shared with the pull gesture); the haptic tick confirms
            // the trigger under the finger.
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onDoubleTapRefresh()
                    },
                )
            },
    ) {
        item {
            // Vague 3 (#604, mockup « Lecture A ») — the header card is DISSOLVED: title and page
            // indicator live in the top app bar (tappable page pill → picker sheet), « Répondre »
            // in the bottom FAB cluster (#283), « Modifier le premier message » in the first
            // post's « … » menu (Phase 2D #148 gates unchanged), and the scrollTo indicator is
            // gone (the amber highlight on arrival is the affordance). This LEAD slot must keep
            // occupying index 0 unconditionally: every index-based scroll computation
            // (ScrollToPost's `index + 1`, end-of-page `posts.size`, saved anchors #307, the
            // re-anchor steps #412) assumes exactly one item before the posts — a poll-less topic
            // renders it zero-size instead of dropping it.
            topic.poll?.let { poll ->
                TopicPollCard(
                    poll = poll,
                    expandedDefault = state.pollsExpandedDefault,
                    manualExpanded = pollManualExpanded,
                    onExpansionChanged = onPollExpansionChanged,
                )
            }
        }
        items(
            items = topic.posts,
            key = { post -> post.numreponse },
        ) { post ->
            // « Citer » is enabled whenever the topic is postable — the `bddpost`
            // reply form was present (#213, same gate as Reply). It does NOT depend
            // on parsing a per-post quote link: HFR identifies the cited post by
            // `numrep={numreponse}` alone (proven via hfr-mcp FetchQuote, which omits
            // `ref` entirely), so an unparseable/obfuscated quote link (cat IA &
            // pinned topics ship them as `md_noclass_cryptlink`, cf. #227) no longer
            // hides Citer. `quoteRef` is forwarded when known (positional, cosmetic)
            // and may be null — the whole quote chain tolerates it.
            // « Citer » and the « + » multi-quote affordance share ONE gate (multi-quote is a
            // flavour of quoting : a topic the user cannot reply to has nothing to quote). Deriving
            // both inside the same branch keeps them in lock-step — they can never drift apart — and
            // avoids a second decision point in this already-dense list builder.
            val quoteAction: (() -> Unit)?
            val multiQuoteToggle: (() -> Unit)?
            if (shouldShowQuoteAction(topic, state.isAuthenticated)) {
                quoteAction = { onQuote(topic.subcat, topic.page, post.numreponse, post.quoteRef) }
                multiQuoteToggle = { onToggleMultiQuote(post.numreponse) }
            } else {
                quoteAction = null
                multiQuoteToggle = null
            }
            // Phase 2D (#147) — « Modifier » is exposed by HFR only on the
            // user's own posts of an unlocked topic. Same canReply gate as
            // Citer (#213) to refuse a read-only topic (no reply form).
            val editAction: (() -> Unit)? = if (shouldShowEditAction(topic, post, state.isAuthenticated)) {
                { onEdit(topic.subcat, topic.page, post.numreponse) }
            } else {
                null
            }
            // Phase 2 finish (#208) — profile tap is enabled only when HFR exposed
            // a profile link for this post (Post.profileId != null). Posts without a
            // profile link (Publicité rows, anonymous reads) keep the tap hidden.
            val profileAction: (() -> Unit)? = post.profileId?.let { profileId ->
                { onOpenProfile(profileId, post.author, post.avatarUrl) }
            }
            // #509 — a blacklisted author's post is replaced by a collapsed placeholder (the post is
            // kept in the list to preserve index/anchor/numreponse invariants), until the reader taps
            // « Afficher ». The placeholder exposes no quote/edit/menu action by design (decision #1).
            // Vague 3 (#600) — traversing « Dernier message lu » separator BELOW the last-read post
            // (mockup « Lecture A »). Rendered INSIDE this post's item (a Column, not an extra list
            // item) so every index-based scroll computation stays untouched (lead item + posts.size
            // invariants). Gated on forceRefresh: #231 sets it ONLY on a drapeau/flag tap — the one
            // navigation whose scrollTo semantically IS « last read ». A quote jump / deep link
            // (forceRefresh=false) keeps the #104 band tint alone; the amber arrival flash (#200)
            // is a third, independent layer.
            val showLastReadMarker = shouldShowLastReadMarker(state.request, post.numreponse)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (post.numreponse in hiddenNumreponses && post.numreponse !in revealedHiddenPosts) {
                    HiddenPostCard(
                        author = post.author,
                        onReveal = { revealedHiddenPosts = revealedHiddenPosts + post.numreponse },
                    )
                } else {
                    TopicPostCard(
                        post = post,
                        highlighted = highlight == post.numreponse,
                        citedCount = citationCounts[post.numreponse] ?: 0,
                        // #699 — makes sourced quote headers tappable (jump to the cited post).
                        onGoToCitedPost = onGoToPost,
                        // #330 — render the author signature beneath the body when the reading preference
                        // is on (the signature is always parsed/cached on the Post; this is render-only).
                        showSignature = state.showSignatures,
                        onQuote = quoteAction,
                        onEdit = editAction,
                        onOpenProfile = profileAction,
                        onOpenMenu = { menuPost = post },
                        // #436 — same membership source as the menu entry (PostMenuSheet).
                        multiQuoteSelected = post.numreponse in multiQuoteSelection,
                        // #436 — per-post add/remove affordance (RF1 quote+/quote- parity), reachable
                        // without opening the « … » menu. Null/non-null under the SAME gate as « Citer »
                        // (derived together above), so the « + » and « Citer » always appear as a pair.
                        onToggleMultiQuote = multiQuoteToggle,
                    )
                }
                if (showLastReadMarker) {
                    LastReadMarker()
                }
            }
        }
        // #379 — explicit end-of-topic marker after the last post of the LAST page. The
        // « page X/Y » counter (#284) lets the reader deduce it; this says it. Reflects the
        // LOADED page (same contract as the counter): replies posted since the fetch surface
        // on the next refresh. Intermediate pages keep their natural « more below » flow.
        // NO explicit key (Codex review): a stable key would make Lazy track the footer
        // across an insertion — a reader parked on the marker would keep it in view while a
        // freshly fetched post lands above the viewport, unseen. Positional identity is
        // correct for a stateless sentinel.
        if (topic.page == topic.totalPages) {
            item {
                EndOfTopicCard()
            }
        } else if (topic.page < topic.totalPages) {
            // Vague 3 (#604) — the #110 hairline marker becomes an actionable boundary card
            // (beta feedback by thibw & styx42 : the divider read too weak, and an intermediate
            // page's end was indistinguishable from the topic's). Tapping opens the next page
            // through the SAME onOpenPage as the › FAB / swipe (#282) — a strict « page + 1 »
            // step never arms the #412 bottom landing, so the reader lands at the top of the
            // next page, which is the natural continuation. Same no-key sentinel rationale.
            item {
                PageBoundaryCard(
                    donePage = topic.page,
                    onNextPage = { onOpenPage(topic.page + 1) },
                )
            }
        }
    }
    // #362 — per-post contextual menu. The permalink is rebuilt from the LOADED topic's
    // (cat, post, page) — not the request — so it always reflects the page HFR actually
    // served (HFR clamps out-of-range pages). citedCount reuses the page-scoped #239 index.
    menuPost?.let { post ->
        // #292 → #418 — « Supprimer » lives in the contextual menu now (anti accidental tap,
        // beta feedback by nicko). Same gates as before : « Modifier »'s gate (HFR allows
        // deletion via the edit form), never the topic's first post (deleting it would remove
        // the whole topic — out-of-scope destructive path), and no delete affordance while a
        // deletion is in flight (the ViewModel also guards ; hiding is the honest UI signal).
        val menuDeleteAction: (() -> Unit)? = if (
            state.deletingNumreponse == null &&
            !isFirstPostOfTopic(topic, post) &&
            shouldShowDeleteAction(topic, post, state.isAuthenticated)
        ) {
            { onDeleteRequest(post.numreponse) }
        } else {
            null
        }
        // Vague 3 (#604) — « Modifier le premier message » migrated here from the dissolved
        // header card (Phase 2D #148, gates unchanged incl. the strict `subcat > 0` of #213 —
        // cf. shouldShowEditFirstPost): the action acts on the topic through its FIRST post,
        // so its natural home is that post's contextual menu.
        val menuEditFirstPostAction: (() -> Unit)? = if (
            isFirstPostOfTopic(topic, post) &&
            shouldShowEditFirstPost(topic, state.isAuthenticated)
        ) {
            { onEditFirstPost(topic.subcat, topic.page, topic.posts.first().numreponse) }
        } else {
            null
        }
        PostMenuSheet(
            post = post,
            permalink = buildPostPermalink(
                cat = topic.cat,
                post = topic.post,
                page = topic.page,
                numreponse = post.numreponse,
            ),
            citedCount = citationCounts[post.numreponse] ?: 0,
            onDismiss = { menuPost = null },
            onDelete = menuDeleteAction,
            onEditFirstPost = menuEditFirstPostAction,
            // #395 — same profileId gate as the post card (#208): Publicité rows and
            // anonymous reads expose no profile link, the hero stays inert.
            onOpenProfile = post.profileId?.let { profileId ->
                { onOpenProfile(profileId, post.author, post.avatarUrl) }
            },
            // #291 — multi-quote toggle, same gate as « Citer » (quoting is a flavour of
            // replying; a locked topic or an anonymous session has nothing to quote).
            multiQuoteSelected = post.numreponse in multiQuoteSelection,
            onToggleMultiQuote = if (shouldShowQuoteAction(topic, state.isAuthenticated)) {
                { onToggleMultiQuote(post.numreponse) }
            } else {
                null
            },
            // #509 — a post reachable through the menu is either not blocked, or blocked-but-revealed;
            // either way `numreponse in hiddenNumreponses` tells whether the author is blacklisted, so
            // the entry flips between Masquer / Ne plus masquer. Hidden for the user's own posts.
            authorBlocked = post.numreponse in hiddenNumreponses,
            onToggleBlockAuthor = if (post.isOwnPost) {
                null
            } else {
                { onSetAuthorBlocked(post.author, post.numreponse !in hiddenNumreponses) }
            },
        )
    }
}

/**
 * #600 → vague 3 (#604) — traversing « Dernier message lu » separator (mockup « Lecture A ») :
 * a full-width primary rule with a centred primary pill. Rendered below the last-read post,
 * INSIDE that post's list item (cf. call site — the index math must not see an extra item).
 * Beta feedback by Colonel MythO : the #104 band tint alone (one notch of tint on the identity
 * band) was too subtle to spot when catching up from a flag.
 */
@Composable
private fun LastReadMarker() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val ruleColor = MaterialTheme.colorScheme.primary.copy(alpha = LAST_READ_RULE_ALPHA)
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 2.dp,
            color = ruleColor,
        )
        Text(
            text = stringResource(R.string.topic_last_read_marker),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.extraLarge,
                )
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 2.dp,
            color = ruleColor,
        )
    }
}

// #600 — the mockup's rule opacity : strong enough to traverse, calmer than the full-strength pill.
private const val LAST_READ_RULE_ALPHA = 0.55f

/**
 * #379 → vague 3 (#604) — calm end-of-topic endcard: an outlined card with a centred title and
 * the #379 caption, rendered as the LAST LazyColumn item of the topic's last page only. Visually
 * OPPOSITE to [PageBoundaryCard] (outline vs filled primaryContainer) so an intermediate page's
 * end and the topic's end can never be confused again (beta feedback by thibw & styx42). Pure
 * presentation — the condition (`topic.page == topic.totalPages`) lives at the call site.
 */
@Composable
private fun EndOfTopicCard() {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.topic_end_of_topic_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.topic_end_of_topic),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * #110 → vague 3 (#604) — actionable page-boundary card on an INTERMEDIATE page
 * (`topic.page < topic.totalPages`, condition at the call site): « Page N terminée » plus a
 * « continue » affordance, the whole card tappable (mockup « Lecture A », arbitré fil DEV).
 * [onNextPage] delegates to the caller's onOpenPage — the same in-place route replace as the
 * › FAB and the horizontal swipe (#282), so scroll restoration semantics stay uniform.
 */
@Composable
private fun PageBoundaryCard(donePage: Int, onNextPage: () -> Unit) {
    val nextPageLabel = stringResource(R.string.topic_page_boundary_next, donePage + 1)
    // Card(onClick) over an inner Row.clickable (gate Codex) : the whole surface is declared as
    // ONE interactive Material component, and the action's wording is already the card's visible
    // subtitle — TalkBack reads it as content, no custom onClickLabel needed.
    Card(
        onClick = onNextPage,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.topic_page_boundary_done, donePage),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = nextPageLabel,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            RedfaceVectorIcon(resId = fr.forumhfr.redface2.core.ui.R.drawable.ic_chevron_right)
        }
    }
}

/**
 * Primary page navigation : Previous / page X/Y indicator / Next + a jump-to-page
 * input for long topics. The Previous button is disabled on page 1, Next on the
 * last page — both intents are no-ops outside their valid range. The legacy
 * exhaustive 1..N row stays below as a complement (kept usable on small topics
 * where a finger-tap on the right page is faster than typing).
 */
@Composable
private fun TopicPageNavigation(
    currentPage: Int,
    availablePages: List<Int>,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onOpenPage: (Int) -> Unit,
) {
    val totalPages = availablePages.lastOrNull() ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { if (canGoPrevious) onOpenPage(currentPage - 1) },
                enabled = canGoPrevious,
            ) {
                Text(stringResource(R.string.topic_page_previous))
            }
            Text(
                text = stringResource(R.string.topic_page_indicator, currentPage, totalPages),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { if (canGoNext) onOpenPage(currentPage + 1) },
                enabled = canGoNext,
            ) {
                Text(stringResource(R.string.topic_page_next))
            }
        }
        TopicPageJumpField(
            currentPage = currentPage,
            totalPages = totalPages,
            onOpenPage = onOpenPage,
        )
        if (availablePages.size in 2..PAGE_GRID_LIMIT) {
            // Compact range row : keeps the historical UX for small topics. Not
            // surfaced for long topics (>40 pages) — Previous/Next + jump cover them.
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                availablePages.forEach { page ->
                    if (page == currentPage) {
                        Button(onClick = {}) {
                            Text(text = page.toString())
                        }
                    } else {
                        OutlinedButton(onClick = { onOpenPage(page) }) {
                            Text(text = page.toString())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopicPageJumpField(
    currentPage: Int,
    totalPages: Int,
    onOpenPage: (Int) -> Unit,
) {
    var input by remember(currentPage) { mutableStateOf("") }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { raw -> input = coercePageJumpInput(raw, totalPages) },
            singleLine = true,
            label = { Text(stringResource(R.string.topic_page_jump_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(160.dp),
        )
        TextButton(
            onClick = {
                val target = input.toIntOrNull() ?: return@TextButton
                if (target in 1..totalPages && target != currentPage) {
                    input = ""
                    onOpenPage(target)
                }
            },
        ) {
            Text(stringResource(R.string.topic_page_jump_action))
        }
    }
}

@Composable
private fun TopicPollCard(
    poll: Poll,
    expandedDefault: Boolean,
    // #465 — the user's manual choice for this topic's poll, hoisted to :app so it survives the
    // per-page TopicRoute swap. `null` = no manual choice yet → follow [expandedDefault] (#456).
    manualExpanded: Boolean?,
    onExpansionChanged: (Boolean) -> Unit,
) {
    // #456 — the preference seeds the initial state; #465 — once the user taps, the manual choice
    // (owned by :app, keyed by topic) wins and survives navigation between the topic's pages. The
    // card is fully controlled: it never holds the revealed state itself, it only reports a toggle.
    val revealed = manualExpanded ?: expandedDefault
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.clickable { onExpansionChanged(!revealed) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = poll.question,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (revealed) {
                        stringResource(R.string.topic_poll_hide)
                    } else {
                        stringResource(R.string.topic_poll_show)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (revealed) {
                poll.options.forEach { option ->
                    Text(
                        text = stringResource(
                            R.string.topic_poll_option,
                            option.text,
                            option.percentage,
                            option.votes,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.topic_poll_summary,
                        poll.totalVotes,
                        if (poll.multipleChoice) {
                            stringResource(R.string.topic_poll_multiple_choices)
                        } else {
                            stringResource(R.string.topic_poll_single_choice)
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * #509 — collapsed placeholder shown in place of a blacklisted author's post. The post is NOT removed
 * from the list (index/anchor/`numreponse` invariants stay intact); only its body is replaced by this
 * one-line card. « Afficher » reveals the real card for the current page only. By design it exposes no
 * quote/edit/menu action (decision #1): the reader reveals first, then acts on the full card.
 */
@Composable
private fun HiddenPostCard(
    author: String,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.topic_post_hidden_by_author, author),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
            TextButton(onClick = onReveal) {
                Text(text = stringResource(R.string.topic_post_hidden_reveal))
            }
        }
    }
}

/**
 * #221 — a Redface 2 creator's pseudo, painted with the animated gold sheen. Kept as its own leaf
 * composable so the per-frame shimmer ([rememberCreatorPseudoBrush]) invalidates only this text node,
 * never the enclosing (and expensive) post card.
 */
@Composable
private fun CreatorPseudoText(author: String, modifier: Modifier = Modifier) {
    Text(
        text = author,
        style = MaterialTheme.typography.titleSmall.copy(brush = rememberCreatorPseudoBrush()),
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
// Rich post card : each optional affordance (multi-quote border + pill + « + »
// toggle, citation badge, profile tap, contextual menu, edit, quote) is its own guarded branch, so
// the cyclomatic count is inherently high — same call as PostRenderer. Splitting it would scatter a
// single visual unit across helpers. LongParameterList : state-hoisted, each param has a distinct
// call-site.
@Suppress("LongParameterList", "CyclomaticComplexMethod")
// `internal` (#436): TopicPostCardMultiQuoteTest mounts the card directly to assert the per-post
// « + » affordance (gating, label flip, tap). Same visibility relaxation as other tested internals.
internal fun TopicPostCard(
    post: Post,
    /**
     * #104 follow-up — true for the scroll-anchor post (quote link / deep link / last-read landing).
     * Tints this post's identity band with tertiaryContainer so the anchored post is findable, without
     * the old card+band double highlight (removed in #104) nor a left rail (dropped as ugly). Default false.
     */
    highlighted: Boolean = false,
    /**
     * #239 — number of posts on the current page that cite this one. 0 hides the badge.
     */
    citedCount: Int,
    /**
     * #330 — when `true` (the « Afficher les signatures » reading preference is on), the author's
     * signature ([Post.signature]) is rendered beneath the body, separated by a divider, in a
     * subdued style. No-op when the post has no signature. Default `false`.
     */
    showSignature: Boolean = false,
    onQuote: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    /**
     * Phase 2 finish (#208) — tapping the avatar or author opens the profile bottom sheet.
     * Null when [Post.profileId] is null (Publicité rows, anonymous reads).
     */
    onOpenProfile: (() -> Unit)? = null,
    /**
     * #362 — opens the per-post contextual menu ([PostMenuSheet]): post number (moved out
     * of the header bar), permalink copy, edit marker, citation count.
     */
    onOpenMenu: () -> Unit = {},
    /**
     * #436 — true when this post sits in the multi-quote basket (#291). Marks the card with a
     * primary border + an « Ajouté à la citation » pill rendered BELOW the identity band (moved out of
     * the band so it no longer grows it on selection), so the selection is visible without opening the
     * per-post menu (dev feedback by Dintr-un lemn).
     */
    multiQuoteSelected: Boolean = false,
    /**
     * #436 — toggles this post in/out of the multi-quote basket directly from the card footer
     * (RF1 quote+/quote- parity), without opening the « … » menu. Null under the same gate as
     * [onQuote] (a non-postable topic has nothing to quote), so the « + » action and « Citer »
     * appear together or not at all. The same [multiQuoteSelected] flag drives the glyph/label
     * here, the border, and the pill — one source of truth, they can never desynchronise.
     */
    onToggleMultiQuote: (() -> Unit)? = null,
    /**
     * #699 — forwarded to [PostRenderer] so a sourced quote's header can jump to the cited post.
     * Null keeps the headers inert (previews/tests that render a card without navigation).
     */
    onGoToCitedPost: ((page: Int, numreponse: Int) -> Unit)? = null,
) {
    // #287 — structural spacing from the active density preset (Comfort = the historical rhythm).
    val m = LocalDisplayMetrics.current
    // #436 — the per-post actions row (Citer / Modifier / multi-quote) is gated as a unit. Computed
    // once so the body slot knows whether the footer slot will render (it owns the card's bottom
    // padding when there is no footer, so the body↔card bottom gap stays exactly m.cardBodyBottom).
    val hasFooter = onQuote != null || onEdit != null || onToggleMultiQuote != null
    val hasBadges = citedCount > 0 || multiQuoteSelected
    PostCardShell(
        // #436 — multi-quote selection outline (lot 1), unchanged.
        border = if (multiQuoteSelected) {
            BorderStroke(width = 2.dp, color = MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        // Identity band — the avatar/pseudo/date header gets its own tinted strip across the full card
        // width (forum idiom, dogfooding v109): secondaryContainer over the neutral card. #104 follow-up
        // (XaTriX): the scroll-anchor post tints ONLY this band with tertiaryContainer (the left rail was
        // dropped as ugly) — a single tertiary band, not the old card+band double tint. The shared
        // PostIdentityBand (#351) sets LocalContentColor from its containerColor for the pseudo; the
        // enclosing Card clips the strip to its rounded corners. The #104 tint logic is UNCHANGED — it
        // stays the topic's decision, passed in as containerColor.
        header = {
            PostIdentityBand(
                containerColor = if (highlighted) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            ) {
                TopicPostIdentityHeader(
                    post = post,
                    onOpenProfile = onOpenProfile,
                    onOpenMenu = onOpenMenu,
                    // #287 — the band's header padding (12.dp horizontal, m.cardHeaderVertical vertical)
                    // is reinjected on the header slot's modifier (densities stay feature-owned, #351).
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = m.cardHeaderVertical),
                )
            }
        },
        // #436/#476 follow-up (XaTriX) — citation + multi-quote pills sit OUT of the identity band (the
        // secondaryContainer Surface above): when the « Ajouté à la citation » pill appeared inside the
        // band, the coloured band itself grew taller (« pop »). On the shell's badges slot — the neutral
        // card surface just below the band — the band keeps a FIXED height; only the neutral area grows.
        badges = if (hasBadges) {
            {
                TopicPostBadges(
                    citedCount = citedCount,
                    multiQuoteSelected = multiQuoteSelected,
                    horizontalPadding = m.cardBodyHorizontal,
                )
            }
        } else {
            null
        },
        body = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // #287 — post-body inner gutters from the density preset. Comfort = the lot A
                    // values (12/10/12/8) that buy the body ~24 dp of extra reading width per line;
                    // Compact tightens them for a denser feed. When there is NO footer slot, this body
                    // also owns the card's bottom padding so the gap to the card edge stays identical.
                    .padding(
                        start = m.cardBodyHorizontal,
                        top = m.cardBodyTop,
                        end = m.cardBodyHorizontal,
                        bottom = if (hasFooter) 0.dp else m.cardBodyBottom,
                    ),
                verticalArrangement = Arrangement.spacedBy(m.postSpacing),
            ) {
                // #281 — topic posts are selectable/copyable (opt-in; default is OFF in PostRenderer).
                PostRenderer(content = post.content, selectable = true, onGoToCitedPost = onGoToCitedPost)
                // #330 — the author signature (web parity), gated by the reading preference. Rendered
                // with the shared PostRenderer (the signature is BBCode/HTML like the body) but in a
                // subdued style: a divider separates it from the body and a reduced alpha makes it
                // subordinate to the post content. No-op when the post carries no signature.
                post.signature?.let { signature ->
                    if (showSignature) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        // #553 — drop the author's web-tuned `[color]` in the signature: on the app theme
                        // (especially dark) those colours read as garish/illegible. The signature then
                        // renders in the neutral subdued body colour (the reduced alpha keeps it
                        // subordinate). Post bodies are unaffected (they don't provide this local).
                        CompositionLocalProvider(LocalIgnoreInlineColors provides true) {
                            PostRenderer(
                                content = signature,
                                modifier = Modifier.alpha(SIGNATURE_ALPHA),
                            )
                        }
                    }
                }
            }
        },
        footer = if (hasFooter) {
            {
                TopicPostActions(
                    onQuote = onQuote,
                    onEdit = onEdit,
                    onToggleMultiQuote = onToggleMultiQuote,
                    multiQuoteSelected = multiQuoteSelected,
                    // Reinjected paddings (#351): the actions row keeps the body gutters, a
                    // m.postSpacing gap above it (the spacing the single body Column used to apply
                    // between the body content and this row) and the card's m.cardBodyBottom below.
                    modifier = Modifier.padding(
                        start = m.cardBodyHorizontal,
                        top = m.postSpacing,
                        end = m.cardBodyHorizontal,
                        bottom = m.cardBodyBottom,
                    ),
                )
            }
        } else {
            null
        },
    )
}

/**
 * #351/#201/#208/#221/#362/#476/#483 — the topic post card's identity line, on the shared
 * [PostIdentityHeader]. A thin adapter: it maps the [Post] fields to the primitive's slots and reinjects
 * the topic's header padding via [modifier] (densities stay feature-owned). The neutral shape — avatar,
 * pseudo, date, optional `⋯` trailing — lives in `:core:ui`; the topic-specific bits stay here:
 *  - `pseudo` slot — the optional `topic_post_index_prefix` + the pseudo (gold-sheen [CreatorPseudoText]
 *    for an RF2 creator #221, plain ellipsised text otherwise), tappable to open the profile (#208);
 *  - `subline` slot — the compact « · édité » marker (#483) when the post was edited;
 *  - `trailing` slot — the per-post `⋯` contextual-menu glyph (#362).
 * Profile-tap labels/min-size and the pseudo's no-min-size convention come from the primitive.
 */
@Composable
private fun TopicPostIdentityHeader(
    post: Post,
    onOpenProfile: (() -> Unit)?,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Phase 2 finish (#208) — tapping the avatar OR the author pseudo opens the profile bottom sheet
    // when `onOpenProfile` is non-null (the date stays inert: review feedback I6). The min-size on the
    // avatar vs no-min-size on the pseudo (so it does not inflate the line) is the primitive's contract.
    val openProfileLabel = if (onOpenProfile != null) {
        stringResource(R.string.topic_open_profile_action)
    } else {
        null
    }
    val menuLabel = stringResource(R.string.topic_post_menu_action)
    PostIdentityHeader(
        author = post.author,
        avatarUrl = post.avatarUrl,
        dateText = post.date.asTopicDate(),
        modifier = modifier,
        onAvatarClick = onOpenProfile,
        onAvatarClickLabel = openProfileLabel,
        // #208 — the avatar carries the 48dp-compliant tap target; the pseudo is a convenience tap at
        // its natural text height (the primitive omits the min-size box on the supplied pseudo slot).
        pseudo = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                post.postIndex?.let { postIndex ->
                    Text(
                        text = stringResource(R.string.topic_post_index_prefix, postIndex),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                val pseudoModifier = if (onOpenProfile != null) {
                    Modifier
                        .weight(weight = 1f, fill = false)
                        .clickable(
                            onClick = onOpenProfile,
                            role = Role.Button,
                            onClickLabel = openProfileLabel,
                        )
                } else {
                    Modifier.weight(weight = 1f, fill = false)
                }
                // #221 — the RF2 creator's pseudo gets the gold sheen easter egg. remember() keyed on
                // the author so canonicalizePseudo (NFC + char walk) runs once per author, not on every
                // recomposition of this hot list row — same off-the-render-path stance as #509.
                val isCreator = remember(post.author) { isRf2Creator(post.author) }
                if (isCreator) {
                    CreatorPseudoText(author = post.author, modifier = pseudoModifier)
                } else {
                    Text(
                        text = post.author,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = pseudoModifier,
                    )
                }
            }
        },
        // #483 — the compact « · édité » marker (beta feedback Azgor). The exact edit time stays in the
        // « … » menu (PostMenuSheet « Édité le … »). Rendered INLINE to the right of the date (dateTrailing
        // slot), same labelMedium / onSurfaceVariant style — identical to the pre-shell single-row layout.
        dateTrailing = if (post.editedAt != null) {
            {
                val editedLabel = stringResource(R.string.topic_post_edited_inline)
                Text(
                    // « · » is a decorative separator — TalkBack reads the contentDescription
                    // (« édité »), so the dot is never vocalised.
                    text = "· $editedLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = editedLabel },
                )
            }
        } else {
            null
        },
        // #362 — per-post contextual menu trigger, flush right of the header. A text glyph, not a
        // Material icon (detekt ForbiddenImport blocks androidx.compose.material.*) — same pattern as
        // PageFab/ReplyFab. Its 48dp touch target sits in the trailing slot, never inflating the pseudo.
        trailing = {
            Text(
                text = "⋯",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clickable(
                        onClick = onOpenMenu,
                        role = Role.Button,
                        onClickLabel = menuLabel,
                    )
                    .semantics { contentDescription = menuLabel },
            )
        },
    )
}

/**
 * #239/#436/#476 — the citation + multi-quote pill strip rendered (via [PostCardShell]'s badges slot)
 * on the neutral card surface just below the identity band, so a pill appearing/disappearing grows the
 * card downward without resizing the tinted band. The call site renders this only when at least one
 * pill is present (`null` badges slot otherwise), so the strip is never an empty row.
 */
@Composable
private fun TopicPostBadges(
    citedCount: Int,
    multiQuoteSelected: Boolean,
    horizontalPadding: Dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Aligned with the post body gutter; top/bottom give breathing room (unchanged from #476).
            .padding(start = horizontalPadding, end = horizontalPadding, top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (citedCount > 0) {
            // #239 — sober pill: how many posts of THIS page cite this one. Page-scoped (cf.
            // citationCountsByNumreponse); jumping to the citing posts is a follow-up.
            // surfaceContainerHighest : a touch above the surfaceContainer card so the pill reads.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = pluralStringResource(R.plurals.topic_post_cited_count, citedCount, citedCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        if (multiQuoteSelected) {
            // #436 — basket-membership pill. primaryContainer : echoes the primary multi-quote
            // border so the two marks read as one selection signal.
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = stringResource(R.string.topic_post_multiquote_selected),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * #146/#147/#436 — the topic post card's footer actions (Modifier / multi-quote « + » / Citer),
 * rendered (via [PostCardShell]'s footer slot) right-aligned and as sober TextButtons so they stay
 * subordinate to the post content. The body↔footer gap and the card's bottom padding ride on
 * [modifier] (reinjected by the call site). « Supprimer » (#292) moved to the contextual menu (#418).
 */
@Composable
private fun TopicPostActions(
    onQuote: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onToggleMultiQuote: (() -> Unit)?,
    multiQuoteSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        if (onEdit != null) {
            TextButton(onClick = onEdit) {
                Text(text = stringResource(R.string.topic_post_edit))
            }
        }
        if (onToggleMultiQuote != null) {
            // #436 — per-post add/remove to the multi-quote basket (RF1 quote+/quote- parity), next to
            // « Citer » (same gate). The glyph + word switch on multiQuoteSelected, echoing the card
            // border + pill. The colour (muted onSurfaceVariant when absent, primary when present) is a
            // SECONDARY cue : the « + »/« ✓ » glyph and the word change carry the state without relying
            // on colour. TalkBack reads the long add/remove label via contentDescription, not the glyph.
            val mqContentDesc = stringResource(
                if (multiQuoteSelected) {
                    R.string.topic_post_menu_multi_quote_remove
                } else {
                    R.string.topic_post_menu_multi_quote_add
                },
            )
            TextButton(
                onClick = onToggleMultiQuote,
                colors = if (multiQuoteSelected) {
                    ButtonDefaults.textButtonColors()
                } else {
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                // #436 — the contentDescription carries the ACTION (« Ajouter/Retirer de la citation
                // multiple »); `selected` carries the STATE so TalkBack announces « sélectionné »
                // independently of the action verb (a real toggle, not a one-shot button). Sighted
                // state stays non-colour-only via the glyph + word + border + pill.
                modifier = Modifier.semantics {
                    contentDescription = mqContentDesc
                    selected = multiQuoteSelected
                },
            ) {
                Text(
                    text = stringResource(
                        if (multiQuoteSelected) {
                            R.string.topic_post_multiquote_remove_short
                        } else {
                            R.string.topic_post_multiquote_add_short
                        },
                    ),
                )
            }
        }
        if (onQuote != null) {
            TextButton(onClick = onQuote) {
                Text(text = stringResource(R.string.topic_post_quote))
            }
        }
    }
}

// #330 — the author signature renders subordinate to the post body: a reduced opacity keeps it
// visually secondary (it shares the body's typography, so colour-only dimming via alpha is the
// least invasive subdued treatment without recolouring PostRenderer's internals).
private const val SIGNATURE_ALPHA = 0.7f

private val topicDateFormatter = DateTimeFormatter
    .ofPattern("dd/MM/yyyy HH:mm:ss", Locale.FRANCE)
    .withZone(ZoneId.of("Europe/Paris"))

// `internal` (#362): PostMenuSheet renders the post date and the « Édité le … » line with the
// exact same format as the post header, so both surfaces always agree.
internal fun java.time.Instant.asTopicDate(): String = topicDateFormatter.format(this)

/**
 * #292 — confirmation before an irreversible post deletion (HFR offers no undo, cf. the #99 flag
 * delete). « Supprimer » is styled as a destructive (error-coloured) action; « Annuler » dismisses.
 */
@Composable
private fun DeletePostConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.topic_post_delete_confirm_title)) },
        text = { Text(text = stringResource(R.string.topic_post_delete_confirm_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(text = stringResource(R.string.topic_post_delete_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.topic_post_delete_confirm_cancel))
            }
        },
    )
}

/**
 * #411 — drives the bottom-action cluster's show-on-scroll-up visibility, derived READ-ONLY from
 * [listState] (it never drives scrolling, so the anchor/restore #307 and the swipe #282 stay
 * untouched). Hides while the list scrolls DOWN, reveals on the first UPWARD scroll — the M3 idiom,
 * matching the collapsible top bar. It also stays visible at the END of the list (and on a short,
 * one-screen topic): reaching the last post is exactly when the reader wants to reply, so the cluster
 * must be there without scrolling back up (#411 beta feedback).
 */
@Composable
private fun rememberBottomActionsVisible(listState: LazyListState): Boolean {
    var visible by remember(listState) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var prevIndex = listState.firstVisibleItemIndex
        var prevOffset = listState.firstVisibleItemScrollOffset
        // snapshotFlow keys on the first-visible item+offset AND canScrollForward, so a recomposition /
        // async layout shift with an identical position never re-reveals the cluster (the last decision
        // is held) — BUT a change in SCROLLABILITY with an unchanged position (e.g. the list shrinks so
        // the current position becomes the end without a scroll) still re-fires the collect and re-
        // evaluates the « visible at the end » rule. Keying on position alone left that case stale until
        // the next real scroll (multi-agent review finding, scored >65). A list that cannot scroll stays
        // visible.
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.canScrollForward,
            )
        }
            .collect { (index, offset, canScrollForward) ->
                visible = when {
                    // At the end of the list (or a short, non-scrollable page) always show: the user is
                    // on the last post and most likely wants to reply, so they should not have to scroll
                    // up to reveal the cluster (#411 beta feedback, tinc t2788220).
                    !canScrollForward -> true
                    index != prevIndex -> index < prevIndex
                    offset != prevOffset -> offset < prevOffset
                    // No real movement (incl. snapshotFlow's initial emission): hold the last decision
                    // so the cluster stays visible on load and never flips without a scroll.
                    else -> visible
                }
                prevIndex = index
                prevOffset = offset
            }
    }
    return visible
}

/** #518 follow-up — one snapshot the reveal reporter reacts to (see [ImmersiveNavBarScrollReporter]). */
private data class NavBarScrollSample(
    val index: Int,
    val offset: Int,
    val canScrollForward: Boolean,
    val itemCount: Int,
)

/**
 * #518 follow-up — reports the topic's raw scroll facts (at-bottom + scrolling-up) UP to `:app` so it
 * can reveal the hidden system navigation bar per the user's chosen mode. READ-ONLY on [listState]
 * (never drives scrolling, like [rememberBottomActionsVisible]). When [active] is false (immersive off /
 * MANUAL mode) it only clears any stale facts once. `:feature:topic` stays free of the reveal-mode enum
 * — it ships facts, `:app` decides.
 *
 * CRITICAL anti-feedback-loop. Revealing the bar consumes the bottom system-bar inset, which SHRINKS the
 * list viewport and so flips `canScrollForward` (and `layoutInfo`) ON ITS OWN — no user gesture. Naively
 * deriving `atBottom = !canScrollForward` would then loop: reach bottom → reveal → inset grows →
 * canScrollForward true → un-reveal → inset shrinks → canScrollForward false → … (the « sursaut » at the
 * bottom, including mid-fling since `isScrollInProgress` stays true while a fling settles).
 *
 * The fix exploits that a viewport-height change leaves `firstVisibleItemIndex` / `firstVisibleItemScroll-
 * Offset` UNTOUCHED (LazyColumn is top-anchored): only a genuine user scroll moves them. So we LATCH
 * `atBottom`: it is SET when the content end is reached while not moving up AND the list actually has
 * content (`itemCount > 0 && !canScrollForward && !scrollingUp` — the itemCount guard avoids latching on
 * an empty/loading list, which would otherwise wrongly reveal the bar at the TOP of a long topic once it
 * loads; it still covers a loaded short topic). It is only CLEARED by a real upward index/offset
 * decrease. The inset-induced `canScrollForward = true` after a reveal therefore cannot clear it (no
 * upward move), and the inset-induced `canScrollForward = false` after a hide cannot re-set it (it lands
 * while `scrollingUp` is true / held).
 *
 * `scrollingUp` is derived only from index/offset deltas, which an inset resize never produces — a pure
 * layout shift is never misread as a scroll direction. (A PROGRAMMATIC scroll — #307 restore — does move
 * index/offset, so it can momentarily seed the direction; a one-shot at landing that self-corrects on the
 * first real user scroll, accepted.)
 */
@Composable
private fun ImmersiveNavBarScrollReporter(
    listState: LazyListState,
    active: Boolean,
    onScrollFacts: (atBottom: Boolean, scrollingUp: Boolean) -> Unit,
) {
    val report by rememberUpdatedState(onScrollFacts)
    if (!active) {
        LaunchedEffect(Unit) { report(false, false) }
        return
    }
    LaunchedEffect(listState) {
        var prevIndex = listState.firstVisibleItemIndex
        var prevOffset = listState.firstVisibleItemScrollOffset
        var scrollingUp = false
        var atBottomLatched = false
        var emitted = false
        var lastAtBottom: Boolean? = null
        var lastScrollingUp: Boolean? = null
        snapshotFlow {
            NavBarScrollSample(
                index = listState.firstVisibleItemIndex,
                offset = listState.firstVisibleItemScrollOffset,
                canScrollForward = listState.canScrollForward,
                itemCount = listState.layoutInfo.totalItemsCount,
            )
        }
            .collect { sample ->
                val movedUp = sample.index < prevIndex ||
                    (sample.index == prevIndex && sample.offset < prevOffset)
                val positionChanged = sample.index != prevIndex || sample.offset != prevOffset
                if (positionChanged) {
                    // Genuine scroll direction — index/offset only move on a real user (or programmatic)
                    // scroll, never on an inset-driven viewport resize.
                    scrollingUp = movedUp
                    // A real upward move is the ONLY thing that clears « at bottom ».
                    if (movedUp) atBottomLatched = false
                }
                prevIndex = sample.index
                prevOffset = sample.offset
                // Reaching the content end of a NON-EMPTY list while not moving up latches « at bottom ».
                // The itemCount guard avoids latching on an empty/loading list (which would wrongly reveal
                // the bar at the top of a long topic). Once latched, the bar's own inset flipping
                // canScrollForward back to true cannot clear it (no upward move).
                if (sample.itemCount > 0 && !sample.canScrollForward && !scrollingUp) {
                    atBottomLatched = true
                }
                val factsChanged = atBottomLatched != lastAtBottom || scrollingUp != lastScrollingUp
                if (!emitted || factsChanged) {
                    lastAtBottom = atBottomLatched
                    lastScrollingUp = scrollingUp
                    emitted = true
                    report(atBottomLatched, scrollingUp)
                }
            }
    }
}

/**
 * #283/#599 — Scaffold-slot host of the bottom cluster. Extracted from [TopicContent] for the
 * detekt cyclomatic-complexity budget. The Scaffold applies the navigation-bar insets to its FAB
 * slot, so no manual padding here; coexists with the #300 scrollbar (slight bottom-right overlap
 * accepted).
 *
 * #599 (vague 3) — composed in EVERY mode, not just Loaded: the slots reserve their geometry from
 * the skeleton on (all invisible while loading), so an affordance that materialises after the
 * parse (« Répondre » needs canReply+auth) lands in its final position instead of shifting the
 * ‹/› page FABs sideways (misclics, dev feedback antiseptiqueIncolore). The null guards on the
 * callbacks are unreachable while a slot is invisible — belt-and-braces.
 */
@Composable
@Suppress("LongParameterList") // hoisted Scaffold-slot host, mirrors the other hosts in this file.
private fun TopicBottomActionsHost(
    state: TopicUiState,
    loaded: TopicUiState.Mode.Loaded?,
    bottomActionsVisible: Boolean,
    multiQuoteSelection: List<Int>,
    onOpenPage: (Int) -> Unit,
    onReply: (subcat: Int, page: Int) -> Unit,
    onMultiQuote: (subcat: Int, page: Int) -> Unit,
) {
    // #411 — tuck the cluster away while reading down, reveal it on the first upward scroll.
    // AnimatedVisibility in the Scaffold's FAB slot simply collapses to nothing when hidden;
    // the slide/fade mirrors the collapsible top bar (#286/#338).
    AnimatedVisibility(
        visible = bottomActionsVisible,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
    ) {
        TopicBottomActions(
            showReply = loaded != null && shouldEnableReply(loaded.topic, state.isAuthenticated),
            showPageFabs = state.showPageFabs,
            canGoPrevious = loaded != null && state.canGoPrevious,
            canGoNext = loaded != null && state.canGoNext,
            // #291 — the « Citer N » FAB shares the reply gate: quoting IS replying.
            multiQuoteCount = loaded?.let {
                effectiveMultiQuoteCount(it.topic, state.isAuthenticated, multiQuoteSelection)
            } ?: 0,
            // Clamp to [1, totalPages]: `canGoPrevious/Next` are derived from `request.page` while
            // the target is computed from the parsed `topic.page`; if those ever desync (HFR clamps
            // an out-of-range page to the last one), the clamp keeps navigation in bounds — same
            // robustness as the header guard and the swipe (#282).
            onPreviousPage = { loaded?.let { onOpenPage((it.topic.page - 1).coerceAtLeast(1)) } },
            onNextPage = { loaded?.let { onOpenPage((it.topic.page + 1).coerceAtMost(it.topic.totalPages)) } },
            onReply = { loaded?.let { onReply(it.topic.subcat, it.topic.page) } },
            onMultiQuote = { loaded?.let { onMultiQuote(it.topic.subcat, it.topic.page) } },
        )
    }
}

/**
 * #283 + bonus — the floating bottom-action cluster: previous/next page mini-FABs and a « Répondre »
 * extended FAB, so posting and page-change are reachable without scrolling back up to the header. Pure
 * presentation: each affordance is gated on the same flags the header already uses, and reuses the
 * existing `onReply`/`onOpenPage` callbacks.
 *
 * #599 (vague 3 Lecture) — FIXED-slot geometry: every affordance owns a reserved [FabSlot] of the
 * small-FAB footprint, empty-but-measured while unavailable, so nothing ever shifts sideways when an
 * affordance (dis)appears — page 1 hides ‹ without moving ›, and « Répondre » materialising after
 * the parse no longer displaces the page FABs under the user's finger (misclics,
 * dev feedback antiseptiqueIncolore). Two deliberate exceptions:
 *  - the #383 « FABs de page » preference OFF drops the ‹/› SLOTS entirely (the user opted out of
 *    page navigation here; there is nothing to keep stable);
 *  - the « ❝N » multi-quote slot is reserved like the others (its arming is a user action, but its
 *    appearance must not shift « Répondre »).
 * An empty slot renders nothing and carries no semantics — invisible to TalkBack.
 */
@Composable
@Suppress("LongParameterList") // hoisted action cluster, mirrors other hoisted composables in this file
private fun TopicBottomActions(
    showReply: Boolean,
    showPageFabs: Boolean,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    multiQuoteCount: Int,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onReply: () -> Unit,
    onMultiQuote: () -> Unit,
) {
    val previousLabel = stringResource(R.string.topic_fab_previous_page)
    val nextLabel = stringResource(R.string.topic_fab_next_page)
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // #291/#599 — the « ❝N » multi-quote slot sits at the FAR LEFT of the cluster: arming the
        // basket materialises it without shifting ‹ › ✎ (nothing is to its left), and while empty
        // its reserved footprint reads as leading whitespace instead of a hole inside the cluster.
        // NOT governed by the #383 page-FABs preference: it is a write affordance the user
        // explicitly armed, not page navigation (call-site zeroes the count when quoting is
        // unavailable).
        FabSlot(visible = multiQuoteCount > 0) {
            MultiQuoteFab(count = multiQuoteCount, onClick = onMultiQuote)
        }
        // #383 — the preference only governs the ‹/› page FABs; « Répondre » keeps its own gate.
        if (showPageFabs) {
            FabSlot(visible = canGoPrevious) {
                PageFab(
                    description = previousLabel,
                    iconRes = fr.forumhfr.redface2.core.ui.R.drawable.ic_chevron_left,
                    onClick = onPreviousPage,
                )
            }
            FabSlot(visible = canGoNext) {
                PageFab(
                    description = nextLabel,
                    iconRes = fr.forumhfr.redface2.core.ui.R.drawable.ic_chevron_right,
                    onClick = onNextPage,
                )
            }
        }
        FabSlot(visible = showReply) {
            ReplyFab(onClick = onReply)
        }
    }
}

/**
 * #599 — one reserved position of the bottom cluster: the small-FAB footprint is ALWAYS measured
 * (min-sized empty Box) so sibling slots never shift when this affordance (dis)appears. The « ❝N »
 * slot may grow past the minimum for a two-digit count — the rare 9→10 growth is accepted.
 */
@Composable
private fun FabSlot(visible: Boolean, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.sizeIn(minWidth = FAB_SLOT_SIZE, minHeight = FAB_SLOT_SIZE),
        contentAlignment = Alignment.Center,
    ) {
        if (visible) content()
    }
}

/** #599 — M3 small-FAB container footprint, the reserved geometry of every [FabSlot]. */
private val FAB_SLOT_SIZE = 40.dp

@Composable
private fun MultiQuoteFab(count: Int, onClick: () -> Unit) {
    // #291 — same SmallFloatingActionButton footprint as PageFab/ReplyFab; the glyph is a
    // decorative « ❝N » (no Material icons — detekt ForbiddenImport blocks
    // androidx.compose.material.*) and the real label rides on contentDescription for TalkBack.
    val label = pluralStringResource(R.plurals.topic_fab_multi_quote, count, count)
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Text("❝$count")
    }
}

@Composable
private fun PageFab(
    description: String,
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
) {
    // #360 / ADR-015 — chevron en vector stroke unifié (poids optique aligné sur la flèche retour),
    // dimensionné en dp via le primitive partagé :core:ui plutôt qu'un glyphe « ‹ »/« › » dépendant
    // de la police. Pas de Material icons (detekt ForbiddenImport). L'étiquette a11y reste sur le FAB,
    // donc l'icône est décorative.
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        RedfaceVectorIcon(resId = iconRes)
    }
}

@Composable
private fun ReplyFab(onClick: () -> Unit) {
    // #360 / ADR-015 — crayon en vector stroke unifié (même poids optique que la flèche retour / les
    // chevrons), via le primitive partagé :core:ui, à la place du glyphe « ✎ » dépendant de la police.
    // Pas de Material icons (detekt ForbiddenImport). L'étiquette a11y reste sur le FAB.
    val replyLabel = stringResource(R.string.topic_fab_reply)
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = replyLabel },
    ) {
        RedfaceVectorIcon(resId = fr.forumhfr.redface2.core.ui.R.drawable.ic_edit)
    }
}

// #220 — write affordances additionally require an authenticated session. A logged-out user
// can still hold a stale cached `canReply = true` row (the topic page cache is intentionally
// not purged on logout, cf. CacheInvalidator), so these gates consult auth explicitly instead
// of trusting `canReply` alone — symmetric with the « Créer topic » FAB
// (CategoryViewModel.canCreateTopic).
internal fun shouldEnableReply(topic: Topic, isAuthenticated: Boolean): Boolean =
    topic.canReply && isAuthenticated

// #291 — the « Citer N » FAB count, zeroed when quoting is unavailable (locked topic, anonymous
// session): the basket may still hold posts, but advertising an unusable action would be a lie.
// Extracted from TopicContent for the detekt cyclomatic-complexity budget.
internal fun effectiveMultiQuoteCount(topic: Topic, isAuthenticated: Boolean, selection: List<Int>): Int =
    if (shouldShowQuoteAction(topic, isAuthenticated)) selection.size else 0

internal fun shouldShowQuoteAction(topic: Topic, isAuthenticated: Boolean): Boolean =
    topic.canReply && isAuthenticated

internal fun shouldShowEditAction(topic: Topic, post: Post, isAuthenticated: Boolean): Boolean =
    post.isEditable && topic.canReply && isAuthenticated

// #292 — « Supprimer » shares the « Modifier » gate: HFR exposes deletion through the same edit
// form, so any post the user can edit, they can delete. The first-post exclusion (deleting it would
// remove the whole topic) is applied at the call site by position, not here.
// #600 (vague 3) — « Dernier message lu » separator gate. `forceRefresh` is #231's flag-tap
// marker: the ONE navigation whose scrollTo is semantically « last read » (the flag handler only
// sets scrollTo when resuming at the last-read page). Every route-replace (pagination #282,
// citation jump #699, overflow landing #226) rebuilds the route WITHOUT forceRefresh, so the
// marker never survives a navigation away from the landing. If forceRefresh ever grows another
// producer, this gate needs its own dedicated route field — cf. TopicActionGatesTest.
internal fun shouldShowLastReadMarker(request: TopicRequest, numreponse: Int): Boolean =
    request.forceRefresh && request.scrollTo == numreponse

internal fun shouldShowDeleteAction(topic: Topic, post: Post, isAuthenticated: Boolean): Boolean =
    post.isEditable && topic.canReply && isAuthenticated

// #292 — the topic's first post is `topic.posts.first()` on page 1. Deleting it would remove the whole
// topic (out of scope for this MVP), so the call site excludes it from the delete affordance. Position
// + identity based: `numreponse` is unique per HFR category, so matching it against the page's first
// row is sufficient within a loaded topic page.
internal fun isFirstPostOfTopic(topic: Topic, post: Post): Boolean =
    topic.page == 1 && post.numreponse == topic.posts.firstOrNull()?.numreponse

// Phase 2D #148 / #220 — « Modifier le premier message ». 6-way conjunction by design: auth,
// FP ownership, postable topic, a real sub-category (FP recategorise is NOT relaxed for subcat=0,
// cf. #213), page 1 (the FP lives there), non-empty posts. Each clause guards a distinct invariant.
@Suppress("ComplexCondition")
internal fun shouldShowEditFirstPost(topic: Topic, isAuthenticated: Boolean): Boolean =
    isAuthenticated &&
        topic.isFirstPostOwner &&
        topic.canReply &&
        topic.subcat > 0 &&
        topic.page == 1 &&
        topic.posts.isNotEmpty()

// #235 — the page-jump field must accept any valid page. Binding the input width to the digit
// count of [totalPages] (instead of a fixed 4-digit ceiling that made pages >= 10000 untypable)
// lets very long topics — e.g. the ~16k-page Ukraine topic — reach their last pages, while a
// small topic stays tight. The jump action already validates `target in 1..totalPages`, so the
// cap only needs to mirror that bound. `maxOf(1, …)` guards a degenerate totalPages <= 0.
internal fun coercePageJumpInput(raw: String, totalPages: Int): String =
    raw.filter(Char::isDigit).take(maxOf(1, totalPages).toString().length)

private const val PAGE_GRID_LIMIT = 40
