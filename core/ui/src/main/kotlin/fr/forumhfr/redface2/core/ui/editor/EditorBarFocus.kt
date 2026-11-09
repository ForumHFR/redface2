package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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

/** Save the keyboard target, never a FocusRequester tied to the old composition. */
@Composable
internal fun rememberEditorBarFocus(mode: EditorBarMode): EditorBarFocus {
    val saved = rememberSaveable { mutableStateOf<EditorBarFocusTarget?>(null) }
    val inputMode = LocalInputModeManager.current
    val focus = remember(saved, inputMode) { EditorBarFocus(saved, inputMode) }
    LaunchedEffect(mode, focus.pending) { focus.clearUnavailableTarget(mode) }
    return focus
}

internal class EditorBarFocus(
    private val saved: MutableState<EditorBarFocusTarget?>,
    private val inputMode: InputModeManager,
) {
    private var focused: EditorBarFocusTarget? = null
    private var sublevelFocused = false
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

    fun navigate(source: EditorBarFocusTarget, destination: EditorBarFocusTarget, action: () -> Unit) {
        navigateWhen(focused == source, destination, action)
    }

    fun navigateBack(mode: EditorBarMode, action: () -> Unit) {
        val destination = when (mode) {
            EditorBarMode.Color -> EditorBarFocusTarget.Color
            EditorBarMode.Image -> EditorBarFocusTarget.Image
            else -> EditorBarFocusTarget.Format
        }
        navigateWhen(sublevelFocused || focused == EditorBarFocusTarget.Format, destination, action)
    }

    private fun navigateWhen(hasFocus: Boolean, destination: EditorBarFocusTarget, action: () -> Unit) {
        if (hasFocus && inputMode.inputMode == InputMode.Keyboard) {
            pending = destination
            saved.value = destination
        }
        action()
    }
}
