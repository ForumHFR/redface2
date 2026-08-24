package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import fr.forumhfr.redface2.core.domain.author.isRf2Creator
import fr.forumhfr.redface2.core.domain.blacklist.canonicalizePseudo
import fr.forumhfr.redface2.core.domain.ego.deriveEgoCanonicalPseudo
import fr.forumhfr.redface2.core.domain.ego.isEgoPost
import fr.forumhfr.redface2.core.domain.messages.PrivateMessageThreadPage
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import fr.forumhfr.redface2.core.model.postContentExcerpt
import fr.forumhfr.redface2.core.model.write.PrivateMessageQuote
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.ui.error.sharedLabelResOrNull
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon
import fr.forumhfr.redface2.core.ui.pager.pageSwipeEdgeHint
import fr.forumhfr.redface2.core.ui.post.CreatorPseudoText
import fr.forumhfr.redface2.core.ui.post.HiddenPostCard
import fr.forumhfr.redface2.core.ui.post.PostCardShellFlatBottomEdge
import fr.forumhfr.redface2.core.ui.post.PostIdentityBand
import fr.forumhfr.redface2.core.ui.post.PostIdentityHeader
import fr.forumhfr.redface2.core.ui.post.PostImageActions
import fr.forumhfr.redface2.core.ui.post.PostImageMenuSheet
import fr.forumhfr.redface2.core.ui.post.PostImageTarget
import fr.forumhfr.redface2.core.ui.post.PostListScaffold
import fr.forumhfr.redface2.core.ui.post.PostMediaDiskCachePolicy
import fr.forumhfr.redface2.core.ui.post.ReadingPostCard
import fr.forumhfr.redface2.core.ui.post.ReadingPostCardPresentation
import fr.forumhfr.redface2.core.ui.theme.LocalBlockedQuoteAuthors
import fr.forumhfr.redface2.core.ui.theme.LocalDisplayMetrics
import fr.forumhfr.redface2.core.ui.zoom.PinchZoomState
import fr.forumhfr.redface2.core.ui.zoom.pinchZoom
import fr.forumhfr.redface2.core.ui.zoom.pinchZoomTransform
import fr.forumhfr.redface2.core.ui.zoom.rememberPinchZoomState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

/**
 * One private-message conversation (#298): renders the messages of a `cat=prive` thread,
 * reusing the shared [ReadingPostCard]; replying rides the [ThreadReplyFab] (#301). The
 * ViewModel receives its arguments via Hilt assisted injection ([PrivateMessageThreadRequest]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList") // Screen host: request + nav callbacks + multi-recipient hint + actions slot.
fun PrivateMessageThreadScreen(
    request: PrivateMessageThreadRequest,
    // Ephemeral UI hint from the inbox row (the route stays opaque, carrying only threadId/page).
    // Complements the page-proven [PrivateMessageThread.isMultiRecipient] so a MultiMP/DT whose
    // current page shows a single other author still reads "Interlocuteurs multiples".
    isMultiRecipientHint: Boolean,
    onLoaded: () -> Unit,
    onBack: () -> Unit,
    pendingSubmitResult: PrivateMessageSubmitResult? = null,
    onSubmitResultConsumed: (PrivateMessageSubmitResult) -> Unit = {},
    onReply: (threadId: Int, page: Int) -> Unit,
    // #1074 — per-message footer action. The quote target is typed before navigation; no private
    // href or BBCode enters the route.
    onQuote: (threadId: Int, page: Int, quote: PrivateMessageQuote) -> Unit,
    // #1074 — scope-filtered basket state is owned by :app; this feature only renders and mutates
    // the current conversation's selections. Complete locators stay inside each QuoteSelection.
    multiQuoteSelections: List<QuoteSelection> = emptyList(),
    onToggleMultiQuote: (QuoteSelection) -> Unit = {},
    onMultiQuote: (threadId: Int, page: Int) -> Unit = { _, _ -> },
    onClearMultiQuote: () -> Unit = {},
    onRemoveMultiQuotes: (Set<Int>) -> Unit = {},
    // #618 — owner-only entry to the recipient editor, from the « Participants » sheet. Navigates to
    // the reply composer with the recipient-manager sheet auto-opened (member changes ship as a reply).
    onManageRecipients: (threadId: Int, page: Int) -> Unit = { _, _ -> },
    // #1042 — tapping a message's avatar or pseudo opens the profile surface. Same contract as the
    // topic's onOpenProfile: the host owns the navigation (the app-level profile sheet), the screen
    // only supplies the identity of the tapped author. Default no-op mirrors the topic screen.
    onOpenProfile: (userId: Int, pseudo: String, avatarUrl: String?) -> Unit = { _, _, _ -> },
    topBarActions: @Composable (() -> Unit)? = null,
) {
    val viewModel = hiltViewModel<PrivateMessageThreadViewModel, PrivateMessageThreadViewModel.Factory>(
        creationCallback = { factory -> factory.create(request) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mode = state.mode
    val networkLoadedThread = mode.networkLoadedThreadOrNull()

    PrivateMessageThreadPrefetchLifecycleGate(viewModel::setPrefetchActive)

    LaunchedEffect(networkLoadedThread) {
        if (networkLoadedThread != null) onLoaded()
    }

    // #1040 lot 6 — editor→retained-ViewModel handoff. The event id keys one consumption even when
    // the editor payload is identical to the previous one; :app clears its slot after this call.
    LaunchedEffect(pendingSubmitResult?.eventId) {
        pendingSubmitResult?.let { result ->
            viewModel.applySubmitResult(result)
            onSubmitResultConsumed(result)
        }
    }

    // #351 — one-shot effects (same idiom as TopicScreen): a keep-content load failure keeps the
    // page on screen and surfaces a Toast inviting a retry (pull again / tap the pager again).
    val context = LocalContext.current
    val refreshFailedMsg = stringResource(R.string.messages_thread_refresh_failed)
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                PrivateMessageThreadEffect.RefreshFailed -> {
                    android.widget.Toast.makeText(
                        context,
                        refreshFailedMsg,
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                PrivateMessageThreadEffect.ImageSaved -> {
                    android.widget.Toast.makeText(
                        context,
                        R.string.messages_image_menu_saved,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                PrivateMessageThreadEffect.ImageSaveFailedFetch -> {
                    android.widget.Toast.makeText(
                        context,
                        R.string.messages_image_menu_save_failed_fetch,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                PrivateMessageThreadEffect.ImageSaveFailedStorage -> {
                    android.widget.Toast.makeText(
                        context,
                        R.string.messages_image_menu_save_failed_storage,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                PrivateMessageThreadEffect.ImageSaveFailedTooLarge -> {
                    android.widget.Toast.makeText(
                        context,
                        R.string.messages_image_menu_save_failed_too_large,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    PrivateMessageThreadContent(
        state = state,
        isMultiRecipientHint = isMultiRecipientHint,
        callbacks = PrivateMessageThreadCallbacks(
            onBack = onBack,
            onReply = { onReply(request.threadId, state.page) },
            onQuote = { message ->
                message.quoteRef?.let { ref ->
                    onQuote(
                        request.threadId,
                        state.page,
                        PrivateMessageQuote(numreponse = message.numreponse, ref = ref),
                    )
                }
            },
            onToggleMultiQuote = onToggleMultiQuote,
            onMultiQuote = { onMultiQuote(request.threadId, state.page) },
            onClearMultiQuote = onClearMultiQuote,
            onRemoveMultiQuotes = onRemoveMultiQuotes,
            onRetry = viewModel::retry,
            onRefresh = viewModel::refresh,
            onSelectPage = viewModel::selectPage,
            isPageWarm = viewModel::isPageWarm,
            onGoToCitedPost = viewModel::goToCitedMessage,
            onAnchorSettled = viewModel::reportPageAnchor,
            onPageLandingConsumed = viewModel::acknowledgePageLanding,
            onOpenRoster = viewModel::openRoster,
            onDismissRoster = viewModel::dismissRoster,
            onRetryRoster = viewModel::retryRoster,
            onManageRecipients = {
                viewModel.dismissRoster()
                onManageRecipients(request.threadId, state.page)
            },
            onOpenProfile = onOpenProfile,
            onSetAuthorBlocked = viewModel::setAuthorBlocked,
            onSaveImage = viewModel::saveImage,
        ),
        presentation = PrivateMessageThreadPresentation(
            multiQuoteSelections = multiQuoteSelections,
        ),
        topBarActions = topBarActions,
    )
}

/**
 * ADR-013 foreground gate. The ViewModel may outlive this composition in the navigation back stack,
 * so authenticated prefetch is enabled only while the owning entry is composed and RESUMED.
 */
@Composable
internal fun PrivateMessageThreadPrefetchLifecycleGate(onActiveChanged: (Boolean) -> Unit) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val currentOnActiveChanged by rememberUpdatedState(onActiveChanged)
    val active = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    DisposableEffect(active) {
        if (active) currentOnActiveChanged(true)
        onDispose {
            if (active) currentOnActiveChanged(false)
        }
    }
}

/** Cache content is display-only; navigation/badge callbacks belong to the network revalidation. */
internal fun PrivateMessageThreadUiState.Mode.networkLoadedThreadOrNull() =
    (this as? PrivateMessageThreadUiState.Mode.Content)
        ?.takeIf { it.source == PrivateMessageThreadPage.Source.NETWORK }
        ?.thread

/**
 * Host-owned transient presentation inputs for [PrivateMessageThreadContent]. The multi-quote
 * selection snapshot shares the screen composition's lifetime; user actions remain in callbacks.
 */
internal data class PrivateMessageThreadPresentation(
    val multiQuoteSelections: List<QuoteSelection> = emptyList(),
)

/** Landing value + its compare-and-clear callback travel as one screen-owned protocol. */
internal data class PrivateMessageLandingPresentation(
    val landing: PrivateMessagePageLanding? = null,
    val onConsumed: (PrivateMessagePageLanding) -> Unit = {},
)

private sealed interface PrivateMessageLandingAction {
    data object None : PrivateMessageLandingAction
    data object Top : PrivateMessageLandingAction
    data class Anchor(val value: PrivateMessageScrollAnchor) : PrivateMessageLandingAction
    data class CitedMessage(val index: Int) : PrivateMessageLandingAction
}

private data class PrivateMessageLandingDecision(
    val action: PrivateMessageLandingAction,
    val acknowledge: Boolean = false,
    val alignWithoutScroll: Boolean = false,
)

/** Data-only snapshot consumed by the pure landing arbitration. */
private data class PrivateMessageLandingDecisionInputs(
    val landing: PrivateMessagePageLanding?,
    val connectedPseudo: String?,
    val renderedPage: Int,
    val citedTargetIndex: Int?,
    val isRefreshing: Boolean,
    val lastRenderedPage: Int?,
)

/** Pure landing arbitration; kept outside Compose to bound the effect's complexity. */
private fun decidePrivateMessageLanding(
    inputs: PrivateMessageLandingDecisionInputs,
): PrivateMessageLandingDecision {
    val landing = inputs.landing
    val citedTargetIndex = inputs.citedTargetIndex
    if (landing == null) {
        return when {
            inputs.lastRenderedPage == null -> PrivateMessageLandingDecision(
                action = PrivateMessageLandingAction.None,
                alignWithoutScroll = true,
            )
            inputs.lastRenderedPage != inputs.renderedPage -> PrivateMessageLandingDecision(
                action = PrivateMessageLandingAction.Top,
            )
            else -> PrivateMessageLandingDecision(PrivateMessageLandingAction.None)
        }
    }
    val landingOwnedHere =
        landing.account == inputs.connectedPseudo && landing.page == inputs.renderedPage
    return when {
        !landingOwnedHere -> PrivateMessageLandingDecision(
            action = PrivateMessageLandingAction.None,
            acknowledge = true,
        )
        landing is PrivateMessagePageLanding.CitedMessage && citedTargetIndex != null ->
            PrivateMessageLandingDecision(
                action = PrivateMessageLandingAction.CitedMessage(citedTargetIndex),
                acknowledge = true,
            )
        landing is PrivateMessagePageLanding.CitedMessage && inputs.isRefreshing ->
            PrivateMessageLandingDecision(PrivateMessageLandingAction.None)
        landing is PrivateMessagePageLanding.CitedMessage -> PrivateMessageLandingDecision(
            action = PrivateMessageLandingAction.Top,
            acknowledge = true,
        )
        landing is PrivateMessagePageLanding.Anchor -> PrivateMessageLandingDecision(
            action = PrivateMessageLandingAction.Anchor(landing.anchor),
            acknowledge = true,
        )
        else -> PrivateMessageLandingDecision(
            action = PrivateMessageLandingAction.Top,
            acknowledge = true,
        )
    }
}

/** Compose-owned objects and rendered values needed to apply one landing decision. */
internal data class PrivateMessageLandingRenderContext(
    val listState: LazyListState,
    val thread: PrivateMessageThread,
    val connectedPseudo: String?,
    val isRefreshing: Boolean,
    val alignment: PrivateMessageListAlignment,
)

/**
 * #351/#1074/#1040 — unique MP landing authority. The ViewModel resolves one candidate per page:
 * cited message (explicit, highest priority), saved session anchor, or default top. Cache content
 * and that candidate arrive atomically; the network revalidation retains the same value, so an
 * anchor/top landing is never restarted. A cited target missing from cache waits while refresh is
 * active, then resolves against the terminal page (top fallback only for an actual missing target).
 *
 * [PrivateMessageListAlignment.onLandingApplied] runs only AFTER the scroll, closing the content /
 * position mismatch window. The acknowledgement is compare-and-clear in the ViewModel; a stale
 * completion may acknowledge only its exact generation/account/page value.
 */
@Composable
internal fun PrivateMessagePageLandingEffect(
    context: PrivateMessageLandingRenderContext,
    presentation: PrivateMessageLandingPresentation,
) {
    val lastRenderedPage = remember { mutableStateOf<Int?>(null) }
    val currentOnConsumed by rememberUpdatedState(presentation.onConsumed)
    val landing = presentation.landing
    val renderedPage = context.thread.page
    val citedTargetIndex = (landing as? PrivateMessagePageLanding.CitedMessage)?.let { cited ->
        context.thread.messages.indexOfFirst { message -> message.numreponse == cited.numreponse }
            .takeIf { index -> index >= 0 }
    }
    // The decision, rather than raw refresh/messages, keys the effect. Anchor/top decisions remain
    // equal across cache→network; a cited target absent from cache changes exactly once at terminal.
    val decision = decidePrivateMessageLanding(
        PrivateMessageLandingDecisionInputs(
            landing = landing,
            connectedPseudo = context.connectedPseudo,
            renderedPage = renderedPage,
            citedTargetIndex = citedTargetIndex,
            isRefreshing = context.isRefreshing,
            lastRenderedPage = lastRenderedPage.value,
        ),
    )
    LaunchedEffect(renderedPage, landing, decision) {
        val scrolled = when (val action = decision.action) {
            is PrivateMessageLandingAction.Anchor -> {
                context.listState.scrollToItem(action.value.index, action.value.offset)
                true
            }
            is PrivateMessageLandingAction.CitedMessage -> {
                context.listState.scrollToItem(action.index)
                true
            }
            PrivateMessageLandingAction.Top -> {
                context.listState.scrollToItem(0)
                true
            }
            PrivateMessageLandingAction.None -> false
        }

        if (scrolled || decision.alignWithoutScroll) {
            context.alignment.onLandingApplied(renderedPage)
            lastRenderedPage.value = renderedPage
        }
        if (landing != null && decision.acknowledge) {
            currentOnConsumed(landing)
        }
    }
}

/**
 * State-hoisted callbacks for [PrivateMessageThreadContent]. Keeping this seam free of Hilt makes
 * the complete thread surface characterizable in JVM Compose tests while the public screen remains
 * the sole owner of the ViewModel and route arguments.
 */
internal typealias CitedPostWithDepartureAnchor = (
    page: Int,
    numreponse: Int,
    departureAnchor: PrivateMessageScrollAnchor?,
) -> Unit

@Suppress("LongParameterList") // One state-hoisted action per independent control on the complete surface.
internal data class PrivateMessageThreadCallbacks(
    val onBack: () -> Unit,
    val onReply: () -> Unit,
    val onRetry: () -> Unit,
    val onRefresh: () -> Unit,
    val onSelectPage: (page: Int, departureAnchor: PrivateMessageScrollAnchor?) -> Unit,
    /** Generation/account-sealed RAM availability probe used only to choose the swipe release. */
    val isPageWarm: (page: Int) -> Boolean = { false },
    val onOpenRoster: () -> Unit,
    val onDismissRoster: () -> Unit,
    val onRetryRoster: () -> Unit,
    val onManageRecipients: () -> Unit,
    /** Parsed quote-header target; production routes it to the page-scoped ViewModel landing. */
    val onGoToCitedPost: CitedPostWithDepartureAnchor? = null,
    /** Scroll-settle report; the content host applies alignment/scrollbar/zoom guards first. */
    val onAnchorSettled: (PrivateMessageScrollAnchor) -> Unit = {},
    /** Exact page landing applied; the ViewModel performs the compare-and-clear. */
    val onPageLandingConsumed: (PrivateMessagePageLanding) -> Unit = {},
    /** Null only in isolated hosts; production supplies it and the list applies reply/ref gates. */
    val onQuote: ((Post) -> Unit)? = null,
    val onToggleMultiQuote: (QuoteSelection) -> Unit = {},
    val onMultiQuote: () -> Unit = {},
    val onClearMultiQuote: () -> Unit = {},
    val onRemoveMultiQuotes: (Set<Int>) -> Unit = {},
    // #1042 — defaulted (unlike its siblings) so the pre-#1042 characterization mounts compile
    // unchanged; a host that does not navigate keeps the tap a no-op, like the topic screen default.
    val onOpenProfile: (userId: Int, pseudo: String, avatarUrl: String?) -> Unit = { _, _, _ -> },
    // #1051 — block/unblock is repository-owned; the live blacklist collector re-renders locally.
    val onSetAuthorBlocked: (author: String, blocked: Boolean) -> Unit = { _, _ -> },
    // #831/#1051 — the ViewModel-owned save survives dismissal of the local image sheet.
    val onSaveImage: (url: String) -> Unit = {},
)

/**
 * Complete private-thread surface driven by immutable UI state. This is the MP counterpart of the
 * topic feature's state-hoisted `TopicContent`: screen chrome, message list, pager, auth/error modes
 * and participant sheet stay in one composition; only ViewModel collection/effects live outside.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivateMessageThreadContent(
    state: PrivateMessageThreadUiState,
    isMultiRecipientHint: Boolean,
    callbacks: PrivateMessageThreadCallbacks,
    presentation: PrivateMessageThreadPresentation = PrivateMessageThreadPresentation(),
    topBarActions: @Composable (() -> Unit)? = null,
) {
    val mode = state.mode
    val multiQuoteSelections = presentation.multiQuoteSelections
    val listState = rememberLazyListState()
    // Account is part of the alignment owner even when the numeric page stays identical across an
    // A -> B switch. Recreating the lock prevents B's first Content frame from accepting A's old
    // list coordinates before B's Top landing runs.
    val alignment = remember(state.connectedPseudo) { PrivateMessageListAlignment() }
    var isScrollbarDragging by remember { mutableStateOf(false) }
    // #1051 — private message whose feature-owned menu is open. Kept local like the image menu:
    // ephemeral UI state contains no route/private-data persistence across process death.
    var messageMenuTarget by remember { mutableStateOf<Post?>(null) }
    // #831/#1051 — post image whose contextual menu is open (null = closed). Deliberately not
    // rememberSaveable: losing an ephemeral menu across process death is acceptable.
    var imageMenuTarget by remember { mutableStateOf<PostImageTarget?>(null) }
    // One stable handler instance is threaded through MessageCard to LocalPostImageActions;
    // remembered so providing it never invalidates the cards. Opening either menu closes the other.
    val postImageActions = remember {
        PostImageActions(
            onLongPress = {
                messageMenuTarget = null
                imageMenuTarget = it
            },
        )
    }
    val openMessageMenu = remember<(Post) -> Unit> {
        { message ->
            imageMenuTarget = null
            messageMenuTarget = message
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val content = mode as? PrivateMessageThreadUiState.Mode.Content
                    Column {
                        Text(
                            text = content?.thread?.subject
                                ?.ifBlank { stringResource(R.string.messages_thread_title) }
                                ?: stringResource(R.string.messages_thread_title),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Multi-recipient if the page proved it OR the inbox hinted it; then show
                        // the localized "Interlocuteurs multiples" label rather than a single
                        // derived participant (which would misrepresent a group conversation).
                        val isMulti = content?.thread?.isMultiRecipient == true || isMultiRecipientHint
                        val subtitle = when {
                            isMulti -> stringResource(R.string.messages_multi_recipient)
                            else -> content?.thread?.correspondent?.takeIf { it.isNotBlank() }
                        }
                        subtitle?.let { subtitleText ->
                            Text(
                                text = subtitleText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    val backLabel = stringResource(R.string.messages_back)
                    IconButton(
                        onClick = callbacks.onBack,
                        modifier = Modifier.semantics { contentDescription = backLabel },
                    ) {
                        // #360 / ADR-015 — vector stroke unifié, dimensionné en dp (indépendant de la
                        // police et de la baseline, contrairement au glyphe « ← »), via le primitive
                        // partagé :core:ui. a11y label sur l'IconButton ; l'icône est décorative.
                        RedfaceVectorIcon(
                            resId = fr.forumhfr.redface2.core.ui.R.drawable.ic_arrow_back,
                        )
                    }
                },
                actions = {
                    // #612 — « Participants » action + the caller's slot. Extracted to keep the
                    // screen host under detekt's cyclomatic-complexity threshold.
                    ThreadTopBarActions(
                        isMultiRecipient = (mode as? PrivateMessageThreadUiState.Mode.Content)
                            ?.thread?.isMultiRecipient == true || isMultiRecipientHint,
                        onOpenRoster = callbacks.onOpenRoster,
                        topBarActions = topBarActions,
                    )
                },
            )
        },
        floatingActionButton = {
            val canReply = (mode as? PrivateMessageThreadUiState.Mode.Content)?.thread?.canReply == true
            ThreadBottomActions(
                canReply = canReply,
                multiQuoteCount = if (canReply) multiQuoteSelections.size else 0,
                onReply = callbacks.onReply,
                onMultiQuote = callbacks.onMultiQuote,
                onClearMultiQuote = callbacks.onClearMultiQuote,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when (mode) {
                PrivateMessageThreadUiState.Mode.RequiresLogin -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Text(
                        text = stringResource(R.string.messages_login_required),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.messages_login_required_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                PrivateMessageThreadUiState.Mode.Loading -> CircularProgressIndicator()

                is PrivateMessageThreadUiState.Mode.Error -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Text(
                        // #324 — the kind is a type-derived closed enum (safe per #316);
                        // ServerDown / Network render the shared :core:ui label, Other keeps
                        // the generic message.
                        text = stringResource(
                            mode.kind.sharedLabelResOrNull() ?: R.string.messages_thread_error_load,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    // No raw error detail on screen (#316): it can embed the private conversation
                    // URL. Kind-resolved message + retry only.
                    Button(onClick = callbacks.onRetry) {
                        Text(text = stringResource(R.string.messages_retry))
                    }
                }

                is PrivateMessageThreadUiState.Mode.Content -> PrivateMessageThreadReader(
                    state = state,
                    mode = mode,
                    callbacks = callbacks,
                    session = PrivateMessageReaderSession(
                        listState = listState,
                        alignment = alignment,
                        isScrollbarDragging = { isScrollbarDragging },
                        onScrollbarDragStateChanged = { dragging ->
                            isScrollbarDragging = dragging
                        },
                    ),
                    presentation = PrivateMessageReaderPresentation(
                        multiQuoteSelections = multiQuoteSelections,
                        onOpenMessageMenu = openMessageMenu,
                        onImageLongPress = postImageActions.onLongPress,
                    ),
                )
            }
        }
    }

    // #612 — participant roster sheet. Renders nothing while Hidden; the ViewModel drives the
    // open / loading / loaded / unavailable / error states (lazy fetch + memory cache).
    ParticipantRosterSheet(
        roster = state.roster,
        onDismiss = callbacks.onDismissRoster,
        onRetry = callbacks.onRetryRoster,
        // #618 — close the roster sheet BEFORE navigating (Codex framing) so it does not reappear when
        // the composer pops back, then open the composer with the recipient manager auto-opened.
        onManageRecipients = callbacks.onManageRecipients,
    )

    ThreadMessageMenuHost(
        mode = mode,
        connectedPseudo = state.connectedPseudo,
        target = messageMenuTarget,
        onOpenProfile = callbacks.onOpenProfile,
        multiQuoteSelections = multiQuoteSelections,
        onToggleMultiQuote = callbacks.onToggleMultiQuote,
        onSetAuthorBlocked = callbacks.onSetAuthorBlocked,
        onClear = { messageMenuTarget = null },
    )

    ThreadImageMenuHost(
        mode = mode,
        target = imageMenuTarget,
        onSave = callbacks.onSaveImage,
        onClear = { imageMenuTarget = null },
    )
}

/**
 * Long-lived list coordination retained by [PrivateMessageThreadContent] across its UI modes.
 * Module visibility lets the production gate be tested without a shadow test-only session DTO.
 */
internal data class PrivateMessageReaderSession(
    val listState: LazyListState,
    val alignment: PrivateMessageListAlignment,
    val isScrollbarDragging: () -> Boolean,
    val onScrollbarDragStateChanged: (Boolean) -> Unit,
)

/** Reader-only presentation inputs owned locally by [PrivateMessageThreadContent]. */
private data class PrivateMessageReaderPresentation(
    val multiQuoteSelections: List<QuoteSelection>,
    val onOpenMessageMenu: (Post) -> Unit,
    val onImageLongPress: (PostImageTarget) -> Unit,
)

/** Loaded reading mode: landing, anchor persistence, zoom, refresh and message-list rendering. */
@Composable
private fun PrivateMessageThreadReader(
    state: PrivateMessageThreadUiState,
    mode: PrivateMessageThreadUiState.Mode.Content,
    callbacks: PrivateMessageThreadCallbacks,
    session: PrivateMessageReaderSession,
    presentation: PrivateMessageReaderPresentation,
) {
    val latestState by rememberUpdatedState(state)
    // #1040 lot 6 — the feature owns the full MP route key. An in-place page landing or a different
    // conversation starts at 1×; the shared state remains ephemeral across process recreation, like
    // the topic reader.
    val zoomAnimationScope = rememberCoroutineScope()
    val zoomState = rememberPinchZoomState(
        pageKey = mode.thread.threadId to mode.thread.page,
        animationScope = zoomAnimationScope,
    )
    val isZoomed by remember(zoomState) { derivedStateOf { zoomState.zoomed } }
    val currentAnchor = {
        PrivateMessageScrollAnchor(
            index = session.listState.firstVisibleItemIndex,
            offset = session.listState.firstVisibleItemScrollOffset,
        )
    }
    // Tap-time departure coordinates are accepted only while content and position agree, and while
    // neither programmatic producer owns the list.
    val alignedDepartureAnchor = {
        val current = latestState
        currentAnchor().takeIf {
            shouldPersistPrivateMessageAnchor(
                alignment = session.alignment,
                canonicalPage = current.page,
                isLoaded = current.mode is PrivateMessageThreadUiState.Mode.Content,
                isScrollbarDragging = session.isScrollbarDragging(),
                isZoomPositionMutationInProgress = zoomState.isListPositionMutationInProgress,
            )
        }
    }
    val swipeInteraction = ThreadSwipeInteraction(
        onSelectPage = { page ->
            callbacks.onSelectPage(page, alignedDepartureAnchor())
        },
        isTargetPageWarm = callbacks.isPageWarm,
        hasCompetingListProducer = {
            hasCompetingThreadListProducer(latestState, session, zoomState)
        },
    )
    // #1074/#1040 — cited > saved anchor > top, one scroll authority. The effect closes the
    // alignment window only after the selected scroll completes.
    PrivateMessagePageLandingEffect(
        context = PrivateMessageLandingRenderContext(
            listState = session.listState,
            thread = mode.thread,
            connectedPseudo = state.connectedPseudo,
            isRefreshing = state.isRefreshing,
            alignment = session.alignment,
        ),
        presentation = PrivateMessageLandingPresentation(
            landing = state.pageLanding,
            onConsumed = callbacks.onPageLandingConsumed,
        ),
    )
    // Same shape as TopicScreen: observe the STOP transition, not every index/offset mutation. The
    // initial idle value is skipped so a pending restore cannot be clobbered by (0, 0). Scrollbar drag
    // and zoom gesture/settle are producers, not reading intent, and remain excluded through their
    // final programmatic scroll.
    LaunchedEffect(session.listState, zoomState) {
        snapshotFlow { session.listState.isScrollInProgress }
            .drop(1)
            .filter { scrolling -> !scrolling }
            .collect {
                val current = latestState
                val canPersist = shouldPersistPrivateMessageAnchor(
                    alignment = session.alignment,
                    canonicalPage = current.page,
                    isLoaded = current.mode is PrivateMessageThreadUiState.Mode.Content,
                    isScrollbarDragging = session.isScrollbarDragging(),
                    isZoomPositionMutationInProgress = zoomState.isListPositionMutationInProgress,
                )
                if (canPersist) callbacks.onAnchorSettled(currentAnchor())
            }
    }
    // #335/#351/#1040 — PullToRefreshBox cannot disable its gesture. The low-level modifier prevents
    // nested-scroll pull consumption and indicator arming while zoomed; gating only
    // callbacks.onRefresh would be too late.
    val pullToRefreshState = rememberPullToRefreshState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(PRIVATE_MESSAGE_THREAD_READER_TAG)
            .pullToRefresh(
                isRefreshing = state.isRefreshing,
                state = pullToRefreshState,
                enabled = !isZoomed,
                onRefresh = callbacks.onRefresh,
            ),
    ) {
        // #300/#351c — the list overlay (LazyColumn + auto-hiding scrollbar) is now the shared
        // PostListScaffold; the swipe chain rides the inner list via listModifier (so the scrollbar
        // stays fixed while the page follows the finger) and the MP list chrome is the feature-owned
        // ThreadListLayout.kt geometry (#1046).
        // #785/#1050 — the same snapshot that selects hidden message placeholders also reaches
        // PostRenderer, so a third party quoting a blocked author cannot leak that author's body.
        // Scoped to the reading list only.
        CompositionLocalProvider(
            LocalBlockedQuoteAuthors provides mode.blockedQuoteAuthors,
        ) {
            ThreadMessages(
                messages = mode.thread.messages,
                hiddenNumreponses = mode.hiddenNumreponses,
                blockedQuoteAuthors = mode.blockedQuoteAuthors,
                page = state.page,
                totalPages = state.totalPages,
                fullWidthPosts = state.fullWidthPosts,
                showSignatures = state.showSignatures,
                egoQuoteEnabled = state.egoQuoteEnabled,
                egoPostEnabled = state.egoPostEnabled,
                connectedPseudo = state.connectedPseudo,
                canReply = mode.thread.canReply,
                multiQuoteSelections = presentation.multiQuoteSelections,
                onSelectPage = { page ->
                    callbacks.onSelectPage(page, alignedDepartureAnchor())
                },
                onQuote = callbacks.onQuote,
                onRemoveMultiQuotes = callbacks.onRemoveMultiQuotes,
                onGoToCitedPost = callbacks.onGoToCitedPost?.let { onGoToCitedPost ->
                    { page, numreponse ->
                        onGoToCitedPost(page, numreponse, alignedDepartureAnchor())
                    }
                },
                onOpenProfile = callbacks.onOpenProfile,
                onOpenMessageMenu = presentation.onOpenMessageMenu,
                onImageLongPress = presentation.onImageLongPress,
                // Codex review — gate the pager buttons with the same condition as the swipe:
                // re-tapping during a keep-content load would only cancel and restart the round-trip
                // (supersede), never advance faster.
                pagerEnabled = !state.isRefreshing,
                scrollSession = ThreadScrollSession(
                    listState = session.listState,
                    zoomState = zoomState,
                    onScrollbarDragStateChanged = session.onScrollbarDragStateChanged,
                    // #351b — horizontal swipe changes page in place (same thresholds and feel as
                    // topic via the shared geometry).
                    swipeModifier = rememberThreadSwipeModifier(
                        renderedPage = mode.thread.page,
                        totalPages = state.totalPages,
                        isRefreshing = state.isRefreshing,
                        interaction = swipeInteraction,
                    ),
                ),
            )
        }
        PullToRefreshDefaults.Indicator(
            state = pullToRefreshState,
            isRefreshing = state.isRefreshing,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        if (isZoomed) {
            ThreadZoomResetChip(
                zoomState = zoomState,
                listState = session.listState,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            )
        }
    }
}

/**
 * Live page-position producers that must settle before a swipe may capture an anchor and commit.
 * A pending same-page cited landing is explicit because its numeric page remains aligned until its
 * programmatic scroll starts; [PrivateMessageListAlignment] covers the cross-page content/position
 * mismatch window. Module visibility keeps the regression proof on this exact predicate.
 */
internal fun hasCompetingThreadListProducer(
    state: PrivateMessageThreadUiState,
    session: PrivateMessageReaderSession,
    zoomState: PinchZoomState,
): Boolean = when {
    zoomState.zoomed -> true
    zoomState.isListPositionMutationInProgress -> true
    session.isScrollbarDragging() -> true
    session.listState.isScrollInProgress -> true
    state.pageLanding != null -> true
    else -> !session.alignment.shouldPersist(
        canonicalPage = state.page,
        isLoaded = state.mode is PrivateMessageThreadUiState.Mode.Content,
    )
}

/** Feature-owned reset chrome; the shared zoom API deliberately contains no labels or surfaces. */
@Composable
private fun ThreadZoomResetChip(
    zoomState: PinchZoomState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val zoomResetDescription = stringResource(R.string.messages_zoom_reset)
    Surface(
        onClick = {
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
        modifier = modifier
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
                text = stringResource(R.string.messages_zoom_reset_chip),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * #1051 — owns the feature-local message sheet and forgets private targets on logout/account switch
 * or when a page landing replaces the target message. The current blacklist snapshot drives both
 * the label and the desired write, so a revealed blocked message offers « Ne plus masquer » without
 * asking the repository a second time.
 */
@Composable
@Suppress("LongParameterList") // Host seam: mode/session/target plus three independent callbacks.
private fun ThreadMessageMenuHost(
    mode: PrivateMessageThreadUiState.Mode,
    connectedPseudo: String?,
    target: Post?,
    onOpenProfile: (userId: Int, pseudo: String, avatarUrl: String?) -> Unit,
    multiQuoteSelections: List<QuoteSelection>,
    onToggleMultiQuote: (QuoteSelection) -> Unit,
    onSetAuthorBlocked: (author: String, blocked: Boolean) -> Unit,
    onClear: () -> Unit,
) {
    val targetStillOnPage = mode is PrivateMessageThreadUiState.Mode.Content &&
        target != null &&
        mode.thread.messages.any { message -> message.numreponse == target.numreponse }

    LaunchedEffect(mode, target) {
        if (!targetStillOnPage) onClear()
    }

    if (mode is PrivateMessageThreadUiState.Mode.Content && targetStillOnPage) {
        target.let { message ->
            val authorCanonical = remember(message.author) { canonicalizePseudo(message.author) }
            val connectedCanonical = remember(connectedPseudo) {
                connectedPseudo?.let(::canonicalizePseudo)
            }
            val authorBlocked = authorCanonical in mode.blockedQuoteAuthors
            val quoteSelection = message.toPrivateMessageQuoteSelectionOrNull(mode.thread.page)
            MessageMenuSheet(
                message = message,
                authorBlocked = authorBlocked,
                onDismiss = onClear,
                onOpenProfile = message.profileId?.let { profileId ->
                    { onOpenProfile(profileId, message.author, message.avatarUrl) }
                },
                // Same self-block gate as the topic, based on the live session pseudo rather than
                // Post.isOwnPost (HFR can omit ownership tools for affichoutils=0 profiles).
                onToggleBlockAuthor = if (
                    connectedCanonical != null && authorCanonical == connectedCanonical
                ) {
                    null
                } else {
                    { onSetAuthorBlocked(message.author, !authorBlocked) }
                },
                multiQuoteSelected = multiQuoteSelections.any { selection ->
                    selection.numreponse == message.numreponse
                },
                // A blocked-but-explicitly-revealed message remains outside the basket until its
                // author is unblocked; otherwise changing page would silently re-hide a selected MP.
                onToggleMultiQuote = quoteSelection
                    ?.takeIf { mode.thread.canReply && !authorBlocked }
                    ?.let { selection -> { onToggleMultiQuote(selection) } },
            )
        }
    }
}

/**
 * #831/#1051 — per-image contextual menu, opened from either renderer image path. The thread screen
 * remains mounted across auth modes, so a private target is cleared as soon as content leaves the
 * current frame. Extracted to keep [PrivateMessageThreadContent] under detekt's complexity threshold.
 */
@Composable
private fun ThreadImageMenuHost(
    mode: PrivateMessageThreadUiState.Mode,
    target: PostImageTarget?,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    // Unlike TopicLoadedContent, this host must explicitly forget the private target when its content
    // branch disappears. This also prevents the sheet from reopening when the user logs back in.
    LaunchedEffect(mode) {
        if (mode !is PrivateMessageThreadUiState.Mode.Content) onClear()
    }

    // The mode gate closes the sheet in the current frame; the effect above then purges its target.
    if (mode is PrivateMessageThreadUiState.Mode.Content) {
        target?.let { imageTarget ->
            PostImageMenuSheet(
                target = imageTarget,
                onSave = onSave,
                onDismiss = onClear,
                // The thumbnail is a second request for the same private PostContent URL and
                // lives outside ReadingPostCard's provider, so carry the policy explicitly.
                mediaDiskCachePolicy = PostMediaDiskCachePolicy.DISABLED,
            )
        }
    }
}

/**
 * #612 — the conversation top-bar actions: a « Participants » button (shown only for a
 * multi-recipient conversation — a one-to-one MP has no roster) followed by the caller's optional
 * slot. The owner-vs-participant resolution happens lazily when the sheet opens (the roster fetch),
 * so the button is offered to every member of a DT; the sheet then shows the full list (owner) or a
 * « non disponible » note (participant). Extracted from the screen host to keep it under detekt's
 * cyclomatic-complexity threshold.
 */
@Composable
private fun ThreadTopBarActions(
    isMultiRecipient: Boolean,
    onOpenRoster: () -> Unit,
    topBarActions: @Composable (() -> Unit)?,
) {
    if (isMultiRecipient) {
        val rosterLabel = stringResource(R.string.messages_roster_action)
        IconButton(
            onClick = onOpenRoster,
            modifier = Modifier.semantics { contentDescription = rosterLabel },
        ) {
            RedfaceVectorIcon(resId = fr.forumhfr.redface2.core.ui.R.drawable.ic_ms_group)
        }
    }
    topBarActions?.invoke()
}

/** Bottom write cluster: basket entry first, then the stable reply affordance. */
@Composable
private fun ThreadBottomActions(
    canReply: Boolean,
    multiQuoteCount: Int,
    onReply: () -> Unit,
    onMultiQuote: () -> Unit,
    onClearMultiQuote: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multiQuoteCount > 0) {
            MessageMultiQuoteFab(
                count = multiQuoteCount,
                onClick = onMultiQuote,
                onClear = onClearMultiQuote,
            )
        }
        ThreadReplyFab(canReply = canReply, onReply = onReply)
    }
}

/**
 * #1074 — visible « Citer N » entry to the MP editor. A long press mirrors the topic affordance and
 * clears the complete scoped basket without navigating; the semantics exposes both gestures.
 */
@Composable
internal fun MessageMultiQuoteFab(count: Int, onClick: () -> Unit, onClear: () -> Unit) {
    val label = pluralStringResource(R.plurals.messages_multi_quote_count, count, count)
    val shortLabel = stringResource(R.string.messages_multi_quote_short, count)
    val clearLabel = stringResource(R.string.messages_multi_quote_clear)
    Surface(
        modifier = Modifier
            .semantics { contentDescription = label }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onClear,
                onLongClickLabel = clearLabel,
                role = Role.Button,
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
    ) {
        Box(
            modifier = Modifier
                .sizeIn(minHeight = 56.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = shortLabel, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * #301 — reply FAB, extracted from the screen host (with [ThreadTopBarActions]) to keep it under
 * detekt's cyclomatic-complexity threshold. Shown only once the page proved a writable reply form
 * ([canReply]); [onReply] carries the page the user is viewing so HFR's prefilled `numrep` matches
 * what is on screen.
 */
@Composable
private fun ThreadReplyFab(canReply: Boolean, onReply: () -> Unit) {
    if (!canReply) return
    val replyLabel = stringResource(R.string.messages_reply)
    ExtendedFloatingActionButton(
        text = { Text(replyLabel) },
        // #360 / ADR-015 — crayon en vector stroke unifié via le primitive :core:ui, à la place du
        // glyphe « ✎ » (poids optique aligné sur la flèche retour / les chevrons). Pas de Material
        // icons (detekt ForbiddenImport).
        icon = { RedfaceVectorIcon(resId = fr.forumhfr.redface2.core.ui.R.drawable.ic_edit) },
        onClick = onReply,
    )
}

/**
 * #351b — builds the swipe modifier chain for the message list: shared edge glow
 * ([pageSwipeEdgeHint]) + in-place page-change gesture ([threadPageSwipe]).
 *
 * The pointer block is re-keyed by rendered page and refresh state: the page re-arms a successful
 * warm transition, while `isRefreshing=true→false` re-arms a cold failure whose rendered page never
 * changed. Counts, callbacks, cache warmth, competing producers and gesture insets stay live through
 * [rememberUpdatedState] without making a page-count/inset change cancel an in-flight release.
 */
internal data class ThreadSwipeInteraction(
    val onSelectPage: (Int) -> Unit,
    val isTargetPageWarm: (Int) -> Boolean = { false },
    val hasCompetingListProducer: () -> Boolean = { false },
)

@Composable
internal fun rememberThreadSwipeModifier(
    renderedPage: Int,
    totalPages: Int,
    isRefreshing: Boolean,
    interaction: ThreadSwipeInteraction,
): Modifier {
    val dragOffset = remember { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current
    val currentTotal = rememberUpdatedState(totalPages)
    val currentRefreshing = rememberUpdatedState(isRefreshing)
    val currentInteraction = rememberUpdatedState(interaction)
    val gestureDensity = rememberUpdatedState(LocalDensity.current)
    val gestureLayoutDirection = rememberUpdatedState(LocalLayoutDirection.current)
    val systemGestureInsets = rememberUpdatedState(WindowInsets.systemGestures)
    val swipeEnabled: () -> Boolean = {
        !currentRefreshing.value && !currentInteraction.value.hasCompetingListProducer()
    }
    // The release owner already parks zero after handing off a warm selection. Repeat the reset on
    // both pointer-input keys as a composition-level belt: a page emission, a failed load or an
    // external refresh that cancels a release can never leave retained content translated.
    LaunchedEffect(renderedPage, isRefreshing) {
        dragOffset.floatValue = 0f
    }
    // Same desaturated accent as the topic edge glow (#282): mostly neutral with a touch of primary.
    val accent = lerp(
        MaterialTheme.colorScheme.onSurfaceVariant,
        MaterialTheme.colorScheme.primary,
        ACCENT_PRIMARY_BLEND,
    )
    val handlers = remember(haptics) {
        ThreadSwipeHandlers(
            haptics = haptics,
            onSelectPage = { page -> currentInteraction.value.onSelectPage(page) },
            enabled = swipeEnabled,
            isTargetPageWarm = { page -> currentInteraction.value.isTargetPageWarm(page) },
            leftGestureInsetPx = {
                systemGestureInsets.value
                    .getLeft(gestureDensity.value, gestureLayoutDirection.value)
            },
            rightGestureInsetPx = {
                systemGestureInsets.value
                    .getRight(gestureDensity.value, gestureLayoutDirection.value)
            },
        )
    }
    return Modifier
        .pageSwipeEdgeHint(
            currentPage = renderedPage,
            totalPages = { currentTotal.value },
            dragOffset = dragOffset,
            accent = accent,
            enabled = swipeEnabled,
        )
        .threadPageSwipe(
            currentPage = renderedPage,
            totalPages = { currentTotal.value },
            isRefreshing = isRefreshing,
            dragOffset = dragOffset,
            handlers = handlers,
        )
}

/** Scroll-position producers grouped so the already-large list host keeps one coherent input. */
private data class ThreadScrollSession(
    val listState: LazyListState,
    val zoomState: PinchZoomState,
    val swipeModifier: Modifier,
    val onScrollbarDragStateChanged: (Boolean) -> Unit,
)

@Composable
@Suppress("LongParameterList") // List host: message presentation/actions remain independent inputs.
private fun ThreadMessages(
    messages: List<Post>,
    hiddenNumreponses: Set<Int>,
    blockedQuoteAuthors: Set<String>,
    page: Int,
    totalPages: Int,
    fullWidthPosts: Boolean,
    showSignatures: Boolean,
    egoQuoteEnabled: Boolean,
    egoPostEnabled: Boolean,
    connectedPseudo: String?,
    canReply: Boolean,
    multiQuoteSelections: List<QuoteSelection>,
    onSelectPage: (Int) -> Unit,
    onQuote: ((Post) -> Unit)?,
    onRemoveMultiQuotes: (Set<Int>) -> Unit,
    onGoToCitedPost: ((page: Int, numreponse: Int) -> Unit)?,
    onOpenProfile: (userId: Int, pseudo: String, avatarUrl: String?) -> Unit,
    onOpenMessageMenu: (Post) -> Unit,
    onImageLongPress: (PostImageTarget) -> Unit,
    pagerEnabled: Boolean = true,
    scrollSession: ThreadScrollSession,
) {
    // #509/#1050 — reveal is deliberately page-local and non-saveable. In-place pagination keeps
    // this composition alive, so keying on the landed page is what re-collapses every placeholder.
    var revealedHiddenMessages by remember(page) { mutableStateOf(emptySet<Int>()) }
    // #1074 — :feature:messages observes the live blacklist while :app owns the scoped basket.
    // Remove any selected message that becomes hidden, including after a settings-side change.
    // revealedHiddenMessages deliberately does not weaken this rule: « Afficher » is temporary,
    // while the basket could otherwise survive the next page change that hides the message again.
    PruneHiddenMultiQuotesEffect(
        hiddenNumreponses = hiddenNumreponses,
        blockedQuoteAuthors = blockedQuoteAuthors,
        selections = multiQuoteSelections,
        onRemoveMultiQuotes = onRemoveMultiQuotes,
    )
    // #1050 — the MP mirror of the topic list's #874 derivation: canonicalize the live session
    // pseudo ONCE for the rendered page, then match the page's message authors once while building
    // the set below. Lazy cards only consume the resulting pseudo/set. `Post.isOwnPost` is
    // deliberately not consulted (see core.domain.ego.isEgoPost): the session-bound author
    // comparison is the source of truth, in a 1:1 conversation as in a DT — the MP list renders
    // uniform cards with no positional alignment, so EgoPost is what tells your messages apart.
    // Both settings off and anonymous sessions collapse to the safe null/empty values.
    val egoCanonicalPseudo = remember(egoQuoteEnabled, egoPostEnabled, connectedPseudo) {
        deriveEgoCanonicalPseudo(
            enabled = egoQuoteEnabled || egoPostEnabled,
            isAuthenticated = connectedPseudo != null,
            connectedPseudo = connectedPseudo,
        )
    }
    val egoQuoteCanonicalPseudo = egoCanonicalPseudo.takeIf { egoQuoteEnabled }
    val egoPostNumreponses = remember(egoPostEnabled, egoCanonicalPseudo, messages) {
        if (!egoPostEnabled || egoCanonicalPseudo == null) {
            emptySet()
        } else {
            messages
                .asSequence()
                .filter { message -> isEgoPost(message, egoCanonicalPseudo) }
                .mapTo(mutableSetOf()) { message -> message.numreponse }
        }
    }
    // #351c/#1042 — the shared list overlay (LazyColumn + auto-hiding scrollbar). Card-INTERNAL
    // density now follows the reader's display-metrics preset through [ReadingPostCard] (#1042);
    // the LIST chrome below stays feature-owned in ThreadListLayout.kt: #1050 switches it only on
    // the global full-width preference, independently of the density preset. This is the same stance
    // as the topic, whose list gutters/insets live in TopicListLayout.kt; DisplayMetrics deliberately
    // scopes out absolute chrome dimensions.
    // The swipe chain (edge glow + gesture + graphicsLayer follow) goes on the inner LazyColumn via
    // [PostListScaffold.listModifier], like the topic's LazyColumn: the scrollbar overlay outside
    // stays fixed on screen. [LocalShowScrollbar] (#105) is honoured by the scaffold's scrollbar,
    // so the call leaves showScrollbar at its default.
    val zoomSuspendsScroll by remember(scrollSession.zoomState) {
        derivedStateOf { scrollSession.zoomState.zoomed }
    }
    PostListScaffold(
        listState = scrollSession.listState,
        userScrollEnabled = !zoomSuspendsScroll,
        contentPadding = threadListContentPadding(fullWidthPosts),
        verticalArrangement = threadListArrangement(fullWidthPosts),
        onScrollbarDragStateChanged = scrollSession.onScrollbarDragStateChanged,
        listModifier = Modifier
            .pinchZoom(scrollSession.zoomState, scrollSession.listState)
            .then(scrollSession.swipeModifier)
            .pinchZoomTransform(scrollSession.zoomState),
    ) {
        itemsIndexed(
            items = messages,
            key = { _, message -> message.numreponse },
        ) { index, message ->
            if (isHiddenMessage(message, hiddenNumreponses, revealedHiddenMessages)) {
                HiddenPostCard(
                    author = message.author,
                    onReveal = {
                        revealedHiddenMessages = revealedHiddenMessages + message.numreponse
                    },
                    modifier = Modifier.threadIslandPadding(fullWidthPosts),
                )
            } else {
                val nextMessage = messages.getOrNull(index + 1)
                val hasFollowingVisibleMessage = nextMessage != null && !isHiddenMessage(
                    nextMessage,
                    hiddenNumreponses,
                    revealedHiddenMessages,
                )
                MessageCard(
                    message = message,
                    multiQuoteSelected = multiQuoteSelections.any { selection ->
                        selection.numreponse == message.numreponse
                    },
                    presentation = ReadingPostCardPresentation(
                        showSignature = showSignatures,
                        flat = fullWidthPosts,
                        flatBottomEdge = threadMessageFlatBottomEdge(
                            fullWidthPosts = fullWidthPosts,
                            hasFollowingMessage = hasFollowingVisibleMessage,
                        ),
                        egoQuoteCanonicalPseudo = egoQuoteCanonicalPseudo,
                        egoPostHighlighted = message.numreponse in egoPostNumreponses,
                    ),
                    // #1042 — same gate as the topic card (#208): a message whose page row carried no
                    // profile link ([Post.profileId] null) keeps its identity line inert.
                    onOpenProfile = message.profileId?.let { profileId ->
                        { onOpenProfile(profileId, message.author, message.avatarUrl) }
                    },
                    onOpenMenu = { onOpenMessageMenu(message) },
                    onImageLongPress = onImageLongPress,
                    onGoToCitedPost = onGoToCitedPost,
                    // #1074 — MP citation is fail-closed: unlike the topic fallback, the measured
                    // contract requires the server-provided 1-based page rank. Missing/zero `ref`,
                    // read-only thread, or absent host callback means no footer action.
                    onQuote = messageQuoteAction(
                        canReply = canReply,
                        authorBlocked = message.numreponse in hiddenNumreponses,
                        message = message,
                        onQuote = onQuote,
                    ),
                )
            }
        }
        if (totalPages > 1) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { onSelectPage(page - 1) },
                        enabled = pagerEnabled && page > 1,
                    ) {
                        Text(text = stringResource(R.string.messages_pager_previous))
                    }
                    Text(
                        text = stringResource(R.string.messages_pager_position, page, totalPages),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    OutlinedButton(
                        onClick = { onSelectPage(page + 1) },
                        enabled = pagerEnabled && page < totalPages,
                    ) {
                        Text(text = stringResource(R.string.messages_pager_next))
                    }
                }
            }
        }
    }
}

/** Fail-closed MP quote gate, kept outside the hot list builder's complexity budget. */
private fun messageQuoteAction(
    canReply: Boolean,
    authorBlocked: Boolean,
    message: Post,
    onQuote: ((Post) -> Unit)?,
): (() -> Unit)? {
    val ref = message.quoteRef ?: return null
    return onQuote
        ?.takeIf { canReply && !authorBlocked && ref >= 1 }
        ?.let { quote -> { quote(message) } }
}

/** Builds the complete locator only when the measured MP contract can be represented. */
internal fun Post.toPrivateMessageQuoteSelectionOrNull(page: Int): QuoteSelection? {
    val ref = quoteRef?.takeIf { it >= 1 } ?: return null
    return QuoteSelection(
        locator = QuoteLocator(page = page, numreponse = numreponse, ref = ref),
        author = author,
        excerpt = postContentExcerpt(content),
    )
}

/** Selected messages hidden by the current blacklist, including snapshots made on older pages. */
internal fun hiddenMultiQuoteNumreponses(
    selections: List<QuoteSelection>,
    hiddenNumreponses: Set<Int>,
    blockedQuoteAuthors: Set<String>,
): Set<Int> = selections
    .asSequence()
    .filter { selection ->
        selection.numreponse in hiddenNumreponses ||
            canonicalizePseudo(selection.author) in blockedQuoteAuthors
    }
    .mapTo(mutableSetOf()) { selection -> selection.numreponse }

/** Prunes selections after any live blacklist change, including changes made outside this screen. */
@Composable
private fun PruneHiddenMultiQuotesEffect(
    hiddenNumreponses: Set<Int>,
    blockedQuoteAuthors: Set<String>,
    selections: List<QuoteSelection>,
    onRemoveMultiQuotes: (Set<Int>) -> Unit,
) {
    LaunchedEffect(hiddenNumreponses, blockedQuoteAuthors, selections) {
        val hiddenSelections = hiddenMultiQuoteNumreponses(
            selections = selections,
            hiddenNumreponses = hiddenNumreponses,
            blockedQuoteAuthors = blockedQuoteAuthors,
        )
        if (hiddenSelections.isNotEmpty()) onRemoveMultiQuotes(hiddenSelections)
    }
}

/** The message stays in the keyed list; this helper selects only which card represents it. */
internal fun isHiddenMessage(message: Post, hidden: Set<Int>, revealed: Set<Int>): Boolean =
    message.numreponse in hidden && message.numreponse !in revealed

/**
 * #1042 — the MP thread's thin feature adapter over the shared [ReadingPostCard], the same pattern
 * as the topic's `TopicPostCard`: it maps one private [message] onto the card's data and supplies
 * the MP identity header, its labels and its callbacks. Capabilities are modelled by presence — a
 * callback or slot this adapter does not pass simply leaves the corresponding affordance out of
 * the composition, never a surface flag on the shared card.
 *
 * Through the shared card the MP inherits the stable reading body: structural spacing read from
 * [LocalDisplayMetrics] (the conversation follows the reader's density preset, like the topic) and
 * a selectable body (#1041 réarbitrage). Per #946 that selection capability is structurally
 * CONSTANT for the whole life of the card — the shared card never swaps its SelectionContainer at
 * runtime, so `rememberSaveable` state nested in the body (an unfolded long quote, a revealed
 * spoiler) survives recomposition, density-preset flips included.
 *
 * [onOpenProfile] — tapping the avatar or the author pseudo opens the profile surface (parity with
 * the topic card, #208); the MP page proves [Post.profileId] for its messages (#1042 fixture).
 * `null` (no profile link on the row) keeps the identity line inert.
 * [onOpenMenu] adds a long-press-only action to the card. Child gestures keep precedence: text
 * selection, profile taps and image long presses retain their own contracts, while a long press on
 * the remaining card surface opens the message menu.
 * [onImageLongPress] is likewise a capability by presence: the MP screen supplies it, while direct
 * hosts that omit it keep every content image inert (editor previews and signatures stay unchanged).
 * [onGoToCitedPost] wires parsed quote-header targets to the thread ViewModel's page/account-scoped
 * landing. A null host keeps the shared quote header inert.
 * [onQuote] supplies the footer « Citer » action. The list passes it only for a writable thread and
 * a message carrying a positive server-provided `quoteRef`; hidden messages never mount this card.
 * [multiQuoteSelected] reuses the shared selected border/semantics without adding a dynamic badge.
 * [presentation] is the shared render-only state bundle. The list derives its values from reader
 * preferences, the session pseudo (#1050 Ego markers) and message position, while this adapter
 * forwards the bundle unchanged — its only addition is the EgoPost StateDescription on the
 * identity band (#874 P1 parity). The band itself stays `secondaryContainer`: EgoPost colours the
 * card below it, never the identity strip. Neutral defaults keep direct test/preview mounts unmarked.
 */
@Composable
@Suppress("LongParameterList") // Thin card adapter: render state plus independent host capabilities.
internal fun MessageCard(
    message: Post,
    presentation: ReadingPostCardPresentation = ReadingPostCardPresentation(),
    multiQuoteSelected: Boolean = false,
    onOpenProfile: (() -> Unit)? = null,
    onOpenMenu: (() -> Unit)? = null,
    onImageLongPress: ((PostImageTarget) -> Unit)? = null,
    onGoToCitedPost: ((page: Int, numreponse: Int) -> Unit)? = null,
    onQuote: (() -> Unit)? = null,
) {
    // #287/#1042 — structural spacing from the active density preset, like the shared card's body.
    val m = LocalDisplayMetrics.current
    val openProfileLabel = if (onOpenProfile != null) {
        stringResource(R.string.messages_open_profile_action)
    } else {
        null
    }
    // #1050 — same #874 P1 gesture as the topic card: the EgoPost a11y marker is a StateDescription
    // on the identity node (TalkBack traverses it first), never a heading. The fallback pseudo or
    // creator slot stays the card's exactly-one heading (#884, pinned by MessageCardShellSmokeTest).
    val egoPostStateDescription = stringResource(R.string.messages_post_ego_state_description)
    val menuLabel = if (onOpenMenu != null) {
        stringResource(R.string.messages_message_menu_action)
    } else {
        null
    }
    val haptics = LocalHapticFeedback.current
    val menuModifier = if (onOpenMenu != null && menuLabel != null) {
        Modifier.messageMenuLongPress(onOpenMenu, haptics, menuLabel)
    } else {
        Modifier
    }
    val citedCount = message.citedCount ?: 0
    // #221 — canonical creator detection (case / format-char / NBSP insensitive) runs once per
    // author, not on every recomposition of this hot list row. Only creators need a pseudo slot;
    // everyone else keeps PostIdentityHeader's neutral fallback and its built-in interaction/a11y.
    val isCreator = remember(message.author) { isRf2Creator(message.author) }
    ReadingPostCard(
        post = message,
        modifier = menuModifier,
        presentation = presentation.copy(selected = multiQuoteSelected),
        // #1096 — the singleton Coil loader has no caller identity. Mark the whole MP
        // PostContent at this host boundary so painters and intrinsic probes cannot persist its
        // media URLs or bytes to Coil's shared disk cache.
        mediaDiskCachePolicy = PostMediaDiskCachePolicy.DISABLED,
        onGoToCitedPost = onGoToCitedPost,
        onImageLongPress = onImageLongPress,
        identity = {
            // An MP has no anchor/category tint, but still carries the same full-width identity band
            // as a normal topic post. Its secondaryContainer colour is therefore FIXED and independent
            // from EgoPost: the highlight belongs to the enclosing card container below the band.
            // PostIdentityBand adds no padding, so the shared band rhythm is reinjected on the
            // header — MP-owned gutters at cardBodyHorizontal, shared symmetric vertical inset at
            // cardHeaderVertical; the header↔body gap remains the body slot's own cardBodyTop.
            PostIdentityBand(
                modifier = Modifier.semantics {
                    if (presentation.egoPostHighlighted) {
                        stateDescription = egoPostStateDescription
                    }
                },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                // A creator supplies the shared gold pseudo leaf; everyone else uses the neutral
                // fallback. Per the slot contract, the creator branch owns both the profile tap and
                // the exactly-one heading on its real text node. The band adds no heading of its own.
                PostIdentityHeader(
                    author = message.author,
                    avatarUrl = message.avatarUrl,
                    dateText = message.date.asMessageDate(),
                    modifier = Modifier.padding(
                        horizontal = m.cardBodyHorizontal,
                        vertical = m.cardHeaderVertical,
                    ),
                    onAvatarClick = onOpenProfile,
                    onAvatarClickLabel = openProfileLabel,
                    onAuthorClick = onOpenProfile,
                    onAuthorClickLabel = openProfileLabel,
                    pseudo = if (isCreator) {
                        {
                            val pseudoModifier = (
                                if (onOpenProfile != null) {
                                    Modifier.clickable(
                                        onClick = onOpenProfile,
                                        role = Role.Button,
                                        onClickLabel = openProfileLabel,
                                    )
                                } else {
                                    Modifier
                                }
                                ).semantics { heading() }
                            CreatorPseudoText(
                                author = message.author,
                                modifier = pseudoModifier,
                            )
                        }
                    } else {
                        null
                    },
                    // #483/#1051 — same compact data-driven marker as the topic; null emits no slot.
                    dateTrailing = if (message.editedAt != null) {
                        {
                            val editedLabel = stringResource(R.string.messages_message_edited_inline)
                            Text(
                                text = "· $editedLabel",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.semantics { contentDescription = editedLabel },
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        },
        // #239/#1051 — the server counter is optional; a null/zero value emits no badges strip.
        badges = if (citedCount > 0) {
            {
                MessageCitedCountBadge(
                    citedCount = citedCount,
                    horizontalPadding = m.cardBodyHorizontal,
                )
            }
        } else {
            null
        },
        footer = onQuote?.let { quote ->
            {
                MessageQuoteAction(
                    onQuote = quote,
                    horizontalPadding = m.cardBodyHorizontal,
                )
            }
        },
    )
}

/** #1074 — sober MP footer action, aligned with the topic card's per-message actions row. */
@Composable
private fun MessageQuoteAction(onQuote: () -> Unit, horizontalPadding: Dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onQuote) {
            Text(text = stringResource(R.string.messages_quote))
        }
    }
}

/** Citation-count pill for the MP card, supplied through [ReadingPostCard]'s badges slot. */
@Composable
private fun MessageCitedCountBadge(citedCount: Int, horizontalPadding: Dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = horizontalPadding, end = horizontalPadding, top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.messages_message_cited_count,
                    citedCount,
                    citedCount,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Long-press-only card gesture. Child nodes consume their own gestures first, preserving profile
 * taps, selectable post text and the dedicated image menu; otherwise this detector owns the long
 * press. The explicit semantics action gives TalkBack the same labelled capability.
 */
private fun Modifier.messageMenuLongPress(
    onOpenMenu: () -> Unit,
    haptics: HapticFeedback,
    label: String,
): Modifier = this
    .pointerInput(onOpenMenu) {
        detectTapGestures(
            onLongPress = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onOpenMenu()
            },
        )
    }
    .semantics {
        onLongClick(label = label) {
            onOpenMenu()
            true
        }
    }

// #351b — same blend as the topic edge glow (#282): a full-primary glow read as an imposing panel.
private const val ACCENT_PRIMARY_BLEND = 0.3f
internal const val PRIVATE_MESSAGE_THREAD_READER_TAG = "private_message_thread_reader"

private val messageDateFormatter = DateTimeFormatter
    .ofPattern("dd/MM/yyyy HH:mm:ss", Locale.FRANCE)
    .withZone(ZoneId.of("Europe/Paris"))

internal fun Instant.asMessageDate(): String = messageDateFormatter.format(this)
