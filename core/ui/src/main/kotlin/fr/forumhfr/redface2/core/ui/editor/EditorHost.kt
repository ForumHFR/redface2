package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.text.input.TextFieldState
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState

/**
 * Shared chrome host: gestures, captured arm identity, event details, Back and body selection/focus.
 * Retain [memory] in the VM; bind the live [source] and intent [actions] at composition scope.
 * The surface supplies eligibility, event deliveries, status icons, optional header/panels and their
 * contents, picker/smiley launchers, business remedies and awaited saving/closing. EditorScaffold owns
 * IME capture/restore via [focusMemory]; pass its return callback to [returnToBody] after overlays close.
 * Wire EditorEventDetails' Masquer to [dismissDetails] and its closing to [closeDetails]: only the former
 * acknowledges a bound uncertain attempt through [EditorHostActions.onAcknowledgeUncertain] (ED-45/170),
 * or a failed attempt write locally through [EditorHostActions.onAcknowledgeLocal].
 * Subclasses may bind business intents without copying these common gestures.
 */
@Suppress("LongParameterList") // Independent screen ports; no ViewModel dependency in core/ui.
open class EditorHost<S : Any>(
    private val source: () -> EditorHostState,
    private val body: TextFieldState,
    private val events: EditorHostEvents,
    private val actions: EditorHostActions,
    private val surfaces: EditorHostSurfaces<S>,
    private val launchPicker: () -> Unit,
    private val openSmileys: () -> Unit,
    private val memory: EditorHostMemory<S>,
    private val openSmileySearch: (() -> Unit)? = null,
) {
    val surface get() = memory.surface
    val detail get() = memory.detail
    val draftOfferOpen get() = surfaces.draftOffer != null && surface == surfaces.draftOffer
    val returnGeneration get() = memory.returnGeneration
    val focusMemory get() = memory.focus
    val onOpenHeader: (() -> Unit)? get() = surfaces.header?.let { { open(it) } }
    private val canComplete get() = actions.onComplete != null || surfaces.header != null

    fun scaffoldChrome(firstStatus: EditorAppBarStatus? = null, secondStatus: EditorAppBarStatus? = null) =
        source().scaffold.copy(bar = currentBar(), event = events.visibleEvent?.withCompletionCapability(canComplete),
            firstStatus = firstStatus, secondStatus = secondStatus)

    val barActions get() = EditorActionBarActions(::gesture, ::format, {},
        { open(surfaces.smileys, openSmileys) }, { open(surfaces.options) }, ::preview, ::activate,
        onSmileySearch = openSmileySearch?.let { search -> { open(surfaces.smileys, search) } })
    val imageActions get() = EditorBarImageActions({ open(surfaces.picker, launchPicker) },
        surfaces.imageUrl?.let { { open(it) } }, surfaces.canWrapSelection)
    val submitState get() = ArmedSubmitState(currentBar().arm != null, source().sendActivatable,
        armToken = currentBar().arm)
    val submitActions: ArmedSubmitActions
        get() {
            // An old composition's confirmation must never borrow a newer arm token.
            val token = currentBar().arm
            return ArmedSubmitActions({ send(CONFIRM_DISARM_DELAY_MS) }, {
                if (!consumeInformation() && source().postAllowed) actions.onConfirm(token)
            }, actions.onDisarm, ::send)
        }

    private fun currentBar() = memory.bar.withSubmission(source().submission)
    private fun consumeInformation() =
        events.dispatch(EditorEventInput.NextGesture(events.state.session)).gestureConsumed

    fun gesture(gesture: EditorBarGesture) {
        if (gesture != EditorBarGesture.OpenSurface && consumeInformation()) return
        memory.bar = reduceEditorBar(currentBar(), gesture)
    }
    private fun send(timeout: Long) {
        if (!consumeInformation() && source().sendActivatable) actions.onArm(timeout)
    }
    private fun format(action: BbcodeAction) {
        if (!consumeInformation() && source().canInsert) actions.onFormat(action)
    }
    fun open(name: S, action: () -> Unit = {}) {
        if (consumeInformation() || !source().scaffold.controlsEnabled) return
        val insertion = name in setOf(surfaces.smileys, surfaces.picker, surfaces.imageUrl)
        if (insertion && !source().canInsert) return
        captureBody()
        memory.surface = name
        action()
    }
    private fun captureBody() {
        memory.selection = body.selection
        memory.textBeforeSurface = body.text.toString()
        memory.bar = reduceEditorBar(memory.bar, EditorBarGesture.OpenSurface)
    }
    /** For the surface's onComplete port or initial opening: the event gesture is already consumed. */
    fun openHeaderForCompletion() {
        val header = surfaces.header ?: return
        if (!source().scaffold.controlsEnabled) return
        captureBody()
        memory.surface = header
    }
    fun closeSurface() {
        val closed = surface ?: return
        memory.surface = null
        if (closed == surfaces.draftOffer || surfaces.returnsToBody(closed)) memory.returnGeneration++
    }
    /** Closing is visual acknowledgement only, never a restore/discard or other business intent. */
    fun closeDraftOffer() {
        if (surfaces.draftOffer != null && surface == surfaces.draftOffer) closeSurface()
    }
    fun consumeReturn(): Boolean {
        if (memory.returnedGeneration == returnGeneration) return false
        memory.returnedGeneration = returnGeneration
        return true
    }
    fun returnToBody(focus: () -> Unit) {
        memory.focus.clearToolSelection()
        val target = if (body.text.toString() == memory.textBeforeSurface) memory.selection else body.selection
        focus()
        if (body.selection != target) body.edit { selection = target }
    }
    private fun preview() {
        if (!consumeInformation() && source().scaffold.controlsEnabled) {
            captureBody()
            actions.onPreview()
        }
    }
    private fun activate(key: EventKey, action: EditorEventAction) {
        val event = events.state.visibleEvent?.takeIf { it.key == key }
            ?.withCompletionCapability(canComplete) ?: return
        val consumedDismiss = action == EditorEventAction.Dismiss && consumeInformation()
        val blockedCompletion = action == EditorEventAction.Complete && !source().scaffold.controlsEnabled
        if (consumedDismiss || event.action != action || blockedCompletion) return
        events.dispatch(EditorEventInput.Activate(key, canComplete)).commands.forEach { command ->
            when (command) {
                is EditorEventCommand.OpenDetails -> {
                    captureBody()
                    memory.detail = command.event
                }
                is EditorEventCommand.CompleteHeader -> complete(command.cause)
                is EditorEventCommand.ReturnToBody -> Unit
            }
        }
        // ED-45: only an explicit Masquer reaches acknowledgement ports, never opening the detail.
        if (action == EditorEventAction.Dismiss) acknowledgeDismissal(event)
    }
    private fun complete(cause: EditorCause) {
        captureBody()
        val onComplete = actions.onComplete
        if (onComplete == null) memory.surface = surfaces.header else onComplete(cause)
    }
    /** Explicit Masquer only; Back and overlay closing never acknowledge submission failures. */
    fun dismissDetails(key: EventKey) {
        if (detail?.key != key) return
        val current = currentDetail(key)
        closeDetails(key)
        if (current != null) acknowledgeDismissal(current)
    }
    private fun acknowledgeDismissal(event: EditorEvent) {
        val cause = (event.content as? EditorEventContent.Problem)?.error?.cause
        if (cause == EditorCause.DraftWriteFailed) actions.onAcknowledgeLocal?.invoke()
        else acknowledgeUncertainty(event.key)
    }
    private fun acknowledgeUncertainty(key: EventKey) {
        val attempt = events.uncertainAttempt(key) ?: return
        if (source().submission == EditorSubmissionState.Uncertain(attempt)) actions.onAcknowledgeUncertain?.invoke()
    }
    fun closeDetails(key: EventKey) {
        if (detail?.key != key) return
        events.dispatch(EditorEventInput.CloseDetails(key))
        memory.detail = null
    }
    /** The open detail while its delivery key is current; a replaced or acknowledged delivery is stale. */
    protected fun currentDetail(key: EventKey) = detail?.takeIf {
        it.key == key && events.state.events.any { event -> event.key == key }
    }

    /** Native smiley/detail/sheet callbacks supply their actual layers to this same resolver. */
    fun back(layers: EditorBackState = EditorBackState(), onLayerBack: (EditorBackAction) -> Unit = {}) {
        when (val action = resolveEditorBack(backState(layers))) {
            is EditorBackAction.CloseSublevel, EditorBackAction.CloseFormat -> {
                memory.bar = reduceEditorBar(currentBar(), EditorBarGesture.Back)
            }
            is EditorBackAction.Disarm -> actions.onDisarm()
            is EditorBackAction.CloseEventDetails -> closeDetails(action.key)
            is EditorBackAction.ClosePanel -> { closeSurface(); onLayerBack(action) }
            is EditorBackAction.CloseSheet -> {
                if (action.sheet == EditorSheet.DraftOffer) closeDraftOffer() else closeSurface()
                onLayerBack(action)
            }
            is EditorBackAction.CloseSharedPreview -> if (source().scaffold.controlsEnabled) {
                // Back is not an action gesture: keep both a displayed information and its masked mode.
                actions.onPreview()
            }
            is EditorBackAction.Leave -> closeRequested()
            else -> onLayerBack(action)
        }
    }
    private fun backState(layers: EditorBackState): EditorBackState {
        val sheet = when (surface) {
            surfaces.smileys -> EditorSheet.Smileys
            surfaces.options -> EditorSheet.Options
            surfaces.draftOffer -> if (surface != null) EditorSheet.DraftOffer else null
            else -> null
        }
        val submission = source().submission
        val succeeded = submission is EditorSubmissionState.Succeeded ||
            submission is EditorSubmissionState.Uncertain && submission.accepted
        return layers.copy(sheet = layers.sheet ?: sheet,
            // 22:12: a mode masked by the displayed event is skipped, never closed nor acknowledged.
            bar = currentBar(), event = events.visibleEvent, eventDetailKey = detail?.key ?: layers.eventDetailKey,
            panelOpen = layers.panelOpen || surface != null,
            sharedPreviewOpen = layers.sharedPreviewOpen || source().scaffold.previewVisible,
            submissionSucceeded = layers.submissionSucceeded || succeeded)
    }
    /** App-bar return bypasses local levels and uses the surface's awaited save/close intent. */
    fun closeRequested() = actions.onClose()
}
