package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #447 — controlled TextFieldValue bridge around the state-based BBCode field. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BbcodeTextFieldCaretOnlyFollowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun parentTextAndSelectionAreAppliedDownward() {
        val fixture = setFieldContent(TextFieldValue("before", TextRange(2)))
        val replacement = TextFieldValue("replacement", TextRange(3, 8))

        composeTestRule.runOnIdle { fixture.value.value = replacement }
        composeTestRule.waitForIdle()

        assertFieldValue(replacement)
        assertTrue("a parent update must not echo to the callback", fixture.emissions.isEmpty())
    }

    @Test
    fun typingEmitsTextSelectionAndNoLegacyComposition() {
        val fixture = setFieldContent(TextFieldValue("abcd", TextRange(2)))
        focusField()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("X")
        composeTestRule.waitUntil(timeoutMillis = 5_000) { fixture.emissions.isNotEmpty() }

        val expected = TextFieldValue("abXcd", TextRange(3), composition = null)
        assertEquals(listOf(expected), fixture.emissions)
        assertFieldValue(expected)
    }

    @Test
    fun receivedValueEqualToStateDoesNotEmit() {
        val fixture = setFieldContent(TextFieldValue("same", TextRange(2)))

        composeTestRule.runOnIdle {
            fixture.value.value = fixture.value.value.copy(composition = TextRange(0, 2))
        }
        composeTestRule.waitForIdle()

        assertFieldValue(TextFieldValue("same", TextRange(2)))
        assertTrue(fixture.emissions.isEmpty())
    }

    @Test
    fun parentSelectionNormalizationConvergesWithoutAnEmissionLoop() {
        val fixture = setFieldContent(
            initial = TextFieldValue("abcd", TextRange(2)),
            normalize = { changed -> changed.copy(selection = TextRange(0)) },
        )
        focusField()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("X")
        composeTestRule.waitUntil(timeoutMillis = 5_000) { fixture.emissions.isNotEmpty() }
        composeTestRule.waitForIdle()

        val emitted = TextFieldValue("abXcd", TextRange(3), composition = null)
        assertEquals(listOf(emitted), fixture.emissions)
        assertFieldValue(emitted.copy(selection = TextRange(0)))
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun selectionOnlyResyncDoesNotCreateATextUndoEntry() {
        val initial = TextFieldValue("draft", TextRange(5))
        val fixture = setBridgeContent(initial)
        val movedSelection = initial.copy(selection = TextRange(0))

        composeTestRule.runOnIdle { fixture.value.value = movedSelection }
        composeTestRule.waitForIdle()

        assertTextFieldStateValue(fixture.fieldState, movedSelection)
        assertFalse(
            "selection-only parent sync must not replace text or create an undo entry",
            fixture.fieldState.undoState.canUndo,
        )
        assertTrue("a parent selection resync must not echo", fixture.emissions.isEmpty())
    }

    @Test
    fun toolbarLikeInsertionLandsAtParentSelection() {
        val fixture = setFieldContent(TextFieldValue("hello world", TextRange(5)))
        val insertion = TextFieldValue(
            text = "hello[b][/b] world",
            selection = TextRange(8),
        )

        composeTestRule.runOnIdle { fixture.value.value = insertion }
        composeTestRule.waitForIdle()

        assertFieldValue(insertion)
        assertTrue(fixture.emissions.isEmpty())
    }

    private fun setFieldContent(
        initial: TextFieldValue,
        normalize: (TextFieldValue) -> TextFieldValue = { it },
    ): FieldFixture {
        lateinit var value: MutableState<TextFieldValue>
        val emissions = mutableListOf<TextFieldValue>()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    value = remember { mutableStateOf(initial) }
                    Box(Modifier.size(320.dp, 240.dp)) {
                        BbcodeTextField(
                            value = value.value,
                            onValueChange = { changedValue ->
                                emissions += changedValue
                                value.value = normalize(changedValue)
                            },
                            label = "Message",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        return FieldFixture(value = value, emissions = emissions)
    }

    private fun setBridgeContent(initial: TextFieldValue): BridgeFixture {
        lateinit var value: MutableState<TextFieldValue>
        lateinit var fieldState: TextFieldState
        val emissions = mutableListOf<TextFieldValue>()
        composeTestRule.setContent {
            value = remember { mutableStateOf(initial) }
            fieldState = rememberTextFieldState(
                initialText = initial.text,
                initialSelection = initial.selection,
            )
            BbcodeTextFieldValueBridge(
                fieldState = fieldState,
                value = value.value,
                onValueChange = { emissions += it },
            )
        }
        composeTestRule.waitForIdle()
        return BridgeFixture(value = value, fieldState = fieldState, emissions = emissions)
    }

    private fun focusField() {
        composeTestRule.onNode(hasSetTextAction()).requestFocus()
        composeTestRule.waitForIdle()
    }

    private fun assertFieldValue(expected: TextFieldValue) {
        val config = composeTestRule.onNode(hasSetTextAction()).fetchSemanticsNode().config
        assertEquals(expected.text, config[SemanticsProperties.EditableText].text)
        assertEquals(expected.selection, config[SemanticsProperties.TextSelectionRange])
    }

    private fun assertTextFieldStateValue(state: TextFieldState, expected: TextFieldValue) {
        assertEquals(expected.text, state.text.toString())
        assertEquals(expected.selection, state.selection)
    }

    private data class FieldFixture(
        val value: MutableState<TextFieldValue>,
        val emissions: MutableList<TextFieldValue>,
    )

    private data class BridgeFixture(
        val value: MutableState<TextFieldValue>,
        val fieldState: TextFieldState,
        val emissions: MutableList<TextFieldValue>,
    )
}
