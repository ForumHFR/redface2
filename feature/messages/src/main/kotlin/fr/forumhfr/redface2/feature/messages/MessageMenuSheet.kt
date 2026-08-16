package fr.forumhfr.redface2.feature.messages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.postContentPlainText
import fr.forumhfr.redface2.core.ui.avatar.RedfaceUserAvatar
import fr.forumhfr.redface2.core.ui.post.hideThenDismiss

/**
 * #1051 — contextual menu owned by `:feature:messages` for one private message.
 *
 * This is deliberately not a specialization of the topic's `PostMenuSheet`: the MP surface has
 * only three author/message capabilities (copy the complete readable text, open the profile, and
 * block/unblock the author) plus the two data-driven information lines. It exposes neither a quote
 * placeholder nor a private permalink: quoting is not implemented, and no tested HFR contract can
 * currently build a precise private-message permalink.
 *
 * The profile action rides on the hero row, like the topic menu. A null callback keeps that row
 * inert. The block action is likewise hidden by capability (the caller omits it for one's own
 * messages), never rendered disabled. Image-only messages project to an empty string through
 * [postContentPlainText]; their copy button stays visible but disabled so the UI never copies an
 * empty clip silently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageMenuSheet(
    message: Post,
    authorBlocked: Boolean,
    onDismiss: () -> Unit,
    onOpenProfile: (() -> Unit)? = null,
    onToggleBlockAuthor: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val plainText = remember(message.content) { postContentPlainText(message.content) }
    val copiedFeedback = stringResource(R.string.messages_message_menu_text_copied)
    val citedCount = message.citedCount ?: 0

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding(),
        ) {
            MessageMenuHero(
                message = message,
                onClick = onOpenProfile?.let { openProfile ->
                    {
                        hideThenDismiss(coroutineScope, sheetState) {
                            onDismiss()
                            openProfile()
                        }
                    }
                },
            )

            if (message.editedAt != null || citedCount > 0) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                message.editedAt?.let { editedAt ->
                    Text(
                        text = stringResource(
                            R.string.messages_message_menu_edited,
                            editedAt.asMessageDate(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                if (citedCount > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.messages_message_menu_cited,
                            citedCount,
                            citedCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    copyMessageTextToClipboard(context, plainText, copiedFeedback)
                    hideThenDismiss(coroutineScope, sheetState, onDismiss)
                },
                enabled = plainText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.messages_message_menu_copy_text))
            }

            if (onToggleBlockAuthor != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        onToggleBlockAuthor()
                        hideThenDismiss(coroutineScope, sheetState, onDismiss)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (authorBlocked) {
                                R.string.messages_message_menu_unblock_author
                            } else {
                                R.string.messages_message_menu_block_author
                            },
                        ),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Hero identity and profile action; intentionally adds no heading to the message semantics tree. */
@Composable
private fun MessageMenuHero(message: Post, onClick: (() -> Unit)?) {
    val openProfileLabel = stringResource(R.string.messages_open_profile_action)
    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            onClick = onClick,
            role = Role.Button,
            onClickLabel = openProfileLabel,
        )
    } else {
        Modifier
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier),
    ) {
        RedfaceUserAvatar(
            avatarUrl = message.avatarUrl,
            author = message.author,
            size = 56.dp,
        )
        Column {
            Text(
                text = message.author,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    R.string.messages_message_menu_number,
                    message.numreponse,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = message.date.asMessageDate(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Clipboard write with the same Android 13+ system-overlay convention as the topic menu. */
private fun copyMessageTextToClipboard(context: Context, text: String, feedback: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("redface2 private message text", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, feedback, Toast.LENGTH_SHORT).show()
    }
}
