package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.ui.editor.EditorScaffoldChrome

/** ED-35: the button may expose a missing context, but only the VM can authorize a POST. */
internal data class PostEditorChromeState(val source: PostEditorState) {
    val sendActivatable: Boolean = source.hasLoadedForm && !source.isLoadingForm && !source.isSubmitting &&
        !source.isInitializing && !source.isUploading && !source.submission.blocksBodyMutation &&
        !source.quoteStorageUnavailable && source.pendingQuotes.isEmpty() &&
        (source.bodyText.isNotBlank() || source.mode == PostEditorMode.Reply && source.quotes.isNotEmpty())
    val postAllowed: Boolean = source.canSubmit && source.hasLoadedForm && source.hasAuthenticatedForm
    val scaffold: EditorScaffoldChrome = EditorScaffoldChrome(
        previewVisible = source.isPreviewVisible,
        controlsEnabled = !source.isInitializing && !source.submission.blocksBodyMutation,
        bodyReadOnly = source.isInitializing || source.submission.blocksBodyMutation,
        uploading = source.isUploading,
        uploadProgress = source.uploadProgress,
        loadingForm = source.isLoadingForm,
    )
}
internal enum class PostEditorChromeSurface { Smileys, Options, Url, Picker, Restore, Quotes, PendingQuote }
