package fr.forumhfr.redface2.feature.topic

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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.fixtures.FixedTopicFixtures
import fr.forumhfr.redface2.core.domain.fixtures.TopicFixtureRepository
import fr.forumhfr.redface2.core.model.Poll
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.ui.RedfacePlaceholderScreen
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TopicScreen(
    request: TopicRequest,
    topicFixtureRepository: TopicFixtureRepository,
    onReply: (Int) -> Unit,
    onOpenPage: (Int) -> Unit,
) {
    if (!FixedTopicFixtures.isFixedTopic(request.cat, request.post)) {
        RedfacePlaceholderScreen(
            title = stringResource(R.string.topic_title, request.post),
            body = stringResource(R.string.topic_body_placeholder, request.cat, request.page),
        ) {
            request.scrollTo?.let {
                Text(text = stringResource(R.string.topic_scroll_to, it))
            }
            Button(onClick = { onReply(request.post) }) {
                Text(text = stringResource(R.string.topic_reply))
            }
        }
        return
    }

    val lazyListState = rememberLazyListState()
    val state by produceState<TopicScreenState>(
        initialValue = TopicScreenState.Loading,
        key1 = request.page,
        key2 = topicFixtureRepository,
    ) {
        value = runCatching { topicFixtureRepository.loadTopicPage(request.page) }
            .fold(
                onSuccess = TopicScreenState::Loaded,
                onFailure = { error ->
                    TopicScreenState.Error(error.message ?: "Unknown error")
                },
            )
    }

    LaunchedEffect(state, request.scrollTo) {
        val topic = (state as? TopicScreenState.Loaded)?.topic ?: return@LaunchedEffect
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
        when (val uiState = state) {
            TopicScreenState.Loading -> {
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

            is TopicScreenState.Error -> {
                RedfacePlaceholderScreen(
                    title = stringResource(R.string.topic_fixed_title),
                    body = stringResource(R.string.topic_error_body, request.page, uiState.message),
                ) {
                    TopicPageButtons(
                        currentPage = request.page,
                        onOpenPage = onOpenPage,
                    )
                }
            }

            is TopicScreenState.Loaded -> {
                TopicLoadedScreen(
                    topic = uiState.topic,
                    scrollTo = request.scrollTo,
                    onReply = { onReply(request.post) },
                    onOpenPage = onOpenPage,
                    listState = lazyListState,
                )
            }
        }
    }
}

@Composable
private fun TopicLoadedScreen(
    topic: Topic,
    scrollTo: Int?,
    onReply: () -> Unit,
    onOpenPage: (Int) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
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
    currentPage: Int,
    onOpenPage: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FixedTopicFixtures.availablePages.forEach { page ->
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
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = poll.question,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
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
            TopicHtmlRenderer(html = post.content)
        }
    }
}

private sealed interface TopicScreenState {
    data object Loading : TopicScreenState

    data class Loaded(
        val topic: Topic,
    ) : TopicScreenState

    data class Error(
        val message: String,
    ) : TopicScreenState
}

private val topicDateFormatter = DateTimeFormatter
    .ofPattern("dd/MM/yyyy HH:mm:ss", Locale.FRANCE)
    .withZone(ZoneId.of("Europe/Paris"))

private fun java.time.Instant.asTopicDate(): String = topicDateFormatter.format(this)
