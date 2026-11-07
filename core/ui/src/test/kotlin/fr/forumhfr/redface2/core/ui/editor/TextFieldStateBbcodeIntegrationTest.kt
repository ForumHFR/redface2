package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #1512 — exercise the shared renderer and the public undo API, without synthesizing Ctrl+Z. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalFoundationApi::class, ExperimentalTestApi::class)
class TextFieldStateBbcodeIntegrationTest {
    @get:Rule
    val compose = createComposeRule()

    private val state = TextFieldState()

    @Test
    fun insertionPreservesSelectionAndUndoContract() {
        setFieldContent()
        field().performTextInput("hello ")
        assertValue("hello ", TextRange(6))
        change { state.editBbcode { text, start, end -> applyBbcodeAction(BbcodeAction.Bold, text, start, end) } }
        assertValue("hello [b][/b]", TextRange(9))
        change { state.undoState.undo() }
        assertValue("hello ", TextRange(6))
        compose.runOnIdle { assertTrue(state.undoState.canUndo) }
        change { state.undoState.undo() }
        assertValue("", TextRange.Zero)
    }

    @Test
    fun typingAfterInsertionUndoesSeparatelyThenInsertionUndoesInOneStep() {
        setFieldContent()
        field().performTextInput("hello ")
        change { state.editBbcode { text, start, end -> applyBbcodeAction(BbcodeAction.Bold, text, start, end) } }
        field().performTextInput("x")
        assertValue("hello [b]x[/b]", TextRange(10))
        change { state.undoState.undo() }
        assertValue("hello [b][/b]", TextRange(9))
        change { state.undoState.undo() }
        assertValue("hello ", TextRange(6))
        change { state.undoState.undo() }
        assertValue("", TextRange.Zero)
    }

    @Test
    fun wrappingSelectionUndoesToOriginalTextAndSelection() {
        setFieldContent()
        field().performTextInput("hello world")
        field().performTextInputSelection(TextRange(6, 11))
        change { state.editBbcode { text, start, end -> applyBbcodeAction(BbcodeAction.Bold, text, start, end) } }
        assertValue("hello [b]world[/b]", TextRange(9, 14))
        change { state.undoState.undo() }
        // À CONFIRMER at the gate: unlike probe S5, assert the public undo API restores the range.
        assertValue("hello world", TextRange(6, 11))
        change { state.undoState.undo() }
        assertValue("", TextRange.Zero)
    }

    @Test
    fun selectionOnlyFormattingUsesLiveBufferAndDoesNotAddTextUndoEntry() {
        setFieldContent()
        field().performTextInput("hello")
        field().performTextInputSelection(TextRange(1, 4))
        change {
            state.editBbcode { text, start, end ->
                assertEquals("hello", text)
                assertEquals(1, start)
                assertEquals(4, end)
                BbcodeFormatResult(text, Int.MAX_VALUE, -1)
            }
        }
        assertValue("hello", TextRange(5, 0))
        change { state.undoState.undo() }
        assertValue("", TextRange.Zero)
    }

    private fun setFieldContent() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Box(Modifier.size(320.dp, 240.dp)) {
                    BbcodeTextField(state = state, label = "Message")
                }
            }
        }
        field().requestFocus()
    }

    private fun field() = compose.onNode(hasSetTextAction())

    private fun change(action: () -> Unit) {
        compose.runOnIdle {
            action()
            Snapshot.sendApplyNotifications()
        }
        compose.waitForIdle()
    }

    private fun assertValue(text: String, selection: TextRange) {
        compose.runOnIdle {
            assertEquals(text, state.text.toString())
            assertEquals(selection, state.selection)
        }
        val semantics = field().fetchSemanticsNode().config
        assertEquals(text, semantics[SemanticsProperties.EditableText].text)
        assertEquals(selection, semantics[SemanticsProperties.TextSelectionRange])
    }
}
