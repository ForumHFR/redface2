package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.Poll
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.ui.RedfacePlaceholderScreen
import fr.forumhfr.redface2.core.ui.avatar.RedfaceUserAvatar
import fr.forumhfr.redface2.core.ui.error.sharedLabelResOrNull
import fr.forumhfr.redface2.core.ui.post.PostRenderer
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

@Composable
@Suppress("LongParameterList") // state-hoisted Composable : each callback has a distinct call-site
// (reply / quote / edit-post / edit-FP / openPage) and bundling them in a callbacks holder would
// hide the navigation surface rather than simplify it.
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
     * (`resolveTopicScrollRestoration`: route `scrollTo` > post-submit landing > saved anchor > top)
     * BEFORE threading the value here, so a non-null anchor already means « the saved position won »
     * — the screen applies it once the first `Loaded` emission lands, exactly once per landing, and
     * it can never compete with the `ScrollToPost` / `ScrollToEndOfPage` effects (their routes
     * resolve to `null` here).
     */
    restoreScrollAnchor: TopicScrollAnchor? = null,
    /**
     * #307 — reports the read position when the screen leaves the composition, so `:app` can cache
     * it per `(cat, post, page)` (twin of [onTitleLoaded] / the title cache). Fired from a single
     * `DisposableEffect` — the unique save point covering EVERY departure (swipe, FAB, header pager,
     * back, tab switch) — and only after the page actually loaded, so a landing abandoned while
     * still `Loading` never clobbers a previously saved position with `(0, 0)`.
     */
    onScrollAnchorSaved: (TopicScrollAnchor) -> Unit = {},
) {
    val viewModel = hiltViewModel<TopicViewModel, TopicViewModel.Factory>(
        creationCallback = { factory -> factory.create(request) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lazyListState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    // Resolve the string at composition time, not inside the LaunchedEffect collect block.
    // Lint flags `context.getString(R.string.…)` inside a Compose call site (the call is in a
    // suspending lambda but the surrounding scope is still a Composable). Capturing the message
    // upfront keeps the rule happy and avoids re-resolving on every effect.
    val refreshFailedMsg = stringResource(R.string.topic_post_submit_refresh_failed)
    // #335 — manual pull-to-refresh failure message (resolved upfront, same rationale).
    val refreshManualFailedMsg = stringResource(R.string.topic_refresh_failed)
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
        onOpenProfile = onOpenProfile,
        onDeleteRequest = { numreponse -> deleteCandidate = numreponse },
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
 * then applies the anchor exactly once per route landing. Subsequent `Loaded` emissions
 * (cache→network refresh of the stale path, manual pull-to-refresh, post-delete reload) never
 * re-scroll: the effect has already completed, mirroring the one-shot contract of the scroll
 * effects. The priority chain was resolved by `:app` — see `restoreScrollAnchor` on [TopicScreen].
 *
 * SAVE: `onDispose` is the ONE save point. `onOpenPage` is shared by swipe, header, pager and
 * FAB, so saving per trigger would multiply call sites (and race); disposal of this composition
 * covers every departure — swipe, FAB, back, tab switch, editor push — with a single write.
 * `scrollAnchorSettled` gates the save: a page abandoned while still Loading reads (0, 0) from
 * a list that never rendered, and must not clobber the real position saved by an earlier visit.
 */
@Composable
private fun TopicScrollRestorationEffects(
    state: StateFlow<TopicUiState>,
    lazyListState: LazyListState,
    request: TopicRequest,
    restoreScrollAnchor: TopicScrollAnchor?,
    onScrollAnchorSaved: (TopicScrollAnchor) -> Unit,
) {
    var scrollAnchorSettled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        state.first { it.mode is TopicUiState.Mode.Loaded }
        restoreScrollAnchor?.let { anchor ->
            lazyListState.scrollToItem(anchor.index, anchor.offset)
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
    onOpenProfile: (userId: Int, pseudo: String, avatarUrl: String?) -> Unit = { _, _, _ -> },
    // #292 — a per-post « Supprimer » tap; the screen owns the confirmation dialog, so this only
    // requests it (carrying the post's numreponse). Never invoked for the first post (excluded).
    onDeleteRequest: (numreponse: Int) -> Unit = {},
) {
    // #285 — the topic title and #284 — the page counter live in a persistent top app bar so they
    // stay visible while the user scrolls (the in-card title/caption scrolls away). When the page
    // is still loading / errored, fall back to a generic title and the requested page.
    val loaded = state.mode as? TopicUiState.Mode.Loaded
    val fallbackTitle = stringResource(R.string.topic_topbar_fallback_title)
    // Honour TopicRequest.titleHint's contract: the cached hint is a LOADING-only stand-in. Once the
    // page is Loaded, the live Topic.title wins (or the generic fallback if it is somehow blank) — we
    // never reach back to the stale hint, so a loaded topic can never display another page's title.
    val barTitle = if (loaded != null) {
        loaded.topic.title.takeIf { it.isNotBlank() } ?: fallbackTitle
    } else {
        state.request.titleHint?.takeIf { it.isNotBlank() } ?: fallbackTitle
    }
    val barCurrentPage = loaded?.topic?.page ?: state.request.page
    val barTotalPages = loaded?.topic?.totalPages
        ?: state.availablePages.lastOrNull()
        ?: state.request.page
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
                            text = stringResource(R.string.topic_page_indicator, barCurrentPage, barTotalPages),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backLabel },
                    ) {
                        // A text glyph used as an icon was unstable: its size depended on the system
                        // font's `←` rendering, the baseline and the font-scale, never matching the
                        // title cleanly (cf. Codex review). Use a dp-sized vector instead — optically
                        // centred by the IconButton, font-independent. The a11y label stays on the
                        // IconButton, so the icon itself is decorative (contentDescription = null).
                        Icon(
                            painter = painterResource(fr.forumhfr.redface2.core.ui.R.drawable.ic_arrow_back),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            // #283 + bonus — quick access to "poster" and page-change without scrolling back to the
            // header. Only in Loaded mode (needs subcat/page/canReply). The Scaffold applies the
            // navigation-bar insets to this slot, so no manual padding here. Coexists with the #300
            // scrollbar (right edge, auto-hiding) — a slight bottom-right overlap is acceptable.
            val current = loaded
            if (current != null) {
                TopicBottomActions(
                    showReply = shouldEnableReply(current.topic, state.isAuthenticated),
                    canGoPrevious = state.canGoPrevious,
                    canGoNext = state.canGoNext,
                    // Clamp to [1, totalPages]: `canGoPrevious/Next` are derived from `request.page`
                    // while the target is computed from the parsed `topic.page`; if those ever desync
                    // (HFR clamps an out-of-range page to the last one), the clamp keeps navigation in
                    // bounds — same robustness as the header guard and the swipe (#282).
                    onPreviousPage = { onOpenPage((current.topic.page - 1).coerceAtLeast(1)) },
                    onNextPage = { onOpenPage((current.topic.page + 1).coerceAtMost(current.topic.totalPages)) },
                    onReply = { onReply(current.topic.subcat, current.topic.page) },
                )
            }
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.topic_loading),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
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
                        // #300 — wrap the list in a Box so the intra-page scrollbar can overlay its
                        // right edge. The scrollbar is pure UI derived from `listState`; it never moves
                        // the read position on its own — only an explicit thumb drag fast-scrolls.
                        Box(modifier = Modifier.fillMaxSize()) {
                            TopicLoadedContent(
                                state = state,
                                topic = mode.topic,
                                onReply = onReply,
                                onQuote = onQuote,
                                onEdit = onEdit,
                                onEditFirstPost = onEditFirstPost,
                                onOpenPage = onOpenPage,
                                onOpenProfile = onOpenProfile,
                                onDeleteRequest = onDeleteRequest,
                                listState = listState,
                            )
                            TopicScrollbar(
                                listState = listState,
                                modifier = Modifier.align(Alignment.CenterEnd),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("LongParameterList") // state-hoisted Composable : each param has a distinct call-site.
private fun TopicLoadedContent(
    state: TopicUiState,
    topic: Topic,
    onReply: (subcat: Int, page: Int) -> Unit,
    onQuote: (subcat: Int, page: Int, quotedNumreponse: Int, quoteRef: Int?) -> Unit,
    onEdit: (subcat: Int, page: Int, numreponse: Int) -> Unit,
    onEditFirstPost: (subcat: Int, page: Int, numreponse: Int) -> Unit,
    onOpenPage: (Int) -> Unit,
    onOpenProfile: (userId: Int, pseudo: String, avatarUrl: String?) -> Unit = { _, _, _ -> },
    onDeleteRequest: (numreponse: Int) -> Unit = {},
    listState: LazyListState,
) {
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
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            // #285 — system-bar insets (status + navigation) are now consumed by the Scaffold/TopAppBar
            // in TopicContent and applied via the content Surface's padding(innerPadding); the list no
            // longer adds statusBarsPadding()/navigationBarsPadding() here to avoid double-insetting.
            // #282 — horizontal swipe changes page via the existing route-driven onOpenPage, with
            // drag-follow feedback: the page tracks the finger (graphicsLayer inside topicPageSwipe)
            // and topicPageSwipeEdge paints an edge glow as the swipe arms. topicPageSwipeEdge must
            // precede topicPageSwipe so the glow draws in untranslated (screen) space.
            // Engages on horizontal slop only, so vertical scroll and the page-grid's own
            // horizontalScroll keep their gestures; edges are a damped no-op.
            .topicPageSwipeEdge(
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
            ),
        state = listState,
        // #283 — extra bottom padding so the last post's right-aligned actions clear the floating
        // bottom-action cluster (the Scaffold FAB slot floats over the content). Harmless extra
        // breathing room when the cluster is absent (anon + single page).
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            // Phase 2D #148 — the « Modifier le premier message » action is
            // exposed only when (a) we are on page 1 (FP lives there by
            // definition), (b) HFR rendered the FP edit link in the toolbar
            // (`Topic.isFirstPostOwner`, parsed from the first post on the
            // page) and (c) the topic is postable WITH a real sub-category.
            // #213 — unlike Reply/Quote/Edit-post (gated on `canReply` alone,
            // subcat=0 OK for a category without sub-category), FP edit also
            // requires `subcat > 0`: the FP recategorise flow
            // (TopicFormViewModel/TopicFormState) is NOT relaxed for subcat=0
            // (its sub-category dropdown contract for a 0-subcat category is not
            // captured yet), so offering it on an IA-style topic would open an
            // editor that fails with MissingSubcat. Kept strict to avoid a
            // button-shows-but-submit-fails regression (FP-in-0-subcat = #213 follow-up).
            // `numreponse` of the FP comes from the first post, not `topic.post`.
            // #220 — the gate (incl. the auth clause) is the testable `shouldShowEditFirstPost`.
            val editFirstPostAction: (() -> Unit)? =
                if (shouldShowEditFirstPost(topic, state.isAuthenticated)) {
                    { onEditFirstPost(topic.subcat, topic.page, topic.posts.first().numreponse) }
                } else {
                    null
                }
            TopicHeaderCard(
                topic = topic,
                state = state,
                onReply = onReply,
                onEditFirstPost = editFirstPostAction,
                onOpenPage = onOpenPage,
            )
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
            val quoteAction: (() -> Unit)? = if (shouldShowQuoteAction(topic, state.isAuthenticated)) {
                { onQuote(topic.subcat, topic.page, post.numreponse, post.quoteRef) }
            } else {
                null
            }
            // Phase 2D (#147) — « Modifier » is exposed by HFR only on the
            // user's own posts of an unlocked topic. Same canReply gate as
            // Citer (#213) to refuse a read-only topic (no reply form).
            val editAction: (() -> Unit)? = if (shouldShowEditAction(topic, post, state.isAuthenticated)) {
                { onEdit(topic.subcat, topic.page, post.numreponse) }
            } else {
                null
            }
            // #292 — « Supprimer » uses the same gate as « Modifier » (HFR allows deletion via the
            // edit form), EXCEPT it is never offered on the topic's first post: deleting that would
            // remove the entire topic, an out-of-scope destructive path for this MVP. The first post
            // is `topic.posts.first()` on page 1.
            // Disable every delete affordance while a deletion is in flight (state.deletingNumreponse
            // != null) so a second tap can't queue another POST mid-request (the ViewModel also
            // guards, but hiding the button is the honest UI signal).
            val deleteAction: (() -> Unit)? = if (
                state.deletingNumreponse == null &&
                !isFirstPostOfTopic(topic, post) &&
                shouldShowDeleteAction(topic, post, state.isAuthenticated)
            ) {
                { onDeleteRequest(post.numreponse) }
            } else {
                null
            }
            // Phase 2 finish (#208) — profile tap is enabled only when HFR exposed
            // a profile link for this post (Post.profileId != null). Posts without a
            // profile link (Publicité rows, anonymous reads) keep the tap hidden.
            val profileAction: (() -> Unit)? = post.profileId?.let { profileId ->
                { onOpenProfile(profileId, post.author, post.avatarUrl) }
            }
            TopicPostCard(
                post = post,
                highlighted = highlight == post.numreponse,
                citedCount = citationCounts[post.numreponse] ?: 0,
                onQuote = quoteAction,
                onEdit = editAction,
                onDelete = deleteAction,
                onOpenProfile = profileAction,
                onOpenMenu = { menuPost = post },
            )
        }
    }
    // #362 — per-post contextual menu. The permalink is rebuilt from the LOADED topic's
    // (cat, post, page) — not the request — so it always reflects the page HFR actually
    // served (HFR clamps out-of-range pages). citedCount reuses the page-scoped #239 index.
    menuPost?.let { post ->
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
        )
    }
}

@Composable
private fun TopicHeaderCard(
    topic: Topic,
    state: TopicUiState,
    onReply: (subcat: Int, page: Int) -> Unit,
    onEditFirstPost: (() -> Unit)?,
    onOpenPage: (Int) -> Unit,
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = topic.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(
                    R.string.topic_caption,
                    topic.post,
                    topic.page,
                    topic.totalPages,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.request.scrollTo?.let { target ->
                Text(
                    text = stringResource(R.string.topic_scroll_to, target),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TopicPageNavigation(
                currentPage = topic.page,
                availablePages = state.availablePages,
                canGoPrevious = state.canGoPrevious,
                canGoNext = state.canGoNext,
                onOpenPage = onOpenPage,
            )
            topic.poll?.let { poll ->
                TopicPollCard(poll)
            }
            Button(
                onClick = { onReply(topic.subcat, topic.page) },
                // #213 — enabled only when HFR rendered the `bddpost` reply form
                // (authenticated, non-locked topic → `canReply`). #220 — also gated on the
                // live auth state so a stale cached `canReply = true` row (the topic cache is
                // not purged on logout) never offers Reply to a logged-out user. `subcat = 0`
                // (cat without sub-category, e.g. IA) is a valid postable value and is forwarded.
                enabled = shouldEnableReply(topic, state.isAuthenticated),
            ) {
                Text(text = stringResource(R.string.topic_reply))
            }
            // Phase 2D #148 — « Modifier le premier message » lives in the header
            // card because it acts on the topic, not on a single post. We render
            // it as an OutlinedButton to stay visually subordinate to the primary
            // « Répondre » action above.
            if (onEditFirstPost != null) {
                OutlinedButton(onClick = onEditFirstPost) {
                    Text(text = stringResource(R.string.topic_edit_first_post))
                }
            }
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
private fun TopicPollCard(poll: Poll) {
    var revealed by rememberSaveable(poll) { mutableStateOf(true) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.clickable { revealed = !revealed },
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

@Composable
@Suppress("LongParameterList") // state-hoisted Composable : each param has a distinct call-site.
private fun TopicPostCard(
    post: Post,
    highlighted: Boolean,
    /**
     * #239 — number of posts on the current page that cite this one. 0 hides the badge.
     */
    citedCount: Int,
    onQuote: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    /**
     * #292 — « Supprimer » this post. Null hides the button (not the user's own post, locked topic,
     * logged out, or the topic's first post — which is excluded to avoid whole-topic deletion).
     */
    onDelete: (() -> Unit)? = null,
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
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // #201 — avatar + author header in a Row so the visual identity of the poster
            // is immediately visible. Falls back to a placeholder square (cf.
            // `RedfaceUserAvatar`) when `Post.avatarUrl == null` or the load errors.
            // Phase 2 finish (#208) — tapping the avatar OR the author pseudo opens the
            // profile bottom sheet when `onOpenProfile` is non-null. Review feedback I6:
            // the clickable surface is now restricted to the avatar + the pseudo Text. The
            // date Text below is intentionally outside the clickable zone so a tap on the
            // date does NOT open the profile (the legacy implementation clickable-d the
            // whole parent Row, which made the date erroneously open the profile). Each
            // clickable element keeps `minimumInteractiveComponentSize()` so it still
            // meets the Material 48dp touch target — the avatar default is 40dp and the
            // pseudo line height is smaller than 48dp.
            val openProfileLabel = if (onOpenProfile != null) {
                stringResource(R.string.topic_open_profile_action)
            } else {
                null
            }
            val avatarModifier = if (onOpenProfile != null) {
                Modifier
                    .minimumInteractiveComponentSize()
                    .clickable(
                        onClick = onOpenProfile,
                        role = Role.Button,
                        onClickLabel = openProfileLabel,
                    )
            } else {
                Modifier
            }
            val pseudoModifier = if (onOpenProfile != null) {
                // No minimumInteractiveComponentSize() on the pseudo: it reserves a 48dp-tall box
                // and centres the text inside it, which inflated the header Row and left the pseudo
                // floating mid-height with the date pushed far below it. The avatar beside it is the
                // 48dp-compliant touch target for the very same `onOpenProfile` action, so the pseudo
                // stays a convenience tap at its natural text height without bloating the layout.
                Modifier.clickable(
                    onClick = onOpenProfile,
                    role = Role.Button,
                    onClickLabel = openProfileLabel,
                )
            } else {
                Modifier
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                // Centre the avatar against the name+date block so the identity line reads as one
                // tidy unit (the previous Top alignment + the inflated pseudo made the pseudo look
                // vertically centred while the date dropped well below the avatar).
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RedfaceUserAvatar(
                    avatarUrl = post.avatarUrl,
                    author = post.author,
                    modifier = avatarModifier,
                )
                Column(
                    // #362 — weight(1f) instead of fillMaxWidth so the menu button below gets its
                    // slot at the right edge of the header; the pseudo keeps its own weight inside.
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
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
                        Text(
                            text = post.author,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // Clickable on the pseudo only — the date stays inert.
                            modifier = Modifier
                                .weight(weight = 1f, fill = false)
                                .then(pseudoModifier),
                        )
                    }
                    Text(
                        text = post.date.asTopicDate(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (citedCount > 0) {
                        // #239 — sober pill: how many posts of THIS page cite this one. Page-scoped
                        // (cf. citationCountsByNumreponse); jumping to the citing posts is a follow-up.
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.topic_post_cited_count,
                                    citedCount,
                                    citedCount,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                // #362 — per-post contextual menu trigger, flush right of the header. The post
                // number that used to trail the pseudo lives in the menu now. A text glyph, not a
                // Material icon (detekt ForbiddenImport blocks androidx.compose.material.*) — same
                // pattern as PageFab/ReplyFab. Sits in the OUTER row (next to the whole
                // avatar+name+date block) so its 48dp touch target never inflates the pseudo line
                // (cf. the pseudo minimumInteractiveComponentSize note above).
                val menuLabel = stringResource(R.string.topic_post_menu_action)
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
            }
            // #281 — topic posts are selectable/copyable (opt-in; default is OFF in PostRenderer).
            PostRenderer(content = post.content, selectable = true)
            if (onQuote != null || onEdit != null || onDelete != null) {
                // Actions row at the bottom of the post card, sober TextButtons
                // so they stay subordinate to the post content. « Modifier »
                // (Phase 2D, #147) and « Supprimer » (#292) appear only on the
                // user's own editable posts when the topic is still postable
                // (« Supprimer » additionally excludes the first post). « Citer »
                // (Phase 2C, #146) appears whenever the topic is postable, even
                // when the per-post `quoteRef` link was obfuscated and parsed as
                // null (#227). Any can be absent — we render the row only if at
                // least one action is provided.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (onDelete != null) {
                        TextButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text(text = stringResource(R.string.topic_post_delete))
                        }
                    }
                    if (onEdit != null) {
                        TextButton(onClick = onEdit) {
                            Text(text = stringResource(R.string.topic_post_edit))
                        }
                    }
                    if (onQuote != null) {
                        TextButton(onClick = onQuote) {
                            Text(text = stringResource(R.string.topic_post_quote))
                        }
                    }
                }
            }
        }
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
 * #283 + bonus — the floating bottom-action cluster: previous/next page mini-FABs and a « Répondre »
 * extended FAB, so posting and page-change are reachable without scrolling back up to the header. Pure
 * presentation: each affordance is gated on the same flags the header already uses, and reuses the
 * existing `onReply`/`onOpenPage` callbacks. Renders nothing when nothing is available (anon + single
 * page), so the Scaffold reserves no FAB space.
 */
@Composable
@Suppress("LongParameterList") // hoisted action cluster, mirrors other hoisted composables in this file
private fun TopicBottomActions(
    showReply: Boolean,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onReply: () -> Unit,
) {
    val previousLabel = stringResource(R.string.topic_fab_previous_page)
    val nextLabel = stringResource(R.string.topic_fab_next_page)
    if (showReply || canGoPrevious || canGoNext) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canGoPrevious) {
                PageFab(description = previousLabel, glyph = "‹", onClick = onPreviousPage)
            }
            if (canGoNext) {
                PageFab(description = nextLabel, glyph = "›", onClick = onNextPage)
            }
            if (showReply) {
                ReplyFab(onClick = onReply)
            }
        }
    }
}

@Composable
private fun PageFab(
    description: String,
    glyph: String,
    onClick: () -> Unit,
) {
    // No Material icons (detekt ForbiddenImport blocks androidx.compose.material.*): the glyph is a
    // decorative Text and the real label rides on the FAB's `contentDescription` for TalkBack — same
    // pattern as the top-bar back button.
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(glyph)
    }
}

@Composable
private fun ReplyFab(onClick: () -> Unit) {
    // Same SmallFloatingActionButton footprint as the page FABs (user request): the « Répondre » label
    // rides on contentDescription for TalkBack and the glyph is decorative (no Material icons — detekt
    // ForbiddenImport blocks androidx.compose.material.*), mirroring PageFab and the top-bar back button.
    val replyLabel = stringResource(R.string.topic_fab_reply)
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = replyLabel },
    ) {
        Text("✎")
    }
}

// #220 — write affordances additionally require an authenticated session. A logged-out user
// can still hold a stale cached `canReply = true` row (the topic page cache is intentionally
// not purged on logout, cf. CacheInvalidator), so these gates consult auth explicitly instead
// of trusting `canReply` alone — symmetric with the « Créer topic » FAB
// (CategoryViewModel.canCreateTopic).
internal fun shouldEnableReply(topic: Topic, isAuthenticated: Boolean): Boolean =
    topic.canReply && isAuthenticated

internal fun shouldShowQuoteAction(topic: Topic, isAuthenticated: Boolean): Boolean =
    topic.canReply && isAuthenticated

internal fun shouldShowEditAction(topic: Topic, post: Post, isAuthenticated: Boolean): Boolean =
    post.isEditable && topic.canReply && isAuthenticated

// #292 — « Supprimer » shares the « Modifier » gate: HFR exposes deletion through the same edit
// form, so any post the user can edit, they can delete. The first-post exclusion (deleting it would
// remove the whole topic) is applied at the call site by position, not here.
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
