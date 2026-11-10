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
    EditorHostActions(::onSubmit, ::onSubmitConfirmed, ::onSubmitConfirmationDismissed,
        ::onToolbarAction, ::onTogglePreview, ::onCloseRequested),
    ::onToggleSignature, ::onToggleSmileyDisabled, ::onToggleEmailNotification, ::onSmileySelected,
    ::onImagePickerEvent, ::onDraftRestoreRequested, ::onDraftDiscardRequested, ::onAddRecipient, ::onRemoveRecipient,
    ::retryFormLoad,
)
