@file:Suppress("MatchingDeclarationName") // Session snapshot and attachment share the restoration boundary.

package fr.forumhfr.redface2.core.ui.editor

import android.content.pm.ApplicationInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Session memory only: neither this snapshot nor an observation belongs in a Saver/checkpoint. */
internal class EditorBodyFocusMemory {
    var snapshot = EditorBodyFocusSnapshot()
    var generation = 0

    fun reset() {
        generation++ // Invalidate callbacks from a composition belonging to the previous session.
        snapshot = EditorBodyFocusSnapshot()
    }
}

internal data class EditorBodyFocusSnapshot(val focused: Boolean = false, val imeVisible: Boolean = false)

/** One field attachment. Old window callbacks cannot overwrite the next attachment's snapshot. */
internal class EditorBodyFocusObservation(
    private val memory: EditorBodyFocusMemory, imeVisible: Boolean,
    internal val trace: EditorBodyFocusTrace = EditorBodyFocusTrace(enabled = false),
    private val isChangingConfigurations: () -> Boolean = { false },
) {
    val restore = memory.snapshot.takeIf { it.focused }
    val showImeOnRestore = restore?.imeVisible != false
    private val generation = ++memory.generation
    var focused by mutableStateOf(false)
        private set
    private var imeVisible = imeVisible
    private var frozen by mutableStateOf(false)
    private var detached = false
    private var restoring = restore != null
    var restorationCancelled by mutableStateOf(false)
        private set
    var cancellationReason: String? = null
        private set
    val canRestore: Boolean get() = !frozen && !detached && generation == memory.generation

    fun cancelRestoration(reason: String = "editor-action") {
        if (!restorationCancelled) cancellationReason = reason
        restorationCancelled = true
    }
    fun focusChanged(value: Boolean) { focused = value; record() }
    fun imeChanged(value: Boolean) { imeVisible = value; record() }
    fun restored() { restoring = false; record() }
    fun resume() { frozen = false; record() }
    fun freeze() { record(); frozen = true }
    fun detach() { freeze(); detached = true }

    private fun record() {
        // The outgoing window can report closed IME insets before ON_PAUSE freezes this attachment.
        // Query the Activity at callback time; preserve the last live snapshot throughout relaunch.
        if (!canRestore || restoring || isChangingConfigurations()) return
        memory.snapshot = EditorBodyFocusSnapshot(focused, imeVisible)
    }
}

@Composable
internal fun rememberEditorBodyFocus(
    state: TextFieldState, memory: EditorBodyFocusMemory, imeVisible: Boolean,
): EditorBodyFocusObservation {
    val owner = LocalLifecycleOwner.current
    val activity = LocalActivity.current
    val context = LocalContext.current
    val observation = remember(state, memory, owner, activity, context) {
        // Library has no BuildConfig: use the application's debug flag, never log in release.
        val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        EditorBodyFocusObservation(memory, imeVisible, EditorBodyFocusTrace(debug)) {
            activity?.isChangingConfigurations == true
        }
    }
    DisposableEffect(owner, observation) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Also protects pauses without a configuration change, before descendant detach.
                Lifecycle.Event.ON_PAUSE -> observation.freeze()
                Lifecycle.Event.ON_RESUME -> observation.resume()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            observation.detach()
            owner.lifecycle.removeObserver(observer)
        }
    }
    SideEffect { observation.imeChanged(imeVisible) }
    return observation
}

/** Attach to the body's enclosing Box: it detaches before BTF2 releases its descendant focus. */
internal fun Modifier.observeEditorBodyFocus(observation: EditorBodyFocusObservation): Modifier =
    then(EditorBodyFocusElement(observation))

private data class EditorBodyFocusElement(val observation: EditorBodyFocusObservation) :
    ModifierNodeElement<EditorBodyFocusNode>() {
    override fun create() = EditorBodyFocusNode(observation)
    override fun update(node: EditorBodyFocusNode) {
        node.observation = observation
        observation.focusChanged(node.focused)
    }
}

private class EditorBodyFocusNode(var observation: EditorBodyFocusObservation) :
    Modifier.Node(), FocusEventModifierNode {
    var focused = false
        private set

    override fun onFocusEvent(focusState: FocusState) {
        focused = focusState.isFocused
        observation.focusChanged(focused)
    }

    override fun onDetach() { observation.detach() }
}

internal enum class EditorBodyFocusAvailability { Pending, Ready, Blocked }

/**
 * Wait for Ready, including through a transient Blocked, then allow a frame to apply the source guard.
 * User input or lost eligibility cancels the attempt. The whole attempt expires after 500 ms: report
 * preview-blocked if still Blocked, otherwise deadline, without reclaiming focus on a later resize.
 */
@Composable
internal fun RestoreRecreatedEditorBodyFocus(
    observation: EditorBodyFocusObservation, allowed: Boolean, state: TextFieldState, focus: FocusRequester,
    availability: EditorBodyFocusAvailability = EditorBodyFocusAvailability.Ready,
) {
    val currentAllowed by rememberUpdatedState(allowed)
    val currentAvailability by rememberUpdatedState(availability)
    val restoreOnAttach = remember(observation) { allowed && observation.restore != null }
    SideEffect { observation.trace.availability(availability) }
    LaunchedEffect(observation) {
        if (restoreOnAttach) {
            val attempt = EditorBodyFocusRestorationAttempt(observation, state,
                allowed = { currentAllowed }, availability = { currentAvailability })
            var outcome = "attachment-disposed"
            try {
                val completed = withTimeoutOrNull(BODY_FOCUS_RESTORATION_TIMEOUT_MS) {
                    if (attempt.awaitReady()) {
                        outcome = if (requestEditorBodyFocus(state, focus)) "restored" else "focus-refused"
                    } else outcome = attempt.cancellationReason
                    true
                }
                if (completed == null) outcome =
                    if (currentAvailability == EditorBodyFocusAvailability.Blocked) "preview-blocked" else "deadline"
            } finally {
                observation.trace.outcome(outcome)
            }
        } else {
            observation.trace.outcome(if (observation.restore == null) "no-snapshot" else "host-blocked")
        }
        observation.restored()
    }
}

/** One attempt's input checkpoint, with live eligibility shared by both waits and the final check. */
private class EditorBodyFocusRestorationAttempt(
    private val observation: EditorBodyFocusObservation,
    private val state: TextFieldState,
    private val allowed: () -> Boolean,
    private val availability: () -> EditorBodyFocusAvailability,
) {
    private val text = state.text.toString()
    private val selection = state.selection
    private val untouched: Boolean
        get() = !observation.restorationCancelled && state.text.toString() == text && state.selection == selection
    private val eligible: Boolean get() = allowed() && untouched
    private val ready: Boolean get() = eligible && availability() == EditorBodyFocusAvailability.Ready
    val cancellationReason: String
        get() = when {
            observation.restorationCancelled -> "cancelled:${observation.cancellationReason}"
            state.text.toString() != text -> "cancelled:text"
            state.selection != selection -> "cancelled:selection"
            !allowed() -> "host-blocked"
            else -> "attachment-unavailable"
        }

    suspend fun awaitReady(): Boolean {
        var restorable = false
        while (eligible && !restorable) {
            snapshotFlow { !eligible || ready }.first { it }
            if (eligible) {
                // Input options are already composed. Finish the old synchronous detach/Y' pass and let
                // the new field attach before requesting focus; do not race its selection checkpoint.
                withFrameNanos { }
                // A pause without stop can leave this composition alive when the frame arrives.
                // Wait for resume within the same deadline, without requesting focus while frozen.
                snapshotFlow { !ready || observation.canRestore }.first { it }
                // A reflow may have blocked the source again during the frame; keep the same deadline.
                restorable = ready && observation.canRestore
            }
        }
        return restorable
    }
}

private const val BODY_FOCUS_RESTORATION_TIMEOUT_MS = 500L

/** Observe the entire editor without consuming input. Keyboard mode may receive Android's default
 * focus before the first draw; without a key or pointer this is not a user's choice. Real input
 * cancels immediately, including when it never moves focus. IME text/selection changes are also checked.
 */
@Composable
internal fun Modifier.cancelEditorBodyFocusRestorationOnInteraction(
    observation: EditorBodyFocusObservation,
): Modifier {
    val inputMode = LocalInputModeManager.current
    return onFocusChanged {
        if (it.hasFocus && inputMode.inputMode != InputMode.Keyboard) observation.cancelRestoration("focus")
    }.focusGroup()
        .onPreviewKeyEvent { observation.cancelRestoration("key"); false }
        .pointerInput(observation) {
            awaitPointerEventScope {
                while (true) {
                    if (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }) {
                        observation.cancelRestoration("pointer")
                    }
                }
            }
        }
}

internal fun requestEditorBodyFocus(state: TextFieldState, focus: FocusRequester): Boolean {
    val selection = state.selection
    val focused = focus.requestFocus()
    // Both oriented endpoints, selection-only: no clearFocus, text replacement or undo entry.
    if (state.selection != selection) state.edit { this.selection = selection }
    return focused
}
