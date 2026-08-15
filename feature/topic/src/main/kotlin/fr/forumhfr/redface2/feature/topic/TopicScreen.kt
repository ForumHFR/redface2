package fr.forumhfr.redface2.feature.topic

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import fr.forumhfr.redface2.core.domain.ego.deriveEgoCanonicalPseudo
import fr.forumhfr.redface2.core.domain.ego.isEgoPost
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.Poll
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.model.editor.WritingSurfacePreset
import fr.forumhfr.redface2.core.model.postContentExcerpt
import fr.forumhfr.redface2.core.model.write.QuotedPostPreview
import fr.forumhfr.redface2.core.ui.RedfacePlaceholderScreen
import fr.forumhfr.redface2.core.ui.error.sharedLabelResOrNull
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon
import fr.forumhfr.redface2.core.ui.pager.pageSwipeEdgeHint
import fr.forumhfr.redface2.core.ui.post.HiddenPostCard
import fr.forumhfr.redface2.core.ui.post.PostCardShellFlatBottomEdge
import fr.forumhfr.redface2.core.ui.post.PostIdentityBand
import fr.forumhfr.redface2.core.ui.post.PostIdentityHeader
import fr.forumhfr.redface2.core.ui.post.PostImageActions
import fr.forumhfr.redface2.core.ui.post.PostImageMenuSheet
import fr.forumhfr.redface2.core.ui.post.PostImageTarget
import fr.forumhfr.redface2.core.ui.post.PostListScaffold
import fr.forumhfr.redface2.core.ui.post.ReadingPostCard
import fr.forumhfr.redface2.core.ui.post.ReadingPostCardPresentation
import fr.forumhfr.redface2.core.ui.post.collectPostMediaUrls
import fr.forumhfr.redface2.core.ui.post.retryFailedPostMedia
import fr.forumhfr.redface2.core.ui.theme.LocalBlockedQuoteAuthors
import fr.forumhfr.redface2.core.ui.theme.LocalDisplayMetrics
import fr.forumhfr.redface2.core.ui.theme.rememberCreatorPseudoBrush
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
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
     * Open the FULL-SCREEN reply editor COLD for this topic — the reply FAB under the FULL_EDITOR
     * preset, « Citer » routed to the full editor (#806), and the #823 long-press. The lambda
     * receives the topic's sub-category id, the current page, and the armed quote cards as FULL
     * previews in citation order (empty for a plain reply) ; cat and topicId are derived from
     * [request]. `:app` hands the previews to the editor through the in-memory handoff (never the
     * route). #843 — a COLD open sets `resumeSharedDraft = false`, so an existing #405 draft is
     * SURFACED via the restore banner (Restaurer / Ignorer) instead of being silently re-applied:
     * these cold paths had lost that choice when #829/#833 reused the escalation flag.
     */
    onReply: (subcat: Int, page: Int, quotes: List<QuotedPostPreview>) -> Unit,
    /**
     * #843 — the quick-reply sheet's ESCALATION to the full editor (the only genuine « resume the
     * same composition » case). Same handoff as [onReply] but `:app` sets `resumeSharedDraft = true`
     * (#790): the sheet JUST wrote the #405 row, so the editor auto-applies it (appending to any
     * typed text) WITHOUT a banner — re-proposing a draft the user is visibly continuing would be
     * noise. Defaults to a no-op for non-topic callers (previews/tests never escalate).
     * #868-#870 — `consumesBasket` forwards the sheet session's basket consumption : true only when
     * the escalated sheet was opened by « Citer N » (its successful submit then empties the basket).
     */
    onEscalateToFullEditor: (
        subcat: Int,
        page: Int,
        quotes: List<QuotedPostPreview>,
        consumesBasket: Boolean,
    ) -> Unit = { _, _, _, _ -> },
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
    /**
     * #285 — leave the topic and go back to the screen that opened it (topic list / flags).
     * Wired to a back-stack pop in `:app`. Surfaced as an explicit back arrow in the top app
     * bar so the user never has to rely on the system / gesture back to exit a topic.
     */
    onBack: () -> Unit,
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
     * #792 — « Envoyer un MP » from a post's contextual menu : `:app` opens the NEW-conversation
     * MP composer with [author] prefilled as recipient (`PrivateMessageComposeRoute.prefilledRecipient`
     * was designed for exactly this entry point). Only emitted on an authenticated session, never
     * for the user's own posts, and only when the post carries a real profile — cf.
     * [shouldShowSendPrivateMessage].
     */
    onSendPrivateMessage: (author: String) -> Unit = {},
    /**
     * #307 — saved read position to restore for the ENTRY landing of this `(cat, post, page)`, or
     * `null` when nothing should be restored. `:app` resolves the entry priority chain
     * (`resolveTopicScrollRestoration`: route `scrollTo` > saved anchor > top) BEFORE threading
     * the value here, so a non-null anchor already means « the saved position won » — the screen
     * applies it once the entry page's first `Loaded` emission lands, exactly once, and it can
     * never compete with the `ScrollToPost` effect (its routes resolve to `null` here). In-topic
     * page changes never come back through this seam : their landings are armed by the in-VM page
     * engine (#895 étape 4) and delivered as page-scoped scroll effects.
     */
    restoreScrollAnchor: TopicScrollAnchor? = null,
    /**
     * #307 — reports the read position (with the CANONICAL page it belongs to — the in-VM engine
     * may have switched pages since entry, cf. #895 étape 4) when the screen leaves the
     * composition, so `:app` can cache it per `(cat, post, page)` (twin of [onTitleLoaded] / the
     * title cache). Fired from a single `DisposableEffect` — the unique save point covering EVERY
     * departure (back, tab switch, editor push) — and only after a page actually loaded, so a
     * landing abandoned while still `Loading` never clobbers a previously saved position with
     * `(0, 0)`.
     */
    onScrollAnchorSaved: (page: Int, anchor: TopicScrollAnchor) -> Unit = { _, _ -> },
    /**
     * #895 étape 4 (PR 2) — pending full-editor submit outcome for THIS topic, published by `:app`
     * BEFORE the editor pop and matched on `(cat, post)` at the nav seam. Consumed exactly once
     * (keyed on [TopicSubmitResult.eventId]) : the screen forwards it to
     * [TopicViewModel.applySubmitResult] and immediately acknowledges through
     * [onSubmitResultConsumed]. `null` when no submit is pending.
     */
    pendingSubmitResult: TopicSubmitResult? = null,
    /** #895 étape 4 (PR 2) — clears the `:app` pending-submit slot once the result was applied. */
    onSubmitResultConsumed: () -> Unit = {},
    /**
     * #291 / #604 lot 3 — the multi-quote selection of THIS topic as FULL previews, in selection
     * order. Owned by `:app` (the basket must survive the editor round-trip and re-entering the
     * topic — and, historically, the pre-#895 per-page entry swap, like the title cache); the
     * screen renders the count and the per-post toggle state, and under the full-screen threshold
     * pre-arms the quick-reply sheet's cards from them.
     */
    multiQuoteSelections: List<QuotedPostPreview> = emptyList(),
    /**
     * #291 — toggles a post in the multi-quote basket. Only invoked under the same gate as
     * « Citer » (`shouldShowQuoteAction`): a topic the user cannot reply to has nothing to quote.
     */
    onToggleMultiQuote: (preview: QuotedPostPreview) -> Unit = {},
    /**
     * #291 / #604 lot 3 — « Citer N » AT OR ABOVE the full-screen threshold : opens the editor
     * with the basket's cards (`:app` hands the previews over in memory and clears the basket).
     * Below the threshold the screen opens the quick-reply sheet itself and consumes the basket
     * through [onClearMultiQuote] instead. Receives the topic's `(subcat, page)` like [onReply].
     */
    onMultiQuote: (subcat: Int, page: Int) -> Unit = { _, _ -> },
    /**
     * #604 lot 3 — clears the multi-quote basket after the sheet consumed it (« Citer N » below
     * the threshold) : the cards live on in the sheet's ViewModel, and backing out must not
     * re-arm a stale « Citer N » — same intent-consumed rule as the full-screen path.
     * #436 — also « Tout vider » : a long press on the « Citer N » FAB empties the whole basket
     * in one gesture. Owned by `:app` (the basket lives there); the screen only triggers the reset.
     */
    onClearMultiQuote: () -> Unit = {},
    /**
     * #465 — the user's MANUAL poll-expansion choice for THIS topic, owned by `:app` so it survives
     * leaving and reopening the topic (like the multi-quote basket / scroll anchors ; hoisted when
     * page changes still swapped the `TopicRoute`, pre-#895 étape 4). `null` means « no manual
     * choice yet — follow the [TopicUiState.pollsExpandedDefault] setting »; `true` / `false`
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
    val favoriteAtPostState by viewModel.favoriteAtPostState.collectAsStateWithLifecycle()
    val lazyListState = rememberLazyListState()
    // #518 follow-up — report scroll facts up so `:app` can reveal the hidden system nav bar per the
    // chosen mode. No-op (and clears stale facts) when the feature is inactive.
    ImmersiveNavBarScrollReporter(
        listState = lazyListState,
        active = immersiveNavBarRevealActive,
        onScrollFacts = onImmersiveNavBarScroll,
    )
    // #895 étape 4 — the reader's CURRENT position, read at call time from the screen-owned
    // LazyListState (raw index/offset, header-aware — same shape as the #307 disposal save).
    // Departure anchors for the page engine are always captured tap-time (#782 rationale).
    val currentAnchor = {
        TopicScrollAnchor(
            index = lazyListState.firstVisibleItemIndex,
            offset = lazyListState.firstVisibleItemScrollOffset,
        )
    }
    // Gate r1 (PR 2) — the list-alignment marker : a switch swaps the CONTENT before its landing
    // moves the POSITION, and inside that window the canonical page already points to the new
    // page. Every position persist below (settle report, disposal save, tap-time departure
    // anchors) is gated on « the list is aligned with the canonical page », so a late fling
    // settle or a dispose can never record page N's coordinates under page N+1.
    val alignment = remember { TopicListAlignment() }
    // Gate r1 — tap-time departure anchor, but ONLY while aligned : right after a rapid second
    // page tap the list may still sit at the previous page's offset. A null departure just falls
    // back to the engine's stored anchor for the departed page.
    val alignedDepartureAnchor = {
        val current = viewModel.state.value
        currentAnchor().takeIf {
            alignment.shouldPersist(
                canonicalPage = current.request.page,
                isLoaded = current.mode is TopicUiState.Mode.Loaded,
            )
        }
    }
    // #782 / #895 étape 4 — unwind ONE quote jump on back while the in-VM chain is non-empty ;
    // once empty the handler disables itself and the next back pops out of the topic as usual.
    // Composed inside the screen (next to the ViewModel that owns the chain) — the historical
    // :app interception died with the route-replace navigation.
    BackHandler(enabled = state.canReturnFromJump) {
        viewModel.returnFromJump(alignedDepartureAnchor())
    }
    // #895 étape 4 (PR 2) — consume the pending full-editor submit outcome exactly once per
    // eventId : hand it to the retained ViewModel (in-place force refresh + landing) and clear
    // the :app slot. The quick-reply sheet path below calls applySubmitResult directly.
    LaunchedEffect(pendingSubmitResult?.eventId) {
        pendingSubmitResult?.let { result ->
            viewModel.applySubmitResult(result.targetPage, result.scrollTo)
            onSubmitResultConsumed()
        }
    }
    // #895 étape 4 — feed the engine's per-page anchor map on every scroll settle (drag/fling
    // end), so revisit landings restore the exact reading position. `drop(1)` skips the initial
    // idle emission (reporting (0, 0) before any scroll would clobber a restored anchor) ; the
    // Loaded gate skips settles on a skeleton.
    LaunchedEffect(Unit) {
        snapshotFlow { lazyListState.isScrollInProgress }
            .drop(1)
            .filter { scrolling -> !scrolling }
            .collect {
                // Gate r1 — a settle that outlives a page switch (fling ends after an LRU
                // activation) must not record the old page's coordinates under the new page.
                val current = viewModel.state.value
                val aligned = alignment.shouldPersist(
                    canonicalPage = current.request.page,
                    isLoaded = current.mode is TopicUiState.Mode.Loaded,
                )
                if (aligned) {
                    viewModel.reportPageAnchor(currentAnchor())
                }
            }
    }
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
    val searchResultsEndMsg = stringResource(R.string.topic_search_results_list_end)
    // #809 — flag-removal feedback messages (resolved upfront, same rationale).
    val flagRemovedMsg = stringResource(R.string.topic_remove_flag_success)
    val flagRemoveFailedMsg = stringResource(R.string.topic_remove_flag_failure)
    val favoriteAddedMsg = stringResource(R.string.topic_post_favorite_added)
    val favoriteFailedMsg = stringResource(R.string.topic_post_favorite_failed)
    val flagNotFoundMsg = stringResource(R.string.topic_remove_flag_not_found)
    // #292 — delete feedback messages, resolved upfront (same rationale as refreshFailedMsg).
    val deleteSuccessMsg = stringResource(R.string.topic_post_delete_success)
    val deleteFailedLoginMsg = stringResource(R.string.topic_post_delete_failed_login)
    val deleteFailedLockedMsg = stringResource(R.string.topic_post_delete_failed_locked)
    val deleteFailedGenericMsg = stringResource(R.string.topic_post_delete_failed_generic)
    // #292 — `numreponse` awaiting delete confirmation (null = no dialog). Local UI state: the
    // confirmation is a pure view concern, only the confirmed deletion reaches the ViewModel.
    var deleteCandidate by rememberSaveable { mutableStateOf<Int?>(null) }
    // #809 — long-press flag removal. The confirmation dialog is state-driven (the ViewModel owns the
    // resolve → confirm → remove flow); the outcomes ride the screen's single TopicEffect collector
    // below, like every other one-shot Toast on this screen.
    val removeTopicFlagState by viewModel.removeTopicFlagState.collectAsStateWithLifecycle()

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
        alignment = alignment,
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
                    val landedState = viewModel.state.first { it.mode is TopicUiState.Mode.Loaded }
                    val loadedMode = landedState.mode as TopicUiState.Mode.Loaded
                    val index = loadedMode.topic.posts.indexOfFirst { it.numreponse == effect.numreponse }
                    if (index >= 0) {
                        // +1 because the LazyColumn header card occupies item 0.
                        val target = index + 1
                        lazyListState.scrollToItem(target)
                        // Gate r1/r2 — aligned only AFTER the scroll actually applied (a suspension
                        // or disposal mid-landing must keep persists blocked) ; the #197 re-anchor
                        // below only re-pins the same target, the position keeps describing this page.
                        alignment.onLandingApplied(landedState.request.page)
                        // #197 — block images above the target grow from 160dp to up to 480dp once
                        // Coil decodes them, shifting the offset *after* this one-shot scroll and
                        // leaving the target off-screen on a cold image cache. Keep it pinned while
                        // the layout settles (bails on user scroll, bounded by a frame budget).
                        lazyListState.reanchorWhileMediaSettles(target)
                    } else {
                        // Gate r2 — not-found : the no-scroll DECISION is the landing application
                        // (the content is this page, at a position the user now owns).
                        alignment.onLandingApplied(landedState.request.page)
                    }
                }
                TopicEffect.ScrollToTopOfResults -> {
                    // #879 — a filtered result page replaced the list in place : reposition at the
                    // top (item 0 = header slot) so its first results are on screen.
                    viewModel.state.first { it.mode is TopicUiState.Mode.Loaded }
                    lazyListState.scrollToItem(0)
                }
                is TopicEffect.ScrollToEndOfPage -> {
                    // Issue #200 — post-reply landing : HFR anchored `#bas`, the parser couldn't
                    // extract a numreponse, so we land on the last item of the freshly-refreshed
                    // page. The new post is by definition the last one HFR served on this page.
                    // Gate #895 r3/r6 — the landing is page-scoped and the wait completes when the
                    // page either LOADS or is ABANDONED (switch to another page) : a stale effect —
                    // whether already stale on consumption or superseded mid-wait — can never wedge
                    // this sequential collector. The scroll only fires if the page still matches
                    // and actually loaded.
                    val landed = viewModel.state.first {
                        it.request.page != effect.page || it.mode is TopicUiState.Mode.Loaded
                    }
                    val loadedMode = landed.mode as? TopicUiState.Mode.Loaded
                    if (landed.request.page == effect.page && loadedMode != null) {
                        if (loadedMode.topic.posts.isNotEmpty()) {
                            // +1 for the header card (same offset rationale as ScrollToPost above).
                            lazyListState.scrollToItem(loadedMode.topic.posts.size)
                        }
                        // Gate r1/r2 — aligned only AFTER the scroll applied (or after the
                        // empty-page decision skipped it) : a suspension or disposal mid-landing
                        // must keep persists blocked.
                        alignment.onLandingApplied(effect.page)
                    }
                }
                is TopicEffect.ScrollToAnchor -> {
                    // #895 étape 4 — revisit / jump-return landing : restore the saved reading
                    // position (raw LazyListState primitives ; clamps to bounds if the content
                    // changed). Unwired until the navigation switch-over — only the in-VM page
                    // engine emits it. Page-scoped (gate r3/r6) : wait for loaded-or-abandoned,
                    // scroll only if the page still matches and loaded.
                    val landed = viewModel.state.first {
                        it.request.page != effect.page || it.mode is TopicUiState.Mode.Loaded
                    }
                    if (landed.request.page == effect.page && landed.mode is TopicUiState.Mode.Loaded) {
                        lazyListState.scrollToItem(effect.anchor.index, effect.anchor.offset)
                        // Gate r1/r2 — aligned only AFTER the scroll applied.
                        alignment.onLandingApplied(effect.page)
                    }
                }
                is TopicEffect.ScrollToTop -> {
                    // #895 étape 4 — default landing of a freshly-switched page : the entry (and
                    // its LazyListState) now survive the switch, so the reset must be explicit.
                    // Page-scoped (gate r3/r6) : wait for loaded-or-abandoned, scroll only if the
                    // page still matches and loaded.
                    val landed = viewModel.state.first {
                        it.request.page != effect.page || it.mode is TopicUiState.Mode.Loaded
                    }
                    if (landed.request.page == effect.page && landed.mode is TopicUiState.Mode.Loaded) {
                        lazyListState.scrollToItem(0)
                        // Gate r1/r2 — aligned only AFTER the scroll applied.
                        alignment.onLandingApplied(effect.page)
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
                TopicEffect.TopicFlagRemoved -> {
                    // #809 — delflag confirmed; the Drapeaux caches are already reconciled.
                    android.widget.Toast.makeText(
                        context,
                        flagRemovedMsg,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                TopicEffect.TopicFlagRemovalFailed -> {
                    android.widget.Toast.makeText(
                        context,
                        flagRemoveFailedMsg,
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                TopicEffect.PostFavoriteAdded -> {
                    // #986 — addflag confirmé ; le repository a déjà réconcilié le cache Drapeaux.
                    android.widget.Toast.makeText(
                        context,
                        favoriteAddedMsg,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                TopicEffect.PostFavoriteAddFailed -> {
                    android.widget.Toast.makeText(
                        context,
                        favoriteFailedMsg,
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                TopicEffect.TopicFlagNotFound -> {
                    android.widget.Toast.makeText(
                        context,
                        flagNotFoundMsg,
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
        onEscalateToFullEditor = onEscalateToFullEditor,
        // Vague 4 (#604) lot 1 / #895 étape 4 — a reply POSTed from the quick-reply sheet goes
        // straight to the retained ViewModel (in-place force refresh, #200/#226) : the sheet
        // never entered the back stack and the topic entry no longer gets replaced.
        onQuickReplySubmitted = viewModel::applySubmitResult,
        onEdit = onEdit,
        onEditFirstPost = onEditFirstPost,
        // #895 étape 4 — MANUAL page change (swipe, header pager, ‹/› FABs, boundary cards) :
        // the in-VM engine switches in place (LRU snapshot, armed landing) — no navigation.
        // The departure anchor is captured tap-time so revisiting this page restores it.
        onOpenPage = { targetPage ->
            viewModel.switchToPage(targetPage, alignedDepartureAnchor())
        },
        // #699/#782 — jump to a cited post : the engine pushes the departure {page, tap-time
        // anchor} on its jump chain and lands on the target (highlight via ScrollToPost).
        onGoToPost = { page, numreponse ->
            viewModel.goToPost(page, numreponse, alignedDepartureAnchor())
        },
        onOpenProfile = onOpenProfile,
        onSendPrivateMessage = onSendPrivateMessage,
        onDeleteRequest = { numreponse -> deleteCandidate = numreponse },
        favoriteAtPostState = favoriteAtPostState,
        onFavoriteMenuOpened = viewModel::resolveFavoriteAtPostState,
        // #986 — le ViewModel construit le FlagAddContext depuis la page courante et le `ref`
        // que HFR a émis pour ce post ; l'écran ne calcule aucune position.
        onFavoriteAction = viewModel::requestAddFavoriteAtPost,
        multiQuoteSelections = multiQuoteSelections,
        onToggleMultiQuote = onToggleMultiQuote,
        onMultiQuote = onMultiQuote,
        onClearMultiQuote = onClearMultiQuote,
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

    // #809 — confirmation gate before the delflag call. Renders only while the ViewModel is in the
    // Confirming state; confirming moves to Removing (action disabled) and fires the removal.
    (removeTopicFlagState as? RemoveTopicFlagState.Confirming)?.let { confirming ->
        RemoveTopicFlagConfirmDialog(
            flag = confirming.flag,
            onConfirm = viewModel::confirmRemoveTopicFlag,
            onDismiss = viewModel::cancelRemoveTopicFlag,
        )
    }

    if (favoriteAtPostState is FavoriteAtPostState.ConfirmingMove) {
        MoveFavoriteConfirmDialog(
            onConfirm = viewModel::confirmMoveFavorite,
            onDismiss = viewModel::cancelMoveFavorite,
        )
    }
}

/**
 * #809 — M3 confirmation dialog shown before the `delflag.php` call, mirroring the Drapeaux view's
 * `RemoveFlagConfirmationDialog` (#99). Spells out the topic title so the user knows exactly what is
 * being un-flagged — the removal is not undoable in-app (no optimistic re-add).
 */
@Composable
private fun RemoveTopicFlagConfirmDialog(
    flag: Flag,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.topic_remove_flag_dialog_title)) },
        text = { Text(stringResource(R.string.topic_remove_flag_dialog_message, flag.title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.topic_remove_flag_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.topic_remove_flag_dialog_cancel))
            }
        },
    )
}

/** #986 — confirmation before replacing the topic's single, position-unknown HFR favourite. */
@Composable
private fun MoveFavoriteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.topic_post_favorite_move_dialog_title)) },
        text = { Text(stringResource(R.string.topic_post_favorite_move_dialog_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.topic_post_favorite_move_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.topic_post_favorite_move_dialog_cancel))
            }
        },
    )
}

/**
 * #197 — keep [target] anchored at the top of the viewport while upstream block images settle.
 *
 * An UNMEASURED `PostBlock.Image` (cold intrinsic cache) renders a `SubcomposeAsyncImage` that
 * starts in the deterministic §6 COLD slot (`coldBlockSlotDp`, since #957 — formerly the
 * `blockImageMinHeight` 160.dp grow-on-load slot) while loading/erroring and settles to its exact
 * web-parity box (`blockImageDisplaySize`) once the intrinsic size lands.
 * Any block image in a post *above* the deep-link target shifts the cumulative scroll offset (in
 * either direction) *after* the initial one-shot `scrollToItem`, leaving the target scrolled
 * off-screen. A warm cache sizes the box exactly before the first measure (#249), which is why #197
 * only reproduces on a cold cache.
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
 * RESTORE: waits for the ENTRY page's first `Loaded` emission OR its abandonment (the in-VM
 * engine may switch pages before the entry page ever loads — waiting unconditionally would apply
 * the entry anchor to the wrong page ; same loaded-or-abandoned pattern as the page-scoped scroll
 * effects, gate r6), then applies the anchor exactly once. Subsequent `Loaded` emissions
 * (cache→network refresh of the stale path, manual pull-to-refresh, post-delete reload, in-VM
 * page switches) never re-scroll: the effect has already completed. The entry priority chain was
 * resolved by `:app` — see `restoreScrollAnchor` on [TopicScreen] ; in-topic landings belong to
 * the engine (#895 étape 4).
 *
 * SAVE: `onDispose` is the ONE save point — disposal covers every departure (back, tab switch,
 * editor push) with a single write. The anchor is saved under the CANONICAL page read from
 * [state] at disposal time (the engine may have switched pages since entry), and ONLY while the
 * [TopicListAlignment] marker says the list is aligned with that page (gate r1) : a disposal
 * racing a fresh switch — content swapped, landing not yet applied — must not save the old
 * page's position under the new page, and a page abandoned while still Loading must not clobber
 * the real position saved by an earlier visit.
 */
@Suppress("LongParameterList") // Private effect holder: the params are TopicScreen's own
// restoration inputs threaded as-is; grouping them into a holder type would only add indirection.
@Composable
private fun TopicScrollRestorationEffects(
    state: StateFlow<TopicUiState>,
    lazyListState: LazyListState,
    request: TopicRequest,
    restoreScrollAnchor: TopicScrollAnchor?,
    alignment: TopicListAlignment,
    onScrollAnchorSaved: (page: Int, anchor: TopicScrollAnchor) -> Unit,
) {
    LaunchedEffect(Unit) {
        val landed = state.first {
            it.request.page != request.page || it.mode is TopicUiState.Mode.Loaded
        }
        if (landed.request.page == request.page && landed.mode is TopicUiState.Mode.Loaded) {
            when {
                // Gate r1/r2 — aligned AFTER the restore scroll ran. The resolver never hands an
                // anchor when the route carries a scrollTo (FollowScrollTo wins), so this branch
                // and the ScrollToPost landing are mutually exclusive.
                restoreScrollAnchor != null -> {
                    lazyListState.scrollToItem(restoreScrollAnchor.index, restoreScrollAnchor.offset)
                    alignment.onLandingApplied(request.page)
                }
                // Gate r3 — a TRUE default-top start only : with a route scrollTo the ScrollToPost
                // effect owns the entry landing, and aligning here on the bare first Loaded would
                // re-open the persist window before (or during) its scroll. ScrollToPost aligns
                // after its own application instead.
                request.scrollTo == null -> alignment.onLandingApplied(request.page)
            }
            // An abandoned entry never aligns here — the switched page's own landing effect will.
        }
    }
    DisposableEffect(request.cat, request.post) {
        onDispose {
            // The save key is the CANONICAL page at disposal time — never the (frozen) route
            // page. Gate r1 — skipped while the list is not aligned with that page (a dispose
            // racing a fresh switch, before its landing applied, would otherwise save the OLD
            // page's position under the NEW page).
            val departed = state.value
            val aligned = alignment.shouldPersist(
                canonicalPage = departed.request.page,
                isLoaded = departed.mode is TopicUiState.Mode.Loaded,
            )
            if (aligned) {
                onScrollAnchorSaved(
                    departed.request.page,
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
// LongParameterList: state-hoisted Composable, each param has a distinct call-site.
// CyclomaticComplexMethod: the #806 tap-time surface routing adds one two-branch `when` per write
// entry point (FAB / « Citer » / « Citer N ») — flat dispatches, the decision table itself lives
// in the pure `writingSurfaceFor`.
@Suppress("LongParameterList", "CyclomaticComplexMethod")
internal fun TopicContent(
    state: TopicUiState,
    listState: LazyListState,
    onIntent: (TopicIntent) -> Unit,
    onBack: () -> Unit,
    onReply: (subcat: Int, page: Int, quotes: List<QuotedPostPreview>) -> Unit,
    // #843 — the quick-reply sheet's escalation (resumeSharedDraft = true, silent append) ; distinct
    // from [onReply] which is a COLD full-editor open (resumeSharedDraft = false → restore banner).
    // #868-#870 — carries the session's basket consumption (cf. TopicScreen KDoc).
    onEscalateToFullEditor: (
        subcat: Int,
        page: Int,
        quotes: List<QuotedPostPreview>,
        consumesBasket: Boolean,
    ) -> Unit = { _, _, _, _ -> },
    onEdit: (subcat: Int, page: Int, numreponse: Int) -> Unit,
    onEditFirstPost: (subcat: Int, page: Int, numreponse: Int) -> Unit,
    onOpenPage: (Int) -> Unit,
    // #699 — quote-header tap, threaded down to the post cards (cf. TopicScreen KDoc).
    onGoToPost: (page: Int, numreponse: Int) -> Unit = { _, _ -> },
    onOpenProfile: (userId: Int, pseudo: String, avatarUrl: String?) -> Unit = { _, _, _ -> },
    // #792 — « Envoyer un MP » entry of the post menu, forwarded up to `:app` (MP composer).
    onSendPrivateMessage: (author: String) -> Unit = {},
    // #292 — a per-post « Supprimer » tap; the screen owns the confirmation dialog, so this only
    // requests it (carrying the post's numreponse). Never invoked for the first post (excluded).
    onDeleteRequest: (numreponse: Int) -> Unit = {},
    favoriteAtPostState: FavoriteAtPostState = FavoriteAtPostState.Unknown,
    onFavoriteMenuOpened: () -> Unit = {},
    /** #986 — action already gated by the resolved topic-level HFR favourite state. */
    onFavoriteAction: (Post) -> Unit = {},
    // #291 / #604 lot 3 — multi-quote selection (owned by :app, full previews) + its actions :
    // toggle on the post menu, « Citer N » on the floating cluster (threshold-routed below),
    // and the basket clear once the sheet consumed the cards.
    multiQuoteSelections: List<QuotedPostPreview> = emptyList(),
    onToggleMultiQuote: (preview: QuotedPostPreview) -> Unit = {},
    onMultiQuote: (subcat: Int, page: Int) -> Unit = { _, _ -> },
    // #436 — empties the whole basket (« Tout vider », long-press on the « Citer N » FAB).
    onClearMultiQuote: () -> Unit = {},
    // #465 — the topic's manual poll choice (owned by :app, null = follow the global default) +
    // the callback recording a tap on the poll card. Threaded to the header card's poll.
    pollManualExpanded: Boolean? = null,
    onPollExpansionChanged: (Boolean) -> Unit = {},
    // Vague 4 (#604) lot 1 — HFR accepted a quick-reply POST. Since #895 étape 4 this feeds
    // `TopicViewModel.applySubmitResult` directly (wired at the stateful entry point): the retained
    // engine force-refreshes and lands the submit — no route refresh (historically `:app` bumped a
    // submitSignal on the route, the same path as the full editor's onSubmitSucceeded, #200).
    onQuickReplySubmitted: (targetPage: Int?, scrollTo: Int?) -> Unit = { _, _ -> },
) {
    // #285 — the topic title and #284 — the page counter live in a persistent top app bar so they
    // stay visible while the user scrolls (the in-card title/caption scrolls away). While loading,
    // the title falls back to the cached hint (or a generic label) and the counter to « Chargement… »
    // — never a page total that has not been parsed yet (#622).
    val loaded = state.mode as? TopicUiState.Mode.Loaded
    // #813/#960 — an EXPLICIT user refresh (pull-to-refresh, double-tap) retries the media that
    // FAILED among the displayed posts' urls, strictly scoped (Sol r3, lock #1): the ledger bumps
    // only the failed urls' generations, so healthy images are never re-probed nor re-decoded.
    // Replaces the pre-#960 process-wide clear + screen-owned refresh-generation bump.
    val refreshWithMediaRetry = {
        loaded?.let { mode ->
            retryFailedPostMedia(mode.topic.posts.flatMapTo(HashSet()) { collectPostMediaUrls(it.content) })
        }
        onIntent(TopicIntent.Refresh)
    }
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
    // Vague 4 (#604) lots 1-2 / #806 — the reply FAB and « Citer » open the quick-reply sheet
    // when the user's writing-surface preset routes them there (writingSurfaceFor, decided at
    // tap time) ; the sheet escalates to the full-screen editor through onReply. Local UI state
    // (like the page picker) : non-null while the sheet is up, carrying the reply coordinates
    // plus the card « Citer » pre-arms (null from the FAB).
    var quickReplyFor by rememberQuickReplyLaunch()
    // #291 — the per-post toggle checkmarks and the « ❝N » count only need the numreponses.
    val multiQuoteNumreponses = multiQuoteSelections.map { it.numreponse }
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
                multiQuoteSelection = multiQuoteNumreponses,
                onOpenPage = onOpenPage,
                // #806 — the surface is decided AT TAP TIME from the preset (never re-evaluated
                // on recomposition, never migrating an already-open sheet). No quotes here, so
                // only the FULL_EDITOR preset skips the sheet.
                onReply = { subcat, page ->
                    when (writingSurfaceFor(state.writingSurfacePreset, quoteCount = 0)) {
                        WritingSurface.SHEET -> quickReplyFor = QuickReplyLaunch(
                            request = QuickReplyRequest(
                                cat = state.request.cat,
                                subcat = subcat,
                                topicId = state.request.post,
                                page = page,
                            ),
                        )
                        // #843 — cold full-editor open (no sheet in flight): in-memory quote handoff
                        // (empty) + PostEditorRoute(resumeSharedDraft = false) → an existing draft is
                        // offered via the restore banner, not silently re-applied.
                        WritingSurface.FULL_EDITOR -> onReply(subcat, page, emptyList())
                    }
                },
                // #604 lot 3 / #806 — preset routing (mockup P3, « le cas qui force le plein
                // écran ») : when the sheet wins, the cards are pre-armed and the basket is
                // consumed HERE (they live on in the sheet's ViewModel) ; when the full-screen
                // editor wins (3+ under SHEET, any citation under SHEET_EXCEPT_QUOTES, always
                // under FULL_EDITOR), the `:app` path (in-memory handoff + basket clear) takes
                // over. The sheet branch snapshots the selection BEFORE its local clear so the
                // launch can never observe a half-emptied basket ; the full-editor branch
                // delegates to the existing `:app` contract, which reads the hoisted basket and
                // hands it over BEFORE clearing it (RedfaceNavigation, « Citer N » entry) — the
                // local snapshot is not what that branch ships (gate Codex #806).
                onMultiQuote = { subcat, page ->
                    val selection = multiQuoteSelections.toList()
                    when (writingSurfaceFor(state.writingSurfacePreset, quoteCount = selection.size)) {
                        WritingSurface.FULL_EDITOR -> onMultiQuote(subcat, page)
                        WritingSurface.SHEET -> {
                            // #868/#869 — the basket is NO LONGER cleared here : closing the sheet
                            // without sending keeps the selection armed (the « Citer N » FAB and
                            // counter survive a cancel). The clear happens on the sheet's
                            // SubmitSucceeded (or its escalation's) via consumesBasket below.
                            quickReplyFor = QuickReplyLaunch(
                                request = QuickReplyRequest(
                                    cat = state.request.cat,
                                    subcat = subcat,
                                    topicId = state.request.post,
                                    page = page,
                                ),
                                initialQuotes = selection,
                                consumesBasket = true,
                            )
                        }
                    }
                },
                // #436 — « Tout vider » : the long press on « ❝N » resets the hoisted basket.
                onClearMultiQuote = onClearMultiQuote,
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
                    // #182 (#937) — magnifier state hoisted above the pull-to-refresh wrapper:
                    // the PTR suspension and the reset chip read it here; the gesture and the draw
                    // layer consume it in TopicLoadedContent.
                    val zoomAnimationScope = rememberCoroutineScope()
                    val zoomState = rememberTopicZoomState(
                        // Full route identity (§2.1) — two topics on the same page number must
                        // never share a zoom ; a page change of the same topic resets too.
                        pageKey = Triple(state.request.cat, state.request.post, mode.topic.page),
                        animationScope = zoomAnimationScope,
                    )
                    // derivedStateOf: the composition only recomposes on the 1× ↔ zoomed TRANSITION,
                    // never per pinch frame (scale/panX are read in the draw phase only).
                    val isZoomed by remember(zoomState) { derivedStateOf { zoomState.zoomed } }
                    // #335 — pull-to-refresh only wraps the loaded content; the pull only engages on
                    // overscroll at the top of the list, so the read position is preserved on refresh.
                    // POC #182 : PullToRefreshBox (m3 1.4.0) does not expose `enabled`, so the wrapper
                    // becomes the low-level Modifier.pullToRefresh + the default Indicator — the pull
                    // gesture is fully suspended while zoomed (a gate in onRefresh would be too late:
                    // the gesture would still be consumed and the indicator armed).
                    val pullToRefreshState = rememberPullToRefreshState()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pullToRefresh(
                                isRefreshing = state.isRefreshing,
                                state = pullToRefreshState,
                                enabled = !isZoomed,
                                // #813 — user refresh also clears + re-probes failed media
                                // measurements.
                                onRefresh = refreshWithMediaRetry,
                            ),
                    ) {
                        // #300/#351 — the intra-page scrollbar now rides inside PostListScaffold
                        // (overlaying the list's right edge, outside the scrolled element), so the
                        // manual Box + LazyListScrollbar wrapper is gone. PullToRefreshBox stays the
                        // feature's wrapper (its refresh state belongs to the ViewModel).
                        //
                        // #785 — the blacklist applies inside quotes too: the canonical blocked set
                        // is provided to the post renderers so QuoteBlock masks a citation OF a
                        // blocked author. Scoped to the reading list only — the quick-reply sheet
                        // and editor previews (outside this provider) keep the empty default.
                        // #946 — LocalTopicZoomed is deliberately NOT provided here any more :
                        // its only consumer was the `selectable` flip, whose structural swap
                        // destroyed the posts' saveable state on every zoom engage.
                        CompositionLocalProvider(
                            LocalBlockedQuoteAuthors provides mode.blockedQuoteAuthors,
                        ) {
                            TopicLoadedContent(
                                state = state,
                                topic = mode.topic,
                                hiddenNumreponses = mode.hiddenNumreponses,
                                zoomState = zoomState,
                                // #604 lot 2 / #806 — « Citer » opens the quick-reply sheet with the
                                // card pre-armed (1-citation session), unless the preset routes any
                                // citation to the full-screen editor (decision at tap time). #843 —
                                // that full-editor open is COLD (onReply, resumeSharedDraft = false):
                                // the cards are handed over, an existing text draft is offered via the
                                // restore banner, not silently appended.
                                onQuoteRequested = { preview ->
                                    when (writingSurfaceFor(state.writingSurfacePreset, quoteCount = 1)) {
                                        WritingSurface.SHEET -> quickReplyFor = QuickReplyLaunch(
                                            request = QuickReplyRequest(
                                                cat = state.request.cat,
                                                subcat = mode.topic.subcat,
                                                topicId = state.request.post,
                                                page = mode.topic.page,
                                            ),
                                            initialQuotes = listOf(preview),
                                        )
                                        WritingSurface.FULL_EDITOR ->
                                            onReply(mode.topic.subcat, mode.topic.page, listOf(preview))
                                    }
                                },
                                // #823 — LONG press on « Citer » : one-shot override of the #806
                                // preset — always the full-screen editor, through the same :app path
                                // as the FULL_EDITOR branch above (cold open, in-memory handoff +
                                // resumeSharedDraft = false → restore banner, #843). Deliberately
                                // does NOT consult writingSurfaceFor: the gesture IS the routing
                                // decision (identical to the tap under the FULL_EDITOR preset).
                                onQuoteFullEditorRequested = { preview ->
                                    onReply(mode.topic.subcat, mode.topic.page, listOf(preview))
                                },
                                onEdit = onEdit,
                                onEditFirstPost = onEditFirstPost,
                                onOpenPage = onOpenPage,
                                onGoToPost = onGoToPost,
                                onOpenProfile = onOpenProfile,
                                onSendPrivateMessage = onSendPrivateMessage,
                                onDeleteRequest = onDeleteRequest,
                                favoriteAtPostState = favoriteAtPostState,
                                onFavoriteMenuOpened = onFavoriteMenuOpened,
                                onFavoriteAction = onFavoriteAction,
                                onDoubleTapRefresh = refreshWithMediaRetry,
                                onSearchNextResults = { onIntent(TopicIntent.SearchNextResultsPage) },
                                listState = listState,
                                multiQuoteSelection = multiQuoteNumreponses,
                                onToggleMultiQuote = onToggleMultiQuote,
                                onSetAuthorBlocked = { author, blocked ->
                                    onIntent(TopicIntent.SetAuthorBlocked(author, blocked))
                                },
                                pollManualExpanded = pollManualExpanded,
                                onPollExpansionChanged = onPollExpansionChanged,
                            )
                        }
                        PullToRefreshDefaults.Indicator(
                            state = pullToRefreshState,
                            isRefreshing = state.isRefreshing,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                        if (isZoomed) {
                            // #182 — discreet, always-visible reset affordance while zoomed
                            // (contract RESET). Chrome: stays OUTSIDE the zoomed layer.
                            val zoomResetDescription = stringResource(R.string.topic_zoom_reset)
                            Surface(
                                onClick = {
                                    // Anchored on the viewport centre: the content the reader is
                                    // looking at stays put while the scale animates back to 1×.
                                    zoomState.settleAnchoredTo(
                                        targetScale = 1f,
                                        anchorX = zoomState.viewportWidthPx / 2f,
                                        anchorY = zoomState.viewportHeightPx / 2f,
                                        listState = listState,
                                    )
                                },
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shadowElevation = 3.dp,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp)
                                    // #937 — a11y : 48 dp minimum touch target, named action,
                                    // explicit button role (validation 5.5).
                                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .semantics {
                                        contentDescription = zoomResetDescription
                                        role = Role.Button
                                    },
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.topic_zoom_reset_chip),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    quickReplyFor?.let { launch ->
        QuickReplySheet(
            request = launch.request,
            initialQuotes = launch.initialQuotes,
            onDismiss = { quickReplyFor = null },
            onEscalate = { quotes ->
                quickReplyFor = null
                // #843 — genuine escalation: resumeSharedDraft = true (silent append), NOT the cold
                // onReply path which surfaces the restore banner. #868-#870 — the escalated editor
                // inherits this session's basket consumption.
                onEscalateToFullEditor(
                    launch.request.subcat,
                    launch.request.page,
                    quotes,
                    launch.consumesBasket,
                )
            },
            onSubmitted = { targetPage, scrollTo ->
                // #868/#869 — a SUCCESSFUL send of a basket-consuming session (« Citer N » ≤ 2)
                // finally consumes the selection ; a dismiss/cancel above never does.
                if (launch.consumesBasket) {
                    onClearMultiQuote()
                }
                quickReplyFor = null
                onQuickReplySubmitted(targetPage, scrollTo)
            },
        )
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
internal fun topicBarPageIndicator(state: TopicUiState, loaded: TopicUiState.Mode.Loaded?): String = when {
    // #895 (quick win 3, revisite du choix #877) — a PROVISIONAL page is the instant cache
    // emission : its pagination describes EXACTLY the content on screen, so show it. Replacing
    // known information with « Chargement… » was the residual flash the maintainer reported —
    // the in-flight refresh is signalled by the discreet progress hairline under the bar (and
    // the a11y description), never by blanking the pill. The #877 guarantee stands : this is the
    // REQUESTED page's own row (never the previous page's number), and « Chargement… » remains
    // for the pure Loading mode below.
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

// #772 — extra room for the title's second line (titleMedium line height) while the title is
// expanded; the small M3 top app bar keeps a fixed container height and would clip it otherwise.
private val TopBarExpandedTitleExtraHeight = 24.dp

// #895 — the discreet under-bar refresh hairline (visible only while the displayed page is
// provisional). The 2 dp strip is permanently reserved so it never shifts the list.
private val TopBarRefreshHairlineHeight = 2.dp

/**
 * #895 — the top-bar page pill. Shows the pagination OF THE DISPLAYED CONTENT (provisional cache
 * included — replacing known information with « Chargement… » was the reported flash) ; while the
 * page is provisional, screen readers get « page X sur Y, actualisation en cours » as the
 * equivalent of the visual hairline. No liveRegion : announcing cache-then-settled twice per
 * navigation would be pure noise (cadrage Sol).
 */
@Composable
private fun TopicBarPagePill(
    text: String,
    loaded: TopicUiState.Mode.Loaded?,
    pagePickerLabel: String,
    onOpenPagePicker: () -> Unit,
) {
    val refreshingLabel = loaded?.takeIf { it.provisional }?.let {
        stringResource(
            R.string.topic_page_indicator_refreshing_a11y,
            it.topic.page,
            it.topic.totalPages,
        )
    }
    val base = if (loaded != null) {
        Modifier.clickable(onClickLabel = pagePickerLabel, onClick = onOpenPagePicker)
    } else {
        Modifier
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (loaded != null) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = if (refreshingLabel != null) {
            base.semantics { contentDescription = refreshingLabel }
        } else {
            base
        },
    )
}

/** #895 — test tag of the provisional-refresh hairline under the top bar. */
const val TOPIC_REFRESH_HAIRLINE_TAG = "topic_refresh_hairline"

/**
 * #285/#284 + Chantier C (#546) — the topic top app bar (title + page counter + back) plus the
 * intra-topic search affordance : a search icon in `actions` (authenticated + page on screen —
 * #877 : NOT gated on the transient form) that opens the [TopicSearchBar] directly beneath the bar.
 * Extracted from `TopicContent` to keep that builder under detekt's cyclomatic-complexity cap.
 * Internal (not private) so the Robolectric UI test can drive the #772 title expansion directly,
 * same pattern as [TopicPostCard].
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList") // hoisted bar : title/page/back inputs + search sink + page picker.
internal fun TopicTopBar(
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
    // #772 — tap on the title reveals it in full (2 lines max), tap again folds it back. Transient
    // by design (arbitrage XaTriX) : a page change or leaving the screen resets it. Since #895
    // étape 4 the composition SURVIVES a page change (in-VM engine, frozen route), so the post/page
    // remember key — a mere safety net when page changes replaced the route — is now the mechanism
    // that actually resets the fold on every page.
    var titleExpanded by remember(state.request.post, state.request.page) { mutableStateOf(false) }
    val titleToggleLabel = stringResource(
        if (titleExpanded) R.string.topic_title_collapse else R.string.topic_title_expand,
    )
    val titleStateLabel = stringResource(
        if (titleExpanded) R.string.topic_title_expanded else R.string.topic_title_collapsed,
    )
    // #809 — long-press on the title opens the drapeau-removal flow (the tap toggle is unchanged).
    val titleLongPressLabel = stringResource(R.string.topic_remove_flag_long_press)
    Column {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = barTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = if (titleExpanded) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            // #809 — combinedClickable adds the long-press (« Gérer le drapeau ») on
                            // top of the #772 tap toggle. onClick is the SAME toggle as before
                            // (heightOffset re-deploy included) so TopicTopBarTitleExpandTest stays
                            // green; onClickLabel + the stateDescription semantics are preserved.
                            .combinedClickable(
                                onClickLabel = titleToggleLabel,
                                onLongClickLabel = titleLongPressLabel,
                                role = Role.Button,
                                onLongClick = { onIntent(TopicIntent.RequestRemoveTopicFlag) },
                                onClick = {
                                    val expanding = !titleExpanded
                                    titleExpanded = expanding
                                    if (expanding) {
                                        // enterAlways may hold the bar partially collapsed — re-deploy
                                        // it so the freshly granted second line shows instead of
                                        // staying clipped behind the current height offset.
                                        scrollBehavior?.state?.heightOffset = 0f
                                    }
                                },
                            )
                            .semantics { stateDescription = titleStateLabel },
                    )
                    TopicBarPagePill(
                        text = barPageIndicator,
                        loaded = loaded,
                        pagePickerLabel = pagePickerLabel,
                        onOpenPagePicker = { pagePickerOpen = true },
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
                // #877 — gated on canOpenSearch (auth + page à l'écran), PAS sur le form transient :
                // les émissions cache n'en portent pas et faisaient disparaître la Loupe.
                if (state.canOpenSearch && !state.search.isActive) {
                    IconButton(
                        onClick = { onIntent(TopicIntent.OpenSearch) },
                        modifier = Modifier.semantics { contentDescription = searchLabel },
                    ) {
                        RedfaceVectorIcon(resId = fr.forumhfr.redface2.core.ui.R.drawable.ic_search)
                    }
                }
            },
            // #772 — the small M3 top app bar has a FIXED container height that never grows for a
            // 2-line title : grant the extra line height while expanded, else keep the M3 default.
            expandedHeight = if (titleExpanded) {
                TopAppBarDefaults.TopAppBarExpandedHeight + TopBarExpandedTitleExtraHeight
            } else {
                TopAppBarDefaults.TopAppBarExpandedHeight
            },
            scrollBehavior = scrollBehavior,
        )
        // #895 (quick win 3) — discreet refresh signal : a 2 dp hairline under the bar while the
        // displayed page is provisional (cache on screen, authenticated refresh in flight). The
        // strip is ALWAYS reserved (transparent when settled) so its appearance never shifts the
        // list below — this PR exists to remove flashes, not to add one.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TopBarRefreshHairlineHeight),
        ) {
            if (loaded?.provisional == true) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TopBarRefreshHairlineHeight)
                        .testTag(TOPIC_REFRESH_HAIRLINE_TAG),
                )
            }
        }
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
            // #894 (cadrage F4) — the « depuis le début » opt-in on its own labelled options row :
            // the default stays HFR's own semantics (anchored to the current page, forward).
            // Ephemeral bar state — no persisted preference.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = search.fromStart,
                    onCheckedChange = { onIntent(TopicIntent.SearchFromStartChanged(it)) },
                    enabled = search.status != TopicSearchStatus.Loading,
                )
                Text(
                    text = stringResource(R.string.topic_search_from_start),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = search.status != TopicSearchStatus.Loading) {
                            onIntent(TopicIntent.SearchFromStartChanged(!search.fromStart))
                        },
                )
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
    onQuoteRequested: (preview: QuotedPostPreview) -> Unit,
    // #823 — LONG press on « Citer » : same preview payload as [onQuoteRequested], but routed
    // STRAIGHT to the full-screen editor by the caller (never through writingSurfaceFor — the
    // gesture IS the one-shot routing decision, overriding the #806 preset).
    onQuoteFullEditorRequested: (preview: QuotedPostPreview) -> Unit,
    onEdit: (subcat: Int, page: Int, numreponse: Int) -> Unit,
    onEditFirstPost: (subcat: Int, page: Int, numreponse: Int) -> Unit,
    onOpenPage: (Int) -> Unit,
    // #699 — quote-header tap, forwarded into each TopicPostCard's PostRenderer.
    onGoToPost: (page: Int, numreponse: Int) -> Unit = { _, _ -> },
    onOpenProfile: (userId: Int, pseudo: String, avatarUrl: String?) -> Unit = { _, _, _ -> },
    // #792 — « Envoyer un MP » entry of the post menu (gated at the mount below).
    onSendPrivateMessage: (author: String) -> Unit = {},
    onDeleteRequest: (numreponse: Int) -> Unit = {},
    favoriteAtPostState: FavoriteAtPostState = FavoriteAtPostState.Unknown,
    onFavoriteMenuOpened: () -> Unit = {},
    onFavoriteAction: (Post) -> Unit = {},
    /** #382 — double-tap anywhere on the list refreshes the current page (RF1 parity). */
    onDoubleTapRefresh: () -> Unit = {},
    /** #879 — filtered search : « résultats suivants » footer tap. */
    onSearchNextResults: () -> Unit = {},
    listState: LazyListState,
    // POC #182 (#935) — magnifier state: the gesture + draw layer attach to the list here, and
    // swipe/double-tap/list-scroll are suspended while zoomed.
    zoomState: TopicZoomState,
    // #291 — selection state + toggle for the post menu's multi-quote entry.
    multiQuoteSelection: List<Int> = emptyList(),
    onToggleMultiQuote: (preview: QuotedPostPreview) -> Unit = {},
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
    // #874 — canonicalize the live session pseudo ONCE for the loaded list, then match the current
    // page's post authors once while building the set below. Lazy cards only consume the resulting
    // pseudo/set and never repeat either operation on recomposition. Both settings off and anonymous
    // sessions collapse to the safe null/empty values.
    val egoCanonicalPseudo = remember(
        state.egoQuoteEnabled,
        state.egoPostEnabled,
        state.isAuthenticated,
        state.connectedPseudo,
    ) {
        deriveEgoCanonicalPseudo(
            enabled = state.egoQuoteEnabled || state.egoPostEnabled,
            isAuthenticated = state.isAuthenticated,
            connectedPseudo = state.connectedPseudo,
        )
    }
    val egoQuoteCanonicalPseudo = egoCanonicalPseudo.takeIf { state.egoQuoteEnabled }
    val egoPostNumreponses = remember(
        state.egoPostEnabled,
        egoCanonicalPseudo,
        topic.posts,
    ) {
        if (!state.egoPostEnabled || egoCanonicalPseudo == null) {
            emptySet()
        } else {
            topic.posts
                .asSequence()
                .filter { post -> isEgoPost(post, egoCanonicalPseudo) }
                .mapTo(mutableSetOf()) { post -> post.numreponse }
        }
    }
    // #362 — post whose contextual menu is open (null = closed). Plain local UI state at the
    // Loaded level: the menu carries no async data, so no ViewModel/hoisting is needed — the
    // sheet lives in :feature:topic (unlike ProfilePreviewSheet, hoisted in :app only because
    // it needs a Hilt ViewModel). Deliberately NOT rememberSaveable: Post is not Parcelable
    // and losing an open overflow menu across process death is acceptable.
    var menuPost by remember { mutableStateOf<Post?>(null) }
    // #831 — post image whose contextual menu is open (null = closed). Same local-UI-state
    // rationale as menuPost above (no async data in the sheet itself); the target is a small
    // value type but deliberately NOT rememberSaveable either — losing an open image menu across
    // process death is acceptable, and symmetry with menuPost keeps ONE dismissal model.
    var imageMenuTarget by remember { mutableStateOf<PostImageTarget?>(null) }
    // #831 — one stable handler instance provided (via TopicPostCard) to the post bodies'
    // LocalPostImageActions; remembered so providing it never invalidates the cards.
    val postImageActions = remember { PostImageActions(onLongPress = { imageMenuTarget = it }) }
    // #831 — « Enregistrer l'image » seam. A dedicated thin @HiltViewModel (precedent
    // QuickReplyViewModel) so the save survives the sheet's dismissal; feedback = Toast
    // (feature-topic convention, no SnackbarHost in TopicScreen).
    val imageActionsViewModel: PostImageActionsViewModel = hiltViewModel()
    val imageActionsContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(imageActionsViewModel) {
        imageActionsViewModel.effects.collect { effect ->
            val messageRes = when (effect) {
                PostImageActionsViewModel.SaveImageEffect.SAVED ->
                    R.string.topic_image_menu_saved
                PostImageActionsViewModel.SaveImageEffect.FAILED_FETCH ->
                    R.string.topic_image_menu_save_failed_fetch
                PostImageActionsViewModel.SaveImageEffect.FAILED_STORAGE ->
                    R.string.topic_image_menu_save_failed_storage
                PostImageActionsViewModel.SaveImageEffect.FAILED_TOO_LARGE ->
                    R.string.topic_image_menu_save_failed_too_large
            }
            // The @StringRes overload resolves at show() time — no LocalContext resource query
            // (LocalContextGetResourceValueCall) for a one-shot Toast.
            android.widget.Toast.makeText(
                imageActionsContext,
                messageRes,
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }
    // #509 — posts the reader chose to reveal despite the author being blacklisted. Temporary and
    // re-keyed on `topic.page`, not persisted: re-hiding on a page change is the intended "masqué by
    // default" behaviour (decision #6). This composable instance is bound to one (cat, post), so the
    // page is the only key dimension that matters here.
    var revealedHiddenPosts by remember(topic.page) { mutableStateOf(emptySet<Int>()) }
    // #509 — a post hidden (author blacklisted) while it sat in the multi-quote basket is dropped from
    // the selection: the placeholder exposes no deselect affordance (decision #1), so leaving it
    // selected would silently quote a masqué post. The basket is hoisted in :app; reuse its toggle.
    LaunchedEffect(hiddenNumreponses, multiQuoteSelection) {
        multiQuoteSelection.filter { it in hiddenNumreponses }.forEach { numreponse ->
            // Removal is keyed on the numreponse alone (cf. toggled()) — resolve the hidden post
            // to rebuild a preview, or fall back to a tombstone if the page no longer carries it.
            val hidden = topic.posts.firstOrNull { it.numreponse == numreponse }
            onToggleMultiQuote(
                hidden?.toQuotedPreview()
                    ?: QuotedPostPreview(numreponse = numreponse, author = "", excerpt = ""),
            )
        }
    }
    // #282 — shared offset between the gesture (drives translationX) and the edge glow. A plain
    // MutableFloatState: the gesture writes it synchronously per frame (no coroutine/alloc), the draw
    // phase reads it; an Animatable inside the gesture handles only release transitions. The
    // composition survives a committed swipe (#895 étape 4 — in-VM page switch): the reset back to
    // rest is the `LaunchedEffect(topic.page)` below, when the target page renders.
    val dragOffset = remember { mutableFloatStateOf(0f) }
    // #282 — hoisted so the gesture can tick on arming and confirm on commit.
    val haptics = LocalHapticFeedback.current
    // #282 — live page count for the swipe gesture, read through a lambda so the gesture sees the
    // latest value WITHOUT re-keying its `pointerInput` (which would cancel an in-flight commit
    // slide-out and drop the navigation — see `topicPageSwipe`). `rememberUpdatedState` keeps the
    // State identity stable while its value tracks `topic.totalPages` across recompositions.
    val currentTotalPages by rememberUpdatedState(topic.totalPages)
    // #282 — the swipe must be INERT while this nav entry is not yet settled (mid NavDisplay
    // transition INTO the topic, lifecycle < RESUMED — since #895 étape 4 page changes stay in the
    // retained entry, so only entry/exit transitions remain). A cached page would otherwise accept
    // a swipe during the transition and commit an onOpenPage mid-flight, interrupting the
    // transition → frozen screen. The lambda reads `lifecycle.currentState` live, so the gesture
    // (whose pointerInput does not re-key on this) always sees the current state.
    val entryLifecycle = LocalLifecycleOwner.current.lifecycle
    // POC #182 — the page swipe (and its edge hint) are suspended while zoomed. Gesture-time read
    // through the lambda: no recomposition per pinch frame.
    val swipeEnabled: () -> Boolean = {
        entryLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !zoomState.zoomed
    }
    // #752 — system-gesture band widths for the swipe's start dead-zone, resolved here (composable)
    // and handed as ALWAYS-FRESH lambdas (rememberUpdatedState) so the currentPage-keyed
    // pointerInput sees rotation/split-screen changes ; px conversion happens per gesture.
    val gestureDensity = rememberUpdatedState(LocalDensity.current)
    val gestureLayoutDirection = rememberUpdatedState(LocalLayoutDirection.current)
    val systemGestureInsets = rememberUpdatedState(WindowInsets.systemGestures)
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
    // POC #182 — native list scrolling is suspended while zoomed: the vertical axis is driven by
    // the magnifier's controlled dispatchRawDelta (screen deltas divided by scale — 1:1 under the
    // finger). derivedStateOf: recomposes on the 1× ↔ zoomed transition only.
    val zoomSuspendsScroll by remember(zoomState) { derivedStateOf { zoomState.zoomed } }
    // #884 (vague 3) — list geometry switched by the « posts en pleine largeur » preference. The
    // historical values (#283 bottom clearance, #398 local side gutter, #287 8 dp rhythm) moved to
    // TopicListLayout.kt and stay byte-identical in card mode; full-width drops the side gutters
    // and the inter-item gap (posts touch, the shell hairline separates them) while every NON-post
    // island below re-inserts its own 8/4 dp inset via this shared modifier.
    val islandModifier = Modifier.islandPadding(state.fullWidthPosts)
    PostListScaffold(
        listState = listState,
        userScrollEnabled = !zoomSuspendsScroll,
        contentPadding = topicListContentPadding(state.fullWidthPosts),
        verticalArrangement = topicListArrangement(state.fullWidthPosts),
        listModifier = Modifier
            // POC #182 (#935) — the magnifier gesture listens FIRST (Initial pass) so that, once
            // pinching, consuming the moves starves the sibling swipe / child scrollers below. It
            // must also sit BEFORE the zoom graphicsLayer at the end of this chain: centroids are
            // read in the untransformed local space that TopicZoomMath models (same coordinate
            // rule as topicPageSwipe).
            .topicMagnifier(zoomState, listState)
            // #285 — system-bar insets (status + navigation) are now consumed by the Scaffold/TopAppBar
            // in TopicContent and applied via the content Surface's padding(innerPadding); the list no
            // longer adds statusBarsPadding()/navigationBarsPadding() here to avoid double-insetting.
            // #282 — horizontal swipe changes page via the same onOpenPage as the pager (in-VM
            // switchToPage since #895 étape 4), with
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
                    leftGestureInsetPx = {
                        systemGestureInsets.value
                            .getLeft(gestureDensity.value, gestureLayoutDirection.value)
                    },
                    rightGestureInsetPx = {
                        systemGestureInsets.value
                            .getRight(gestureDensity.value, gestureLayoutDirection.value)
                    },
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
                        // #182 — double-tap refresh is suspended while zoomed (contract ZOOMÉ).
                        // The magnifier already consumes the down on its Initial pass while
                        // zoomed (replied mode), so this guard is DEFENSE IN DEPTH — it keeps
                        // the suspension correct even if the modifier stacking ever changes.
                        if (!zoomState.zoomed) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onDoubleTapRefresh()
                        }
                    },
                )
            }
            // POC #182 — the zoom draw layer, LAST in the chain (innermost): the magnifier and the
            // swipe read their pointers in untransformed space, and the swipe's own translation
            // layer (identity while zoomed — the swipe is suspended) composes OUTSIDE this scale.
            // Top-left origin per the contract; scale/panX are frame-state reads (no recomposition).
            .graphicsLayer {
                val zoomScale = zoomState.scale.floatValue
                scaleX = zoomScale
                scaleY = zoomScale
                translationX = zoomState.panX.floatValue
                // Bounded complement of the real scroll at the bottom edge (contract amendment,
                // POC iter 1) — never exposes uncomposed content, see TopicZoomState.panY.
                translationY = zoomState.panY.floatValue
                transformOrigin = TransformOrigin(0f, 0f)
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
                    // #884 — island: keeps its inset when the posts go full-width.
                    modifier = islandModifier,
                )
            }
        }
        // #983 — indexed so a post can tell what FOLLOWS it (cf. TopicFollowingKind below). The key
        // is unchanged (numreponse), so Lazy's item identity — and every #307/#412 anchor that
        // depends on it — is untouched.
        itemsIndexed(
            items = topic.posts,
            key = { _, post -> post.numreponse },
        ) { index, post ->
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
            // #823 — the « Citer » long press (full-editor override) is derived INSIDE the same
            // branch as the tap, so the gesture can never outlive or outreach « Citer » itself
            // (a non-postable topic exposes neither).
            val quoteLongPressAction: (() -> Unit)?
            val multiQuoteToggle: (() -> Unit)?
            if (shouldShowQuoteAction(topic, state.isAuthenticated)) {
                quoteAction = { onQuoteRequested(post.toQuotedPreview()) }
                quoteLongPressAction = { onQuoteFullEditorRequested(post.toQuotedPreview()) }
                multiQuoteToggle = { onToggleMultiQuote(post.toQuotedPreview()) }
            } else {
                quoteAction = null
                quoteLongPressAction = null
                multiQuoteToggle = null
            }
            // Phase 2D (#147) — « Modifier » is exposed by HFR only on the
            // user's own posts of an unlocked topic. Same canReply gate as
            // Citer (#213) to refuse a read-only topic (no reply form).
            val editAction: (() -> Unit)? = if (
                shouldShowEditAction(topic, post, state.isAuthenticated, state.connectedPseudo)
            ) {
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
            // #983 — the flat shell's closing hairline is drawn only at an ordinary post → ordinary
            // post boundary: everywhere else the NEXT element brings its own boundary (the separator's
            // 2.dp rules, a placeholder's card border, a closing island's border) and the two traits
            // stacked a few dp apart were the reported defect. What follows this post is not always
            // the next list item — the separator lives inside this very item, and the closing island
            // (poll / page boundary / end-of-topic / search footer) is a further one, which is why a
            // trailing post declares NONE rather than guessing which island will close the list.
            val nextPost = topic.posts.getOrNull(index + 1)
            val followingKind = when {
                showLastReadMarker -> TopicFollowingKind.NON_POST
                nextPost == null -> TopicFollowingKind.NONE
                isHiddenPost(nextPost, hiddenNumreponses, revealedHiddenPosts) ->
                    TopicFollowingKind.NON_POST
                else -> TopicFollowingKind.POST
            }
            val flatBottomEdge = if (
                topicPostRequestsBottomHairline(state.fullWidthPosts, followingKind)
            ) {
                PostCardShellFlatBottomEdge.HAIRLINE
            } else {
                PostCardShellFlatBottomEdge.NONE
            }
            Column(verticalArrangement = topicPostChildrenArrangement(state.fullWidthPosts)) {
                if (isHiddenPost(post, hiddenNumreponses, revealedHiddenPosts)) {
                    HiddenPostCard(
                        author = post.author,
                        onReveal = { revealedHiddenPosts = revealedHiddenPosts + post.numreponse },
                        // #884 — the placeholder is an island too: it stays an inset card
                        // (revealing swaps in the full-width post).
                        modifier = islandModifier,
                    )
                } else {
                    TopicPostCard(
                        post = post,
                        highlighted = highlight == post.numreponse,
                        // #863 — the SERVER count (« Message cité N fois », cross-page), parsed
                        // from div.edited ; null = never cited. The page-scoped client scan is gone.
                        citedCount = post.citedCount ?: 0,
                        // #699 — makes sourced quote headers tappable (jump to the cited post).
                        onGoToCitedPost = onGoToPost,
                        // #330 — render the author signature beneath the body when the reading preference
                        // is on (the signature is always parsed/cached on the Post; this is render-only).
                        showSignature = state.showSignatures,
                        onQuote = quoteAction,
                        // #823 — full-editor long-press override, same gate as « Citer »
                        // (derived together above).
                        onQuoteLongPress = quoteLongPressAction,
                        onEdit = editAction,
                        onOpenProfile = profileAction,
                        onOpenMenu = {
                            menuPost = post
                            if (state.isAuthenticated && (post.quoteRef ?: 0) >= 1) {
                                onFavoriteMenuOpened()
                            }
                        },
                        // #436 — same membership source as the menu entry (PostMenuSheet).
                        multiQuoteSelected = post.numreponse in multiQuoteSelection,
                        // #436 — per-post add/remove affordance (RF1 quote+/quote- parity), reachable
                        // without opening the « … » menu. Null/non-null under the SAME gate as « Citer »
                        // (derived together above), so the « + » and « Citer » always appear as a pair.
                        onToggleMultiQuote = multiQuoteToggle,
                        // #831 — long-press on a post image opens the image contextual menu.
                        onImageLongPress = postImageActions.onLongPress,
                        // #884 — « posts en pleine largeur »: boundary-less card, full bleed.
                        flat = state.fullWidthPosts,
                        // #983 — who closes this post's bottom edge (derived above).
                        flatBottomEdge = flatBottomEdge,
                        // #874 — Q4 and P1 are independent: an own post carrying an auto-citation
                        // receives both nested containers. Hidden posts never enter this branch.
                        egoQuoteCanonicalPseudo = egoQuoteCanonicalPseudo,
                        egoPostHighlighted = post.numreponse in egoPostNumreponses,
                    )
                }
                if (showLastReadMarker) {
                    // #983 — the separator owns its own symmetric vertical rhythm in full-width
                    // (no container adds a gap there), and stays edge to edge like the posts it cuts
                    // through — it is a rule, not an island card.
                    LastReadMarker(modifier = Modifier.separatorPadding(state.fullWidthPosts))
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
        if (state.search.showingFilteredResults) {
            // #879 — the page on screen is a FILTERED result list : its pager belongs to the
            // search. The canonical boundary cards are suppressed (their onOpenPage would leave
            // the search silently) ; instead the footer offers the next RESULT page, or states
            // the end of the results.
            // Gate finding 3 — the footer tells Loading, retry and true end apart : hidden while a
            // fetch is in flight ; after a FAILED next-page fetch the pager is untouched, so the
            // « more » card stays and doubles as the retry affordance ; the end marker is only
            // truthful once Done with no page left.
            if (state.search.status != TopicSearchStatus.Loading) {
                item {
                    if (state.search.hasMoreFilteredResults) {
                        SearchMoreResultsCard(onNext = onSearchNextResults, modifier = islandModifier)
                    } else if (state.search.status == TopicSearchStatus.Done) {
                        EndOfSearchResultsCard(modifier = islandModifier)
                    }
                }
            }
        } else if (topic.page == topic.totalPages) {
            item {
                EndOfTopicCard(modifier = islandModifier)
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
                    // #884 — island: keeps its inset when the posts go full-width.
                    modifier = islandModifier,
                )
            }
        }
    }
    // #362 — per-post contextual menu. The permalink is rebuilt from the LOADED topic's
    // (cat, post, page) — not the request — so it always reflects the page HFR actually
    // served (HFR clamps out-of-range pages). citedCount = the server counter (#863).
    menuPost?.let { post ->
        // #292 → #418 — « Supprimer » lives in the contextual menu now (anti accidental tap,
        // beta feedback by nicko). Same gates as before : « Modifier »'s gate (HFR allows
        // deletion via the edit form), never the topic's first post (deleting it would remove
        // the whole topic — out-of-scope destructive path), and no delete affordance while a
        // deletion is in flight (the ViewModel also guards ; hiding is the honest UI signal).
        val menuDeleteAction: (() -> Unit)? = if (
            state.deletingNumreponse == null &&
            !isFirstPostOfTopic(topic, post) &&
            shouldShowDeleteAction(topic, post, state.isAuthenticated, state.connectedPseudo)
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
            shouldShowEditFirstPost(topic, state.isAuthenticated, state.connectedPseudo)
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
            citedCount = post.citedCount ?: 0,
            onDismiss = { menuPost = null },
            onDelete = menuDeleteAction,
            onEditFirstPost = menuEditFirstPostAction,
            favoriteAction = favoriteActionFor(
                isAuthenticated = state.isAuthenticated,
                quoteRef = post.quoteRef,
                state = favoriteAtPostState,
            ),
            onFavoriteClick = { onFavoriteAction(post) },
            // #395 — same profileId gate as the post card (#208): Publicité rows and
            // anonymous reads expose no profile link, the hero stays inert.
            onOpenProfile = post.profileId?.let { profileId ->
                { onOpenProfile(profileId, post.author, post.avatarUrl) }
            },
            // #792 — « Envoyer un MP » : auth-gated, never on own posts, real profiles only
            // (« Publicité » rows are not messageable). Carries the author pseudo to `:app`.
            onSendPrivateMessage = if (
                shouldShowSendPrivateMessage(post, state.isAuthenticated, state.connectedPseudo)
            ) {
                { onSendPrivateMessage(post.author) }
            } else {
                null
            },
            // #291 — multi-quote toggle, same gate as « Citer » (quoting is a flavour of
            // replying; a locked topic or an anonymous session has nothing to quote).
            multiQuoteSelected = post.numreponse in multiQuoteSelection,
            onToggleMultiQuote = if (shouldShowQuoteAction(topic, state.isAuthenticated)) {
                { onToggleMultiQuote(post.toQuotedPreview()) }
            } else {
                null
            },
            // #509 — a post reachable through the menu is either not blocked, or blocked-but-revealed;
            // either way `numreponse in hiddenNumreponses` tells whether the author is blacklisted, so
            // the entry flips between Masquer / Ne plus masquer. Hidden for the user's own posts.
            authorBlocked = post.numreponse in hiddenNumreponses,
            // #509 + #545 — never offer self-masking, including when the toolbar-blind parser
            // could not flag the post as own (affichoutils=0 profiles).
            onToggleBlockAuthor = if (isOwnPostEffective(post, state.connectedPseudo)) {
                null
            } else {
                { onSetAuthorBlocked(post.author, post.numreponse !in hiddenNumreponses) }
            },
        )
    }
    // #831 — per-image contextual menu, opened by a long-press on a post image (wired through
    // LocalPostImageActions inside TopicPostCard). Hosted at the same level as PostMenuSheet so
    // the two sheets share one lifecycle model.
    imageMenuTarget?.let { target ->
        PostImageMenuSheet(
            target = target,
            onSave = imageActionsViewModel::saveImage,
            onDismiss = { imageMenuTarget = null },
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
// `internal` (#884): TopicListFullWidthAnchorTest mounts the real marker inside a post item to
// guard the full-width toggle. Same visibility relaxation as other tested internals.
// #983 — [modifier] carries the separator's own vertical rhythm in full-width mode
// (`Modifier.separatorPadding`): no container inserts a gap there, so the marker must be symmetric
// on its own. It stays traversing (no horizontal inset) — cf. separatorPadding's KDoc.
internal fun LastReadMarker(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
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
private fun EndOfTopicCard(modifier: Modifier = Modifier) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
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
 * [onNextPage] delegates to the caller's onOpenPage — the same in-VM page switch (#895 étape 4)
 * as the › FAB and the horizontal swipe (#282), so scroll restoration semantics stay uniform.
 */
@Composable
// `internal` (#884): TopicListFullWidthAnchorTest mounts the real boundary card as the list's
// closing island to guard the full-width toggle. Same visibility relaxation as other tested internals.
internal fun PageBoundaryCard(donePage: Int, onNextPage: () -> Unit, modifier: Modifier = Modifier) {
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
        modifier = modifier.fillMaxWidth(),
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
 * #894 — footer of a FILTERED search-result list when HFR truncated its scan window (the response
 * advertised a resume cursor). Same actionable card language as [PageBoundaryCard] (filled
 * primaryContainer = « there is more ») ; the tap re-submits the SEARCH with the resume cursor —
 * web parity with HFR's own « Résultats suivants » button, never the canonical pager.
 */
@Composable
private fun SearchMoreResultsCard(onNext: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onNext,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = modifier.fillMaxWidth(),
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
                    text = stringResource(R.string.topic_search_results_truncated),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.topic_search_results_next),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            RedfaceVectorIcon(resId = fr.forumhfr.redface2.core.ui.R.drawable.ic_chevron_right)
        }
    }
}

/**
 * #879 — quiet outline marker closing a filtered result list (mirrors [EndOfTopicCard]'s calm
 * language : end of RESULTS, not of the topic).
 */
@Composable
private fun EndOfSearchResultsCard(modifier: Modifier = Modifier) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.topic_search_results_list_end),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
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
    // #465 — the user's manual choice for this topic's poll, hoisted to :app so it survives leaving
    // and reopening the topic (pre-#895: the per-page TopicRoute swap). `null` = no manual choice
    // yet → follow [expandedDefault] (#456).
    manualExpanded: Boolean?,
    onExpansionChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // #456 — the preference seeds the initial state; #465 — once the user taps, the manual choice
    // (owned by :app, keyed by topic) wins and survives navigation between the topic's pages. The
    // card is fully controlled: it never holds the revealed state itself, it only reports a toggle.
    val revealed = manualExpanded ?: expandedDefault
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = modifier.clickable { onExpansionChanged(!revealed) },
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
                        // #697 — the FORM shape carries no numbers : render the bare label instead
                        // of a misleading « 0.0% (0 votes) ».
                        text = if (poll.resultsAvailable) {
                            stringResource(
                                R.string.topic_poll_option,
                                option.text,
                                option.percentage,
                                option.votes,
                            )
                        } else {
                            option.text
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val choiceLabel = if (poll.multipleChoice) {
                    stringResource(R.string.topic_poll_multiple_choices)
                } else {
                    stringResource(R.string.topic_poll_single_choice)
                }
                Text(
                    // #697 — no total on the FORM shape either ; a factual hint replaces it (the
                    // in-app vote is #779, so no promise about WHERE to vote).
                    text = if (poll.resultsAvailable) {
                        stringResource(R.string.topic_poll_summary, poll.totalVotes, choiceLabel)
                    } else {
                        stringResource(R.string.topic_poll_no_results, choiceLabel)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
// LongParameterList: compatibility wrapper for direct feature tests/previews; every parameter maps
// one existing topic decision onto the shared ReadingPostCard presentation, callbacks or slots.
@Suppress("LongParameterList")
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
    /**
     * #884 — « posts en pleine largeur » (vague 3): forwarded to [ReadingPostCard]. `true` renders
     * this card boundary-less (transparent, rectangular, closed by the shell hairline) so it can
     * bleed edge to edge in the list; the INTERNAL paddings below (header 12.dp, body 12/10.dp,
     * footer) are deliberately UNTOUCHED — the validated flat look keeps the text gutters.
     * Default `false`: the historical card.
     */
    flat: Boolean = false,
    /**
     * #983 — forwarded to [ReadingPostCard]: whether this flat post draws its own closing hairline, or
     * draws none — because what follows brings its own boundary (separator rule, island border), or
     * because it is the last post of the page. Derived by the list builder, the only place that knows
     * the rendered sequence. Ignored when [flat] is false. Default
     * [PostCardShellFlatBottomEdge.HAIRLINE] — the #884 behaviour, for previews and tests that mount
     * a lone card.
     */
    flatBottomEdge: PostCardShellFlatBottomEdge = PostCardShellFlatBottomEdge.HAIRLINE,
    /**
     * #874 Q4 — canonical session pseudo provided to the BODY renderer only. `null` disables the
     * quote marker (setting off / anonymous / previews). Default keeps direct test mounts and every
     * non-topic host neutral.
     */
    egoQuoteCanonicalPseudo: String? = null,
    /**
     * #874 P1 — whether this card belongs to the live authenticated session and the setting is on.
     * Resolved by the list from [isEgoPost]; default `false` preserves preview/test call sites.
     */
    egoPostHighlighted: Boolean = false,
    onQuote: (() -> Unit)?,
    /**
     * #823 — LONG press on « Citer » : opens the full-screen editor directly, a one-shot override
     * of the #806 writing-surface preset (decided at gesture time ; under the FULL_EDITOR preset
     * it is identical to the tap). Null under the same gate as [onQuote] (both are derived in the
     * same branch at the call site) and for previews/tests that only exercise the tap. Ignored
     * while [onQuote] is null — no « Citer » button, nothing to long-press.
     */
    onQuoteLongPress: (() -> Unit)? = null,
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
     * primary border and flips the footer toggle to « ✓ Cité », so the selection is visible without
     * opening the per-post menu (dev feedback by Dintr-un lemn). The dynamic « Ajouté à la citation »
     * pill was DROPPED (#882 P1): it grew the card on every tap and shifted the whole content below
     * it — the border + button flip + TalkBack `selected` state carry the signal without layout shift.
     */
    multiQuoteSelected: Boolean = false,
    /**
     * #436 — toggles this post in/out of the multi-quote basket directly from the card footer
     * (RF1 quote+/quote- parity), without opening the « … » menu. Null under the same gate as
     * « Citer » (a non-postable topic has nothing to quote), so the « + » action and « Citer »
     * appear together or not at all. The same [multiQuoteSelected] flag drives the glyph/label
     * here and the border — one source of truth, they can never desynchronise.
     */
    onToggleMultiQuote: (() -> Unit)? = null,
    /**
     * #699 — forwarded to [ReadingPostCard] so a sourced quote's header can jump to the cited post.
     * Null keeps the headers inert (previews/tests that render a card without navigation).
     */
    onGoToCitedPost: ((page: Int, numreponse: Int) -> Unit)? = null,
    /**
     * #831 — provided to [ReadingPostCard] for the BODY renderer only, so a long-press on a post
     * image (inline `[img]`, block, promoted) opens the image contextual menu. Signatures stay
     * outside that capability and keep their historical inert images. Null leaves every image inert.
     */
    onImageLongPress: ((PostImageTarget) -> Unit)? = null,
) {
    // #287 — structural spacing from the active density preset (Comfort = the historical rhythm).
    val m = LocalDisplayMetrics.current
    // #436 — the per-post actions row (Citer / Modifier / multi-quote) is gated as a unit. Computed
    // once so the shared card receives either the whole footer or null; its body then owns the card's
    // bottom padding only in the null branch, keeping the body↔card gap at m.cardBodyBottom.
    val hasFooter = onQuote != null || onEdit != null || onToggleMultiQuote != null
    // #882 P1 — only the STABLE citation-count pill gates the badges strip now: the dynamic
    // « Ajouté à la citation » pill is gone, so multi-quote selection never grows the card.
    val hasBadges = citedCount > 0
    val egoPostStateDescription = stringResource(R.string.topic_post_ego_state_description)
    ReadingPostCard(
        post = post,
        presentation = ReadingPostCardPresentation(
            showSignature = showSignature,
            flat = flat,
            flatBottomEdge = flatBottomEdge,
            egoQuoteCanonicalPseudo = egoQuoteCanonicalPseudo,
            egoPostHighlighted = egoPostHighlighted,
            selected = multiQuoteSelected,
        ),
        onGoToCitedPost = onGoToCitedPost,
        onImageLongPress = onImageLongPress,
        // Identity band — the avatar/pseudo/date header gets its own tinted strip across the full card
        // width (forum idiom, dogfooding v109): secondaryContainer over the neutral card. #104 follow-up
        // (XaTriX): the scroll-anchor post tints ONLY this band with tertiaryContainer (the left rail was
        // dropped as ugly) — a single tertiary band, not the old card+band double tint. The shared
        // PostIdentityBand (#351) sets LocalContentColor from its containerColor for the pseudo; the
        // enclosing Card clips the strip to its rounded corners. The #104 tint logic is UNCHANGED — it
        // stays the topic's decision, passed in as containerColor.
        identity = {
            PostIdentityBand(
                // #874 P1 — the EgoPost a11y marker sits on the identity node, which is present in
                // both display modes and is what TalkBack traverses first on a post.
                modifier = Modifier
                    .testTag(TOPIC_POST_IDENTITY_BAND_TAG)
                    .semantics {
                        if (egoPostHighlighted) {
                            stateDescription = egoPostStateDescription
                        }
                    },
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
        // #239/#863 — the citation-count pill sits OUT of the identity band (the secondaryContainer
        // Surface above), on the shell's badges slot — the neutral card surface just below the band.
        // Since #882 P1 this strip is STABLE for a given page: it only carries the server-side
        // « cité N fois » count, so tapping « + Citer » never adds/removes a pill (no layout shift).
        badges = if (hasBadges) {
            {
                TopicPostBadges(
                    citedCount = citedCount,
                    horizontalPadding = m.cardBodyHorizontal,
                )
            }
        } else {
            null
        },
        footer = if (hasFooter) {
            {
                TopicPostActions(
                    onQuote = onQuote,
                    onQuoteLongPress = onQuoteLongPress,
                    onEdit = onEdit,
                    onToggleMultiQuote = onToggleMultiQuote,
                    multiQuoteSelected = multiQuoteSelected,
                    // Reinjected paddings (#351/#882 P1): the actions row keeps the body gutters
                    // but NO vertical margins any more — the 48 dp M3 touch target of its buttons
                    // IS the row's layout (the ~14 dp of built-in minimumInteractiveComponentSize
                    // whitespace around the 20 dp label already provides the breathing room that
                    // the old m.postSpacing/m.cardBodyBottom paddings duplicated, inflating the
                    // footer to ~64 dp for 20 dp of useful text).
                    modifier = Modifier.padding(horizontal = m.cardBodyHorizontal),
                )
            }
        } else {
            null
        },
    )
}

internal const val TOPIC_POST_IDENTITY_BAND_TAG = "TopicPostIdentityBand"

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
                // #884 a11y (vague 3) — heading() rides on the REAL pseudo text node (both variants
                // below share this modifier), so TalkBack heading navigation jumps post to post on
                // the pseudo itself. The shared PostIdentityHeader adds NO wrapper heading around a
                // supplied slot — this node is the post's single heading.
                val pseudoModifier = (
                    if (onOpenProfile != null) {
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
                    ).semantics { heading() }
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
 * #239/#476 — the citation-count pill strip rendered (via [ReadingPostCard]'s badges slot) on the
 * neutral card surface just below the identity band. The call site renders this only when the pill
 * is present (`null` badges slot otherwise), so the strip is never an empty row.
 *
 * #882 P1 — the strip now carries ONLY the stable « cité N fois » pill. The dynamic basket-membership
 * pill (« Ajouté à la citation », #436) was removed: appearing on tap, it grew the card and shifted
 * everything below by its own height. The selection signal survives without it — primary card border,
 * « + Citer » → « ✓ Cité » button flip, and the TalkBack `selected` state on the toggle (all three
 * live independently of this strip).
 */
@Composable
private fun TopicPostBadges(
    citedCount: Int,
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
            // #239/#863 — sober pill: HFR's server-side citation count (cross-page,
            // authoritative). Jumping to the citing posts is a follow-up (#783).
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
    }
}

/** #882 P1 — test hook for the footer actions row (height / layout-stability assertions). */
const val TOPIC_POST_ACTIONS_ROW_TAG = "topic_post_actions_row"

/**
 * #146/#147/#436 — the topic post card's footer actions (Modifier / multi-quote « + » / Citer),
 * rendered (via [ReadingPostCard]'s footer slot) right-aligned and as sober TextButtons so they stay
 * subordinate to the post content. Horizontal gutters ride on [modifier] (reinjected by the call
 * site). « Supprimer » (#292) moved to the contextual menu (#418).
 *
 * #882 P1 — the row IS the 48 dp M3 touch target: no vertical margins are stacked around it any
 * more (the buttons' own `minimumInteractiveComponentSize` whitespace is the breathing room). The
 * `heightIn(min = …)` guard is a MINIMUM, never a fixed height: under a large fontScale the grown
 * buttons make the row taller — labels must never clip.
 */
// LongParameterList: state-hoisted Composable, each param has a distinct call-site.
@Suppress("LongParameterList")
@Composable
private fun TopicPostActions(
    onQuote: (() -> Unit)?,
    // #823 — LONG press on « Citer » : straight to the full-screen editor (one-shot override of
    // the #806 writing-surface preset). Null keeps a plain tap-only « Citer ».
    onQuoteLongPress: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onToggleMultiQuote: (() -> Unit)?,
    multiQuoteSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(TOPIC_POST_ACTIONS_ROW_TAG),
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
            // border. The colour (muted onSurfaceVariant when absent, primary when present) is a
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
                // state stays non-colour-only via the glyph + word + border.
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
            QuoteTextButton(onQuote = onQuote, onQuoteLongPress = onQuoteLongPress)
        }
    }
}

/**
 * #823 — the « Citer » footer action, hand-rolled so a LONG press can open the full-screen editor
 * directly (a one-shot override of the #806 writing-surface preset ; under the FULL_EDITOR preset
 * the gesture is identical to the tap). A real M3 [TextButton] is a Surface(onClick) whose inner
 * clickable swallows the pointer input of any combinedClickable stacked on its modifier — the same
 * trap as the FABs (#436/#822, pinned by [MultiQuoteFabClearTest]) — so this button carries the
 * combinedClickable itself. Shape / labelLarge / primary content colour / content padding / min
 * size / 48 dp touch target replicate the M3 TextButton defaults of the sibling footer actions,
 * and combinedClickable brings the built-in long-press haptics plus the TalkBack announcement via
 * onLongClickLabel. A null [onQuoteLongPress] falls back to a plain clickable so no long-press
 * semantics are advertised (ForumListRow idiom, #457).
 */
@Composable
private fun QuoteTextButton(onQuote: () -> Unit, onQuoteLongPress: (() -> Unit)?) {
    val longPressLabel = stringResource(R.string.topic_post_quote_full_editor)
    val interaction = if (onQuoteLongPress != null) {
        Modifier.combinedClickable(
            onClick = onQuote,
            onLongClick = onQuoteLongPress,
            onLongClickLabel = longPressLabel,
            role = Role.Button,
        )
    } else {
        Modifier.clickable(role = Role.Button, onClick = onQuote)
    }
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(ButtonDefaults.textShape)
            .then(interaction)
            .defaultMinSize(minWidth = ButtonDefaults.MinWidth, minHeight = ButtonDefaults.MinHeight)
            .padding(ButtonDefaults.TextButtonContentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.topic_post_quote),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

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
    // #436 — empties the whole basket (« Tout vider », long-press on the « Citer N » FAB).
    onClearMultiQuote: () -> Unit,
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
            // #822 — long press on ‹/› jumps to the first/last page. Both targets are in bounds by
            // construction (1 and the parsed totalPages) ; same belt-and-braces null-guard as the
            // single-page steps above. The #383 preference gate is untouched — the gestures live on
            // FABs already governed by showPageFabs.
            onFirstPage = { loaded?.let { onOpenPage(1) } },
            onLastPage = { loaded?.let { onOpenPage(it.topic.totalPages) } },
            onReply = { loaded?.let { onReply(it.topic.subcat, it.topic.page) } },
            onMultiQuote = { loaded?.let { onMultiQuote(it.topic.subcat, it.topic.page) } },
            onClearMultiQuote = onClearMultiQuote,
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
    // #822 — long press on ‹/› jumps straight to the first/last page (tap keeps the single step).
    onFirstPage: () -> Unit,
    onLastPage: () -> Unit,
    onReply: () -> Unit,
    onMultiQuote: () -> Unit,
    onClearMultiQuote: () -> Unit,
) {
    val previousLabel = stringResource(R.string.topic_fab_previous_page)
    val nextLabel = stringResource(R.string.topic_fab_next_page)
    val firstPageLabel = stringResource(R.string.topic_fab_first_page)
    val lastPageLabel = stringResource(R.string.topic_fab_last_page)
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
            MultiQuoteFab(count = multiQuoteCount, onClick = onMultiQuote, onClear = onClearMultiQuote)
        }
        // #383 — the preference only governs the ‹/› page FABs; « Répondre » keeps its own gate.
        if (showPageFabs) {
            FabSlot(visible = canGoPrevious) {
                PageFab(
                    description = previousLabel,
                    iconRes = fr.forumhfr.redface2.core.ui.R.drawable.ic_chevron_left,
                    onClick = onPreviousPage,
                    // #822 — long press jumps to page 1.
                    onLongClick = onFirstPage,
                    onLongClickLabel = firstPageLabel,
                )
            }
            FabSlot(visible = canGoNext) {
                PageFab(
                    description = nextLabel,
                    iconRes = fr.forumhfr.redface2.core.ui.R.drawable.ic_chevron_right,
                    onClick = onNextPage,
                    // #822 — long press jumps to the last page.
                    onLongClick = onLastPage,
                    onLongClickLabel = lastPageLabel,
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

// `internal` (#436): MultiQuoteFabClearTest mounts the FAB directly to pin the « Tout vider »
// long-press wiring (tap → onClick, long press → onClear) without standing up the whole screen.
@Composable
internal fun MultiQuoteFab(count: Int, onClick: () -> Unit, onClear: () -> Unit) {
    // #291 — same small-FAB footprint as PageFab/ReplyFab; the glyph is a decorative « ❝N » (no
    // Material icons — detekt ForbiddenImport blocks androidx.compose.material.*) and the real
    // label rides on contentDescription for TalkBack.
    // #436 — a LONG PRESS empties the whole basket (« Tout vider »), on the FAB where the user
    // sees the count (XaTriX arbitrage: not in the post menu nor the editor). Hand-rolled FAB
    // (pattern FlagItem) : a NON-clickable Surface carries the combinedClickable, because a real
    // SmallFloatingActionButton's inner clickable swallows the pointer input of any
    // combinedClickable stacked on its modifier — neither gesture ever fires (pinned by
    // MultiQuoteFabClearTest). combinedClickable brings the built-in long-press haptics and
    // exposes « Tout vider » through onLongClickLabel for TalkBack. Shape/colors/elevation and
    // the labelLarge glyph mirror the M3 small-FAB defaults of the sibling FABs.
    val label = pluralStringResource(R.plurals.topic_fab_multi_quote, count, count)
    val clearLabel = stringResource(R.string.topic_fab_multi_quote_clear)
    Surface(
        modifier = Modifier
            .semantics { contentDescription = label }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onClear,
                onLongClickLabel = clearLabel,
                role = Role.Button,
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
    ) {
        Box(
            modifier = Modifier.sizeIn(minWidth = FAB_SLOT_SIZE, minHeight = FAB_SLOT_SIZE),
            contentAlignment = Alignment.Center,
        ) {
            Text("❝$count", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// `internal` (#822): PageFabLongPressTest mounts the FAB directly to pin the gesture split
// (tap → onClick, long press → onLongClick) without standing up the whole screen — same
// visibility relaxation as MultiQuoteFab above.
@Composable
internal fun PageFab(
    description: String,
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onLongClickLabel: String,
) {
    // #360 / ADR-015 — chevron en vector stroke unifié (poids optique aligné sur la flèche retour),
    // dimensionné en dp via le primitive partagé :core:ui plutôt qu'un glyphe « ‹ »/« › » dépendant
    // de la police. Pas de Material icons (detekt ForbiddenImport). L'étiquette a11y reste sur le FAB,
    // donc l'icône est décorative.
    // #822 — a LONG PRESS jumps straight to the first/last page (‹ → page 1, › → totalPages), on
    // the same FAB as the single-page step. Hand-rolled FAB (pattern #820, cloned from
    // MultiQuoteFab above): a NON-clickable Surface carries the combinedClickable, because a real
    // SmallFloatingActionButton's inner clickable swallows the pointer input of any
    // combinedClickable stacked on its modifier — neither gesture ever fires (pinned by
    // MultiQuoteFabClearTest). combinedClickable brings the built-in long-press haptics and
    // announces the jump through onLongClickLabel for TalkBack. Shape/colors/elevation and the
    // FAB_SLOT_SIZE footprint mirror the M3 small-FAB defaults of the sibling ReplyFab.
    Surface(
        modifier = Modifier
            .semantics { contentDescription = description }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel,
                role = Role.Button,
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
    ) {
        Box(
            modifier = Modifier.sizeIn(minWidth = FAB_SLOT_SIZE, minHeight = FAB_SLOT_SIZE),
            contentAlignment = Alignment.Center,
        ) {
            RedfaceVectorIcon(resId = iconRes)
        }
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
// #604 lots 2-3 / #806 — what opens the quick-reply sheet : the reply coordinates, plus the cards
// this opening pre-arms (one for « Citer », the whole basket for « Citer N », empty from the reply
// FAB) — whenever [writingSurfaceFor] routed the tap to the sheet rather than the editor.
internal data class QuickReplyLaunch(
    val request: QuickReplyRequest,
    val initialQuotes: List<QuotedPostPreview> = emptyList(),
    /**
     * #868-#870 — true only when this opening consumed the hoisted multi-quote basket
     * (« Citer N » under the sheet threshold) : a successful submit of THIS session (or of its
     * full-screen escalation) then empties the basket. « Citer » simple and the reply FAB leave
     * the basket alone — they never shipped it.
     */
    val consumesBasket: Boolean = false,
)

/**
 * The quick-reply sheet's mount-point state — non-null while the sheet is up, null once dismissed
 * (see [QuickReplyLaunch] above for what an opening carries).
 *
 * #953 (F3) — SAVEABLE across activity recreation, unlike the deliberately non-saveable local UI
 * states of this screen (`menuPost` / `imageMenuTarget`, see TopicLoadedContent) : those are
 * ephemeral overflow menus with nothing in flight, whereas this state is the MOUNT POINT of the
 * sheet's effect collector while [QuickReplyViewModel] — scoped to the topic's NAV ENTRY, so it
 * survives the recreation — may hold an in-flight POST. Losing the mount point desynchronised
 * sheet and ViewModel : the recreation tore the sheet down mid-submit (the #788 dismiss guards
 * only cover user dismissal), the POST then succeeded into the VM's buffered effects Channel with
 * no collector, and the stale SubmitSucceeded was REPLAYED at the next manual opening (instant
 * close + phantom refresh/scroll + basket purge). Restoring the launch re-mounts the sheet over
 * the SAME nav-entry ViewModel, so the buffered effect is consumed legitimately — the refresh of
 * a POST that succeeded during the rotation IS the correct outcome. Every field is a plain value
 * ([QuickReplyLaunchSaver]), so saving it costs nothing.
 */
@Composable
internal fun rememberQuickReplyLaunch(): MutableState<QuickReplyLaunch?> =
    rememberSaveable(stateSaver = QuickReplyLaunchSaver) { mutableStateOf(null) }

/**
 * #953 (F3) — explicit [Saver] for the (non-Parcelable) [QuickReplyLaunch] : the flat list is
 * `[cat, subcat, topicId, page, consumesBasket, then numreponse/author/excerpt per quote]` —
 * primitives only, all Bundle-safe. A null launch (sheet closed) is handled by [listSaver]
 * itself : the empty list it produces is stored as null, restored as null, and this saver's
 * `restore` only ever sees non-empty lists.
 */
internal val QuickReplyLaunchSaver: Saver<QuickReplyLaunch?, Any> = listSaver<QuickReplyLaunch?, Any>(
    save = { launch ->
        if (launch == null) {
            emptyList()
        } else {
            buildList {
                add(launch.request.cat)
                add(launch.request.subcat)
                add(launch.request.topicId)
                add(launch.request.page)
                add(launch.consumesBasket)
                launch.initialQuotes.forEach { quote ->
                    add(quote.numreponse)
                    add(quote.author)
                    add(quote.excerpt)
                }
            }
        }
    },
    restore = { saved ->
        if (saved.size < QUICK_REPLY_SAVER_HEADER_SIZE) {
            null
        } else {
            QuickReplyLaunch(
                request = QuickReplyRequest(
                    cat = saved[0] as Int,
                    subcat = saved[1] as Int,
                    topicId = saved[2] as Int,
                    page = saved[3] as Int,
                ),
                initialQuotes = saved
                    .drop(QUICK_REPLY_SAVER_HEADER_SIZE)
                    .chunked(QUICK_REPLY_SAVER_QUOTE_FIELDS)
                    .map { (numreponse, author, excerpt) ->
                        QuotedPostPreview(
                            numreponse = numreponse as Int,
                            author = author as String,
                            excerpt = excerpt as String,
                        )
                    },
                consumesBasket = saved[4] as Boolean,
            )
        }
    },
)

/** [QuickReplyLaunchSaver] layout : request Ints + consumesBasket before the quote triplets. */
private const val QUICK_REPLY_SAVER_HEADER_SIZE = 5

/** [QuickReplyLaunchSaver] layout : numreponse, author, excerpt per armed quote. */
private const val QUICK_REPLY_SAVER_QUOTE_FIELDS = 3

/** #806 — the two composition surfaces a write tap can open. */
internal enum class WritingSurface { SHEET, FULL_EDITOR }

/**
 * #806 — which surface a write tap opens, from the user's [preset] and the number of citations the
 * tap carries (0 for the reply FAB, 1 for « Citer », the basket size for « Citer N »).
 *
 * - [WritingSurfacePreset.SHEET] (experimental opt-in, #951) keeps the 0.25.1 routing exactly :
 *   the quick-reply sheet, except a multi-quote basket of [MULTI_QUOTE_FULL_EDITOR_THRESHOLD]+
 *   cards (mockup P3 : « le cas qui force le plein écran », #604 lot 3) — up to that the sheet
 *   stays comfortable with the keyboard open.
 * - [WritingSurfacePreset.SHEET_EXCEPT_QUOTES] : any citation (1..N) opens the full-screen editor.
 * - [WritingSurfacePreset.FULL_EDITOR] (default since #951) : always the full-screen editor.
 *
 * Pure so the routing table is unit-testable ([MultiQuoteRoutingTest]).
 */
internal fun writingSurfaceFor(preset: WritingSurfacePreset, quoteCount: Int): WritingSurface = when (preset) {
    WritingSurfacePreset.FULL_EDITOR -> WritingSurface.FULL_EDITOR
    WritingSurfacePreset.SHEET_EXCEPT_QUOTES ->
        if (quoteCount > 0) WritingSurface.FULL_EDITOR else WritingSurface.SHEET
    WritingSurfacePreset.SHEET ->
        if (quoteCount >= MULTI_QUOTE_FULL_EDITOR_THRESHOLD) WritingSurface.FULL_EDITOR else WritingSurface.SHEET
}

/**
 * #604 lot 3 — cadrage Codex : « 3 citations = plein écran », a named constant. Since #806 the
 * user setting is the PRESET above ; this threshold remains a constant guarding the
 * [WritingSurfacePreset.SHEET] preset only (the other presets ignore it by construction).
 */
internal const val MULTI_QUOTE_FULL_EDITOR_THRESHOLD = 3

// #604 lot 2 — the quote-card snapshot, built AT SELECTION TIME where the full Post is in scope
// (cadrage Codex : the cards never re-parse a post ; the exact [quotemsg] is fetched at
// materialisation). Uniqueness in the basket stays keyed on the numreponse.
internal fun Post.toQuotedPreview(): QuotedPostPreview = QuotedPostPreview(
    numreponse = numreponse,
    author = author,
    excerpt = postContentExcerpt(content),
)

internal fun shouldEnableReply(topic: Topic, isAuthenticated: Boolean): Boolean =
    topic.canReply && isAuthenticated

// #291 — the « Citer N » FAB count, zeroed when quoting is unavailable (locked topic, anonymous
// session): the basket may still hold posts, but advertising an unusable action would be a lie.
// Extracted from TopicContent for the detekt cyclomatic-complexity budget.
internal fun effectiveMultiQuoteCount(topic: Topic, isAuthenticated: Boolean, selection: List<Int>): Int =
    if (shouldShowQuoteAction(topic, isAuthenticated)) selection.size else 0

internal fun shouldShowQuoteAction(topic: Topic, isAuthenticated: Boolean): Boolean =
    topic.canReply && isAuthenticated

/**
 * #986 — maps the resolved topic-level favourite state to the post-menu row. Authentication and
 * HFR's own 1-based `ref` remain hard gates; unknown/failed state stays visible but disabled so the
 * action is never blind.
 */
internal fun favoriteActionFor(
    isAuthenticated: Boolean,
    quoteRef: Int?,
    state: FavoriteAtPostState,
): PostFavoriteAction {
    if (!isAuthenticated || (quoteRef ?: 0) < 1) return PostFavoriteAction.HIDDEN
    return when (state) {
        FavoriteAtPostState.Unknown,
        FavoriteAtPostState.Resolving,
        is FavoriteAtPostState.ConfirmingMove
        -> PostFavoriteAction.CHECKING
        is FavoriteAtPostState.Ready ->
            if (state.topicHasFavorite) PostFavoriteAction.MOVE else PostFavoriteAction.ADD
        is FavoriteAtPostState.Adding -> PostFavoriteAction.ADDING
        FavoriteAtPostState.Unavailable -> PostFavoriteAction.UNAVAILABLE
    }
}

// #792 — « Envoyer un MP » from the post's contextual menu. Auth-only (the MP composer is a
// logged-in surface), never on the user's own posts, and only for authors with a real HFR
// profile (`profileId != null` : « Publicité » rows and anonymous reads are not messageable —
// same gate as the profile hero). Topic lock is irrelevant : the MP leaves the topic entirely.
internal fun shouldShowSendPrivateMessage(
    post: Post,
    isAuthenticated: Boolean,
    connectedPseudo: String?,
): Boolean =
    isAuthenticated && !isOwnPostEffective(post, connectedPseudo) && post.profileId != null

// #545 — `post.isEditable` is blind when the profile disables « Affichage des outils »
// (affichoutils=0 : HFR strips the whole toolbar), so ownership-by-pseudo is an OR-fallback.
// HFR's edit form itself works regardless of the option — only the link was missing.
internal fun shouldShowEditAction(
    topic: Topic,
    post: Post,
    isAuthenticated: Boolean,
    connectedPseudo: String?,
): Boolean =
    (post.isEditable || isOwnPostBySession(post, connectedPseudo)) &&
        topic.canReply &&
        isAuthenticated

// #292 — « Supprimer » shares the « Modifier » gate: HFR exposes deletion through the same edit
// form, so any post the user can edit, they can delete. The first-post exclusion (deleting it would
// remove the whole topic) is applied at the call site by position, not here.
// #600 (vague 3) — « Dernier message lu » separator gate. `forceRefresh` is #231's flag-tap
// marker: the ONE navigation whose scrollTo is semantically « last read » (the flag handler only
// sets scrollTo when resuming at the last-read page). Since #895, pagination and citation jumps
// happen in-VM and can preserve these request fields instead of rebuilding the route. This gate is
// intentionally unchanged for the beta; #953/F4 tracks the marker that can therefore outlive its
// landing. If forceRefresh ever grows another producer, give this semantic its own field first.
internal fun shouldShowLastReadMarker(request: TopicRequest, numreponse: Int): Boolean =
    request.forceRefresh && request.scrollTo == numreponse

// #509 → #983 — a post renders as the collapsed blacklist placeholder while its author is hidden
// AND the reader has not revealed it. Extracted because #983 needs the same predicate on the NEXT
// post (a placeholder is an island, so the post above it must not draw its hairline), and two
// copies of a two-clause condition drift.
internal fun isHiddenPost(post: Post, hidden: Set<Int>, revealed: Set<Int>): Boolean =
    post.numreponse in hidden && post.numreponse !in revealed

internal fun shouldShowDeleteAction(
    topic: Topic,
    post: Post,
    isAuthenticated: Boolean,
    connectedPseudo: String?,
): Boolean =
    (post.isEditable || isOwnPostBySession(post, connectedPseudo)) &&
        topic.canReply &&
        isAuthenticated

// #292 — the topic's first post is `topic.posts.first()` on page 1. Deleting it would remove the whole
// topic (out of scope for this MVP), so the call site excludes it from the delete affordance. Position
// + identity based: `numreponse` is unique per HFR category, so matching it against the page's first
// row is sufficient within a loaded topic page.
internal fun isFirstPostOfTopic(topic: Topic, post: Post): Boolean =
    topic.page == 1 && post.numreponse == topic.posts.firstOrNull()?.numreponse

// Phase 2D #148 / #220 — « Modifier le premier message ». 6-way conjunction by design: auth,
// FP ownership, postable topic, a real sub-category (FP recategorise is NOT relaxed for subcat=0,
// cf. #213), page 1 (the FP lives there), non-empty posts. Each clause guards a distinct invariant.
// #545 — `isFirstPostOwner` is parser-derived from the FIRST post's edit link, itself absent for
// affichoutils=0 profiles ; ownership-by-pseudo of that same first post is the OR-fallback.
@Suppress("ComplexCondition")
internal fun shouldShowEditFirstPost(
    topic: Topic,
    isAuthenticated: Boolean,
    connectedPseudo: String?,
): Boolean =
    isAuthenticated &&
        (
            topic.isFirstPostOwner ||
                topic.posts.firstOrNull()?.let { isOwnPostBySession(it, connectedPseudo) } == true
            ) &&
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
