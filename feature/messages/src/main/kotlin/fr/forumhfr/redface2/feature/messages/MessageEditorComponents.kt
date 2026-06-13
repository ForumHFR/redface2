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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
