package fr.forumhfr.redface2.feature.messages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import fr.forumhfr.redface2.core.ui.editor.EditorCause
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorHostMemory
import fr.forumhfr.redface2.core.ui.editor.EditorHostState
import fr.forumhfr.redface2.core.ui.editor.EditorScaffoldChrome

/** C3 projection only; the legacy screen remains mounted until C4. */
internal data class PrivateMessageComposeChromeState(val source: PrivateMessageComposeUiState) {
    private val busy = source.isSubmitting || source.submission.blocksBodyMutation
    val scaffold = EditorScaffoldChrome(
        previewVisible = source.isPreviewVisible, controlsEnabled = !source.isInitializing && !busy,
        bodyReadOnly = source.isInitializing || busy, uploading = source.isUploading,
        uploadProgress = source.uploadProgress, loadingForm = source.isLoadingForm,
    )
    val host = EditorHostState(scaffold, source.submission, source.sendActivatable, source.canSubmit)
    val optionsEnabled = scaffold.controlsEnabled && !source.isLoadingForm
    val restoreEnabled = scaffold.controlsEnabled && !source.isUploading && !source.isLoadingForm
}

/** D4: an uncommitted pseudo wins over missing chips; no implicit comma is ever inserted. */
internal val PrivateMessageComposeUiState.headerError get() = when {
    recipientState.buffer.isNotBlank() -> EditorErrors.incompleteRecipient
    recipientState.chips.none(String::isNotBlank) -> EditorErrors.missingRecipients
    subject.isBlank() -> EditorErrors.missingSubject
    else -> null
}

internal enum class PrivateMessageComposeChromeSurface { Header, Restore, Options, Smileys, Picker }
internal enum class PrivateMessageComposeHeaderField { Recipients, Subject }

/** Only the automatic-entry latch is saved; panels, focus targets and all private text stay in memory. */
internal class PrivateMessageComposeChromeMemory(private val saved: SavedStateHandle, recreated: Boolean = false) {
    val host = EditorHostMemory<PrivateMessageComposeChromeSurface>()
    private var entryHandled = recreated || saved.get<Boolean>(INITIAL_HEADER_HANDLED) == true
    var field by mutableStateOf(PrivateMessageComposeHeaderField.Recipients)
        private set
    fun claimInitialEntry(): Boolean {
        val initial = !entryHandled
        entryHandled = true
        saved[INITIAL_HEADER_HANDLED] = true
        return initial
    }
    fun complete(cause: EditorCause) {
        field = if (cause == EditorCause.MissingSubject) PrivateMessageComposeHeaderField.Subject
            else PrivateMessageComposeHeaderField.Recipients
    }
    fun reset() {
        host.reset()
        field = PrivateMessageComposeHeaderField.Recipients
    }
    companion object {
        const val INITIAL_HEADER_HANDLED = "messageComposeEditor.initialHeaderHandled"
    }
}
