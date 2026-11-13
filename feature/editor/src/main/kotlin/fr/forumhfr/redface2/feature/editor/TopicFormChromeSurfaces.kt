package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.ui.editor.EditorDraftOfferSheet
import fr.forumhfr.redface2.core.ui.editor.EditorOptions
import fr.forumhfr.redface2.core.ui.editor.EditorOptionsLabels
import fr.forumhfr.redface2.core.ui.editor.EditorOptionsSheet
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerSheet
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState

/**
 * Stable overlay site: panels and the offer sheet keep the live source composed, including its undo/viewport.
 */
@Composable
@Suppress("LongParameterList") // Overlay slots plus the host's compact-height signal.
internal fun TopicFormChromeSurfaces(
    adapter: TopicFormChromeAdapter,
    bodyState: TextFieldState,
    smileys: SmileyPickerController,
    onReturnToBody: () -> Unit,
    @Suppress("UNUSED_PARAMETER") compactHeight: Boolean, // Retain the host's overlay call contract.
    body: @Composable () -> Unit,
) {
    val host = adapter.host
    LaunchedEffect(host.returnGeneration) {
        if (host.consumeReturn()) host.returnToBody(onReturnToBody)
    }
    TopicFormEventDetails(adapter, bodyState, onReturnToBody) {
        TopicFormHeaderPanel(adapter, bodyState, onReturnToBody, body)
    }
    val source = adapter.state.source
    EditorDraftOfferSheet(host.draftOfferOpen, source.restorableDraft != null || source.restorableSubject != null,
        host::closeDraftOffer, adapter::restoreDraft, adapter::discardDraft, offers = source.draftOffers,
        restoreEnabled = adapter.state.scaffold.controlsEnabled && !source.isLoadingForm && !source.isUploading,
        discardEnabled = adapter.state.scaffold.controlsEnabled)
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
