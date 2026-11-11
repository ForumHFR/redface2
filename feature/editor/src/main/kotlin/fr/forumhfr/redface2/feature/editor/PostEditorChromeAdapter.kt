package fr.forumhfr.redface2.feature.editor

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
import fr.forumhfr.redface2.core.ui.editor.EditorHost
import fr.forumhfr.redface2.core.ui.editor.EditorHostActions
import fr.forumhfr.redface2.core.ui.editor.EditorHostMemory
import fr.forumhfr.redface2.core.ui.editor.EditorHostSurfaces
import fr.forumhfr.redface2.core.ui.editor.EventKey

/** Post projections, quotes and business intents; common gestures live in EditorHost. */
@Suppress("LongParameterList") // Independent host ports, without a ViewModel dependency in Compose tests.
internal class PostEditorChromeAdapter(
    private val source: () -> PostEditorState,
    body: TextFieldState,
    private val events: PostEditorChromeEvents,
    private val onIntent: (PostEditorIntent) -> Unit,
    launchPicker: () -> Unit,
    openSmileys: () -> Unit,
    memory: EditorHostMemory<PostEditorChromeSurface> = EditorHostMemory(),
    openSmileySearch: (() -> Unit)? = null,
) : EditorHost<PostEditorChromeSurface>(
    { PostEditorChromeState(source()).host }, body, events,
    EditorHostActions(
        { onIntent(PostEditorIntent.SubmitClicked(it)) },
        { onIntent(PostEditorIntent.SubmitConfirmed(it)) },
        { onIntent(PostEditorIntent.SubmitConfirmationDismissed) },
        { onIntent(PostEditorIntent.ToolbarActionClicked(it)) },
        { onIntent(PostEditorIntent.TogglePreview) },
        { onIntent(PostEditorIntent.CloseRequested) },
        onAcknowledgeUncertain = { onIntent(PostEditorIntent.ErrorDismissed) },
        onAcknowledgeLocal = { onIntent(PostEditorIntent.DraftWriteFailureDismissed) },
    ),
    EditorHostSurfaces(PostEditorChromeSurface.Smileys, PostEditorChromeSurface.Options,
        PostEditorChromeSurface.Picker, imageUrl = PostEditorChromeSurface.Url, canWrapSelection = true,
        returnsToBody = { it !in setOf(PostEditorChromeSurface.Restore, PostEditorChromeSurface.Quotes,
            PostEditorChromeSurface.PendingQuote) }),
    launchPicker, openSmileys, memory, openSmileySearch,
) {
    val state: PostEditorChromeState get() = PostEditorChromeState(source())

    @Composable
    fun chrome() = scaffoldChrome(
        firstStatus = if (!showPendingQuote && source().quotes.isEmpty()) null
        else EditorAppBarStatus(
            UiR.drawable.ic_editor_quote,
            pluralStringResource(UiR.plurals.editor_status_quotes, quoteCount, quoteCount),
            { open(if (showPendingQuote) PostEditorChromeSurface.PendingQuote
                else PostEditorChromeSurface.Quotes) }, quoteCount, state.scaffold.controlsEnabled,
        ),
        secondStatus = if (source().restorableDraft == null) null else EditorAppBarStatus(
            UiR.drawable.ic_editor_restore, stringResource(UiR.string.editor_status_restore),
            { open(PostEditorChromeSurface.Restore) }, enabled = state.scaffold.controlsEnabled,
        ),
    )
    /** Render this keyed detail with the existing offer panel; EditorHost retains ED-45 acknowledgement. */
    val pendingQuoteDetailKey get() = detail?.key?.takeIf(events::isPendingQuoteEvent)

    // The save information may be acknowledged or retired by autosave while Q still needs its CAS.
    // Keep a recovery panel reachable from the quote status independently of that event's lifetime.
    val hasPendingQuoteRecovery get() = source().pendingQuotes.isNotEmpty() &&
        source().restorableDraft == null && !source().hasDormantQuote
    private val showPendingQuote get() = source().hasDormantQuote || hasPendingQuoteRecovery
    private val quoteCount get() = source().quotes.size + source().pendingQuotes.size
    fun signature(enabled: Boolean) = option(PostEditorIntent.ToggleSignature(enabled))
    fun smileyDisabled(disabled: Boolean) = option(PostEditorIntent.ToggleSmileyDisabled(disabled))
    fun notification(enabled: Boolean) = option(PostEditorIntent.ToggleEmailNotification(enabled))
    fun tone(icon: Int) = option(PostEditorIntent.MsgIconSelected(icon))
    private fun option(intent: PostEditorIntent) {
        if (state.scaffold.controlsEnabled && !source().isLoadingForm) onIntent(intent)
    }
    fun quoteIntent(intent: PostEditorIntent) {
        if (state.scaffold.controlsEnabled) onIntent(intent)
    }
    fun clearQuotes() = quoteIntent(PostEditorIntent.QuotesCleared)
    fun smiley(token: String) = insert(PostEditorIntent.SmileySelected(token))
    fun imageUrl(url: String) = insert(PostEditorIntent.ImageUrlInserted(url))
    private fun insert(intent: PostEditorIntent) {
        if (state.canInsert) onIntent(intent)
        closeSurface()
    }

    fun picker(event: ImagePickerEvent) {
        if (event !is ImagePickerEvent.Launched) closeSurface()
        onIntent(PostEditorIntent.ImagePickerEventReceived(event))
    }
    fun pickerCancelled() = closeSurface()
    fun restoreDraft() {
        // A non-blank restore can resolve an unknown edit body after the initial GET failed (#1574).
        if (source().canRestoreDraft && state.scaffold.controlsEnabled && !source().isUploading) {
            val key = pendingQuoteDetailKey
            onIntent(PostEditorIntent.DraftRestoreRequested)
            if (key == null) closeSurface() else closeDetails(key)
        }
    }
    fun discardDraft() {
        if (state.scaffold.controlsEnabled) {
            val key = pendingQuoteDetailKey
            onIntent(PostEditorIntent.DraftDiscardRequested)
            if (key == null) closeSurface() else closeDetails(key)
        }
    }
    fun resumeQuote(key: EventKey? = null) {
        if (source().hasDormantQuote) quoteDecision(key, PostEditorIntent.PendingQuoteResumed)
    }
    fun ignoreQuote(key: EventKey? = null) {
        if (source().canIgnorePendingQuote) quoteDecision(key, PostEditorIntent.PendingQuoteIgnored)
    }
    private fun quoteDecision(key: EventKey?, intent: PostEditorIntent) {
        if (source().isLoadingForm || !state.scaffold.controlsEnabled) return
        if (!isQuoteDecisionCurrent(key)) return
        onIntent(intent)
        if (key == null) closeSurface() else closeDetails(key)
    }
    private fun isQuoteDecisionCurrent(key: EventKey?) = if (key == null) {
        surface == PostEditorChromeSurface.PendingQuote
    } else {
        currentDetail(key) != null
    }
    fun canReload(key: EventKey): Boolean {
        val error = (currentDetail(key)?.content as? EditorEventContent.Problem)?.error ?: return false
        val quoteRecovery = hasPendingQuoteRecovery && error.cause == EditorCause.DraftSaveFailed
        return error.action == EditorErrorAction.Reload || source().isAwaitingInitialBody || quoteRecovery
    }
    fun reload(key: EventKey) {
        if (!canReload(key) || source().isLoadingForm) return
        onIntent(PostEditorIntent.ReloadRequested)
        closeDetails(key)
    }
    fun reloadPendingQuote() {
        if (!hasPendingQuoteRecovery || source().isLoadingForm || !state.scaffold.controlsEnabled) return
        if (surface != PostEditorChromeSurface.PendingQuote) return
        onIntent(PostEditorIntent.ReloadRequested)
        closeSurface()
    }
}
