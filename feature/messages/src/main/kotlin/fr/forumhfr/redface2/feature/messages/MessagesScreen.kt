package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.messages.PrivateMessageSummary
import fr.forumhfr.redface2.core.ui.avatar.RedfaceUserAvatar
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
    // isMultiRecipient is an ephemeral UI hint forwarded to the thread screen (the route stays
    // opaque — it never persists this private metadata in the back stack).
    onOpenThread: (threadId: Int, isMultiRecipient: Boolean) -> Unit,
    readThreadIds: Set<Int> = emptySet(),
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
                    MessagesUiState.Mode.RequiresLogin -> CenteredBox {
                        Column(
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
                    }

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
                        state = InboxContentState(
                            conversations = mode.conversations,
                            readThreadIds = readThreadIds,
                            page = state.page,
                            totalPages = state.totalPages,
                        ),
                        onSelectPage = viewModel::selectPage,
                        onConversationClick = { conversation ->
                            onOpenThread(conversation.threadId, conversation.isMultiRecipient)
                        },
                    )
                }
            }
        }
    }
}

private data class InboxContentState(
    val conversations: List<PrivateMessageSummary>,
    val readThreadIds: Set<Int>,
    val page: Int,
    val totalPages: Int,
)

@Composable
private fun InboxContent(
    state: InboxContentState,
    onSelectPage: (Int) -> Unit,
    onConversationClick: (PrivateMessageSummary) -> Unit,
) {
    // Always a LazyColumn (even when empty) so Material 3 pull-to-refresh — driven by nested
    // scroll — keeps working on an empty inbox; the empty message fills the viewport via
    // fillParentMaxSize.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.conversations.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillParentMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.messages_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(state.conversations, key = { it.threadId }) { conversation ->
                val effectiveConversation = if (conversation.threadId in state.readThreadIds) {
                    conversation.copy(hasUnread = false)
                } else {
                    conversation
                }
                // Multi-recipient conversations carry no single pseudo (HFR shows
                // "Interlocuteurs multiples"). The thread route remains opaque; this label is
                // list-only and the thread screen reads its header from the fetched page.
                val displayName = if (effectiveConversation.isMultiRecipient) {
                    stringResource(R.string.messages_multi_recipient)
                } else {
                    effectiveConversation.correspondent
                }
                ConversationRow(
                    conversation = effectiveConversation,
                    displayName = displayName,
                    onClick = { onConversationClick(effectiveConversation) },
                )
            }
            if (state.totalPages > 1) {
                item {
                    InboxPager(
                        page = state.page,
                        totalPages = state.totalPages,
                        onSelectPage = onSelectPage,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: PrivateMessageSummary,
    displayName: String,
    onClick: () -> Unit,
) {
    val readState = stringResource(
        if (conversation.hasUnread) {
            R.string.messages_state_unread
        } else {
            R.string.messages_state_read
        },
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = readState }
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Read-state dot, shown systematically: a filled primary dot for an unread
            // conversation, a hollow outline ring for a read one (a11y carried by the Card's
            // stateDescription above).
            ReadStateDot(unread = conversation.hasUnread)
            // Leading avatar — placeholder for now (the inbox listing does not expose the
            // correspondent's avatar URL; a future pass can resolve it). Single conversations
            // show the correspondent's initial, group ones the "Interlocuteurs multiples" initial.
            RedfaceUserAvatar(
                avatarUrl = null,
                author = displayName,
                contentDescriptionOverride = if (conversation.isMultiRecipient) {
                    stringResource(R.string.messages_avatar_group)
                } else {
                    null
                },
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = displayName,
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
private fun ReadStateDot(unread: Boolean) {
    val dotModifier = Modifier.size(10.dp)
    if (unread) {
        Box(modifier = dotModifier.background(MaterialTheme.colorScheme.primary, CircleShape))
    } else {
        Box(modifier = dotModifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape))
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
