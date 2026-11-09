package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitActions
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitState
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.EditorActionBarActions
import fr.forumhfr.redface2.core.ui.editor.EditorAppBarStatus
import fr.forumhfr.redface2.core.ui.editor.EditorBackAction
import fr.forumhfr.redface2.core.ui.editor.EditorBackState
import fr.forumhfr.redface2.core.ui.editor.EditorBarGesture
import fr.forumhfr.redface2.core.ui.editor.EditorBarImageActions
import fr.forumhfr.redface2.core.ui.editor.EditorBarState
import fr.forumhfr.redface2.core.ui.editor.EditorErrorAction
import fr.forumhfr.redface2.core.ui.editor.EditorEvent
import fr.forumhfr.redface2.core.ui.editor.EditorEventAction
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.EditorEventInput
import fr.forumhfr.redface2.core.ui.editor.EditorSheet
import fr.forumhfr.redface2.core.ui.editor.EventKey
import fr.forumhfr.redface2.core.ui.editor.reduceEditorBar
import fr.forumhfr.redface2.core.ui.editor.resolveEditorBack
import fr.forumhfr.redface2.core.ui.R as UiR

/** Detached host; retain across recompositions. Scaffold owns IME capture/restore, root owns the picker launcher. */
@Suppress("LongParameterList") // Independent host ports, without a ViewModel dependency in Compose tests.
internal class PostEditorChromeAdapter(
    private val source: () -> PostEditorState,
    private val body: TextFieldState,
    private val events: PostEditorChromeEvents,
    private val onIntent: (PostEditorIntent) -> Unit,
    private val launchPicker: () -> Unit,
    private val openSmileys: () -> Unit,
) {
    var surface by mutableStateOf<PostEditorChromeSurface?>(null)
        private set
    var detail by mutableStateOf<EditorEvent?>(null)
        private set
    private var bar by mutableStateOf(EditorBarState())
    private var selection = TextRange.Zero
    private var textBeforeSurface = ""
    var returnGeneration by mutableIntStateOf(0)
        private set
    val state: PostEditorChromeState get() = PostEditorChromeState(source())

    @Composable
    fun chrome() = state.scaffold.copy(
        bar = bar.withSubmission(source().submission), event = events.visibleEvent,
        firstStatus = if (source().quotes.isEmpty() && !source().hasDormantQuote) null else EditorAppBarStatus(
            UiR.drawable.ic_editor_quote,
            pluralStringResource(UiR.plurals.editor_status_quotes, quoteCount, quoteCount),
            { open(if (source().hasDormantQuote) PostEditorChromeSurface.PendingQuote
                else PostEditorChromeSurface.Quotes) }, quoteCount, state.scaffold.controlsEnabled,
        ),
        secondStatus = if (source().restorableDraft == null) null else EditorAppBarStatus(
            UiR.drawable.ic_editor_restore, stringResource(UiR.string.editor_status_restore),
            { open(PostEditorChromeSurface.Restore) }, enabled = state.scaffold.controlsEnabled,
        ),
    )
    private val quoteCount get() = source().quotes.size + source().pendingQuotes.size
    val barActions get() = EditorActionBarActions(::gesture, ::format, {},
        { open(PostEditorChromeSurface.Smileys, openSmileys) }, { open(PostEditorChromeSurface.Options) },
        ::preview, ::activate)
    val imageActions get() = EditorBarImageActions({ open(PostEditorChromeSurface.Picker, launchPicker) },
        { open(PostEditorChromeSurface.Url) }, true)
    val submitState get() = ArmedSubmitState(source().armToken != null, state.sendActivatable,
        armToken = source().armToken)
    val submitActions: ArmedSubmitActions
        get() {
            // A callback retained by an old composition must never borrow a newer arm token.
            val token = source().armToken
            return ArmedSubmitActions({ send(4_000L) }, {
                if (!consumeInformation() && state.postAllowed) onIntent(PostEditorIntent.SubmitConfirmed(token))
            }, { onIntent(PostEditorIntent.SubmitConfirmationDismissed) }, ::send)
        }
    private fun consumeInformation() =
        events.dispatch(EditorEventInput.NextGesture(events.state.session)).gestureConsumed
    fun gesture(gesture: EditorBarGesture) {
        if (gesture != EditorBarGesture.OpenSurface && consumeInformation()) return
        bar = reduceEditorBar(bar.withSubmission(source().submission), gesture)
    }

    private fun send(timeout: Long) {
        if (!consumeInformation() && state.sendActivatable) onIntent(PostEditorIntent.SubmitClicked(timeout))
    }

    private fun format(action: BbcodeAction) {
        if (!consumeInformation() && canInsert) onIntent(PostEditorIntent.ToolbarActionClicked(action))
    }
    private val canInsert get() = state.scaffold.controlsEnabled && !source().isUploading
    private fun open(name: PostEditorChromeSurface, action: () -> Unit = {}) {
        if (consumeInformation() || !state.scaffold.controlsEnabled) return
        val insertion = name in setOf(PostEditorChromeSurface.Smileys, PostEditorChromeSurface.Picker,
            PostEditorChromeSurface.Url)
        if (insertion && !canInsert) return
        captureBody()
        surface = name
        action()
    }
    private fun captureBody() {
        selection = body.selection
        textBeforeSurface = body.text.toString()
        bar = reduceEditorBar(bar, EditorBarGesture.OpenSurface)
    }
    fun closeSurface() {
        val closed = surface ?: return
        surface = null
        if (closed !in setOf(PostEditorChromeSurface.Restore, PostEditorChromeSurface.Quotes,
                PostEditorChromeSurface.PendingQuote)) returnGeneration++
    }
    fun returnToBody(focus: () -> Unit) {
        val target = if (body.text.toString() == textBeforeSurface) selection else body.selection
        focus()
        if (body.selection != target) body.edit { selection = target }
    }
    fun signature(enabled: Boolean) = option(PostEditorIntent.ToggleSignature(enabled))
    fun smileyDisabled(disabled: Boolean) = option(PostEditorIntent.ToggleSmileyDisabled(disabled))
    fun notification(enabled: Boolean) = option(PostEditorIntent.ToggleEmailNotification(enabled))
    fun tone(icon: Int) = option(PostEditorIntent.MsgIconSelected(icon))
    private fun option(intent: PostEditorIntent) {
        if (state.scaffold.controlsEnabled && !source().isLoadingForm) onIntent(intent)
    }
    fun smiley(token: String) = insert(PostEditorIntent.SmileySelected(token))
    fun imageUrl(url: String) = insert(PostEditorIntent.ImageUrlInserted(url))
    private fun insert(intent: PostEditorIntent) {
        if (canInsert) onIntent(intent)
        closeSurface()
    }

    fun picker(event: ImagePickerEvent) {
        if (event !is ImagePickerEvent.Launched) closeSurface()
        onIntent(PostEditorIntent.ImagePickerEventReceived(event))
    }
    fun pickerCancelled() = closeSurface()
    fun restoreDraft() {
        if (source().canRestoreDraft && canInsert) {
            onIntent(PostEditorIntent.DraftRestoreRequested)
            closeSurface()
        }
    }
    fun discardDraft() {
        if (state.scaffold.controlsEnabled) {
            onIntent(PostEditorIntent.DraftDiscardRequested)
            closeSurface()
        }
    }
    private fun preview() {
        if (!consumeInformation() && state.scaffold.controlsEnabled) {
            captureBody()
            onIntent(PostEditorIntent.TogglePreview)
        }
    }
    private fun activate(key: EventKey, action: EditorEventAction) {
        val event = events.state.visibleEvent?.takeIf { it.key == key } ?: return
        // The bar uses Dismiss for ED-44, even when this information exposes ED-45 details.
        if (action == EditorEventAction.Dismiss && consumeInformation() || event.action != action) return
        events.dispatch(EditorEventInput.Activate(key))
        if (action == EditorEventAction.Details) { captureBody(); detail = event }
    }

    fun closeDetails(key: EventKey) {
        if (detail?.key != key) return
        events.dispatch(EditorEventInput.CloseDetails(key))
        detail = null
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
    private fun currentDetail(key: EventKey) = detail?.takeIf { it.key == key && it in events.state.events }
    fun reload(key: EventKey) {
        val error = (currentDetail(key)?.content as? EditorEventContent.Problem)?.error ?: return
        if (error.action != EditorErrorAction.Reload || source().isLoadingForm) return
        onIntent(PostEditorIntent.ReloadRequested)
        closeDetails(key)
    }

    /** Native smiley detail/sheet callbacks supply their actual layers before using this resolver. */
    fun back(layers: EditorBackState = EditorBackState(), onLayerBack: (EditorBackAction) -> Unit = {}) {
        val sheet = when (surface) {
            PostEditorChromeSurface.Smileys -> EditorSheet.Smileys
            PostEditorChromeSurface.Options -> EditorSheet.Options
            else -> null
        }
        val action = resolveEditorBack(layers.copy(sheet = layers.sheet ?: sheet,
            bar = bar.withSubmission(source().submission), eventDetailKey = detail?.key,
            panelOpen = surface != null, sharedPreviewOpen = source().isPreviewVisible,
            // 22:12: a mode masked by the displayed event is skipped, never closed nor acknowledged.
            event = events.visibleEvent))
        when (action) {
            is EditorBackAction.CloseSublevel, EditorBackAction.CloseFormat -> gesture(EditorBarGesture.Back)
            is EditorBackAction.Disarm -> onIntent(PostEditorIntent.SubmitConfirmationDismissed)
            is EditorBackAction.CloseEventDetails -> closeDetails(action.key)
            is EditorBackAction.ClosePanel, is EditorBackAction.CloseSheet -> { closeSurface(); onLayerBack(action) }
            is EditorBackAction.CloseSharedPreview -> preview()
            is EditorBackAction.Leave -> onIntent(PostEditorIntent.CloseRequested)
            else -> onLayerBack(action)
        }
    }

    /** App-bar return bypasses local levels but always uses the awaited save/close intent. */
    fun closeRequested() = onIntent(PostEditorIntent.CloseRequested)
}
