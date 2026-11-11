package fr.forumhfr.redface2.feature.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.forumhfr.redface2.core.ui.editor.EditorCause
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorHostMemory
import fr.forumhfr.redface2.core.ui.editor.EditorHostState
import fr.forumhfr.redface2.core.ui.editor.EditorScaffoldChrome

/** ED-35: activation exposes incomplete headers; canSubmit remains the strict POST guard. */
internal data class TopicFormChromeState(val source: TopicFormState) {
    private val busy = source.isSubmitting || source.submission.blocksBodyMutation
    val unsupportedFirstPost = source.mode == TopicFormMode.EditFirstPost && source.firstPostSubcategoryRefused
    val sendActivatable = source.sendActivatable
    val postAllowed = source.canSubmit && !unsupportedFirstPost
    val categoryId = source.cat // Routing context, never inferred from an edited title or sub-category.
    val scaffold = EditorScaffoldChrome(
        previewVisible = source.isPreviewVisible,
        controlsEnabled = !source.isInitializing && !busy,
        bodyReadOnly = source.isInitializing || source.isAwaitingInitialBody || busy,
        uploading = source.isUploading, uploadProgress = source.uploadProgress, loadingForm = source.isLoadingForm,
    )
    val host = EditorHostState(scaffold, source.submission, sendActivatable, postAllowed)
    val headerEnabled = !busy && (!source.isInitializing || source.isRestoringSession)
    val headerReadOnly = source.isRestoringSession
    val showSubcategory = source.hasSubcategorySelect && !unsupportedFirstPost && source.subcategoryChoices.isNotEmpty()
    val subcategoryEnabled = headerEnabled && !headerReadOnly && !source.isLoadingForm
    val selectedSubcategory = source.subcategoryChoices.firstOrNull {
        it.id != null && it.id == source.selectedSubcat
    }?.label?.takeIf { showSubcategory }
}

internal val TopicFormState.headerError get() = when {
    subject.text.isBlank() -> EditorErrors.missingTitle
    hasSubcategorySelect && !firstPostSubcategoryRefused && (selectedSubcat ?: 0) <= 0 ->
        EditorErrors.missingSubcategory
    else -> null
}

internal enum class TopicFormChromeSurface { Header, Smileys, Options, Picker, Url, Restore }
internal enum class TopicFormHeaderField { Title, Subcategory }

/** VM-owned transient state: survives rotation, never serialized into the ED-81 checkpoint. */
internal class TopicFormChromeMemory {
    val host = EditorHostMemory<TopicFormChromeSurface>()
    val headerOpen get() = host.surface == TopicFormChromeSurface.Header
    var headerField by mutableStateOf(TopicFormHeaderField.Title)
        private set
    private var entryHandled = false
    fun initial(source: TopicFormState) = !entryHandled && !source.isRestoringSession &&
        TopicFormChromeState(source).scaffold.controlsEnabled
    fun finishEntry() { entryHandled = true }
    fun complete(cause: EditorCause) {
        headerField = if (cause == EditorCause.MissingSubcategory) TopicFormHeaderField.Subcategory
            else TopicFormHeaderField.Title
    }
    fun reset() {
        host.reset()
        headerField = TopicFormHeaderField.Title
        entryHandled = false
    }
}
