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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
fun PrivateMessageThreadScreen(
    request: PrivateMessageThreadRequest,
    onBack: () -> Unit,
    topBarActions: @Composable (() -> Unit)? = null,
) {
    val viewModel = hiltViewModel<PrivateMessageThreadViewModel, PrivateMessageThreadViewModel.Factory>(
        creationCallback = { factory -> factory.create(request) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = request.subject.ifBlank { request.correspondent },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = request.correspondent,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    val backLabel = stringResource(R.string.messages_back)
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backLabel },
                    ) {
                        Text("←")
                    }
                },
                actions = { topBarActions?.invoke() },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when (val mode = state.mode) {
                PrivateMessageThreadUiState.Mode.Loading -> CircularProgressIndicator()

                is PrivateMessageThreadUiState.Mode.Error -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Text(
                        text = stringResource(R.string.messages_thread_error_load),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    mode.message?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = viewModel::retry) {
                        Text(text = stringResource(R.string.messages_retry))
                    }
                }

                is PrivateMessageThreadUiState.Mode.Content -> ThreadMessages(
                    messages = mode.thread.messages,
                    page = state.page,
                    totalPages = state.totalPages,
                    onSelectPage = viewModel::selectPage,
                )
            }
        }
    }
}

@Composable
private fun ThreadMessages(
    messages: List<Post>,
    page: Int,
    totalPages: Int,
    onSelectPage: (Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
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
                    OutlinedButton(onClick = { onSelectPage(page - 1) }, enabled = page > 1) {
                        Text(text = stringResource(R.string.messages_pager_previous))
                    }
                    Text(
                        text = stringResource(R.string.messages_pager_position, page, totalPages),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    OutlinedButton(onClick = { onSelectPage(page + 1) }, enabled = page < totalPages) {
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

private val messageDateFormatter = DateTimeFormatter
    .ofPattern("dd/MM/yyyy HH:mm:ss", Locale.FRANCE)
    .withZone(ZoneId.of("Europe/Paris"))

private fun Instant.asMessageDate(): String = messageDateFormatter.format(this)
