package fr.forumhfr.redface2.feature.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
import fr.forumhfr.redface2.core.ui.editor.EditorBarState
import fr.forumhfr.redface2.core.ui.editor.EditorEvent
import fr.forumhfr.redface2.core.ui.editor.EditorScaffoldChrome
import fr.forumhfr.redface2.core.ui.editor.EditorSurfaceFocusMemory

/** ED-35: the button may expose a missing context, but only the VM can authorize a POST. */
internal data class PostEditorChromeState(val source: PostEditorState) {
    val sendActivatable: Boolean = source.hasLoadedForm && !source.isLoadingForm && !source.isSubmitting &&
        !source.isInitializing && !source.isAwaitingInitialBody && !source.isUploading &&
        !source.submission.blocksBodyMutation &&
        !source.quoteStorageUnavailable && source.pendingQuotes.isEmpty() &&
        (source.bodyText.isNotBlank() || source.mode == PostEditorMode.Reply && source.quotes.isNotEmpty())
    val postAllowed: Boolean = source.canSubmit && source.hasLoadedForm && source.hasAuthenticatedForm
    val scaffold: EditorScaffoldChrome = EditorScaffoldChrome(
        previewVisible = source.isPreviewVisible,
        controlsEnabled = !source.isInitializing && !source.submission.blocksBodyMutation,
        bodyReadOnly = source.isInitializing || source.isAwaitingInitialBody || source.submission.blocksBodyMutation,
        uploading = source.isUploading,
        uploadProgress = source.uploadProgress,
        loadingForm = source.isLoadingForm,
    )
    val canInsert: Boolean = !scaffold.bodyReadOnly && !source.isUploading
}
internal enum class PostEditorChromeSurface { Smileys, Options, Url, Picker, Restore, Quotes, PendingQuote }

/** ED-86: retained by the VM for rotation, never serialized into the process-death checkpoint. */
internal class PostEditorChromeMemory {
    val focus = EditorSurfaceFocusMemory()
    var surface by mutableStateOf<PostEditorChromeSurface?>(null)
    var detail by mutableStateOf<EditorEvent?>(null)
    var bar by mutableStateOf(EditorBarState())
    var selection = TextRange.Zero
    var textBeforeSurface = ""
    var returnGeneration by mutableIntStateOf(0)
    var returnedGeneration = 0
    fun reset() {
        surface = null
        detail = null
        bar = EditorBarState()
        selection = TextRange.Zero
        textBeforeSurface = ""
        returnGeneration = 0
        returnedGeneration = 0
        focus.imeBeforeSurface = false
        focus.autoFocusConsumed = false
    }
}
