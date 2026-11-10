package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.ui.editor.EditorHostActions

/** Existing reply methods remain the business boundary; no new submission or membership path. */
@Suppress("LongParameterList") // Each callback is an independent editor action.
internal data class PrivateMessageReplyChromeActions(
    val host: EditorHostActions,
    val signature: (Boolean) -> Unit,
    val smileyDisabled: (Boolean) -> Unit,
    val notification: (Boolean) -> Unit,
    val smiley: (String) -> Unit,
    val picker: (ImagePickerEvent) -> Unit,
    val restoreDraft: () -> Unit,
    val discardDraft: () -> Unit,
    val addRecipient: (String) -> Unit,
    val removeRecipient: (String) -> Unit,
    val reloadForm: () -> Unit,
)

internal fun PrivateMessageReplyViewModel.chromeActions() = PrivateMessageReplyChromeActions(
    host = EditorHostActions(onArm = ::onSubmit, onConfirm = ::onSubmitConfirmed,
        onDisarm = ::onSubmitConfirmationDismissed, onFormat = ::onToolbarAction,
        onPreview = ::onTogglePreview, onClose = ::onCloseRequested,
        // ED-45/170: the host calls it only for the explicit Masquer of the bound uncertain delivery.
        onAcknowledgeUncertain = ::onErrorDismissed),
    signature = ::onToggleSignature,
    smileyDisabled = ::onToggleSmileyDisabled,
    notification = ::onToggleEmailNotification,
    smiley = ::onSmileySelected,
    picker = ::onImagePickerEvent,
    restoreDraft = ::onDraftRestoreRequested,
    discardDraft = ::onDraftDiscardRequested,
    addRecipient = ::onAddRecipient,
    removeRecipient = ::onRemoveRecipient,
    reloadForm = ::retryFormLoad,
)
