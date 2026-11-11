package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
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

/** Topic projections and business intents; gestures, selection and focus live in the shared host. */
@Suppress("LongParameterList") // Independent ports, matching the post adapter without depending on a VM.
internal class TopicFormChromeAdapter(
    private val source: () -> TopicFormState,
    body: TextFieldState,
    private val events: TopicFormEvents,
    private val onIntent: (TopicFormIntent) -> Unit,
    launchPicker: () -> Unit,
    openSmileys: () -> Unit,
    val memory: TopicFormChromeMemory = TopicFormChromeMemory(),
    openSmileySearch: (() -> Unit)? = null,
) {
    val state get() = TopicFormChromeState(source())
    // The completion port owns the header, including its focus target and return to the body.
    // It opens directly after acknowledgement, without consuming a second gesture on a hidden information.
    // ED-45/170: only the host's explicit Masquer of the bound uncertain delivery reaches ErrorDismissed,
    // and only its explicit Masquer of a failed attempt write reaches DraftWriteFailureDismissed.
    val host = EditorHost(
        { state.host }, body, events,
        EditorHostActions(
            { onIntent(TopicFormIntent.SubmitClicked(it)) },
            { onIntent(TopicFormIntent.SubmitConfirmed(it)) },
            { onIntent(TopicFormIntent.SubmitConfirmationDismissed) },
            { onIntent(TopicFormIntent.ToolbarActionClicked(it)) },
            { onIntent(TopicFormIntent.TogglePreview) }, { onIntent(TopicFormIntent.CloseRequested) },
            onComplete = ::completeHeader,
            onAcknowledgeUncertain = { onIntent(TopicFormIntent.ErrorDismissed) },
            onAcknowledgeLocal = { onIntent(TopicFormIntent.DraftWriteFailureDismissed) },
        ),
        EditorHostSurfaces(TopicFormChromeSurface.Smileys, TopicFormChromeSurface.Options,
            TopicFormChromeSurface.Picker, imageUrl = TopicFormChromeSurface.Url,
            header = TopicFormChromeSurface.Header,
            returnsToBody = { it != TopicFormChromeSurface.Header },
            draftOffer = TopicFormChromeSurface.Restore),
        launchPicker, openSmileys, memory.host, openSmileySearch,
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
        if (enabled) { onIntent(TopicFormIntent.DraftRestoreRequested); host.closeDraftOffer() }
    }
    fun discardDraft() {
        if (state.scaffold.controlsEnabled) {
            onIntent(TopicFormIntent.DraftDiscardRequested)
            host.closeDraftOffer()
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
        firstStatus = if (!source().pollPresent || source().pollEditable) null else EditorAppBarStatus(
            UiR.drawable.ic_editor_poll, stringResource(R.string.editor_topic_poll_status),
            { openHeader() }, enabled = state.scaffold.controlsEnabled,
        ),
        secondStatus = if (source().restorableDraft == null && source().restorableSubject == null) null
        else EditorAppBarStatus(UiR.drawable.ic_ms_restore_page, stringResource(UiR.string.editor_status_restore),
            { host.open(TopicFormChromeSurface.Restore) }, enabled = state.scaffold.controlsEnabled),
    )
    fun option(intent: TopicFormIntent) {
        if (state.scaffold.controlsEnabled && !source().isLoadingForm) onIntent(intent)
    }
    fun smiley(token: String) = insert(TopicFormIntent.SmileySelected(token))
    fun imageUrl(url: String) = insert(TopicFormIntent.ImageUrlInserted(url))
    private fun insert(intent: TopicFormIntent) {
        if (state.host.canInsert) onIntent(intent)
        host.closeSurface()
    }
    fun picker(event: ImagePickerEvent) {
        if (event !is ImagePickerEvent.Launched) host.closeSurface()
        onIntent(TopicFormIntent.ImagePickerEventReceived(event))
    }
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
