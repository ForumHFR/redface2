@file:Suppress("MatchingDeclarationName") // Session snapshot and attachment share the restoration boundary.

package fr.forumhfr.redface2.core.ui.editor

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.node.ModifierNodeElement
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

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
    private val isChangingConfigurations: () -> Boolean = { false },
) {
    val restore = memory.snapshot.takeIf { it.focused }
    val showImeOnRestore = restore?.imeVisible != false
    private val generation = ++memory.generation
    private var focused = false
    private var imeVisible = imeVisible
    private val frozen = MutableStateFlow(false)
    private var detached = false
    private var restoring = restore != null
    val canRestore: Boolean get() = !frozen.value && !detached && generation == memory.generation

    fun focusChanged(value: Boolean) { focused = value; record() }
    fun imeChanged(value: Boolean) { imeVisible = value; record() }
    fun restored() { restoring = false; record() }
    fun resume() { frozen.value = false; record() }
    fun freeze() { record(); frozen.value = true }
    fun detach() { freeze(); detached = true }
    suspend fun awaitResumed() { frozen.first { !it } }

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
    val observation = remember(state, memory, owner, activity) {
        EditorBodyFocusObservation(memory, imeVisible) { activity?.isChangingConfigurations == true }
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

/** The host's guard is checked again after suspension; blocked restoration is consumed, never deferred. */
@Composable
internal fun RestoreRecreatedEditorBodyFocus(
    observation: EditorBodyFocusObservation, allowed: Boolean, state: TextFieldState, focus: FocusRequester,
) {
    val currentAllowed by rememberUpdatedState(allowed)
    val restoreOnAttach = remember(observation) { allowed && observation.restore != null }
    LaunchedEffect(observation) {
        if (restoreOnAttach) {
            // Input options are already composed. Finish the old synchronous detach/Y' pass and let
            // the new field attach before requesting focus; do not race its selection checkpoint.
            withFrameNanos { }
            // A pause without stop can leave this composition alive when the frame arrives.
            // Keep the snapshot pending until resume rather than consuming it while frozen.
            observation.awaitResumed()
            if (currentAllowed && observation.canRestore) requestEditorBodyFocus(state, focus)
        }
        observation.restored()
    }
}

internal fun requestEditorBodyFocus(state: TextFieldState, focus: FocusRequester) {
    val selection = state.selection
    focus.requestFocus()
    // Both oriented endpoints, selection-only: no clearFocus, text replacement or undo entry.
    if (state.selection != selection) state.edit { this.selection = selection }
}
