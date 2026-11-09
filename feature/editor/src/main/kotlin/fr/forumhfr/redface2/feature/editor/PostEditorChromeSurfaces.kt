package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.ui.editor.EditorEventDetails
import fr.forumhfr.redface2.core.ui.editor.EditorHeaderPanel
import fr.forumhfr.redface2.core.ui.editor.EditorOptionsSheet
import fr.forumhfr.redface2.core.ui.editor.EventKey
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerSheet
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import fr.forumhfr.redface2.core.ui.R as UiR

/** Keep mounted inside EditorScaffold.overlay; PR 3 supplies Quotes and the preview separately. */
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
        if (adapter.returnGeneration > 0) adapter.returnToBody(onReturnToBody)
    }
    val source = adapter.state.source
    EditorEventDetails(adapter.detail, bodyState, adapter::closeDetails, adapter::reload,
        { adapter.returnToBody(onReturnToBody) },
        onIgnoreQuote = if (source.canIgnorePendingQuote && !source.hasDormantQuote) adapter::ignoreQuote else null,
        body = {
            PostEditorHeaderSurface(adapter, source, bodyState, onReturnToBody, body)
        }) { event ->
        if (source.hasDormantQuote) QuoteDecisions(adapter, event.key)
    }
    when (adapter.surface) {
        PostEditorChromeSurface.Options -> PostEditorOptionsSurface(adapter, source)
        PostEditorChromeSurface.Url -> ImageUrlDialog(adapter::closeSurface, adapter::imageUrl)
        PostEditorChromeSurface.Smileys -> PostEditorSmileysSurface(adapter, smileys)
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
    val title = if (pending) R.string.editor_quote_pending else R.string.editor_draft_restore_message
    EditorHeaderPanel(adapter.surface == PostEditorChromeSurface.Restore || pending,
        stringResource(title), bodyState, adapter::closeSurface,
        { adapter.returnToBody(onReturnToBody) },
        body = body) {
        if (pending) QuoteDecisions(adapter) else DraftRestoreBanner(
            adapter::restoreDraft, adapter::discardDraft,
            restoreEnabled = source.canRestoreDraft && !source.isUploading &&
                adapter.state.scaffold.controlsEnabled)
    }
}

@Composable
private fun PostEditorOptionsSurface(adapter: PostEditorChromeAdapter, source: PostEditorState) {
    EditorOptionsSheet(adapter::closeSurface) {
        PostEditorOptions(source.signatureEnabled, source.smileyDisabled, source.emailNotificationEnabled,
            source.msgIcon, adapter.state.scaffold.controlsEnabled && !source.isLoadingForm,
            adapter::signature, adapter::smileyDisabled, adapter::notification, adapter::tone, expandedTone = true)
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
