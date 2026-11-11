@file:Suppress("MatchingDeclarationName") // The chrome snapshot is part of the scaffold API.

package fr.forumhfr.redface2.core.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R

/** Host-owned chrome only. Text and selection changes must not produce statuses or events (ED-41). */
data class EditorScaffoldChrome(
    val bar: EditorBarState = EditorBarState(),
    val event: EditorEvent? = null,
    val firstStatus: EditorAppBarStatus? = null,
    val secondStatus: EditorAppBarStatus? = null,
    val previewVisible: Boolean = false,
    val shortcut: EditorBarShortcut? = null,
    val controlsEnabled: Boolean = true,
    val bodyReadOnly: Boolean = false,
    val uploading: Boolean = false,
    val uploadProgress: UploadProgress? = null,
    val loadingForm: Boolean = false,
)

/**
 * Retain entry consumption, surface IME and actual body focus/IME across rotation.
 * A host keeps one in its ViewModel; the default [Saver] only saves the entry/surface booleans (R1a-1).
 */
class EditorSurfaceFocusMemory {
    internal val toolFocus = EditorBodyToolFocus()
    var imeBeforeSurface: Boolean = false
    var autoFocusConsumed: Boolean = false
    internal val preview = EditorPreviewFocusMemory()
    internal var body by mutableStateOf(EditorBodyFocusMemory())
        private set

    /** Invalidate before programmatic body/selection writes, including equal-text restorations. */
    fun clearToolSelection() { toolFocus.clear() }

    fun reset() {
        clearToolSelection()
        imeBeforeSurface = false
        autoFocusConsumed = false
        preview.reset()
        body.reset()
        body = EditorBodyFocusMemory()
    }

    companion object {
        /** Entry/surface booleans only; body focus/IME and preview returns never survive process death. */
        val Saver: Saver<EditorSurfaceFocusMemory, Any> = listSaver(
            save = { listOf(it.imeBeforeSurface, it.autoFocusConsumed) },
            restore = { saved ->
                EditorSurfaceFocusMemory().apply {
                    imeBeforeSurface = saved[0]
                    autoFocusConsumed = saved[1]
                }
            },
        )
    }
}

/** Live, composition-owned tooltips: the screen resolves their ED-85 level before leaving. */
@OptIn(ExperimentalMaterial3Api::class)
class EditorTooltipHost {
    internal val states = mutableListOf<TooltipState>()
    val isVisible: Boolean get() = states.any { it.isVisible }
    fun dismiss() { states.filter { it.isVisible }.forEach { it.dismiss() } }
}

val LocalEditorTooltipHost = staticCompositionLocalOf<EditorTooltipHost?> { null }

/**
 * B5 assembly; no navigation, submission controller, picker or persistence is installed.
 * [windowMetrics] comes from rememberEditorWindowMetrics once non-null, never from IME-sized content.
 * Give this component the full bounded window: it owns horizontal safeDrawing, the top inset and
 * the single navigationBars/IME union under the action bar. Do not add Scaffold/IME padding outside.
 *
 * The host reduces [barActions] gestures, edits [bodyState] with the existing keep formatter, and
 * guards [onCloseRequested] (save/leave). Inline insertions restore [bodyFocusRequester] here.
 * Tool focus entry captures selection and IME before BTF2's focus-loss collapse; insertion and surface
 * dispatch restore that selection if the buffer still matches.
 * Surface gestures capture IME visibility before dispatch; captured returns enqueue a Surface return.
 * Its effect preserves selection and restores focus and that visibility after the input options update.
 * An initial panel preserves selection while waiting for closed-IME options.
 * Other focus requests open the IME by default; a return or recreation
 * with a previously closed IME suppresses automatic opening until a completed body tap, accessibility
 * click or surface capture. Suppression is local to this composition and is not saved.
 * [autoFocus] requests body focus once on entry: a recreation that keeps [surfaceFocus] never repeats it (R2).
 * [surfaceFocus] owns the consumed autofocus and the captured IME state across recreation: pass host-retained
 * memory; without one the default saves its two booleans, so it stays consumed even after process death.
 * [bodyFocusRestoreAllowed] is supplied explicitly by every host: false while any panel, sheet,
 * external surface covers the body. EditorPreview owns the covering fallback's focus guard; a shared
 * preview keeps the source editable. Only the editable body passed to [fieldArea] is guarded:
 * it refuses fresh focus entry, including from the new window; other [fieldArea] controls remain reachable.
 * A retained body's focus is restored once after detach,
 * with its actual pre-pause IME visibility; a blocked restoration waits for Ready until the 500 ms
 * deadline, then is discarded, leaving panel returns to their existing callbacks. This rotation
 * snapshot is never saved by [EditorSurfaceFocusMemory.Saver].
 * [overlay] must invoke its body exactly once, unconditionally, at a stable composition site. Wrap it
 * with EditorHeaderPanel / EditorEventDetails, passing [bodyState] and its onReturnToBody callback; their opaque
 * overlay hides and blocks the body without losing its viewport or undo history. Sheets/tooltips are
 * registered later by the host. Open panels through the supplied header/event actions; other surfaces
 * must dispatch OpenSurface before showing; this also disarms submission and closes tool modes.
 * A panel open on entry defaults to a closed keyboard.
 * [bodyCaretRevealResetKey] reads a snapshot-backed counter incremented with server hydration or draft
 * restoration, in the same snapshot as the buffer change. The host retains scroll restoration.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongParameterList") // Assembly boundary: existing component contracts stay host-owned.
fun EditorScaffold(
    bodyState: TextFieldState,
    windowMetrics: EditorWindowMetrics,
    title: String,
    context: String,
    chrome: EditorScaffoldChrome,
    barActions: EditorActionBarActions,
    submitState: ArmedSubmitState,
    submitActions: ArmedSubmitActions,
    imageActions: EditorBarImageActions,
    onCloseRequested: () -> Unit,
    bodyFocusRestoreAllowed: Boolean,
    modifier: Modifier = Modifier,
    onOpenHeader: (() -> Unit)? = null,
    headerLabel: String = stringResource(R.string.editor_header_topic),
    bodyFocusRequester: FocusRequester = remember { FocusRequester() },
    bodyScrollState: ScrollState = rememberScrollState(),
    inputTransformation: InputTransformation? = null,
    autoFocus: Boolean = false,
    bodyCaretRevealResetKey: () -> Int = { 0 },
    surfaceFocus: EditorSurfaceFocusMemory = rememberSaveable(bodyState, saver = EditorSurfaceFocusMemory.Saver) {
        EditorSurfaceFocusMemory()
    },
    onSystemBack: (() -> Unit)? = null,
    fieldArea: @Composable (field: @Composable () -> Unit) -> Unit = { field -> field() },
    overlay: @Composable (onReturnToBody: () -> Unit, body: @Composable () -> Unit) -> Unit = { _, body -> body() },
) {
    val metrics = EditorChromeMetrics(windowMetrics.compactHeight)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val bodyFocus = rememberEditorBodyFocus(bodyState, surfaceFocus.body, imeVisible)
    val toolFocus = surfaceFocus.toolFocus
    DisposableEffect(bodyState, toolFocus) {
        toolFocus.clear()
        onDispose { toolFocus.clear() }
    }
    val armed = submitState.armed || chrome.bar.arm != null
    // Entry autofocus stays consumed across recreation. Restoring a retained body's actual focus is
    // separate; its IME option is local to this attachment and initialized from the session snapshot.
    // Resetting session memory must not rearm entry autofocus on an already mounted field.
    var initialFocus by remember(bodyState, surfaceFocus) {
        mutableStateOf(autoFocus && !surfaceFocus.autoFocusConsumed)
    }
    LaunchedEffect(bodyState, surfaceFocus, bodyFocus) {
        if (autoFocus) surfaceFocus.autoFocusConsumed = true
    }
    // Apply the pre-rotation IME option before the restoration effect can request focus.
    // A session reset replaces the observation, but must preserve this mounted field's IME policy.
    var restoreImeOnFocus by remember(bodyState) {
        mutableStateOf(bodyFocus.showImeOnRestore)
    }
    var pendingBodyFocusReturn by remember(bodyState) { mutableStateOf<EditorBodyFocusReturn?>(null) }
    val captureIme = {
        bodyFocus.cancelRestoration("surface")
        initialFocus = false
        toolFocus.captureSurfaceIme(bodyState, surfaceFocus, imeVisible)
        restoreImeOnFocus = false
        if (armed) submitActions.onDisarm()
    }
    val returnToBody: (EditorBodyFocusReturn) -> Unit = { reason ->
        toolFocus.clear()
        initialFocus = false
        restoreImeOnFocus = reason.showImeOnFocus(surfaceFocus.imeBeforeSurface)
        pendingBodyFocusReturn = reason
    }
    val onReturnToBody: () -> Unit = { returnToBody(EditorBodyFocusReturn.Surface) }
    // Wait until the input options and host guard have recomposed after closing the surface.
    // Use H's selection-preserving path; an already focused field still needs explicit IME restore.
    LaunchedEffect(pendingBodyFocusReturn) {
        val reason = pendingBodyFocusReturn
        if (reason != null) {
            val focused = bodyFocusRestoreAllowed && requestEditorBodyFocus(
                bodyState, bodyFocusRequester, toolFocus::clear)
            restoreImeOnFocus = restoreImeOnFocus and focused
            // ED-61: preview returns never command the keyboard. A covering preview may also refuse
            // a surface return: consume it without commanding the keyboard or deferring another try.
            if (focused) reason.restoreIme(keyboard, restoreImeOnFocus)
            pendingBodyFocusReturn = null
        }
    }
    val actions = barActions.withScaffoldFocus(captureIme, bodyState, toolFocus, bodyFocusRequester)
    val previewAction = previewWithoutImeChange(chrome.event, barActions, armed, submitActions.onDisarm)
    val preview = { bodyFocus.cancelRestoration("preview"); previewAction() }
    val returnFromPreview: () -> Unit = {
        // ED-61: a preview control already ended the body's session when it took focus. Returning
        // must not start another one, even with visible IME insets: that would request IME.show().
        // An explicit body tap/accessibility click can enable a session afterwards, as for panels.
        returnToBody(EditorBodyFocusReturn.Preview)
    }
    val open: (() -> Unit) -> Unit = { action ->
        if (!consumeEditorBarInformation(chrome.event, actions.onEvent)) {
            actions.onGesture(EditorBarGesture.OpenSurface)
            action()
        }
    }
    val status: (EditorAppBarStatus?) -> EditorAppBarStatus? = { value ->
        value?.copy(onClick = { open(value.onClick) })
    }
    val header = onOpenHeader?.let { action -> { open(action) } }
    // ED-85: overlay details win, then disarm, then visible tool levels, panel, preview and guarded exit.
    // OpenSurface disarms before a panel opens; a displayed event masks retained modes without a Back level.
    // Composed before the bar's sublevel handlers and the overlay's panel handlers; [onSystemBack] replaces it.
    EditorSystemBack(onSystemBack, armed, { submitActions.onDisarm() }, onCloseRequested,
        { bodyFocus.cancelRestoration("back") })
    CompositionLocalProvider(LocalEditorPreviewFocusMemory provides surfaceFocus.preview) {
        Surface(modifier.fillMaxSize().cancelEditorBodyFocusRestorationOnInteraction(bodyFocus),
            color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(editorHorizontalInsets())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
                overlay(onReturnToBody) {
                    Column(Modifier.fillMaxSize().semantics { paneTitle = title; isTraversalGroup = true }
                        .testTag("editor-scaffold-column")) {
                        EditorAppBar(title, context, onCloseRequested, preview,
                            modifier = Modifier.semantics { isTraversalGroup = true; traversalIndex = 0f }
                                .testTag("editor-scaffold-app-bar"),
                            firstStatus = status(chrome.firstStatus), secondStatus = status(chrome.secondStatus),
                            onOpenHeader = header, headerActionLabel = headerLabel,
                            compactHeight = metrics.compactHeight,
                            previewEnabled = chrome.controlsEnabled, headerEnabled = chrome.controlsEnabled,
                            previewVisible = chrome.previewVisible)
                        Spacer(Modifier.height(metrics.columnSpacing / 2))
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            CompositionLocalProvider(
                                LocalEditorPreviewCompactHeight provides windowMetrics.compactHeight,
                                LocalEditorPreviewReturnFocus provides returnFromPreview) {
                                fieldArea {
                                    RestoreRecreatedEditorBodyFocus(bodyFocus, bodyFocusRestoreAllowed, bodyState,
                                        bodyFocusRequester,
                                        availability = LocalEditorPreviewBodyFocusAvailability.current,
                                        clearToolSelection = surfaceFocus::clearToolSelection)
                                    Box(Modifier.guardEditorBodyFocus(
                                        bodyFocusRestoreAllowed, bodyState, toolFocus, imeVisible)) {
                                        BbcodeTextField(bodyState, stringResource(R.string.editor_scaffold_message),
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
                                                .testTag("editor-scaffold-field").focusRequester(bodyFocusRequester)
                                                .observeEditorBodyFocus(bodyFocus)
                                                .semantics { isTraversalGroup = true; traversalIndex = 1f }
                                                .tabMovesFocus(focusManager)
                                                .onEditorBodyTap(bodyState) { restoreImeOnFocus = true },
                                            inputTransformation = inputTransformation,
                                            readOnly = chrome.uploading || chrome.bodyReadOnly,
                                            placeholder = stringResource(R.string.editor_scaffold_placeholder),
                                            revealCaretOnChange = true,
                                            scrollState = bodyScrollState, showLabel = metrics.showFieldLabel,
                                            autoFocus = initialFocus,
                                            showKeyboardOnFocus = initialFocus || restoreImeOnFocus,
                                            caretRevealResetKey = bodyCaretRevealResetKey,
                                            onMinimumHeightMeasured = LocalEditorPreviewFieldMinimum.current,
                                            onSemanticsClick = editorBodyClick(restoreImeOnFocus, bodyFocusRequester) {
                                                restoreImeOnFocus = true
                                            })
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(metrics.columnSpacing / 2))
                        Box(Modifier.windowInsetsPadding(editorBottomInsets()).semantics {
                            isTraversalGroup = true; traversalIndex = 2f
                        }) {
                            EditorActionBar(chrome.bar, actions, submitState, submitActions, imageActions,
                                modifier = Modifier.testTag("editor-scaffold-action-bar"),
                                event = chrome.event, compactHeight = metrics.compactHeight,
                                firstStatus = chrome.firstStatus.takeIf { metrics.compactHeight },
                                secondStatus = chrome.secondStatus.takeIf { metrics.compactHeight },
                                onOpenHeader = if (metrics.compactHeight) onOpenHeader else null,
                                headerLabel = headerLabel, previewVisible = chrome.previewVisible,
                                shortcut = chrome.shortcut, controlsEnabled = chrome.controlsEnabled,
                                uploading = chrome.uploading, uploadProgress = chrome.uploadProgress,
                                loadingForm = chrome.loadingForm, insertionsEnabled = !chrome.bodyReadOnly,
                                onPreviewWithoutImeChange = preview)
                        }
                    }
                }
            }
        }
    }
}

private enum class EditorBodyFocusReturn {
    Surface, Preview;

    fun showImeOnFocus(imeBeforeSurface: Boolean): Boolean = this == Surface && imeBeforeSurface

    fun restoreIme(keyboard: SoftwareKeyboardController?, visible: Boolean) {
        if (this == Surface) {
            if (visible) keyboard?.show() else keyboard?.hide()
        }
    }
}

/** A new window can attempt native focus entry independently of our restoration effect. */
private fun Modifier.guardEditorBodyFocus(
    allowed: Boolean, state: TextFieldState, tools: EditorBodyToolFocus, imeVisible: Boolean,
): Modifier = focusProperties {
    onEnter = { if (!allowed) cancelFocusChange() else tools.clear() }
    onExit = { tools.capture(state, imeVisible) }
}.focusGroup()

/** ED-61: preview reduces tools/disarms without the IME capture used by other surfaces. */
private fun previewWithoutImeChange(
    event: EditorEvent?, actions: EditorActionBarActions, armed: Boolean, onDisarm: () -> Unit,
): () -> Unit = {
    if (!consumeEditorBarInformation(event, actions.onEvent)) {
        if (armed) onDisarm()
        actions.onGesture(EditorBarGesture.OpenSurface)
        actions.onPreview()
    }
}

/** Capture before BTF2 collapses selection on focus loss, including Tab to an editor tool. */
internal class EditorBodyToolFocus {
    internal data class Capture(
        val state: TextFieldState, val text: String, val selection: TextRange, val imeVisible: Boolean,
    )
    internal var captured: Capture? = null
        private set

    fun capture(state: TextFieldState, imeVisible: Boolean) {
        captured = Capture(state, state.text.toString(), state.selection, imeVisible)
    }

    fun clear() { captured = null }

    fun captureSurfaceIme(state: TextFieldState, surfaceFocus: EditorSurfaceFocusMemory, imeVisible: Boolean) {
        surfaceFocus.imeBeforeSurface = restoreSelection(state) ?: imeVisible
    }

    /** Restore only the same buffer/selection or BTF2's bare collapse to the saved maximum. */
    fun restoreSelection(state: TextFieldState): Boolean? {
        val saved = captured ?: return null
        return if (!saved.canRestoreSelection(state)) {
            clear()
            null
        } else {
            if (state.selection != saved.selection) state.edit { selection = saved.selection }
            saved.imeVisible
        }
    }

    private fun Capture.canRestoreSelection(state: TextFieldState): Boolean {
        val selectionMatches = state.selection == selection || state.selection == TextRange(selection.max)
        return state === this.state && state.text.toString() == text && selectionMatches
    }
}

/** Keep surface capture before host dispatch, including typed shortcuts and events opening a surface. */
private fun EditorActionBarActions.withScaffoldFocus(
    captureIme: () -> Unit, body: TextFieldState, tools: EditorBodyToolFocus, focus: FocusRequester,
) = copy(
    onBbcode = { action -> tools.restoreSelection(body); onBbcode(action) },
    onGesture = { gesture ->
        if (gesture == EditorBarGesture.OpenSurface) captureIme()
        onGesture(gesture)
    },
    onEvent = { key, action ->
        if (action != EditorEventAction.Dismiss) {
            captureIme()
            onGesture(EditorBarGesture.OpenSurface)
        }
        onEvent(key, action)
    },
    onBodyFocus = { requestEditorBodyFocus(body, focus, tools::clear); onBodyFocus() },
)

/** Native clicks own the default case; closed-return clicks must first enable a new input session. */
private fun editorBodyClick(
    restoreImeOnFocus: Boolean, focus: FocusRequester, onRestoreIme: () -> Unit,
): (() -> Boolean)? = if (restoreImeOnFocus) null else {
    { onRestoreIme(); focus.requestFocus(); true }
}

/** Observe a completed short tap without consuming the field's selection or scroll gestures. */
private fun Modifier.onEditorBodyTap(state: TextFieldState, onTap: () -> Unit): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val timeout = viewConfiguration.longPressTimeoutMillis
        val up = withTimeoutOrNull(timeout) { waitForUpOrCancellation(PointerEventPass.Initial) }
        if (up != null && up.uptimeMillis - down.uptimeMillis < timeout &&
            (up.position - down.position).getDistance() <= viewConfiguration.touchSlop) {
            onTap()
        }
    }
}

/** System back for the whole editor; composed before the bar's and overlay's own handlers (ED-85). */
@Composable
private fun EditorSystemBack(
    onSystemBack: (() -> Unit)?, armed: Boolean, onDisarm: () -> Unit, onClose: () -> Unit, onInteraction: () -> Unit,
) {
    BackHandler {
        onInteraction()
        when {
            onSystemBack != null -> onSystemBack()
            armed -> onDisarm()
            else -> onClose()
        }
    }
}

/** Tab moves focus; both key edges are consumed so a tab never enters the BBCode buffer. */
private fun Modifier.tabMovesFocus(focusManager: FocusManager): Modifier = onPreviewKeyEvent {
    if (it.key != Key.Tab) return@onPreviewKeyEvent false
    if (it.type == KeyEventType.KeyDown) {
        focusManager.moveFocus(if (it.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
    }
    true
}
