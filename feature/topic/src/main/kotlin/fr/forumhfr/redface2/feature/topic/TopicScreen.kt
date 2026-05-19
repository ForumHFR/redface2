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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.Poll
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.ui.RedfacePlaceholderScreen
import fr.forumhfr.redface2.core.ui.post.PostRenderer
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.first

@Composable
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
    onOpenPage: (Int) -> Unit,
) {
    val viewModel = hiltViewModel<TopicViewModel, TopicViewModel.Factory>(
        creationCallback = { factory -> factory.create(request) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lazyListState = rememberLazyListState()

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
                        lazyListState.scrollToItem(index + 1)
                    }
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
        onOpenPage = onOpenPage,
    )
}

@Composable
@Suppress("LongParameterList") // state-hoisted Composable : each param has a distinct call-site.
internal fun TopicContent(
    state: TopicUiState,
    listState: LazyListState,
    onIntent: (TopicIntent) -> Unit,
    onReply: (subcat: Int, page: Int) -> Unit,
    onQuote: (subcat: Int, page: Int, quotedNumreponse: Int, quoteRef: Int) -> Unit,
    onEdit: (subcat: Int, page: Int, numreponse: Int) -> Unit,
    onOpenPage: (Int) -> Unit,
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
                    onOpenPage = onOpenPage,
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
    onOpenPage: (Int) -> Unit,
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
            TopicHeaderCard(
                topic = topic,
                state = state,
                onReply = onReply,
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
            TopicPostCard(
                post = post,
                highlighted = highlight == post.numreponse,
                onQuote = quoteAction,
                onEdit = editAction,
            )
        }
    }
}

@Composable
private fun TopicHeaderCard(
    topic: Topic,
    state: TopicUiState,
    onReply: (subcat: Int, page: Int) -> Unit,
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
            Text(
                text = post.postIndex?.let { postIndex ->
                    stringResource(
                        R.string.topic_post_header_with_index,
                        postIndex,
                        post.author,
                        post.numreponse,
                    )
                } ?: stringResource(
                    R.string.topic_post_header_without_index,
                    post.author,
                    post.numreponse,
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = post.date.asTopicDate(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
