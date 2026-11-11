package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.ui.R as UiR
import fr.forumhfr.redface2.core.ui.editor.EditorAppBarStatus
import fr.forumhfr.redface2.core.ui.editor.EditorCause
import fr.forumhfr.redface2.core.ui.editor.EditorErrorAction
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.EditorEventRank
import fr.forumhfr.redface2.core.ui.editor.EditorHost
import fr.forumhfr.redface2.core.ui.editor.EditorHostSurfaces
import fr.forumhfr.redface2.core.ui.editor.EventKey

@Suppress("LongParameterList") // Business and platform ports stay independent of the ViewModel.
internal class PrivateMessageComposeChromeAdapter(
    private val source: () -> PrivateMessageComposeUiState,
    body: TextFieldState,
    private val events: PrivateMessageComposeEvents,
    private val actions: PrivateMessageComposeChromeActions,
    launchPicker: () -> Unit,
    openSmileys: () -> Unit,
    val memory: PrivateMessageComposeChromeMemory,
    openSmileySearch: (() -> Unit)? = null,
) : EditorHost<PrivateMessageComposeChromeSurface>(
    { PrivateMessageComposeChromeState(source()).host }, body, events.completingHeader(memory::complete),
    actions.host,
    EditorHostSurfaces(PrivateMessageComposeChromeSurface.Smileys, PrivateMessageComposeChromeSurface.Options,
        PrivateMessageComposeChromeSurface.Picker, header = PrivateMessageComposeChromeSurface.Header,
        canWrapSelection = true, draftOffer = PrivateMessageComposeChromeSurface.Restore),
    launchPicker, openSmileys, memory.host, openSmileySearch,
) {
    val state get() = PrivateMessageComposeChromeState(source())
    val waitingForInformation get() = events.visibleEvent?.rank == EditorEventRank.Information
    fun openInitialHeader() {
        if (!state.scaffold.controlsEnabled || source().isLoadingForm || !source().formAvailable) return
        if (waitingForInformation || !memory.claimInitialEntry()) return
        // Automatic entry must not consume an information without a user gesture.
        val empty = source().recipientState.let { it.chips.none(String::isNotBlank) && it.buffer.isBlank() }
        if (empty) openHeader()
    }
    fun openHeader() = open(PrivateMessageComposeChromeSurface.Header) {
        memory.complete(EditorCause.MissingRecipients)
    }
    @Composable fun title() = stringResource(R.string.messages_compose_title)
    @Composable fun context(): String {
        val count = source().recipientState.chips.count(String::isNotBlank)
        val recipients = if (count == 0) stringResource(R.string.messages_compose_add_recipients)
            else pluralStringResource(R.plurals.messages_compose_recipient_count, count, count)
        val subject = source().subject.ifBlank { stringResource(R.string.messages_compose_add_subject) }
        return "$recipients · $subject"
    }
    @Composable fun chrome() = scaffoldChrome(
        firstStatus = if (source().restorableDraft == null) null else EditorAppBarStatus(
            UiR.drawable.ic_ms_restore_page, stringResource(UiR.string.editor_status_restore),
            { open(PrivateMessageComposeChromeSurface.Restore) }, enabled = state.scaffold.controlsEnabled),
    )
    fun signature(value: Boolean) { if (state.optionsEnabled) actions.signature(value) }
    fun smileyDisabled(value: Boolean) { if (state.optionsEnabled) actions.smileyDisabled(value) }
    fun notification(value: Boolean) { if (state.optionsEnabled) actions.notification(value) }
    fun smiley(token: String) {
        if (state.host.canInsert) actions.smiley(token)
        closeSurface()
    }
    fun picker(event: ImagePickerEvent) {
        if (event !is ImagePickerEvent.Launched) closeSurface()
        actions.picker(event)
    }
    fun restoreDraft() {
        if (state.restoreEnabled) { actions.restore(); closeDraftOffer() }
    }
    fun discardDraft() {
        if (state.scaffold.controlsEnabled) { actions.discard(); closeDraftOffer() }
    }
    fun retryFormLoad() = actions.reload()
    /** ED-130 replaces the whole editor: no header, sheet or detail comes back after « Réessayer ». */
    fun closeLayersForFormFailure(dismissSmileys: () -> Unit) {
        if (surface == PrivateMessageComposeChromeSurface.Smileys) dismissSmileys()
        detail?.let { closeDetails(it.key) }
        closeSurface()
    }
    fun reload(key: EventKey) {
        val current = events.state.events.any { it.key == key }
        val error = (detail?.takeIf { it.key == key && current }?.content as? EditorEventContent.Problem)?.error
        if (error?.action != EditorErrorAction.Reload || source().isLoadingForm) return
        actions.reload()
        closeDetails(key)
    }
}
