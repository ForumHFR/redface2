package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.messages.PrivateMessageSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Messages tab — the private-message inbox (#298). Lists the user's conversations and opens a
 * thread on tap. Replaces the former Phase 3 placeholder. The account / alpha-tools block lives
 * in the global account menu, wired by the navigation host through [topBarActions].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    onOpenThread: (threadId: Int, correspondent: String, subject: String) -> Unit,
    topBarActions: @Composable (() -> Unit)? = null,
    viewModel: MessagesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.messages_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                topBarActions?.invoke()
            }

            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when (val mode = state.mode) {
                    MessagesUiState.Mode.Loading -> CenteredBox {
                        CircularProgressIndicator()
                    }

                    is MessagesUiState.Mode.Error -> CenteredBox {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(horizontal = 24.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.messages_error_load),
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
                    }

                    is MessagesUiState.Mode.Content -> InboxContent(
                        conversations = mode.conversations,
                        page = state.page,
                        totalPages = state.totalPages,
                        onOpenThread = onOpenThread,
                        onSelectPage = viewModel::selectPage,
                    )
                }
            }
        }
    }
}

@Composable
private fun InboxContent(
    conversations: List<PrivateMessageSummary>,
    page: Int,
    totalPages: Int,
    onOpenThread: (threadId: Int, correspondent: String, subject: String) -> Unit,
    onSelectPage: (Int) -> Unit,
) {
    if (conversations.isEmpty()) {
        CenteredBox {
            Text(
                text = stringResource(R.string.messages_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(conversations, key = { it.threadId }) { conversation ->
            ConversationRow(
                conversation = conversation,
                onClick = {
                    onOpenThread(conversation.threadId, conversation.correspondent, conversation.subject)
                },
            )
        }
        if (totalPages > 1) {
            item {
                InboxPager(
                    page = page,
                    totalPages = totalPages,
                    onSelectPage = onSelectPage,
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: PrivateMessageSummary,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (conversation.hasUnread) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = conversation.correspondent,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = conversation.subject,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (conversation.hasUnread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = conversation.date.asInboxDate(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InboxPager(
    page: Int,
    totalPages: Int,
    onSelectPage: (Int) -> Unit,
) {
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

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private val inboxDateFormatter = DateTimeFormatter
    .ofPattern("dd/MM/yyyy HH:mm", Locale.FRANCE)
    .withZone(ZoneId.of("Europe/Paris"))

private fun Instant.asInboxDate(): String = inboxDateFormatter.format(this)
