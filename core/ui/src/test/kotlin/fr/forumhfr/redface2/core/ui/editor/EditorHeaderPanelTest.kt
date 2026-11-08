package fr.forumhfr.redface2.core.ui.editor

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorHeaderPanelTest {
    @get:Rule val compose = createComposeRule()
    private val open = mutableStateOf(false)
    private val bodyFocus = FocusRequester()
    private val titleFocus = FocusRequester()
    private lateinit var dispatcher: OnBackPressedDispatcher
    private lateinit var bodyState: TextFieldState
    private lateinit var bodyScroll: ScrollState
    private lateinit var inputMode: InputModeManager
    private var disposals = 0
    private var closes = 0
    private var leaves = 0
    private var bodyClicks = 0

    @Test
    fun `ED101 title receives focus and Return or Back preserves body composition selection scroll and undo`() {
        val initialBody = (1..30).joinToString("\n") { "Ligne $it" }
        setPanel(initialBody = initialBody)
        compose.runOnIdle { bodyFocus.requestFocus() }
        compose.onNodeWithTag("body").performTextInput("Ajout ")
        val selection = compose.runOnIdle { TextRange(bodyState.text.length - 4, bodyState.text.length - 1) }
        val stateBefore = bodyState
        val scrollBefore = bodyScroll
        val textBefore = compose.runOnIdle { bodyState.text.toString() }
        listOf(selection, TextRange(selection.end, selection.start)).forEachIndexed { index, range ->
            compose.onNodeWithTag("body").performTextInputSelection(range)
            val offsetBefore = compose.runOnIdle { bodyScroll.value }
            compose.runOnIdle {
                assertEquals(range, bodyState.selection)
                assertTrue(offsetBefore > 0)
                assertTrue(bodyState.undoState.canUndo)
                open.value = true
            }
            compose.onNodeWithTag("title").assertIsFocused()
            compose.runOnIdle { assertEquals(range, bodyState.selection) }
            if (index == 0) compose.onNodeWithText("Retour au message").performClick()
            else compose.runOnIdle { dispatcher.onBackPressed() }
            compose.onNodeWithTag("body").assertIsFocused()
            compose.runOnIdle {
                assertSame(stateBefore, bodyState)
                assertSame(scrollBefore, bodyScroll)
                assertEquals(range, bodyState.selection)
                assertEquals(textBefore, bodyState.text.toString())
                assertEquals(offsetBefore, bodyScroll.value)
                assertTrue(bodyState.undoState.canUndo)
                assertEquals(0, disposals)
                assertEquals(index + 1, closes)
            }
        }
        compose.runOnIdle { bodyState.undoState.undo() }
        compose.runOnIdle { assertEquals(initialBody, bodyState.text.toString()) }
    }

    @Test
    fun `ED85 auto opened panel consumes first system Back then editor receives the next`() {
        open.value = true
        setPanel()
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.onNodeWithTag("body").assertIsFocused()
        compose.runOnIdle {
            assertFalse(open.value)
            assertEquals(1, closes)
            assertEquals(0, leaves)
            dispatcher.onBackPressed()
            assertEquals(1, leaves)
            assertEquals(1, closes)
        }
    }

    @Test
    fun `ED85 ED93 covered body has no semantics and rejects direct focus and Tab entry`() {
        setPanel()
        val coveredTarget = compose.onNodeWithTag("body-action").fetchSemanticsNode().boundsInRoot.center
        compose.runOnIdle {
            assertTrue(inputMode.requestInputMode(InputMode.Keyboard))
            bodyFocus.requestFocus()
            assertTrue(bodyFocus.captureFocus())
            open.value = true
        }
        compose.onNodeWithTag("title").assertIsFocused()
        compose.onNodeWithTag("body").assertDoesNotExist()
        compose.onNodeWithTag("body-action").assertDoesNotExist()
        compose.onNodeWithTag("body", useUnmergedTree = true).assertExists().assertIsNotFocused()
        val hidden = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility),
            useUnmergedTree = true)
        assertTrue(hidden.fetchSemanticsNode().config.isClearingSemantics)
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
        compose.runOnIdle { assertFalse(bodyFocus.requestFocus()) }
        compose.onNodeWithTag("title").assertIsFocused()
        compose.onNodeWithTag("title").performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        val returnButton = compose.onNodeWithText("Retour au message")
        returnButton.assertIsFocused().performKeyInput { pressKey(Key.Tab) }
        val title = compose.onNodeWithTag("title")
        title.assertIsFocused().performKeyInput { pressKey(Key.Tab) }
        title.assertIsFocused() // Forward traversal at the last control is bounded.
        returnButton.requestFocus().performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        returnButton.assertIsFocused() // Backward traversal at the first control is bounded.
        compose.onNodeWithTag("title").assertTextEquals("Titre fourni")
        compose.onNodeWithTag("body", useUnmergedTree = true).assertIsNotFocused()
        compose.onRoot().performTouchInput { click(coveredTarget) }
        compose.runOnIdle { assertEquals(0, bodyClicks) }
    }

    @Test
    fun `ED113 recipient slot can receive initial focus and a disabled supplied field stays disabled`() {
        open.value = true
        setPanel(recipients = true)
        compose.onNodeWithTag("title").assertIsFocused().assertTextEquals("Alice")
        compose.onNodeWithTag("disabled-subject").assertIsNotEnabled()
        compose.onNodeWithText("Retour au message").performClick()
        compose.onNodeWithTag("body").assertIsFocused()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    private fun setPanel(recipients: Boolean = false, initialBody: String = "Message initial") {
        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            inputMode = LocalInputModeManager.current
            bodyState = rememberTextFieldState(initialBody)
            RedfaceTheme {
                BackHandler { leaves++ }
                EditorHeaderPanel(
                    isOpen = open.value,
                    title = if (recipients) "Destinataires et sujet" else "Titre et sous-catégorie",
                    bodyState = bodyState,
                    onReturnToBody = { bodyFocus.requestFocus() },
                    initialFocusRequester = titleFocus,
                    onClose = { closes++; open.value = false },
                    modifier = Modifier.fillMaxSize(),
                    body = { Body() },
                ) {
                    val field = remember { TextFieldState(if (recipients) "Alice" else "Titre fourni") }
                    BasicTextField(field, Modifier.focusRequester(titleFocus).testTag("title"),
                        lineLimits = TextFieldLineLimits.SingleLine)
                    if (recipients) OutlinedTextField("Sujet fourni", {}, Modifier.testTag("disabled-subject"),
                        enabled = false, label = { Text("Sujet") })
                }
            }
        }
    }

    @Composable
    private fun Body() {
        bodyScroll = rememberScrollState()
        DisposableEffect(Unit) { onDispose { disposals++ } }
        Column {
            BasicTextField(bodyState, Modifier.fillMaxWidth().height(120.dp)
                .focusRequester(bodyFocus).testTag("body"), scrollState = bodyScroll)
            Button(onClick = { bodyClicks++ }, modifier = Modifier.testTag("body-action")) {
                Text("Action du corps")
            }
        }
    }
}
