package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun TopicScreen(
    request: TopicRequest,
    onReply: (Int) -> Unit,
    onOpenPage: (Int) -> Unit,
) {
    val viewModel = hiltViewModel<TopicViewModel, TopicViewModel.Factory>(
        creationCallback = { factory -> factory.create(request) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    TopicContent(
        state = state,
        onIntent = viewModel::send,
        onReply = { onReply(request.post) },
        onOpenPage = onOpenPage,
    )
}

@Composable
internal fun TopicContent(
    state: TopicUiState,
    onIntent: (TopicIntent) -> Unit,
    onReply: () -> Unit,
    onOpenPage: (Int) -> Unit,
) {
    val request = state.request
    val lazyListState = rememberLazyListState()

    LaunchedEffect(state.mode, request.scrollTo) {
        val topic = (state.mode as? TopicUiState.Mode.Loaded)?.topic ?: return@LaunchedEffect
        val target = request.scrollTo ?: return@LaunchedEffect
        val index = topic.posts.indexOfFirst { it.numreponse == target }
        if (index >= 0) {
            lazyListState.scrollToItem(index + 1)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        when (val mode = state.mode) {
            TopicUiState.Mode.Placeholder -> {
                RedfacePlaceholderScreen(
                    title = stringResource(R.string.topic_title, request.post),
                    body = stringResource(R.string.topic_body_placeholder, request.cat, request.page),
                ) {
                    request.scrollTo?.let { target ->
                        Text(text = stringResource(R.string.topic_scroll_to, target))
                    }
                    Button(onClick = onReply) {
                        Text(text = stringResource(R.string.topic_reply))
                    }
                }
            }

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
                    title = stringResource(R.string.topic_fixed_title),
                    body = stringResource(R.string.topic_error_body, request.page, mode.message),
                ) {
                    TopicPageButtons(
                        availablePages = state.availablePages,
                        currentPage = request.page,
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
                    onOpenPage = onOpenPage,
                    listState = lazyListState,
                )
            }
        }
    }
}

@Composable
private fun TopicLoadedContent(
    state: TopicUiState,
    topic: Topic,
    onReply: () -> Unit,
    onOpenPage: (Int) -> Unit,
    listState: LazyListState,
) {
    val scrollTo = state.request.scrollTo
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
                availablePages = state.availablePages,
                scrollTo = scrollTo,
                onReply = onReply,
                onOpenPage = onOpenPage,
            )
        }
        items(
            items = topic.posts,
            key = { post -> post.numreponse },
        ) { post ->
            TopicPostCard(
                post = post,
                highlighted = scrollTo == post.numreponse,
            )
        }
    }
}

@Composable
private fun TopicHeaderCard(
    topic: Topic,
    availablePages: List<Int>,
    scrollTo: Int?,
    onReply: () -> Unit,
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
                    R.string.topic_fixture_caption,
                    topic.post,
                    topic.page,
                    topic.totalPages,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            scrollTo?.let { target ->
                Text(
                    text = stringResource(R.string.topic_scroll_to, target),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TopicPageButtons(
                availablePages = availablePages,
                currentPage = topic.page,
                onOpenPage = onOpenPage,
            )
            topic.poll?.let { poll ->
                TopicPollCard(poll)
            }
            Button(onClick = onReply) {
                Text(text = stringResource(R.string.topic_reply))
            }
        }
    }
}

@Composable
private fun TopicPageButtons(
    availablePages: List<Int>,
    currentPage: Int,
    onOpenPage: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }
    }
}

private val topicDateFormatter = DateTimeFormatter
    .ofPattern("dd/MM/yyyy HH:mm:ss", Locale.FRANCE)
    .withZone(ZoneId.of("Europe/Paris"))

private fun java.time.Instant.asTopicDate(): String = topicDateFormatter.format(this)
