package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.ui.editor.EditorBarShortcut
import fr.forumhfr.redface2.core.ui.editor.EditorHostState
import fr.forumhfr.redface2.core.ui.editor.EditorScaffoldChrome
import fr.forumhfr.redface2.core.ui.post.PostMediaDiskCachePolicy
import fr.forumhfr.redface2.core.model.editor.EditorShortcutSurface

/** ED-35: the button may expose a missing context, but only the VM can authorize a POST. */
internal data class PostEditorChromeState(val source: PostEditorState) {
    val sendActivatable: Boolean = source.hasLoadedForm && !source.isLoadingForm && !source.isSubmitting &&
        !source.isInitializing && !source.isAwaitingInitialBody && !source.isUploading &&
        !source.submission.blocksBodyMutation &&
        !source.quoteStorageUnavailable && source.pendingQuotes.isEmpty() &&
        (source.bodyText.isNotBlank() || source.mode == PostEditorMode.Reply && source.quotes.isNotEmpty())
    val postAllowed: Boolean = source.canSubmit && source.hasLoadedForm && source.hasAuthenticatedForm
    val scaffold: EditorScaffoldChrome = EditorScaffoldChrome(
        shortcut = source.editorShortcut.takeIf { it.isAvailableOn(EditorShortcutSurface.POST) }
            ?.let { EditorBarShortcut(it, EditorShortcutSurface.POST) },
        previewVisible = source.isPreviewVisible,
        controlsEnabled = !source.isInitializing && !source.submission.blocksBodyMutation,
        bodyReadOnly = source.isInitializing || source.isAwaitingInitialBody || source.submission.blocksBodyMutation,
        uploading = source.isUploading,
        uploadProgress = source.uploadProgress,
        loadingForm = source.isLoadingForm,
    )
    val host: EditorHostState = EditorHostState(scaffold, source.submission, sendActivatable, postAllowed)
    val canInsert: Boolean get() = host.canInsert
    val restoreEnabled = scaffold.controlsEnabled && source.canRestoreDraft && !source.isUploading &&
        source.draftDecisionsEnabled
    val discardEnabled = scaffold.controlsEnabled && source.draftDecisionsEnabled
    /** ADR-018 § 9: media referenced by a private message never reach Coil's disk cache. */
    val mediaDiskCachePolicy: PostMediaDiskCachePolicy = when (source.target) {
        PostEditorTarget.Public -> PostMediaDiskCachePolicy.ENABLED
        is PostEditorTarget.PrivateMessage -> PostMediaDiskCachePolicy.DISABLED
    }
}
internal enum class PostEditorChromeSurface { Smileys, Options, Url, Picker, Restore, Quotes, PendingQuote, Subject }
