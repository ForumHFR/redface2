package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.Poll
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.ui.RedfacePlaceholderScreen
import fr.forumhfr.redface2.core.ui.avatar.RedfaceUserAvatar
import fr.forumhfr.redface2.core.ui.post.PostRenderer
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
     * but the editor will GET HFR's quote form (`?numrep=…&ref=…`) and hydrate the
     * draft with the `[quotemsg=…]` block HFR prefills. The call-site supplies
     * `quotedNumreponse = post.numreponse` and `quoteRef = post.quoteRef`, captured
     * from the topic page HTML. Posts whose HTML did not expose a quote link
     * (locked topic special cases, anonymous fallback) keep the « Citer » button
     * hidden — we never reach this callback for those.
     */
    onQuote: (subcat: Int, page: Int, quotedNumreponse: Int, quoteRef: Int) -> Unit,
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
            }
        }
    }

    TopicContent(
        state = state,
        listState = lazyListState,
        onIntent = viewModel::send,
        onReply = onReply,
        onQuote = onQuote,
        onEdit = onEdit,
        onEditFirstPost = onEditFirstPost,
        onOpenPage = onOpenPage,
        onOpenProfile = onOpenProfile,
    )
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
 * manual scrolling — extending the single-shot, no-focus-stealing contract on [TopicEffect]. Inline
 * smileys/images are *not* a factor: their `InlineTextContent` placeholders are fixed-size, so only
 * block images move the geometry.
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
        when (
            val step = reanchorStep(
                current = current,
                previous = previous,
                target = target,
                stableFrames = stableFrames,
                stableThreshold = REANCHOR_STABLE_FRAMES,
                canStop = frame >= REANCHOR_MIN_FRAMES,
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
 * Stop only when [canStop] is true and the target's position has held still ([current] equal to
 * [previous]) for [stableThreshold] consecutive frames. Otherwise carry the updated stable count and
 * ask for a re-pin whenever the target is not currently at the very top ([ReanchorFrame.index] !=
 * [target] or a non-zero offset) — a no-op when it already is, harmless when the list cannot scroll
 * it higher.
 *
 * @param current the target row's position this frame
 * @param previous the same reading from the previous frame, or `null` on the first frame
 * @param target the item index we want pinned to the top
 * @param stableFrames consecutive still frames observed so far
 * @param stableThreshold still frames required to consider the layout settled
 * @param canStop false during the initial cold-decode guard window
 */
internal fun reanchorStep(
    current: ReanchorFrame,
    previous: ReanchorFrame?,
    target: Int,
    stableFrames: Int,
    stableThreshold: Int,
    canStop: Boolean = true,
): ReanchorStep {
    val moved = previous == null || current != previous
    val nextStableFrames = if (moved) 0 else stableFrames + 1
    if (canStop && nextStableFrames >= stableThreshold) return ReanchorStep.Stop
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

@Composable
@Suppress("LongParameterList") // state-hoisted Composable : each param has a distinct call-site.
internal fun TopicContent(
    state: TopicUiState,
    listState: LazyListState,
    onIntent: (TopicIntent) -> Unit,
    onReply: (subcat: Int, page: Int) -> Unit,
    onQuote: (subcat: Int, page: Int, quotedNumreponse: Int, quoteRef: Int) -> Unit,
    onEdit: (subcat: Int, page: Int, numreponse: Int) -> Unit,
    onEditFirstPost: (subcat: Int, page: Int, numreponse: Int) -> Unit,
    onOpenPage: (Int) -> Unit,
    onOpenProfile: (userId: Int, pseudo: String, avatarUrl: String?) -> Unit = { _, _, _ -> },
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        when (val mode = state.mode) {
            TopicUiState.Mode.Loading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
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
                RedfacePlaceholderScreen(
                    title = stringResource(R.string.topic_error_title),
                    body = stringResource(R.string.topic_error_body, state.request.page, mode.message),
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
                TopicLoadedContent(
                    state = state,
                    topic = mode.topic,
                    onReply = onReply,
                    onQuote = onQuote,
                    onEdit = onEdit,
                    onEditFirstPost = onEditFirstPost,
                    onOpenPage = onOpenPage,
                    onOpenProfile = onOpenProfile,
                    listState = listState,
                )
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
    onQuote: (subcat: Int, page: Int, quotedNumreponse: Int, quoteRef: Int) -> Unit,
    onEdit: (subcat: Int, page: Int, numreponse: Int) -> Unit,
    onEditFirstPost: (subcat: Int, page: Int, numreponse: Int) -> Unit,
    onOpenPage: (Int) -> Unit,
    onOpenProfile: (userId: Int, pseudo: String, avatarUrl: String?) -> Unit = { _, _, _ -> },
    listState: LazyListState,
) {
    val highlight = state.request.scrollTo
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            // Phase 2D #148 — the « Modifier le premier message » action is
            // exposed only when (a) we are on page 1 (FP lives there by
            // definition), (b) HFR rendered the FP edit link in the toolbar
            // (`Topic.isFirstPostOwner`, parsed from the first post on the
            // page) and (c) the topic has a usable `subcat`. `numreponse` of
            // the FP comes from the first post, not from `topic.post` (which
            // is the topic id, a different scope).
            @Suppress("ComplexCondition") // FP visibility = 4-way conjunction by design : ownership,
            // valid subcat, page 1, non-empty posts. Extracting is unhelpful — each clause guards a
            // different invariant (HFR permission, write contract, page scope, fixture safety).
            val editFirstPostAction: (() -> Unit)? = if (
                topic.isFirstPostOwner &&
                topic.hasSubcat &&
                topic.page == 1 &&
                topic.posts.isNotEmpty()
            ) {
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
            // « Citer » is enabled only when (a) the topic has a usable subcat
            // (same gate as Reply) and (b) HFR exposed a quote link for *this*
            // post (locked topics, anonymous-fallback rows do not). Both go via
            // the same `PostEditorRoute`, only the editor request shape differs.
            val quoteAction: (() -> Unit)? = post.quoteRef?.takeIf { topic.hasSubcat }
                ?.let { ref -> { onQuote(topic.subcat, topic.page, post.numreponse, ref) } }
            // Phase 2D (#147) — « Modifier » is exposed by HFR only on the
            // user's own posts of an unlocked topic. Same hasSubcat gate as
            // Citer to refuse the SUBCAT_UNKNOWN cache.
            val editAction: (() -> Unit)? = if (post.isEditable && topic.hasSubcat) {
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
            TopicPostCard(
                post = post,
                highlighted = highlight == post.numreponse,
                onQuote = quoteAction,
                onEdit = editAction,
                onOpenProfile = profileAction,
            )
        }
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
                // Topic pages cached before Phase 2C have `subcat = SUBCAT_UNKNOWN`. We
                // refuse to open the editor in that state — the next live refresh of
                // the topic will populate a real subcat and the button comes back.
                enabled = topic.hasSubcat,
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
            onValueChange = { raw -> input = raw.filter(Char::isDigit).take(JUMP_MAX_DIGITS) },
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
private fun TopicPostCard(
    post: Post,
    highlighted: Boolean,
    onQuote: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    /**
     * Phase 2 finish (#208) — tapping the avatar or author opens the profile bottom sheet.
     * Null when [Post.profileId] is null (Publicité rows, anonymous reads).
     */
    onOpenProfile: (() -> Unit)? = null,
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
                    modifier = Modifier.fillMaxWidth(),
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
                            // Clickable on the pseudo only — post number and date stay inert.
                            modifier = Modifier
                                .weight(weight = 1f, fill = false)
                                .then(pseudoModifier),
                        )
                        Text(
                            text = stringResource(R.string.topic_post_numreponse_suffix, post.numreponse),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = post.date.asTopicDate(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PostRenderer(content = post.content)
            if (onQuote != null || onEdit != null) {
                // Actions row at the bottom of the post card, sober TextButtons
                // so they stay subordinate to the post content. « Modifier »
                // (Phase 2D, #147) appears only on the user's own editable posts.
                // « Citer » (Phase 2C, #146) appears whenever HFR exposed a
                // quote link. Either can be absent — we only render the row at
                // all if at least one action is provided.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
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

private fun java.time.Instant.asTopicDate(): String = topicDateFormatter.format(this)

private const val PAGE_GRID_LIMIT = 40
private const val JUMP_MAX_DIGITS = 4
