package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import fr.forumhfr.redface2.core.ui.R as UiR
import fr.forumhfr.redface2.core.ui.editor.EditorAppBarStatus
import fr.forumhfr.redface2.core.ui.editor.EditorBackAction
import fr.forumhfr.redface2.core.ui.editor.EditorBackState
import fr.forumhfr.redface2.core.ui.editor.EditorCause
import fr.forumhfr.redface2.core.ui.editor.EditorErrorAction
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.EditorHost
import fr.forumhfr.redface2.core.ui.editor.EditorHostActions
import fr.forumhfr.redface2.core.ui.editor.EditorHostSurfaces
import fr.forumhfr.redface2.core.ui.editor.EventKey

/** T2 projection only: TopicFormScreen switches to this host in T3. */
@Suppress("LongParameterList") // Independent ports, matching the post adapter without depending on a VM.
internal class TopicFormChromeAdapter(
    private val source: () -> TopicFormState,
    body: TextFieldState,
    private val events: TopicFormEvents,
    private val onIntent: (TopicFormIntent) -> Unit,
    launchPicker: () -> Unit,
    openSmileys: () -> Unit,
    val memory: TopicFormChromeMemory = TopicFormChromeMemory(),
) {
    val state get() = TopicFormChromeState(source())
    // The completion port owns the header, including its focus target and return to the body.
    // It opens directly after acknowledgement, without consuming a second gesture on a hidden information.
    val host = EditorHost(
        { state.host }, body, events,
        EditorHostActions(
            { onIntent(TopicFormIntent.SubmitClicked(it)) },
            { onIntent(TopicFormIntent.SubmitConfirmed(it)) },
            { onIntent(TopicFormIntent.SubmitConfirmationDismissed) },
            { onIntent(TopicFormIntent.ToolbarActionClicked(it)) },
            { onIntent(TopicFormIntent.TogglePreview) }, { onIntent(TopicFormIntent.CloseRequested) },
            onComplete = ::completeHeader,
        ),
        EditorHostSurfaces(TopicFormChromeSurface.Smileys, TopicFormChromeSurface.Options,
            TopicFormChromeSurface.Picker, imageUrl = TopicFormChromeSurface.Url,
            header = TopicFormChromeSurface.Header,
            returnsToBody = { it !in setOf(TopicFormChromeSurface.Header, TopicFormChromeSurface.Restore) }),
        launchPicker, openSmileys, memory.host,
    )
    private fun completeHeader(cause: EditorCause) {
        memory.complete(cause)
        host.openHeaderForCompletion()
    }
    fun openInitialHeader() {
        val source = source()
        if (!memory.initial(source)) return
        if (source.mode == TopicFormMode.New && source.subject.text.isBlank()) {
            completeHeader(EditorCause.MissingTitle)
            if (memory.headerOpen) memory.finishEntry()
        } else memory.finishEntry()
    }
    fun openHeader() = host.open(TopicFormChromeSurface.Header) { memory.complete(EditorCause.MissingTitle) }
    fun closeHeader() = host.closeSurface()
    fun restoreDraft() {
        val headerWritable = state.headerEnabled && !state.headerReadOnly
        val enabled = headerWritable && !source().isLoadingForm && !source().isUploading
        if (enabled) { onIntent(TopicFormIntent.DraftRestoreRequested); host.closeSurface() }
    }
    fun discardDraft() {
        if (state.scaffold.controlsEnabled) {
            onIntent(TopicFormIntent.DraftDiscardRequested)
            host.closeSurface()
        }
    }
    fun back(layers: EditorBackState = EditorBackState(), onLayerBack: (EditorBackAction) -> Unit = {}) {
        host.back(layers, onLayerBack)
    }
    @Composable
    fun title() = stringResource(if (source().mode == TopicFormMode.New) R.string.editor_topic_new_title
        else R.string.editor_post_edit_title)
    @Composable
    fun context(): String {
        val source = source()
        val awaitingTitle = source.mode == TopicFormMode.EditFirstPost && !source.subjectHydratedFromServer
        val title = source.subject.text.ifBlank {
            if (awaitingTitle) "" else stringResource(R.string.editor_topic_add_title)
        }
        return listOfNotNull(title.takeIf { it.isNotEmpty() }, state.selectedSubcategory).joinToString(" · ")
    }
    @Composable
    fun headerLabel() = stringResource(if (state.showSubcategory) UiR.string.editor_app_bar_open_topic_header
        else R.string.editor_topic_open_title)
    @Composable
    fun chrome() = host.scaffoldChrome(
        firstStatus = if (!source().pollPresent) null else EditorAppBarStatus(
            UiR.drawable.ic_editor_poll, stringResource(R.string.editor_topic_poll_status),
            { openHeader() }, enabled = state.scaffold.controlsEnabled,
        ),
        secondStatus = if (source().restorableDraft == null && source().restorableSubject == null) null
        else EditorAppBarStatus(UiR.drawable.ic_editor_restore, stringResource(UiR.string.editor_status_restore),
            { host.open(TopicFormChromeSurface.Restore) }, enabled = state.scaffold.controlsEnabled),
    )
    fun subject(value: TextFieldValue) {
        if (state.headerEnabled && !state.headerReadOnly) onIntent(TopicFormIntent.SubjectChanged(value))
    }
    fun subcategory(id: Int) {
        val offered = source().subcategoryChoices.any { it.id == id && id > 0 }
        if (state.subcategoryEnabled && state.showSubcategory && offered) {
            onIntent(TopicFormIntent.SubcatSelected(id))
        }
    }
    fun reload(key: EventKey) {
        val current = events.state.events.any { it.key == key }
        val detail = host.detail?.takeIf { it.key == key && current }
        val error = (detail?.content as? EditorEventContent.Problem)?.error
        val remedy = error?.action == EditorErrorAction.Reload || source().isAwaitingInitialBody
        if (error == null || !remedy || source().isLoadingForm) return
        onIntent(TopicFormIntent.ReloadRequested)
        host.closeDetails(key)
    }
}
