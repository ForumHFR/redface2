package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.ui.R as UiR
import fr.forumhfr.redface2.core.ui.editor.EditorErrorAction
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.EditorEventDetails
import fr.forumhfr.redface2.core.ui.editor.EditorHeaderPanel
import fr.forumhfr.redface2.core.ui.editor.EditorOptions
import fr.forumhfr.redface2.core.ui.editor.EditorOptionsLabels
import fr.forumhfr.redface2.core.ui.editor.EditorOptionsSheet
import fr.forumhfr.redface2.core.ui.editor.EventKey
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerSheet
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState

/** Keep mounted inside EditorScaffold.overlay, including while the field is covered. */
@Composable
internal fun PostEditorChromeSurfaces(
    adapter: PostEditorChromeAdapter,
    bodyState: TextFieldState,
    smileys: SmileyPickerController,
    onReturnToBody: () -> Unit,
    body: @Composable () -> Unit,
) {
    // Run after the overlay/dialog is removed, when the body can accept focus again. Exactly once.
    LaunchedEffect(adapter.returnGeneration) {
        if (adapter.consumeReturn()) adapter.returnToBody(onReturnToBody)
    }
    val source = adapter.state.source
    EditorEventDetails(adapter.detail, bodyState, adapter::closeDetails, adapter::reload,
        { adapter.returnToBody(onReturnToBody) },
        onIgnoreQuote = if (source.canIgnorePendingQuote && !source.hasDormantQuote) adapter::ignoreQuote else null,
        onDismiss = adapter::dismissDetails,
        body = {
            PostEditorHeaderSurface(adapter, source, bodyState, onReturnToBody, body)
        }) { event ->
        if (source.hasDormantQuote) QuoteDecisions(adapter, event.key)
        val error = (event.content as? EditorEventContent.Problem)?.error
        // Keep the uncertainty detail intact while offering the missing edit form's GET (#1574).
        if (source.isAwaitingInitialBody && error != null && error.action != EditorErrorAction.Reload) {
            TextButton(onClick = { adapter.reload(event.key) }, enabled = !source.isLoadingForm) {
                Text(stringResource(UiR.string.editor_action_reload))
            }
        }
    }
    when (adapter.surface) {
        PostEditorChromeSurface.Options -> PostEditorOptionsSurface(adapter, source)
        PostEditorChromeSurface.Url -> if (adapter.state.canInsert) {
            ImageUrlDialog(adapter::closeSurface, adapter::imageUrl)
        }
        PostEditorChromeSurface.Smileys -> if (adapter.state.canInsert) PostEditorSmileysSurface(adapter, smileys)
        else -> Unit
    }
}

@Composable
private fun PostEditorHeaderSurface(
    adapter: PostEditorChromeAdapter,
    source: PostEditorState,
    bodyState: TextFieldState,
    onReturnToBody: () -> Unit,
    body: @Composable () -> Unit,
) {
    val pending = adapter.surface == PostEditorChromeSurface.PendingQuote
    // Short title: the offer sentence appears once, in the panel body (gate R1b T3 r1, minor 3).
    val title = if (pending) R.string.editor_quote_pending else R.string.editor_draft_restore_title
    EditorHeaderPanel(adapter.surface == PostEditorChromeSurface.Restore || pending,
        stringResource(title), bodyState, adapter::closeSurface,
        { adapter.returnToBody(onReturnToBody) },
        body = { PostEditorQuotePanel(adapter, bodyState, onReturnToBody, body) }) {
        if (!pending && source.restorableDraft != null && source.pendingQuotes.isNotEmpty()) {
            Text(stringResource(R.string.editor_quote_pending))
        }
        if (pending) QuoteDecisions(adapter) else DraftRestoreChoice(
            adapter::restoreDraft, adapter::discardDraft,
            restoreEnabled = source.canRestoreDraft && !source.isUploading &&
                adapter.state.scaffold.controlsEnabled)
    }
}

@Composable
private fun PostEditorOptionsSurface(adapter: PostEditorChromeAdapter, source: PostEditorState) {
    EditorOptionsSheet(adapter::closeSurface) {
        val enabled = adapter.state.scaffold.controlsEnabled && !source.isLoadingForm
        EditorOptions(source.signatureEnabled, source.smileyDisabled, source.emailNotificationEnabled, enabled,
            adapter::signature, adapter::smileyDisabled, adapter::notification,
            EditorOptionsLabels(stringResource(R.string.editor_options_title),
                stringResource(R.string.editor_option_signature),
                stringResource(R.string.editor_option_smiley_disabled),
                stringResource(R.string.editor_option_email_notification))) {
            MessageIconPicker(source.msgIcon, enabled, adapter::tone, alwaysExpanded = true)
        }
    }
}

@Composable
private fun PostEditorSmileysSurface(adapter: PostEditorChromeAdapter, smileys: SmileyPickerController) {
    val picker = smileys.state.collectAsStateWithLifecycle().value as? SmileyPickerState.Open
    if (picker != null) SmileyPickerSheet(picker, { smileys.dismiss(); adapter.closeSurface() },
        smileys::onQueryChanged, adapter::smiley)
}

@Composable
private fun QuoteDecisions(adapter: PostEditorChromeAdapter, key: EventKey? = null) {
    val source = adapter.state.source
    TextButton(onClick = { adapter.resumeQuote(key) }, enabled = source.hasDormantQuote && !source.isLoadingForm) {
        Text(stringResource(R.string.editor_action_resume_quote))
    }
    TextButton(onClick = { adapter.ignoreQuote(key) }, enabled = source.canIgnorePendingQuote) {
        Text(stringResource(UiR.string.editor_action_ignore_quote))
    }
}
