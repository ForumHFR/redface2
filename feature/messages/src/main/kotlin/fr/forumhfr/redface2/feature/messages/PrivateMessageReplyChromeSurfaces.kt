package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.ui.editor.EditorEventDetails
import fr.forumhfr.redface2.core.ui.editor.EditorHeaderPanel
import fr.forumhfr.redface2.core.ui.editor.EditorOptions
import fr.forumhfr.redface2.core.ui.editor.EditorOptionsLabels
import fr.forumhfr.redface2.core.ui.editor.EditorOptionsSheet
import fr.forumhfr.redface2.core.ui.editor.EditorPreview
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerSheet
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState

/** Mounted around the live field, like the post: panels hide it without losing its viewport/undo. */
@Composable
@Suppress("LongParameterList") // Live field, picker and the scaffold's focus callback are separate ports.
internal fun PrivateMessageReplyChromeSurfaces(
    adapter: PrivateMessageReplyChromeAdapter,
    bodyState: TextFieldState,
    smileys: SmileyPickerController,
    autoOpenRecipients: Boolean,
    onReturnToBody: () -> Unit,
    body: @Composable () -> Unit,
) {
    EditorEventDetails(adapter.detail, bodyState, adapter::closeDetails, adapter::reload,
        { adapter.returnToBody(onReturnToBody) }, body = {
            // Short title: the offer sentence appears once, in the panel body (gate R1b R3, minor 2).
            EditorHeaderPanel(adapter.surface == PrivateMessageReplyChromeSurface.Restore,
                stringResource(R.string.messages_draft_restore_title), bodyState, adapter::closeSurface,
                { adapter.returnToBody(onReturnToBody) }, body = body) {
                ReplyDraftRestoreChoice(adapter::restoreDraft, adapter::discardDraft, adapter.state.restoreEnabled)
            }
        })
    PrivateMessageReplyChromeSheets(adapter, autoOpenRecipients, onReturnToBody)
    if (adapter.surface == PrivateMessageReplyChromeSurface.Smileys && adapter.state.canInsert) {
        val picker = smileys.state.collectAsStateWithLifecycle().value as? SmileyPickerState.Open
        if (picker != null) SmileyPickerSheet(picker, { smileys.dismiss(); adapter.closeSurface() },
            smileys::onQueryChanged, adapter::smiley)
    }
}

@Composable
internal fun PrivateMessageReplyChromeSheets(
    adapter: PrivateMessageReplyChromeAdapter,
    autoOpenRecipients: Boolean,
    onReturnToBody: () -> Unit,
) {
    val source = adapter.state.source
    LaunchedEffect(adapter, autoOpenRecipients, adapter.state.recipientsEnabled, adapter.scaffoldChrome().event?.key) {
        adapter.openInitialRecipients(autoOpenRecipients)
    }
    LaunchedEffect(adapter, source.canManageRecipients) {
        if (!source.canManageRecipients && adapter.surface == PrivateMessageReplyChromeSurface.Recipients) {
            adapter.closeSurface()
        }
    }
    // The sheet must be removed before the body can take focus and restore its captured IME state.
    LaunchedEffect(adapter.returnGeneration) {
        if (adapter.consumeReturn()) adapter.returnToBody(onReturnToBody)
    }
    when (adapter.surface) {
        PrivateMessageReplyChromeSurface.Recipients -> if (source.canManageRecipients) RecipientManagerSheet(
            source.recipients, adapter.state.recipientsEnabled,
            adapter::addRecipient, adapter::removeRecipient, adapter::closeSurface,
        )
        PrivateMessageReplyChromeSurface.Options -> EditorOptionsSheet(adapter::closeSurface) {
            EditorOptions(source.signatureEnabled, source.smileyDisabled, source.emailNotificationEnabled,
                adapter.state.optionsEnabled, adapter::signature, adapter::smileyDisabled, adapter::notification,
                EditorOptionsLabels(stringResource(R.string.messages_reply_options_title),
                    stringResource(R.string.messages_reply_option_signature),
                    stringResource(R.string.messages_reply_option_smiley_disabled),
                    stringResource(R.string.messages_reply_option_email_notification)))
        }
        else -> Unit
    }
}

/**
 * Reply draft offer for the ↺ panel and the full-screen form failure. Unlike the one-row banner kept by
 * the new-message editor, the sentence has no line cap, so font scale 1.3 never ellipsizes it.
 */
@Composable
internal fun ReplyDraftRestoreChoice(
    onRestore: () -> Unit,
    onDiscard: () -> Unit,
    restoreEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.messages_draft_restore_message), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onRestore, Modifier.heightIn(min = 48.dp), enabled = restoreEnabled) {
                Text(stringResource(R.string.messages_draft_restore))
            }
            TextButton(onDiscard, Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.messages_draft_discard))
            }
        }
    }
}

@Composable
internal fun PrivateMessageReplyChromePreview(
    adapter: PrivateMessageReplyChromeAdapter,
    bodyState: TextFieldState,
    focus: FocusRequester,
    body: @Composable () -> Unit,
) {
    EditorPreview(adapter.state.scaffold.previewVisible, adapter.state.source.preview, bodyState, focus,
        adapter.state.mediaDiskCachePolicy, stringResource(R.string.messages_reply_preview_show), body = body)
}
