package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.ui.editor.EditorHostMemory
import fr.forumhfr.redface2.core.ui.editor.EditorHostState
import fr.forumhfr.redface2.core.ui.editor.EditorScaffoldChrome
import fr.forumhfr.redface2.core.ui.post.PostMediaDiskCachePolicy

/** Reply policy projected into the common chrome, without a header or quote panel. */
internal data class PrivateMessageReplyChromeState(val source: PrivateMessageReplyUiState) {
    private val busy = source.isSubmitting || source.submission.blocksBodyMutation
    val sendActivatable = source.canSubmit
    val postAllowed = source.canSubmit
    val context = source.conversationTitle.takeIf { source.formAvailable && !source.isLoadingForm }.orEmpty()
    val scaffold = EditorScaffoldChrome(
        previewVisible = source.isPreviewVisible,
        controlsEnabled = !source.isInitializing && !busy,
        bodyReadOnly = source.isInitializing || busy,
        uploading = source.isUploading, uploadProgress = source.uploadProgress, loadingForm = source.isLoadingForm,
    )
    val host = EditorHostState(scaffold, source.submission, sendActivatable, postAllowed)
    val canInsert get() = host.canInsert
    val optionsEnabled = scaffold.controlsEnabled && !source.isLoadingForm
    val recipientsEnabled = optionsEnabled && source.formAvailable && source.canManageRecipients
    val restoreEnabled = scaffold.controlsEnabled && !source.isLoadingForm && !source.isUploading
    val mediaDiskCachePolicy = PostMediaDiskCachePolicy.DISABLED
}

internal enum class PrivateMessageReplyChromeSurface { Smileys, Options, Picker, Restore, Recipients }

/** VM-owned transient state: rotation retains the open manager and its one-shot entry latch. */
internal class PrivateMessageReplyChromeMemory {
    val host = EditorHostMemory<PrivateMessageReplyChromeSurface>()
    var recipientEntryHandled = false
        private set
    fun finishRecipientEntry() { recipientEntryHandled = true }
    fun reset() {
        host.reset()
        recipientEntryHandled = false
    }
}
