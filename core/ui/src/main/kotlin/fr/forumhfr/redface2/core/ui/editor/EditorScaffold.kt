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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
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
    var imeBeforeSurface: Boolean = false
    var autoFocusConsumed: Boolean = false
    internal var body by mutableStateOf(EditorBodyFocusMemory())
        private set

    fun reset() {
        imeBeforeSurface = false
        autoFocusConsumed = false
        body.reset()
        body = EditorBodyFocusMemory()
    }

    companion object {
        /** Entry/surface booleans only; the body focus/IME snapshot never survives process death. */
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
 * Surface gestures capture IME visibility before dispatch; captured returns restore focus synchronously
 * and restore that visibility. An initial panel preserves selection while waiting for closed-IME options.
 * Other focus requests open the IME by default; a return or recreation
 * with a previously closed IME suppresses automatic opening until a completed body tap, accessibility
 * click or surface capture. Suppression is local to this composition and is not saved.
 * [autoFocus] requests body focus once on entry: a recreation that keeps [surfaceFocus] never repeats it (R2).
 * [surfaceFocus] owns the consumed autofocus and the captured IME state across recreation: pass host-retained
 * memory; without one the default saves its two booleans, so it stays consumed even after process death.
 * [bodyFocusRestoreAllowed] is supplied explicitly by every host: false while any panel, sheet,
 * external surface or preview covers the body. Only the editable body passed to [fieldArea] is guarded:
 * it refuses fresh focus entry, including from the new window; other [fieldArea] controls remain reachable.
 * A retained body's focus is restored once after detach,
 * with its actual pre-pause IME visibility; a blocked restoration is discarded, leaving panel returns
 * to their existing callbacks. This rotation snapshot is never saved by [EditorSurfaceFocusMemory.Saver].
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
    RestoreRecreatedEditorBodyFocus(bodyFocus, bodyFocusRestoreAllowed, bodyState, bodyFocusRequester)
    var pendingBodyFocus by remember(bodyState) { mutableStateOf(false) }
    val captureIme = {
        initialFocus = false
        surfaceFocus.imeBeforeSurface = imeVisible
        restoreImeOnFocus = false
        if (armed) submitActions.onDisarm()
    }
    val onReturnToBody: () -> Unit = {
        initialFocus = false
        restoreImeOnFocus = surfaceFocus.imeBeforeSurface
        pendingBodyFocus = true
    }
    // Wait until the input options and host guard have recomposed after closing the surface.
    // Use H's selection-preserving path; an already focused field still needs explicit IME restore.
    LaunchedEffect(pendingBodyFocus) {
        if (pendingBodyFocus) {
            if (bodyFocusRestoreAllowed) {
                requestEditorBodyFocus(bodyState, bodyFocusRequester)
                if (restoreImeOnFocus) keyboard?.show() else keyboard?.hide()
            }
            pendingBodyFocus = false // A covered body must not receive a deferred return later.
        }
    }
    val actions = barActions.withScaffoldFocus(captureIme, bodyFocusRequester)
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
    EditorSystemBack(onSystemBack, armed, { submitActions.onDisarm() }, onCloseRequested)
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(editorHorizontalInsets())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
            overlay(onReturnToBody) {
                Column(Modifier.fillMaxSize().semantics { paneTitle = title; isTraversalGroup = true }
                    .testTag("editor-scaffold-column")) {
                    EditorAppBar(title, context, onCloseRequested, { open(barActions.onPreview) },
                        modifier = Modifier.semantics { isTraversalGroup = true; traversalIndex = 0f }
                            .testTag("editor-scaffold-app-bar"),
                        firstStatus = status(chrome.firstStatus), secondStatus = status(chrome.secondStatus),
                        onOpenHeader = header, headerActionLabel = headerLabel, compactHeight = metrics.compactHeight,
                        previewEnabled = chrome.controlsEnabled, headerEnabled = chrome.controlsEnabled)
                    Spacer(Modifier.height(metrics.columnSpacing / 2))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        fieldArea {
                            Box(Modifier.guardEditorBodyFocus(bodyFocusRestoreAllowed)) {
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
                                    autoFocus = initialFocus, showKeyboardOnFocus = initialFocus || restoreImeOnFocus,
                                    caretRevealResetKey = bodyCaretRevealResetKey,
                                    onSemanticsClick = editorBodyClick(restoreImeOnFocus, bodyFocusRequester) {
                                        restoreImeOnFocus = true
                                    })
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
                            loadingForm = chrome.loadingForm, insertionsEnabled = !chrome.bodyReadOnly)
                    }
                }
            }
        }
    }
}

/** A new window can attempt native focus entry independently of our restoration effect. */
private fun Modifier.guardEditorBodyFocus(allowed: Boolean): Modifier = focusProperties {
    onEnter = { if (!allowed) cancelFocusChange() }
}.focusGroup()

/** Keep surface capture before host dispatch, including events which open their own surface. */
private fun EditorActionBarActions.withScaffoldFocus(captureIme: () -> Unit, focus: FocusRequester) = copy(
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
    onBodyFocus = { focus.requestFocus(); onBodyFocus() },
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
private fun EditorSystemBack(onSystemBack: (() -> Unit)?, armed: Boolean, onDisarm: () -> Unit, onClose: () -> Unit) {
    BackHandler {
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
