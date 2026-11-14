package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.ui.R as UiR
import fr.forumhfr.redface2.core.ui.editor.EditorAppBarStatus
import fr.forumhfr.redface2.core.ui.editor.EditorErrorAction
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.EditorEventRank
import fr.forumhfr.redface2.core.ui.editor.EditorHost
import fr.forumhfr.redface2.core.ui.editor.EditorHostSurfaces
import fr.forumhfr.redface2.core.ui.editor.EventKey

/** Only reply policies live here; gestures, displayed-event Back and focus/IME use the common host. */
@Suppress("LongParameterList") // Independent ports, as in the post adapter; no VM required by tests.
internal class PrivateMessageReplyChromeAdapter(
    private val source: () -> PrivateMessageReplyUiState,
    body: TextFieldState,
    private val events: PrivateMessageReplyEvents,
    private val actions: PrivateMessageReplyChromeActions,
    launchPicker: () -> Unit,
    openSmileys: () -> Unit,
    private val memory: PrivateMessageReplyChromeMemory = PrivateMessageReplyChromeMemory(),
    openSmileySearch: (() -> Unit)? = null,
) : EditorHost<PrivateMessageReplyChromeSurface>(
    { PrivateMessageReplyChromeState(source()).host }, body, events, actions.host,
    // Reply has neither header nor quote panel, and never completes a recipient refusal as a new MP would.
    EditorHostSurfaces(PrivateMessageReplyChromeSurface.Smileys, PrivateMessageReplyChromeSurface.Options,
        PrivateMessageReplyChromeSurface.Picker, canWrapSelection = true,
        draftOffer = PrivateMessageReplyChromeSurface.Restore),
    launchPicker, openSmileys, memory.host, openSmileySearch,
) {
    val state get() = PrivateMessageReplyChromeState(source())
    @Composable fun title() = stringResource(R.string.messages_reply_title)
    @Composable fun chrome() = scaffoldChrome(
        firstStatus = if (source().restorableDraft == null) null else EditorAppBarStatus(
            UiR.drawable.ic_ms_restore_page, stringResource(UiR.string.editor_status_restore),
            { open(PrivateMessageReplyChromeSurface.Restore) }, enabled = state.scaffold.controlsEnabled,
            showDot = true,
        ),
        secondStatus = if (!source().canManageRecipients) null else EditorAppBarStatus(
            UiR.drawable.ic_editor_recipients,
            pluralStringResource(R.plurals.messages_reply_recipient_status, source().recipients.size,
                source().recipients.size),
            ::openRecipients, source().recipients.size, state.recipientsEnabled,
        ),
    )
    fun openRecipients() {
        if (state.recipientsEnabled) open(PrivateMessageReplyChromeSurface.Recipients)
    }
    fun openInitialRecipients(requested: Boolean) {
        if (!requested || memory.recipientEntryHandled || !state.recipientsEnabled) return
        // Automatic entry is not a gesture: leave information visible until the user acknowledges it.
        if (events.visibleEvent?.rank == EditorEventRank.Information) return
        openRecipients()
        if (surface == PrivateMessageReplyChromeSurface.Recipients) memory.finishRecipientEntry()
    }
    fun addRecipient(pseudo: String) {
        if (state.recipientsEnabled) actions.addRecipient(pseudo)
    }
    fun removeRecipient(pseudo: String) {
        if (state.recipientsEnabled && source().recipients.size > 1) actions.removeRecipient(pseudo)
    }
    fun signature(enabled: Boolean) {
        if (state.optionsEnabled) actions.signature(enabled)
    }
    fun smileyDisabled(disabled: Boolean) {
        if (state.optionsEnabled) actions.smileyDisabled(disabled)
    }
    fun notification(enabled: Boolean) {
        if (state.optionsEnabled) actions.notification(enabled)
    }
    fun smiley(token: String) {
        if (state.canInsert) actions.smiley(token)
        closeSurface()
    }
    fun picker(event: ImagePickerEvent) {
        if (event !is ImagePickerEvent.Launched) closeSurface()
        actions.picker(event)
    }
    fun pickerCancelled() { closeSurface() }
    fun retryFormLoad() { actions.reloadForm() }
    /** ED-130 replaces the whole editor: no panel, sheet or detail comes back after « Réessayer ». */
    fun closeLayersForFormFailure(dismissSmileys: () -> Unit) {
        if (surface == PrivateMessageReplyChromeSurface.Smileys) dismissSmileys()
        detail?.let { closeDetails(it.key) }
        closeSurface()
    }
    fun restoreOffer(id: String) {
        if (state.restoreEnabled && source().canDecideDraftOffer(id)) { actions.restoreOffer(id); closeDraftOffer() }
    }
    fun discardOffer(id: String) {
        if (state.discardEnabled && source().canDecideDraftOffer(id)) actions.discardOffer(id)
    }
    fun resumeQuote() { if (source().hasDormantQuote) actions.resumeQuote() }
    fun ignoreQuote() { if (source().canIgnorePendingQuote) actions.ignoreQuote() }
    fun restoreDraft() {
        if (state.restoreEnabled && source().canDecideDraftOffer()) { actions.restoreDraft(); closeDraftOffer() }
    }
    fun discardDraft() {
        if (state.discardEnabled && source().canDecideDraftOffer()) { actions.discardDraft(); closeDraftOffer() }
    }
    fun reload(key: EventKey) {
        val error = (detail?.takeIf { it.key == key }?.content as? EditorEventContent.Problem)?.error
        val current = events.state.events.any { it.key == key }
        if (error?.action != EditorErrorAction.Reload || !current || source().isLoadingForm) return
        actions.reloadForm()
        closeDetails(key)
    }
}
