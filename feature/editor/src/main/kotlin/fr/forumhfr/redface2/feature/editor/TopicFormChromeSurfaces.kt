package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.ui.editor.EditorHeaderPanel
import fr.forumhfr.redface2.core.ui.editor.EditorOptions
import fr.forumhfr.redface2.core.ui.editor.EditorOptionsLabels
import fr.forumhfr.redface2.core.ui.editor.EditorOptionsSheet
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerSheet
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState

/** Stable overlay site: every panel keeps the live source composed, including its undo/viewport. */
@Composable
internal fun TopicFormChromeSurfaces(
    adapter: TopicFormChromeAdapter,
    bodyState: TextFieldState,
    smileys: SmileyPickerController,
    onReturnToBody: () -> Unit,
    body: @Composable () -> Unit,
) {
    val host = adapter.host
    LaunchedEffect(host.returnGeneration) {
        if (host.consumeReturn()) host.returnToBody(onReturnToBody)
    }
    TopicFormEventDetails(adapter, bodyState, onReturnToBody) {
        TopicFormHeaderPanel(adapter, bodyState, onReturnToBody) {
            // Short title: the offer sentence appears once, in the panel body (gate R1b T3 r1, minor 3).
            EditorHeaderPanel(host.surface == TopicFormChromeSurface.Restore,
                stringResource(R.string.editor_draft_restore_title), bodyState, host::closeSurface,
                { host.returnToBody(onReturnToBody) }, body = body) {
                DraftRestoreChoice(adapter::restoreDraft, adapter::discardDraft,
                    restoreEnabled = adapter.state.scaffold.controlsEnabled &&
                        !adapter.state.source.isLoadingForm && !adapter.state.source.isUploading)
            }
        }
    }
    when (host.surface) {
        TopicFormChromeSurface.Options -> TopicFormOptionsSurface(adapter)
        TopicFormChromeSurface.Url -> if (adapter.state.host.canInsert) {
            ImageUrlDialog(host::closeSurface, adapter::imageUrl)
        }
        TopicFormChromeSurface.Smileys -> if (adapter.state.host.canInsert) TopicFormSmileysSurface(adapter, smileys)
        else -> Unit
    }
}

@Composable
private fun TopicFormOptionsSurface(adapter: TopicFormChromeAdapter) {
    val source = adapter.state.source
    EditorOptionsSheet(adapter.host::closeSurface) {
        val enabled = adapter.state.scaffold.controlsEnabled && !source.isLoadingForm
        EditorOptions(source.signatureEnabled, source.smileyDisabled, source.emailNotificationEnabled, enabled,
            { adapter.option(TopicFormIntent.ToggleSignature(it)) },
            { adapter.option(TopicFormIntent.ToggleSmileyDisabled(it)) },
            { adapter.option(TopicFormIntent.ToggleEmailNotification(it)) },
            EditorOptionsLabels(stringResource(R.string.editor_options_title),
                stringResource(R.string.editor_option_signature),
                stringResource(R.string.editor_option_smiley_disabled),
                stringResource(R.string.editor_option_email_notification))) {
            MessageIconPicker(source.msgIcon, enabled, { adapter.option(TopicFormIntent.MsgIconSelected(it)) },
                alwaysExpanded = true)
        }
    }
}

@Composable
private fun TopicFormSmileysSurface(adapter: TopicFormChromeAdapter, smileys: SmileyPickerController) {
    val picker = smileys.state.collectAsStateWithLifecycle().value as? SmileyPickerState.Open
    if (picker != null) SmileyPickerSheet(picker, { smileys.dismiss(); adapter.host.closeSurface() },
        smileys::onQueryChanged, adapter::smiley)
}
