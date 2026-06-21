package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitActions
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitButton
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitLabels
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitState

/**
 * Building blocks shared by the two private-message editors — the conversation reply
 * (#301, [PrivateMessageReplyScreen]) and the new-conversation composer (#301 follow-up,
 * [PrivateMessageComposeScreen]). Extracted verbatim from the reply screen so the composer
 * does not fork the IME-pinned bar, the option toggles, or the load/error scaffolding.
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
 * #405 — non-destructive draft-restore banner shared by the two private-message editors.
 * « Restaurer » pre-fills the editor from the cached draft ; « Ignorer » deletes the cached row.
 * The draft is never silently applied nor lost.
 */
@Composable
internal fun MessageDraftRestoreBanner(
    onRestore: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.messages_draft_restore_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRestore) {
                    Text(text = stringResource(R.string.messages_draft_restore))
                }
                TextButton(onClick = onDiscard) {
                    Text(text = stringResource(R.string.messages_draft_discard))
                }
            }
        }
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
            Button(onClick = onRetry) {
                Text(text = stringResource(R.string.messages_retry))
            }
        }
    }
}

/**
 * Send button pinned to the bottom, lifted above the IME so the user never dismisses the keyboard to
 * reach « Envoyer ». Mirrors the post editor's submit bar (the editor's `EditorSubmitBar` is module-
 * private, so the window-insets pattern is replicated here). Requires `windowSoftInputMode=adjustNothing`.
 * The armed-confirmation behaviour (#312 v2, countdown drain included) lives in the shared
 * [ArmedSubmitButton].
 */
@Composable
@Suppress("LongParameterList") // Mirrors the editor bar's state + actions.
internal fun MessageSubmitBar(
    canSubmit: Boolean,
    isSubmitting: Boolean,
    confirmArmed: Boolean,
    onSubmit: () -> Unit,
    onConfirmSubmit: () -> Unit,
    onDisarmConfirm: () -> Unit,
    onOpenOptions: () -> Unit,
    onOpenSmileys: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Single bottom inset = max(navBar, ime) via union(), so the two never stack into a
                    // phantom gap. Keyboard closed → clears the gesture nav bar; open → rides on the IME.
                    .windowInsetsPadding(
                        WindowInsets.navigationBars
                            .union(WindowInsets.ime)
                            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Tonal container for the secondary trigger, filled for « Envoyer » — same M3
                // emphasis pair as the post editor's bar. Hidden while the confirmation is
                // armed so the armed label never wraps (same fix as the editor bar).
                if (!confirmArmed) {
                    FilledTonalButton(onClick = onOpenOptions) {
                        Text(text = stringResource(R.string.messages_reply_actions_options))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    // #387 — same Options/Smileys/Envoyer trio as the post editor's bar (#388).
                    FilledTonalButton(onClick = onOpenSmileys) {
                        Text(text = stringResource(R.string.messages_reply_actions_smileys))
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                }
                ArmedSubmitButton(
                    state = ArmedSubmitState(armed = confirmArmed, enabled = canSubmit),
                    labels = ArmedSubmitLabels(
                        submit = stringResource(R.string.messages_reply_submit),
                        confirm = stringResource(R.string.messages_reply_submit_confirm),
                    ),
                    actions = ArmedSubmitActions(
                        onSubmit = onSubmit,
                        onConfirmSubmit = onConfirmSubmit,
                        onDisarm = onDisarmConfirm,
                    ),
                )
            }
        }
    }
}

@Composable
@Suppress("LongParameterList") // 3 toggles + 3 callbacks + enabled — each call-site distinct.
internal fun MessageEditorOptions(
    signatureEnabled: Boolean,
    smileyDisabled: Boolean,
    emailNotificationEnabled: Boolean,
    enabled: Boolean,
    onSignatureChanged: (Boolean) -> Unit,
    onSmileyDisabledChanged: (Boolean) -> Unit,
    onEmailNotificationChanged: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.messages_reply_options_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MessageOptionToggle(
            label = stringResource(R.string.messages_reply_option_signature),
            checked = signatureEnabled,
            enabled = enabled,
            onCheckedChange = onSignatureChanged,
        )
        MessageOptionToggle(
            label = stringResource(R.string.messages_reply_option_smiley_disabled),
            checked = smileyDisabled,
            enabled = enabled,
            onCheckedChange = onSmileyDisabledChanged,
        )
        MessageOptionToggle(
            label = stringResource(R.string.messages_reply_option_email_notification),
            checked = emailNotificationEnabled,
            enabled = enabled,
            onCheckedChange = onEmailNotificationChanged,
        )
    }
}

@Composable
private fun MessageOptionToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 16.dp),
        )
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

/**
 * #618 (Bug 1) — compact owner-only recipients summary shown in the reply composer in place of the
 * old inline editor. « Destinataires : N » + a « Gérer » action; the tap opens the dedicated
 * [RecipientManagerSheet] so the message body and the send bar stay reachable (the inline editor used
 * to crowd them out, especially for a 29+-member DT). Gated by `canManageRecipients` at the call site.
 */
@Composable
internal fun MessageRecipientsSummary(count: Int, onManage: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.messages_recipients_summary, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onManage) {
            Text(text = stringResource(R.string.messages_recipients_manage))
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

internal val PrivateMessageReplyError.bannerResId: Int
    get() = when (this) {
        PrivateMessageReplyError.Empty -> R.string.messages_reply_error_empty
        PrivateMessageReplyError.InvalidHashCheck -> R.string.messages_reply_error_invalid_hash
        PrivateMessageReplyError.AntiFlood -> R.string.messages_reply_error_anti_flood
        PrivateMessageReplyError.LoginRequired -> R.string.messages_reply_error_login_required
        PrivateMessageReplyError.Network -> R.string.messages_reply_error_network
        PrivateMessageReplyError.SessionExpired -> R.string.messages_reply_error_session_expired
        PrivateMessageReplyError.Unexpected -> R.string.messages_reply_error_unexpected
    }
