package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.ui.editor.EditorHostActions

@Suppress("LongParameterList") // Independent business ports, matching the existing editor host.
internal data class PrivateMessageComposeChromeActions(
    val host: EditorHostActions,
    val signature: (Boolean) -> Unit,
    val smileyDisabled: (Boolean) -> Unit,
    val notification: (Boolean) -> Unit,
    val smiley: (String) -> Unit,
    val picker: (ImagePickerEvent) -> Unit,
    val restore: () -> Unit,
    val discard: () -> Unit,
    val reload: () -> Unit,
    /** ED-40/121: one decision per listed version; a host with a single offer keeps the defaults. */
    val restoreOffer: (String) -> Unit = { restore() },
    val discardOffer: (String) -> Unit = { discard() },
)

internal fun PrivateMessageComposeViewModel.chromeActions() = PrivateMessageComposeChromeActions(
    host = EditorHostActions(::onSubmit, ::onSubmitConfirmed, ::onSubmitConfirmationDismissed,
        ::onToolbarAction, ::onTogglePreview, ::onCloseRequested, onAcknowledgeUncertain = ::onErrorDismissed,
        onAcknowledgeLocal = ::onDraftWriteFailureDismissed),
    signature = ::onToggleSignature, smileyDisabled = ::onToggleSmileyDisabled,
    notification = ::onToggleEmailNotification, smiley = ::onSmileySelected, picker = ::onImagePickerEvent,
    restore = { onDraftRestoreRequested() }, discard = { onDraftDiscardRequested() }, reload = ::retryFormLoad,
    restoreOffer = { id -> onDraftRestoreRequested(id) }, discardOffer = { id -> onDraftDiscardRequested(id) },
)
