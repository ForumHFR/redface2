package fr.forumhfr.redface2.core.ui.editor

import androidx.activity.OnBackPressedCallback
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView

/**
 * M3 owns a ComponentDialog with its own predictive callback. The ambient Compose dispatcher
 * can still be the Activity's, inherited across the dialog subcomposition. Resolve the VIEW
 * tree instead, and register after M3: a committed back closes only the detail, while a
 * cancelled predictive gesture does nothing. Outside taps and swipes still dismiss the sheet.
 */
@Composable
internal fun SmileyDetailBackHandler(state: SmileyPickerState.Open) {
    val dispatcher = LocalView.current.findViewTreeOnBackPressedDispatcherOwner()?.onBackPressedDispatcher
    val currentState = rememberUpdatedState(state)
    val callback = remember {
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                currentState.value.onDetailChange?.invoke(null)
            }
        }
    }
    SideEffect { callback.isEnabled = state.detailSmiley != null }
    DisposableEffect(dispatcher) {
        dispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }
}
