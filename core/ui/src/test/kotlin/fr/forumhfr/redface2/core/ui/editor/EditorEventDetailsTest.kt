package fr.forumhfr.redface2.core.ui.editor

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
class EditorEventDetailsTest {
    @get:Rule val compose = createComposeRule()
    private val session = EditorSession("panels", "alice", "topic:23:42")
    private val model = mutableStateOf(EditorEventState(session))
    private val detail = mutableStateOf<EditorEvent?>(null)
    private val header = mutableStateOf(false)
    private val bodyFocus = FocusRequester()
    private val headerFocus = FocusRequester()
    private val reloads = mutableListOf<EventKey>()
    private val ignored = mutableListOf<EventKey>()
    private val closed = mutableListOf<EventKey>()
    private val hostClose: (EventKey) -> Unit = { closed += it; dispatch(EditorEventInput.CloseDetails(it)) }
    private lateinit var dispatcher: OnBackPressedDispatcher
    private lateinit var inputMode: InputModeManager
    private lateinit var bodyState: TextFieldState
    private var leaves = 0
    private var quotePending = true
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `ED45 ED132 reload and ignore coexist with separate exact keyed callbacks and no implicit close`() {
        val event = event(EditorErrors.quoteFetchFailed)
        receive(event)
        setHost(ignoreAvailable = true)
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        activate(event)
        compose.onNodeWithText(event.detail!!.resolve(context)).assertExists()
        compose.onNodeWithText("Contexte fourni").assertExists()
        compose.onAllNodes(hasClickAction()).assertCountEquals(3)
        compose.onNodeWithText("Recharger").performClick()
        compose.runOnIdle {
            assertEquals(listOf(event.key), reloads)
            assertTrue(ignored.isEmpty())
            assertTrue(closed.isEmpty())
            assertTrue(quotePending)
            assertEquals(event.key, model.value.detailKey)
        }
        compose.onNodeWithText("Ignorer la citation").performClick()
        compose.runOnIdle {
            assertEquals(listOf(event.key), ignored)
            assertEquals(listOf(event.key), reloads)
            assertFalse(quotePending)
            assertTrue(closed.isEmpty())
        }
        compose.onNodeWithText("Masquer").performClick()
        compose.onNodeWithTag("body").assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf(event.key), closed)
            assertTrue(model.value.events.isEmpty())
            assertNull(model.value.detailKey)
        }
    }

    @Test
    fun `ED45 section8 every detail has full text and only its cause remedies including information`() {
        setHost(ignoreAvailable = true)
        val contents = editorErrorSamples.map { EditorEventContent.Problem(it) } + listOf(
            EditorEventContent.Finished(EditorErrors.network.summary, EditorErrors.network.detail),
            EditorEventContent.Information(EditorErrors.network.summary, EditorErrors.network.detail),
        )
        contents.mapIndexed { index, content ->
            event(EditorErrors.network, index.toLong() + 1).copy(content = content)
        }
            .filter { it.action == EditorEventAction.Details }.forEach { event ->
                compose.runOnIdle { receive(event) }
                activate(event)
                compose.onNodeWithText(requireNotNull(event.detail).resolve(context)).assertExists()
                val error = (event.content as? EditorEventContent.Problem)?.error
                val hasReload = error?.action == EditorErrorAction.Reload
                val hasIgnore = error?.cause == EditorCause.QuoteFetchFailed
                compose.onAllNodes(hasClickAction()).assertCountEquals(
                    1 + (if (hasReload) 1 else 0) + (if (hasIgnore) 1 else 0),
                )
                if (hasReload) compose.onNodeWithText("Recharger").assertExists()
                else compose.onNodeWithText("Recharger").assertDoesNotExist()
                if (hasIgnore) compose.onNodeWithText("Ignorer la citation").assertExists()
                else compose.onNodeWithText("Ignorer la citation").assertDoesNotExist()
                compose.onNodeWithText("Masquer").performClick()
                compose.onNodeWithTag("body").assertIsFocused()
            }
        compose.runOnIdle {
            assertTrue(reloads.isEmpty())
            assertTrue(ignored.isEmpty())
            assertTrue(quotePending)
        }
    }

    @Test
    fun `ED85 detail consumes Back acknowledges exact key returns body focus and preserves pending quote`() {
        val event = event(EditorErrors.quoteFetchFailed)
        receive(event)
        setHost()
        compose.runOnIdle { bodyFocus.requestFocus() }
        val selection = TextRange(6, 1)
        val textBefore = compose.runOnIdle { bodyState.text.toString() }
        compose.onNodeWithTag("body").performTextInputSelection(selection)
        // R0a A / ED-22 keep preserves the selected extent; the field may normalize its direction.
        compose.runOnIdle {
            assertEquals(selection.min, bodyState.selection.min)
            assertEquals(selection.max, bodyState.selection.max)
        }
        activate(event)
        compose.runOnIdle {
            assertEquals(selection.min, bodyState.selection.min)
            assertEquals(selection.max, bodyState.selection.max)
            assertEquals(textBefore, bodyState.text.toString())
        }
        compose.onNodeWithText("Ignorer la citation").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.onNodeWithTag("body").assertIsFocused()
        compose.runOnIdle {
            assertEquals(selection.min, bodyState.selection.min)
            assertEquals(selection.max, bodyState.selection.max)
            assertEquals(textBefore, bodyState.text.toString())
            assertEquals(listOf(event.key), closed)
            assertTrue(quotePending)
            assertEquals(0, leaves)
            dispatcher.onBackPressed()
            assertEquals(1, leaves)
        }
    }

    @Test
    fun `ED45 closing an old snapshot cannot acknowledge its replacement event`() {
        val old = event(EditorErrors.quoteFetchFailed)
        val replacement = event(EditorErrors.formNetwork, sequence = 2)
        receive(old)
        setHost()
        activate(old)
        compose.runOnIdle { receive(replacement) }
        compose.onNodeWithText(requireNotNull(old.detail).resolve(context)).assertExists()
        compose.onNodeWithText("Masquer").performClick()
        compose.onNodeWithTag("body").assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf(old.key), closed)
            assertEquals(listOf(replacement), model.value.events)
            assertNull(model.value.detailKey)
        }
    }

    @Test
    fun `ED45 a stale close callback neither closes nor acknowledges a newer open detail`() {
        val old = event(EditorErrors.quoteFetchFailed)
        val newer = event(EditorErrors.formNetwork, sequence = 2)
        receive(old)
        setHost()
        activate(old)
        // A late callback bound to the old detail (never a click on its disposed node) still names its own key.
        val staleClose = { hostClose(old.key) }
        compose.runOnIdle { receive(newer) }
        // Equal-rank events stay FIFO: close the old detail before the newer event is actionable.
        compose.onNodeWithText(requireNotNull(old.detail).resolve(context)).assertExists()
        compose.onNodeWithText("Masquer").performClick()
        compose.onNodeWithTag("body").assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf(old.key), closed)
            assertEquals(newer, model.value.visibleEvent)
        }
        activate(newer)
        compose.onNodeWithText(requireNotNull(newer.detail).resolve(context)).assertExists()
        compose.onNodeWithText(requireNotNull(old.detail).resolve(context)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(newer.key, model.value.detailKey) }
        // A stale key neither closes another key's detail nor returns focus (Fable gate on #1583).
        compose.runOnIdle { staleClose() }
        compose.onNodeWithText(requireNotNull(newer.detail).resolve(context)).assertExists()
        compose.runOnIdle {
            assertEquals(listOf(old.key, old.key), closed)
            assertEquals(listOf(newer), model.value.events)
            assertEquals(newer.key, model.value.detailKey)
            assertEquals(newer, detail.value)
        }
        compose.onNodeWithText("Masquer").performClick()
        compose.onNodeWithTag("body").assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf(old.key, old.key, newer.key), closed)
            assertTrue(model.value.events.isEmpty())
            assertNull(model.value.detailKey)
            assertNull(detail.value)
        }
    }

    @Test
    fun `ED45 CompleteHeader opens supplied field instead of details and Return restores body`() {
        setHost()
        listOf(EditorErrors.missingTitle, EditorErrors.missingRecipients, EditorErrors.unknownRecipient)
            .forEachIndexed { index, error ->
                val event = event(error, index.toLong() + 1)
                val selection = TextRange(index + 1, index + 3)
                compose.runOnIdle { bodyFocus.requestFocus() }
                compose.onNodeWithTag("body").performTextInputSelection(selection)
                compose.runOnIdle { receive(event) }
                activate(event, "Compléter")
                compose.onNodeWithTag("header-field").assertIsFocused()
                compose.onNodeWithText("Masquer").assertDoesNotExist()
                compose.runOnIdle {
                    assertNull(detail.value)
                    assertTrue(model.value.events.isEmpty())
                    assertEquals(selection, bodyState.selection)
                }
                compose.onNodeWithText("Retour au message").performClick()
                compose.onNodeWithTag("body").assertIsFocused()
                compose.runOnIdle { assertEquals(selection, bodyState.selection) }
            }
    }

    @Test
    fun `ED90 ED93 Tab follows remedy order and cannot enter the covered body`() {
        val event = event(EditorErrors.quoteFetchFailed)
        receive(event)
        setHost(ignoreAvailable = true)
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        activate(event)
        compose.onNodeWithTag("body").assertDoesNotExist()
        val reload = compose.onNodeWithText("Recharger")
        val dismiss = compose.onNodeWithText("Masquer")
        reload.requestFocus().performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        reload.assertIsFocused().performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithText("Ignorer la citation").assertIsFocused().performKeyInput { pressKey(Key.Tab) }
        dismiss.assertIsFocused().performKeyInput { pressKey(Key.Tab) }
        dismiss.assertIsFocused().performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        compose.onNodeWithText("Ignorer la citation").assertIsFocused()
        compose.runOnIdle { assertFalse(bodyFocus.requestFocus()) }
        compose.onNodeWithTag("body", useUnmergedTree = true).assertIsNotFocused()
    }

    private fun event(error: EditorError, sequence: Long = 1) = EditorEvent(
        EventKey(session, OperationRef.Local(EditorLocalOperation.FormLoad, sequence), 1),
        EditorEventContent.Problem(error), sequence,
    )

    private fun receive(event: EditorEvent) { dispatch(EditorEventInput.Received(event)) }

    private fun activate(event: EditorEvent, action: String = "Afficher les détails") {
        val label = hasContentDescription(event.accessibleSummary.resolve(context) + ", " + action)
        // The live region belongs to the button itself, with no second readable sibling.
        compose.onAllNodes(label, useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(label and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
            .performClick()
    }

    private fun dispatch(input: EditorEventInput) {
        val transition = reduceEditorEvents(model.value, input)
        model.value = transition.state
        transition.commands.forEach { command ->
            when (command) {
                is EditorEventCommand.OpenDetails -> detail.value = command.event
                is EditorEventCommand.CompleteHeader -> header.value = true
                EditorEventCommand.ReturnToBody -> detail.value = null
            }
        }
    }

    private fun setHost(ignoreAvailable: Boolean = false) {
        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            inputMode = LocalInputModeManager.current
            bodyState = rememberTextFieldState("Message")
            RedfaceTheme {
                BackHandler { leaves++ }
                EditorHeaderPanel(
                    header.value, "En-tête fourni", bodyState, { header.value = false }, { bodyFocus.requestFocus() },
                    Modifier.fillMaxSize(), initialFocusRequester = headerFocus,
                    body = { DetailHost(ignoreAvailable) },
                ) {
                    BasicTextField(rememberTextFieldState("Titre fourni"),
                        Modifier.focusRequester(headerFocus).testTag("header-field"))
                }
            }
        }
    }

    @Composable
    private fun DetailHost(ignoreAvailable: Boolean) {
        EditorEventDetails(
            event = detail.value,
            bodyState = bodyState,
            onReturnToBody = { bodyFocus.requestFocus() },
            onClose = hostClose,
            onReload = { reloads += it },
            onIgnoreQuote = if (ignoreAvailable) {
                { key -> ignored += key; quotePending = false }
            } else null,
            modifier = Modifier.fillMaxSize(),
            body = { Body() },
        ) { Text("Contexte fourni") }
    }

    @Composable
    private fun Body() {
        Column {
            BasicTextField(bodyState, Modifier.focusRequester(bodyFocus).testTag("body"))
            EditorEventControl(model.value.visibleEvent, 181.dp, { key, _ -> dispatch(EditorEventInput.Activate(key)) })
        }
    }
}
