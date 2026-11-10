package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager

internal enum class EditorBarFocusTarget { Format, Color, Image, Return }

/**
 * Save the keyboard target, never a FocusRequester tied to the old composition. [masked] is true while
 * an event or an arm hides the tools: such a mode change is never a Back transition.
 */
@Composable
internal fun rememberEditorBarFocus(mode: EditorBarMode, masked: Boolean): EditorBarFocus {
    val saved = rememberSaveable { mutableStateOf<EditorBarFocusTarget?>(null) }
    val inputMode = LocalInputModeManager.current
    val focus = remember(saved, inputMode) { EditorBarFocus(saved, inputMode) }
    val shown = mode.takeUnless { masked }
    // Read while composing: disposing the left row clears its focus flag without a focus callback.
    val hostBack = focus.hostBackDestination(shown)
    SideEffect { focus.settle(shown, hostBack) }
    LaunchedEffect(mode, focus.pending) { focus.clearUnavailableTarget(mode) }
    return focus
}

internal class EditorBarFocus(
    private val saved: MutableState<EditorBarFocusTarget?>,
    private val inputMode: InputModeManager,
) {
    private var focused: EditorBarFocusTarget? = null
    private var sublevelFocused = false
    private var shown: EditorBarMode? = null
    private var ownGesture = false
    var pending by mutableStateOf(saved.value)
        private set

    @Composable
    fun sublevelModifier(): Modifier {
        DisposableEffect(this) {
            // A focused row can disappear without another focus callback (Back, event or arm).
            onDispose { sublevelFocused = false }
        }
        return Modifier.onFocusChanged { sublevelFocused = it.hasFocus }.focusGroup()
    }

    fun clearUnavailableTarget(mode: EditorBarMode) {
        val available = when (pending) {
            EditorBarFocusTarget.Format -> mode == EditorBarMode.Normal || mode == EditorBarMode.Format
            EditorBarFocusTarget.Color -> mode == EditorBarMode.Format
            EditorBarFocusTarget.Image -> mode == EditorBarMode.Normal
            EditorBarFocusTarget.Return -> mode == EditorBarMode.Color || mode == EditorBarMode.Image
            null -> true
        }
        if (!available) {
            pending = null
            saved.value = focused
        }
    }

    @Composable
    fun modifier(target: EditorBarFocusTarget): Modifier {
        val requester = remember { FocusRequester() }
        // Run at the destination's call site, after its focus node has been attached.
        LaunchedEffect(pending) {
            if (pending == target && inputMode.inputMode == InputMode.Keyboard) {
                requester.requestFocus()
                pending = null
            }
        }
        return Modifier.focusRequester(requester).onFocusChanged {
            if (it.isFocused) {
                focused = target
                saved.value = target
            } else if (focused == target) {
                focused = null
                if (pending == null) saved.value = null
            }
        }
    }

    /**
     * Gestures dispatched by the bar settle focus themselves: navigation, Back, insertion or surface.
     * The host must reduce them synchronously: the flag only lasts until the bar's next composition, so a
     * deferred reduction would read as a host Back (an image action would return focus to Image).
     */
    fun ownGestures(onGesture: (EditorBarGesture) -> Unit): (EditorBarGesture) -> Unit = { gesture ->
        ownGesture = true
        onGesture(gesture)
    }

    fun navigate(source: EditorBarFocusTarget, destination: EditorBarFocusTarget, action: () -> Unit) {
        navigateWhen(focused == source, destination, action)
    }

    fun navigateBack(mode: EditorBarMode, action: () -> Unit) {
        navigateWhen(hasBackFocus(), backDestination(mode), action)
    }

    /**
     * ED-85: the host resolves Back over layers this bar ignores and may reduce it without [navigateBack].
     * A shown Back transition that no bar gesture dispatched restores focus exactly like [navigateBack].
     */
    fun hostBackDestination(next: EditorBarMode?): EditorBarFocusTarget? {
        val left = shown
        val closed = left != null && next != left &&
            next == reduceEditorBar(EditorBarState(left), EditorBarGesture.Back).mode
        return if (closed && !ownGesture && hasBackFocus()) backDestination(left) else null
    }

    fun settle(next: EditorBarMode?, hostBack: EditorBarFocusTarget?) {
        shown = next
        ownGesture = false
        if (hostBack != null) target(hostBack)
    }

    private fun hasBackFocus() = sublevelFocused || focused == EditorBarFocusTarget.Format

    private fun backDestination(mode: EditorBarMode?) = when (mode) {
        EditorBarMode.Color -> EditorBarFocusTarget.Color
        EditorBarMode.Image -> EditorBarFocusTarget.Image
        else -> EditorBarFocusTarget.Format
    }

    private fun navigateWhen(hasFocus: Boolean, destination: EditorBarFocusTarget, action: () -> Unit) {
        if (hasFocus) target(destination)
        action()
    }

    private fun target(destination: EditorBarFocusTarget) {
        if (inputMode.inputMode == InputMode.Keyboard) {
            pending = destination
            saved.value = destination
        }
    }
}
