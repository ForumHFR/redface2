package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.ui.editor.EditorEventDetails
import fr.forumhfr.redface2.core.ui.editor.EditorHeaderPanel
import fr.forumhfr.redface2.core.ui.editor.EditorOptions
import fr.forumhfr.redface2.core.ui.editor.EditorOptionsLabels
import fr.forumhfr.redface2.core.ui.editor.EditorOptionsSheet
import fr.forumhfr.redface2.core.ui.editor.EditorPreview
import fr.forumhfr.redface2.core.ui.editor.EditorRecipientField
import fr.forumhfr.redface2.core.ui.editor.EditorRecipientInput
import fr.forumhfr.redface2.core.ui.editor.EditorSubjectField
import fr.forumhfr.redface2.core.ui.editor.EditorSubjectInput
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerSheet
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import fr.forumhfr.redface2.core.ui.post.PostMediaDiskCachePolicy

/** EditorScaffold.overlay keeps the body in composition with its original viewport (C7). */
@Composable
@Suppress("LongParameterList") // Live input owners and overlay slots, without private-text savers.
internal fun PrivateMessageComposeChromeSurfaces(
    adapter: PrivateMessageComposeChromeAdapter,
    bodyState: TextFieldState,
    recipients: EditorRecipientInput,
    subject: EditorSubjectInput,
    smileys: SmileyPickerController,
    onReturnToBody: () -> Unit,
    body: @Composable () -> Unit,
) {
    val source = adapter.state.source
    LaunchedEffect(source.isInitializing, source.isLoadingForm, adapter.state.scaffold.controlsEnabled,
        adapter.waitingForInformation) {
        adapter.openInitialHeader()
    }
    LaunchedEffect(adapter.returnGeneration) {
        if (adapter.consumeReturn()) adapter.returnToBody(onReturnToBody)
    }
    EditorEventDetails(adapter.detail, bodyState, adapter::closeDetails, adapter::reload,
        { adapter.returnToBody(onReturnToBody) }, body = {
            ComposeHeader(adapter, bodyState, recipients, subject, onReturnToBody, body)
        })
    when (adapter.surface) {
        PrivateMessageComposeChromeSurface.Options -> EditorOptionsSheet(adapter::closeSurface) {
            EditorOptions(source.signatureEnabled, source.smileyDisabled, source.emailNotificationEnabled,
                adapter.state.optionsEnabled, adapter::signature, adapter::smileyDisabled, adapter::notification,
                EditorOptionsLabels(stringResource(R.string.messages_reply_options_title),
                    stringResource(R.string.messages_reply_option_signature),
                    stringResource(R.string.messages_reply_option_smiley_disabled),
                    stringResource(R.string.messages_reply_option_email_notification)))
        }
        PrivateMessageComposeChromeSurface.Smileys -> {
            val picker = smileys.state.collectAsStateWithLifecycle().value as? SmileyPickerState.Open
            if (picker != null) SmileyPickerSheet(picker, { smileys.dismiss(); adapter.closeSurface() },
                smileys::onQueryChanged, adapter::smiley)
        }
        // The host invokes the Activity Result launcher on opening; adapter.picker closes on every result.
        PrivateMessageComposeChromeSurface.Picker -> Unit
        PrivateMessageComposeChromeSurface.Header, PrivateMessageComposeChromeSurface.Restore, null -> Unit
    }
}

@Composable
@Suppress("LongParameterList") // Header focus and body return are separate responsibilities.
private fun ComposeHeader(
    adapter: PrivateMessageComposeChromeAdapter,
    bodyState: TextFieldState,
    recipients: EditorRecipientInput,
    subject: EditorSubjectInput,
    onReturnToBody: () -> Unit,
    body: @Composable () -> Unit,
) {
    val recipientFocus = remember { FocusRequester() }
    val subjectFocus = remember { FocusRequester() }
    val restore = adapter.surface == PrivateMessageComposeChromeSurface.Restore
    val header = adapter.surface == PrivateMessageComposeChromeSurface.Header
    val focus = if (adapter.memory.field == PrivateMessageComposeHeaderField.Subject) subjectFocus else recipientFocus
    EditorHeaderPanel(header || restore, stringResource(if (restore) R.string.messages_compose_restore_title
        else R.string.messages_compose_header), bodyState, adapter::closeSurface,
        { adapter.returnToBody(onReturnToBody) }, initialFocusRequester = focus.takeIf { header }, body = body) {
        // The panel has the full height: never ellipsize the offer at 1.3 (gate C4 m8).
        if (restore) MessageDraftRestoreBanner(adapter::restoreDraft, adapter::discardDraft,
            restoreEnabled = adapter.state.restoreEnabled, messageMaxLines = Int.MAX_VALUE)
        else {
            EditorRecipientField(adapter.state.source.recipientState, recipients, { subjectFocus.requestFocus() },
                Modifier.focusRequester(recipientFocus), enabled = adapter.state.scaffold.controlsEnabled)
            EditorSubjectField(adapter.state.source.subject, subject,
                Modifier.fillMaxWidth().focusRequester(subjectFocus), enabled = adapter.state.scaffold.controlsEnabled)
        }
    }
}

@Composable
internal fun PrivateMessageComposeChromePreview(
    adapter: PrivateMessageComposeChromeAdapter,
    bodyState: TextFieldState,
    bodyFocus: FocusRequester,
    body: @Composable () -> Unit,
) {
    EditorPreview(adapter.state.source.isPreviewVisible, adapter.state.source.preview, bodyState, bodyFocus,
        PostMediaDiskCachePolicy.DISABLED, stringResource(R.string.messages_reply_preview_show), body = body)
}
