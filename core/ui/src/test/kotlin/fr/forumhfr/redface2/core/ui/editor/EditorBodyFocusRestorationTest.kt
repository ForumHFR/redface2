package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorBodyFocusRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pauseBeforeTheRestorationFrameKeepsFocusPendingUntilResume() {
        val owner = BodyLifecycleOwner()
        val snapshot = EditorBodyFocusSnapshot(focused = true, imeVisible = false)
        val memory = EditorBodyFocusMemory().apply { this.snapshot = snapshot }
        val selection = TextRange(6, 1)
        val body = TextFieldState("Bonjour", selection)
        val focus = FocusRequester()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val observation = rememberEditorBodyFocus(body, memory, imeVisible = false)
                RedfaceTheme(darkTheme = false, dynamicColor = false) {
                    BbcodeTextField(body, "Message", Modifier.focusRequester(focus)
                        .observeEditorBodyFocus(observation), showKeyboardOnFocus = false)
                }
                RestoreRecreatedEditorBodyFocus(observation, true, body, focus)
                // Pause without stop after attachment, before the restoration's first frame.
                DisposableEffect(Unit) {
                    owner.registry.currentState = Lifecycle.State.STARTED
                    onDispose { }
                }
            }
        }
        // Let the frame pass while paused: no focus request and no consumed snapshot.
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.runOnIdle {
            assertEquals(snapshot, memory.snapshot)
            assertEquals(selection, body.selection)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(snapshot, memory.snapshot)
            assertEquals(selection, body.selection)
        }
    }

    private class BodyLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
