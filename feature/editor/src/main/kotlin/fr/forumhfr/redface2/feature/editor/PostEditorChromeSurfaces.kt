package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.ui.R as UiR
import fr.forumhfr.redface2.core.ui.editor.EditorDraftOfferSheet
import fr.forumhfr.redface2.core.ui.editor.EditorErrorAction
import fr.forumhfr.redface2.core.ui.editor.EditorEvent
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
@Suppress("LongParameterList") // Host ports plus the editor-settings exit.
@Composable
internal fun PostEditorChromeSurfaces(
    adapter: PostEditorChromeAdapter,
    bodyState: TextFieldState,
    smileys: SmileyPickerController,
    onReturnToBody: () -> Unit,
    body: @Composable () -> Unit,
    onOpenEditorSettings: () -> Unit = {},
) {
    // Run after the overlay/dialog is removed, when the body can accept focus again. Exactly once.
    LaunchedEffect(adapter.returnGeneration) {
        if (adapter.consumeReturn()) adapter.returnToBody(onReturnToBody)
    }
    val source = adapter.state.source
    EditorEventDetails(adapter.detail,
        bodyState, adapter::closeDetails, adapter::reload,
        { adapter.returnToBody(onReturnToBody) },
        onIgnoreQuote = if (source.canIgnorePendingQuote && !source.hasDormantQuote) adapter::ignoreQuote else null,
        onDismiss = adapter::dismissDetails,
        body = {
            PostEditorHeaderSurface(adapter, bodyState, onReturnToBody, body)
        }) { event -> PostEditorEventRemedies(adapter, event) }
    PostEditorDraftOffer(adapter)
    when (adapter.surface) {
        PostEditorChromeSurface.Options -> PostEditorOptionsSurface(adapter, source, onOpenEditorSettings)
        PostEditorChromeSurface.Url -> if (adapter.state.canInsert) {
            ImageUrlDialog(adapter::closeSurface, adapter::imageUrl)
        }
        PostEditorChromeSurface.Smileys -> if (adapter.state.canInsert) PostEditorSmileysSurface(adapter, smileys)
        else -> Unit
    }
}

@Composable
private fun PostEditorEventRemedies(adapter: PostEditorChromeAdapter, event: EditorEvent) {
    val source = adapter.state.source
    if (source.hasDormantQuote) QuoteDecisions(adapter, event.key)
    val error = (event.content as? EditorEventContent.Problem)?.error
    // Host recovery also covers a pending quote's CAS; it must not replay the insertion.
    if (adapter.canReload(event.key) && error?.action != EditorErrorAction.Reload) {
        TextButton(onClick = { adapter.reload(event.key) }, enabled = !source.isLoadingForm) {
            Text(stringResource(UiR.string.editor_action_reload))
        }
    }
}

@Composable
private fun PostEditorDraftOffer(adapter: PostEditorChromeAdapter) {
    val source = adapter.state.source
    EditorDraftOfferSheet(adapter.draftOfferOpen, source.restorableDraft != null, adapter::closeDraftOffer,
        adapter::restoreDraft, adapter::discardDraft,
        offers = source.draftOffers, onRestoreOffer = adapter::restoreOffer, onDiscardOffer = adapter::discardOffer,
        restoreEnabled = source.canRestoreDraft && !source.isUploading && adapter.state.scaffold.controlsEnabled,
        discardEnabled = adapter.state.scaffold.controlsEnabled)
}

@Composable
private fun PostEditorHeaderSurface(
    adapter: PostEditorChromeAdapter,
    bodyState: TextFieldState,
    onReturnToBody: () -> Unit,
    body: @Composable () -> Unit,
) {
    EditorHeaderPanel(adapter.surface == PostEditorChromeSurface.PendingQuote,
        stringResource(R.string.editor_quote_pending), bodyState, adapter::closeSurface,
        { adapter.returnToBody(onReturnToBody) },
        body = { PostEditorQuotePanel(adapter, bodyState, onReturnToBody, body) }) {
        QuoteDecisions(adapter)
    }
}

@Composable
private fun PostEditorOptionsSurface(
    adapter: PostEditorChromeAdapter,
    source: PostEditorState,
    onOpenEditorSettings: () -> Unit,
) {
    EditorOptionsSheet(adapter::closeSurface, adapter.optionsFormats) {
        val enabled = adapter.state.scaffold.controlsEnabled && !source.isLoadingForm
        EditorOptions(source.signatureEnabled, source.smileyDisabled, source.emailNotificationEnabled, enabled,
            adapter::signature, adapter::smileyDisabled, adapter::notification,
            EditorOptionsLabels(stringResource(R.string.editor_options_title),
                stringResource(R.string.editor_option_signature),
                stringResource(R.string.editor_option_smiley_disabled),
                stringResource(R.string.editor_option_email_notification)),
            onOpenSettings = { adapter.openSettings(onOpenEditorSettings) },
            settingsEnabled = !source.isUploading,
            settingsShortcut = source.editorShortcut) {
            MessageIconPicker(source.msgIcon, enabled, adapter::tone)
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
    if (source.hasDormantQuote) {
        TextButton(onClick = { adapter.resumeQuote(key) }, enabled = !source.isLoadingForm) {
            Text(stringResource(UiR.string.editor_action_resume_quote))
        }
    } else if (adapter.hasPendingQuoteRecovery) {
        TextButton(onClick = adapter::reloadPendingQuote, enabled = !source.isLoadingForm) {
            Text(stringResource(UiR.string.editor_action_reload))
        }
    }
    TextButton(onClick = { adapter.ignoreQuote(key) }, enabled = source.canIgnorePendingQuote) {
        Text(stringResource(UiR.string.editor_action_ignore_quote))
    }
}
