package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.ui.avatar.RedfaceUserAvatar
import fr.forumhfr.redface2.core.ui.error.sharedLabelResOrNull
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon
import fr.forumhfr.redface2.core.ui.list.LazyListScrollbar
import fr.forumhfr.redface2.core.ui.pager.pageSwipeEdgeHint
import fr.forumhfr.redface2.core.ui.post.PostRenderer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One private-message conversation (#298): renders the messages of a `cat=prive` thread,
 * reusing the shared [PostRenderer]. Read-only for the MVP — replying is a follow-up. The
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
    topBarActions: @Composable (() -> Unit)? = null,
) {
    val viewModel = hiltViewModel<PrivateMessageThreadViewModel, PrivateMessageThreadViewModel.Factory>(
        creationCallback = { factory -> factory.create(request) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mode = state.mode
    val listState = rememberLazyListState()

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
            }
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
                        onClick = onBack,
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
                actions = { topBarActions?.invoke() },
            )
        },
        floatingActionButton = {
            // #301 — reply affordance, shown only once the page proved a reply form is available
            // (`canReply`). Carries the page the user is viewing so the form HFR pre-fills (and its
            // `numrep`) matches what's on screen.
            val content = mode as? PrivateMessageThreadUiState.Mode.Content
            if (content?.thread?.canReply == true) {
                val replyLabel = stringResource(R.string.messages_reply)
                ExtendedFloatingActionButton(
                    text = { Text(replyLabel) },
                    // #360 / ADR-015 — crayon en vector stroke unifié via le primitive :core:ui,
                    // à la place du glyphe « ✎ » (poids optique aligné sur la flèche retour / les
                    // chevrons). Pas de Material icons (detekt ForbiddenImport).
                    icon = {
                        RedfaceVectorIcon(resId = fr.forumhfr.redface2.core.ui.R.drawable.ic_edit)
                    },
                    onClick = { onReply(request.threadId, state.page) },
                )
            }
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
                    Button(onClick = viewModel::retry) {
                        Text(text = stringResource(R.string.messages_retry))
                    }
                }

                is PrivateMessageThreadUiState.Mode.Content -> {
                    ScrollToTopOnPageChange(listState = listState, renderedPage = mode.thread.page)
                    // #335/#351 — pull-to-refresh re-fetches the displayed page; the indicator also
                    // covers the keep-content page changes (same isRefreshing flag).
                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // #300/#351 — same overlay layout as the topic page: the shared scrollbar
                        // rides the right edge of the message list, OUTSIDE the swiped element so
                        // it stays put while the page follows the finger.
                        Box(modifier = Modifier.fillMaxSize()) {
                            ThreadMessages(
                                messages = mode.thread.messages,
                                page = state.page,
                                totalPages = state.totalPages,
                                onSelectPage = viewModel::selectPage,
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
                                    onSelectPage = viewModel::selectPage,
                                ),
                            )
                            LazyListScrollbar(
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

/**
 * #351 — land at the top when a NEW page replaces the kept-on-screen previous one (in-place
 * pagination: the composition survives the page change, unlike the topic's route-driven model where
 * a fresh screen starts at the top for free). Keyed on the RENDERED page: a same-page refresh keeps
 * the read position. Only fires when a previous page was rendered in THIS composition and differs
 * (Codex review on the first cut): on the first Content render the guard is still null, so a
 * rotation / recreation with content already loaded keeps the position `rememberLazyListState` just
 * restored instead of being yanked back to the top. Extracted from the screen host to keep it under
 * detekt's cyclomatic-complexity threshold.
 */
@Composable
private fun ScrollToTopOnPageChange(listState: LazyListState, renderedPage: Int) {
    val lastRenderedPage = remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(renderedPage) {
        if (lastRenderedPage.value != null && lastRenderedPage.value != renderedPage) {
            listState.scrollToItem(0)
        }
        lastRenderedPage.value = renderedPage
    }
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
private fun rememberThreadSwipeModifier(
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
    // Codex review — dragOffset SURVIVES the in-place page change (no composition teardown, unlike
    // the topic's route-driven model where the offset state dies with the screen). Drop any residual
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
    onSelectPage: (Int) -> Unit,
    pagerEnabled: Boolean = true,
    listState: LazyListState,
    swipeModifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        // #351b — the swipe chain (edge glow + gesture + graphicsLayer follow) applies to the list
        // itself, like the topic's LazyColumn: the scrollbar overlay outside stays fixed on screen.
        modifier = Modifier
            .fillMaxSize()
            .then(swipeModifier),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(messages, key = { it.numreponse }) { message ->
            MessageCard(message = message)
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

@Composable
private fun MessageCard(message: Post) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RedfaceUserAvatar(
                    avatarUrl = message.avatarUrl,
                    author = message.author,
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = message.author,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = message.date.asMessageDate(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PostRenderer(content = message.content)
        }
    }
}

// #351b — same blend as the topic edge glow (#282): a full-primary glow read as an imposing panel.
private const val ACCENT_PRIMARY_BLEND = 0.3f

private val messageDateFormatter = DateTimeFormatter
    .ofPattern("dd/MM/yyyy HH:mm:ss", Locale.FRANCE)
    .withZone(ZoneId.of("Europe/Paris"))

private fun Instant.asMessageDate(): String = messageDateFormatter.format(this)
