package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R as UiR

/**
 * Building blocks shared by the two private-message editors — the conversation reply
 * (#301, [PrivateMessageReplyScreen]) and the new-conversation composer (#301 follow-up,
 * [PrivateMessageComposeScreen]): header, draft offer, recipient sheet and load/error scaffolding.
 * The send bar and the options sheet come from the common editor chrome (`core/ui` editor).
 */

@Composable
internal fun MessageEditorHeader(title: String, onBack: () -> Unit) {
    val backLabel = stringResource(R.string.messages_back)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.semantics { contentDescription = backLabel },
        ) {
            // dp-sized vector instead of a text « ← » glyph (font/baseline-dependent, cf. Codex
            // review). a11y label on the IconButton; the icon is decorative.
            Icon(
                painter = painterResource(fr.forumhfr.redface2.core.ui.R.drawable.ic_arrow_back),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * #405 — non-destructive draft offer of the reply's full-screen form failure; the ↺ status of both editors
 * opens the shared EditorDraftOfferSheet instead. « Restaurer » pre-fills the editor from the cached draft ;
 * « Ignorer » deletes the cached row. The draft is never silently applied nor lost. The sentence has no line
 * cap nor ellipsis, so font scale 1.3 never truncates it (gates R1b R3 m2, C4 m8), above the two 48 dp actions.
 */
@Composable
internal fun MessageDraftRestoreChoice(
    onRestore: () -> Unit,
    onDiscard: () -> Unit,
    restoreEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.messages_draft_restore_message), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MessageDraftRestoreActions(onRestore, onDiscard, restoreEnabled)
        }
    }
}

/** Emits « Restaurer » then « Ignorer » into the caller's row. */
@Composable
private fun MessageDraftRestoreActions(
    onRestore: () -> Unit, onDiscard: () -> Unit, restoreEnabled: Boolean,
) {
    TextButton(onRestore, Modifier.heightIn(min = 48.dp), enabled = restoreEnabled) {
        Text(stringResource(R.string.messages_draft_restore))
    }
    TextButton(onDiscard, Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.messages_draft_discard))
    }
}

/**
 * #606 — DT/MultiMP member editor, shown only to the conversation OWNER (gated by
 * `state.canManageRecipients` at the call site). Renders each current member as an [InputChip] with
 * a remove affordance, plus a text field + button to append a new member. The change is sent with
 * the reply (HFR mutates the member list only via a posted reply). Removing « Administration »
 * raises an inline warning but is not blocked. The last remaining member can't be removed — the VM
 * enforces « un destinataire au minimum », so its remove chip is disabled here too.
 */
@Composable
@Suppress("LongParameterList") // List + 2 callbacks + enabled — each call-site distinct.
internal fun MessageRecipientsEditor(
    recipients: List<String>,
    enabled: Boolean,
    onAddRecipient: (String) -> Unit,
    onRemoveRecipient: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingPseudo by remember { mutableStateOf("") }
    val canRemove = recipients.size > 1
    val showsAdminWarning = recipients.none { it.equals(ADMIN_PSEUDO, ignoreCase = true) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.messages_members_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.messages_members_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            recipients.forEach { pseudo ->
                val removeLabel = stringResource(R.string.messages_members_remove, pseudo)
                InputChip(
                    selected = false,
                    enabled = enabled && canRemove,
                    onClick = { onRemoveRecipient(pseudo) },
                    label = { Text(pseudo) },
                    trailingIcon = {
                        Icon(
                            painter = painterResource(fr.forumhfr.redface2.core.ui.R.drawable.ic_close),
                            contentDescription = removeLabel,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = pendingPseudo,
                onValueChange = { pendingPseudo = it },
                singleLine = true,
                enabled = enabled,
                label = { Text(stringResource(R.string.messages_members_add_label)) },
                placeholder = { Text(stringResource(R.string.messages_members_add_placeholder)) },
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = {
                    onAddRecipient(pendingPseudo)
                    pendingPseudo = ""
                },
                enabled = enabled && pendingPseudo.isNotBlank(),
            ) {
                Text(text = stringResource(R.string.messages_members_add))
            }
        }
        if (showsAdminWarning) {
            Text(
                text = stringResource(R.string.messages_members_admin_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
internal fun MessageFormLoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun MessageFormErrorState(
    onRetry: () -> Unit,
    message: String = stringResource(R.string.messages_reply_form_error),
    retryLabel: String = stringResource(R.string.messages_retry),
    onIgnoreQuote: (() -> Unit)? = null,
    onResumeQuote: (() -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            if (onResumeQuote != null) TextButton(onClick = onResumeQuote) {
                Text(stringResource(UiR.string.editor_action_resume_quote))
            }
            Button(onClick = onRetry, enabled = onResumeQuote == null) {
                Text(text = retryLabel)
            }
            if (onIgnoreQuote != null) TextButton(onClick = onIgnoreQuote) {
                Text(stringResource(UiR.string.editor_action_ignore_quote))
            }
        }
    }
}

/**
 * #618 (Bug 1) — bottom sheet hosting the DT/MultiMP member editor, moved out of the composer flow.
 * Wraps the existing [MessageRecipientsEditor] (chips + add field) in a height-capped, scrollable
 * column so a 29+-member DT scrolls inside the sheet instead of pushing the composer off-screen. The
 * change still ships with the reply (HFR mutates members only via a posted reply, #606 wiring
 * unchanged) — closing the sheet just returns to the composer. `navigationBarsPadding()` + the sheet's
 * own IME handling keep the add field above the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList") // List + 2 edit callbacks + enabled + dismiss — each call-site distinct.
internal fun RecipientManagerSheet(
    recipients: List<String>,
    enabled: Boolean,
    onAddRecipient: (String) -> Unit,
    onRemoveRecipient: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MessageRecipientsEditor(
                recipients = recipients,
                enabled = enabled,
                onAddRecipient = onAddRecipient,
                onRemoveRecipient = onRemoveRecipient,
            )
        }
    }
}

/** #606 — HFR's system member of a group conversation ; removing it warrants a warning, not a block. */
private const val ADMIN_PSEUDO = "Administration"
