package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.ui.error.sharedLabelResOrNull
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon
import fr.forumhfr.redface2.core.ui.list.ScrollToTopOnPageChange
import fr.forumhfr.redface2.core.ui.pager.pageSwipeEdgeHint
import fr.forumhfr.redface2.core.ui.post.PostCardShellFlatBottomEdge
import fr.forumhfr.redface2.core.ui.post.PostIdentityHeader
import fr.forumhfr.redface2.core.ui.post.PostImageActions
import fr.forumhfr.redface2.core.ui.post.PostImageMenuSheet
import fr.forumhfr.redface2.core.ui.post.PostImageTarget
import fr.forumhfr.redface2.core.ui.post.PostListScaffold
import fr.forumhfr.redface2.core.ui.post.ReadingPostCard
import fr.forumhfr.redface2.core.ui.post.ReadingPostCardPresentation
import fr.forumhfr.redface2.core.ui.theme.LocalDisplayMetrics
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One private-message conversation (#298): renders the messages of a `cat=prive` thread,
 * reusing the shared [ReadingPostCard]; replying rides the [ThreadReplyFab] (#301). The
 * ViewModel receives its arguments via Hilt assisted injection ([PrivateMessageThreadRequest]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList") // Screen host: route request + 3 nav callbacks + multi-recipient hint + actions slot.
fun PrivateMessageThreadScreen(
    request: PrivateMessageThreadRequest,
    // Ephemeral UI hint from the inbox row (the route stays opaque, carrying only threadId/page).
    // Complements the page-proven [PrivateMessageThread.isMultiRecipient] so a MultiMP/DT whose
    // current page shows a single other author still reads "Interlocuteurs multiples".
    isMultiRecipientHint: Boolean,
    onLoaded: () -> Unit,
    onBack: () -> Unit,
    onReply: (threadId: Int, page: Int) -> Unit,
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

    LaunchedEffect(mode) {
        if (mode is PrivateMessageThreadUiState.Mode.Content) {
            onLoaded()
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
            onRetry = viewModel::retry,
            onRefresh = viewModel::refresh,
            onSelectPage = viewModel::selectPage,
            onOpenRoster = viewModel::openRoster,
            onDismissRoster = viewModel::dismissRoster,
            onRetryRoster = viewModel::retryRoster,
            onManageRecipients = {
                viewModel.dismissRoster()
                onManageRecipients(request.threadId, state.page)
            },
            onOpenProfile = onOpenProfile,
            onSaveImage = viewModel::saveImage,
        ),
        topBarActions = topBarActions,
    )
}

/**
 * State-hoisted callbacks for [PrivateMessageThreadContent]. Keeping this seam free of Hilt makes
 * the complete thread surface characterizable in JVM Compose tests while the public screen remains
 * the sole owner of the ViewModel and route arguments.
 */
@Suppress("LongParameterList") // One state-hoisted action per independent control on the complete surface.
internal data class PrivateMessageThreadCallbacks(
    val onBack: () -> Unit,
    val onReply: () -> Unit,
    val onRetry: () -> Unit,
    val onRefresh: () -> Unit,
    val onSelectPage: (Int) -> Unit,
    val onOpenRoster: () -> Unit,
    val onDismissRoster: () -> Unit,
    val onRetryRoster: () -> Unit,
    val onManageRecipients: () -> Unit,
    // #1042 — defaulted (unlike its siblings) so the pre-#1042 characterization mounts compile
    // unchanged; a host that does not navigate keeps the tap a no-op, like the topic screen default.
    val onOpenProfile: (userId: Int, pseudo: String, avatarUrl: String?) -> Unit = { _, _, _ -> },
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
    topBarActions: @Composable (() -> Unit)? = null,
) {
    val mode = state.mode
    val listState = rememberLazyListState()
    // #831/#1051 — post image whose contextual menu is open (null = closed). Deliberately not
    // rememberSaveable: losing an ephemeral menu across process death is acceptable.
    var imageMenuTarget by remember { mutableStateOf<PostImageTarget?>(null) }
    // One stable handler instance is threaded through MessageCard to LocalPostImageActions;
    // remembered so providing it never invalidates the cards.
    val postImageActions = remember { PostImageActions(onLongPress = { imageMenuTarget = it }) }

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
            ThreadReplyFab(
                // #301 — reply affordance, shown only once the page proved a reply form is available.
                canReply = (mode as? PrivateMessageThreadUiState.Mode.Content)?.thread?.canReply == true,
                onReply = callbacks.onReply,
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

                is PrivateMessageThreadUiState.Mode.Content -> {
                    // #351c — moved to :core:ui (was a local copy here). Lands at the top when a NEW
                    // page replaces the kept-on-screen one; the null guard on the first render keeps a
                    // restored position on rotation/recreation.
                    ScrollToTopOnPageChange(listState = listState, renderedPage = mode.thread.page)
                    // #335/#351 — pull-to-refresh re-fetches the displayed page; the indicator also
                    // covers the keep-content page changes (same isRefreshing flag).
                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = callbacks.onRefresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // #300/#351c — the list overlay (LazyColumn + auto-hiding scrollbar) is now the
                        // shared PostListScaffold; the swipe chain rides the inner list via listModifier
                        // (so the scrollbar stays fixed while the page follows the finger) and the MP
                        // list chrome is the feature-owned ThreadListLayout.kt geometry (#1046).
                        ThreadMessages(
                            messages = mode.thread.messages,
                            page = state.page,
                            totalPages = state.totalPages,
                            fullWidthPosts = state.fullWidthPosts,
                            showSignatures = state.showSignatures,
                            onSelectPage = callbacks.onSelectPage,
                            onOpenProfile = callbacks.onOpenProfile,
                            onImageLongPress = postImageActions.onLongPress,
                            // Codex review — gate the pager buttons with the same condition as
                            // the swipe: re-tapping during a keep-content load would only cancel
                            // and restart the round-trip (supersede), never advance faster.
                            pagerEnabled = !state.isRefreshing,
                            listState = listState,
                            // #351b — horizontal swipe changes page in place (same thresholds
                            // and feel as the topic via the shared :core:ui geometry).
                            swipeModifier = rememberThreadSwipeModifier(
                                renderedPage = mode.thread.page,
                                totalPages = state.totalPages,
                                isRefreshing = state.isRefreshing,
                                onSelectPage = callbacks.onSelectPage,
                            ),
                        )
                    }
                }
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

    ThreadImageMenuHost(
        mode = mode,
        target = imageMenuTarget,
        onSave = callbacks.onSaveImage,
        onClear = { imageMenuTarget = null },
    )
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
 * Every input the gesture needs later is wrapped in [rememberUpdatedState] and read through
 * stable lambdas: [threadPageSwipe]'s `pointerInput` is keyed on `Unit` and NEVER re-keyed (the
 * in-place pager changes pages under a live composition), so the lambdas captured by its initial
 * block must keep reading live values for the whole life of the composition. The gesture is gated
 * off while a load is in flight ([isRefreshing]) and re-arms when it settles.
 */
@Composable
internal fun rememberThreadSwipeModifier(
    renderedPage: Int,
    totalPages: Int,
    isRefreshing: Boolean,
    onSelectPage: (Int) -> Unit,
): Modifier {
    val dragOffset = remember { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current
    val currentPage = rememberUpdatedState(renderedPage)
    val currentTotal = rememberUpdatedState(totalPages)
    val swipeEnabled = rememberUpdatedState(!isRefreshing)
    val currentOnSelectPage = rememberUpdatedState(onSelectPage)
    // Codex review — dragOffset SURVIVES the in-place page change (no composition teardown; the
    // topic's offset survives too since #895 étape 4 and is reset the same way, by a
    // LaunchedEffect keyed on the rendered page — pre-#895 it died with the route-replaced
    // screen). Drop any residual
    // translation when a new page lands so the incoming content never inherits the old offset. An
    // in-flight spring-back may stream a few frames after this reset; it converges to 0 by
    // construction, so the transient is negligible. A drag still under the finger keeps following it
    // (rare: the page swapped under an active drag) — coherent with the finger, assumed.
    LaunchedEffect(renderedPage) {
        dragOffset.floatValue = 0f
    }
    // Same desaturated accent as the topic edge glow (#282): mostly neutral with a touch of primary.
    val accent = lerp(
        MaterialTheme.colorScheme.onSurfaceVariant,
        MaterialTheme.colorScheme.primary,
        ACCENT_PRIMARY_BLEND,
    )
    // Captured ONCE by threadPageSwipe's pointerInput(Unit) block — deliberately remember-ed without
    // keys so the code does not pretend a recreation would reach the gesture (it would not: the
    // initial block keeps its first capture). The callback and the gate stay live through the
    // rememberUpdatedState-backed lambdas; haptics (LocalHapticFeedback) is stable per Activity.
    val handlers = remember {
        ThreadSwipeHandlers(
            haptics = haptics,
            onSelectPage = { page -> currentOnSelectPage.value(page) },
            enabled = { swipeEnabled.value },
        )
    }
    return Modifier
        .pageSwipeEdgeHint(
            currentPage = renderedPage,
            totalPages = { currentTotal.value },
            dragOffset = dragOffset,
            accent = accent,
            enabled = { swipeEnabled.value },
        )
        .threadPageSwipe(
            currentPage = { currentPage.value },
            totalPages = { currentTotal.value },
            dragOffset = dragOffset,
            handlers = handlers,
        )
}

@Composable
@Suppress("LongParameterList") // List host: pager state + hoisted list state + swipe chain, all distinct.
private fun ThreadMessages(
    messages: List<Post>,
    page: Int,
    totalPages: Int,
    fullWidthPosts: Boolean,
    showSignatures: Boolean,
    onSelectPage: (Int) -> Unit,
    onOpenProfile: (userId: Int, pseudo: String, avatarUrl: String?) -> Unit,
    onImageLongPress: (PostImageTarget) -> Unit,
    pagerEnabled: Boolean = true,
    listState: LazyListState,
    swipeModifier: Modifier = Modifier,
) {
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
    PostListScaffold(
        listState = listState,
        contentPadding = threadListContentPadding(fullWidthPosts),
        verticalArrangement = threadListArrangement(fullWidthPosts),
        listModifier = swipeModifier,
    ) {
        itemsIndexed(
            items = messages,
            key = { _, message -> message.numreponse },
        ) { index, message ->
            MessageCard(
                message = message,
                presentation = ReadingPostCardPresentation(
                    showSignature = showSignatures,
                    flat = fullWidthPosts,
                    flatBottomEdge = threadMessageFlatBottomEdge(
                        fullWidthPosts = fullWidthPosts,
                        hasFollowingMessage = index < messages.lastIndex,
                    ),
                ),
                // #1042 — same gate as the topic card (#208): a message whose page row carried no
                // profile link ([Post.profileId] null) keeps its identity line inert.
                onOpenProfile = message.profileId?.let { profileId ->
                    { onOpenProfile(profileId, message.author, message.avatarUrl) }
                },
                onImageLongPress = onImageLongPress,
            )
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
 * [onImageLongPress] is likewise a capability by presence: the MP screen supplies it, while direct
 * hosts that omit it keep every content image inert (editor previews and signatures stay unchanged).
 * [presentation] is the shared render-only state bundle. The list derives its values from reader
 * preferences and message position, while this adapter forwards the bundle unchanged. Its neutral
 * defaults also cover presentation details that the MP surface does not resolve yet.
 */
@Composable
internal fun MessageCard(
    message: Post,
    presentation: ReadingPostCardPresentation = ReadingPostCardPresentation(),
    onOpenProfile: (() -> Unit)? = null,
    onImageLongPress: ((PostImageTarget) -> Unit)? = null,
) {
    // #287/#1042 — structural spacing from the active density preset, like the shared card's body.
    val m = LocalDisplayMetrics.current
    val openProfileLabel = if (onOpenProfile != null) {
        stringResource(R.string.messages_open_profile_action)
    } else {
        null
    }
    ReadingPostCard(
        post = message,
        presentation = presentation,
        onImageLongPress = onImageLongPress,
        identity = {
            // Band-less identity (an MP has no anchor/category tint): the plain shared header, its
            // padding reinjected here (densities stay slot-owned, #351) from the SAME preset values
            // as the body so the whole card breathes to one rhythm — gutters at cardBodyHorizontal,
            // card-top inset at cardBodyTop; the header↔body gap is the body slot's own cardBodyTop.
            // No pseudo slot is supplied, so the header's fallback pseudo text carries the card's
            // exactly-one TalkBack heading (#884 contract, pinned by MessageCardShellSmokeTest) and
            // its author tap rides onAuthorClick.
            PostIdentityHeader(
                author = message.author,
                avatarUrl = message.avatarUrl,
                dateText = message.date.asMessageDate(),
                modifier = Modifier.padding(
                    start = m.cardBodyHorizontal,
                    top = m.cardBodyTop,
                    end = m.cardBodyHorizontal,
                ),
                onAvatarClick = onOpenProfile,
                onAvatarClickLabel = openProfileLabel,
                onAuthorClick = onOpenProfile,
                onAuthorClickLabel = openProfileLabel,
            )
        },
    )
}

// #351b — same blend as the topic edge glow (#282): a full-primary glow read as an imposing panel.
private const val ACCENT_PRIMARY_BLEND = 0.3f

private val messageDateFormatter = DateTimeFormatter
    .ofPattern("dd/MM/yyyy HH:mm:ss", Locale.FRANCE)
    .withZone(ZoneId.of("Europe/Paris"))

private fun Instant.asMessageDate(): String = messageDateFormatter.format(this)
