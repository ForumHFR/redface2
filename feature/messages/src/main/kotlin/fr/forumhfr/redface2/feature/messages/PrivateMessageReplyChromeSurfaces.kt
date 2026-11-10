package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import fr.forumhfr.redface2.core.ui.editor.EditorOptions
import fr.forumhfr.redface2.core.ui.editor.EditorOptionsLabels
import fr.forumhfr.redface2.core.ui.editor.EditorOptionsSheet
import fr.forumhfr.redface2.core.ui.editor.EditorPreview

/** Prepared for the R3 overlay; the source stays mounted while these sheets cover it. */
@Composable
internal fun PrivateMessageReplyChromeSheets(
    adapter: PrivateMessageReplyChromeAdapter,
    autoOpenRecipients: Boolean,
    onReturnToBody: () -> Unit,
) {
    LaunchedEffect(adapter, autoOpenRecipients, adapter.state.recipientsEnabled) {
        adapter.openInitialRecipients(autoOpenRecipients)
    }
    // The sheet must be removed before the body can take focus and restore its captured IME state.
    LaunchedEffect(adapter.returnGeneration) {
        if (adapter.consumeReturn()) adapter.returnToBody(onReturnToBody)
    }
    val source = adapter.state.source
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
