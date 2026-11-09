package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorScaffoldTest {
    @get:Rule val compose = createComposeRule()
    private val host = EditorScaffoldFixture()

    @Test
    fun `ED21 ED22 ED93 opening and scrolling format keeps selection and keyboard insertion restores field focus`() {
        compose.setContent { host.Content() }
        val selection = host.body.selection
        val selected = host.body.text.substring(selection.min, selection.max)
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        compose.onNodeWithContentDescription("Couleur").performScrollTo()
        compose.runOnIdle {
            assertEquals(selection, host.body.selection)
            host.inputMode.requestInputMode(InputMode.Keyboard)
        }
        val bold = compose.onNodeWithContentDescription("Gras").performScrollTo()
        bold.requestFocus().performKeyInput { pressKey(Key.Enter) }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(selected, host.body.text.substring(host.body.selection.min, host.body.selection.max))
            assertTrue(host.body.text.contains("[b]$selected[/b]"))
            assertTrue(host.calls.isEmpty())
        }
        val before = host.body.text.toString()
        compose.onNode(hasSetTextAction()).performTextInput("Suite")
        compose.runOnIdle {
            assertEquals(EditorBarMode.Format, host.chrome.value.bar.mode)
            assertEquals(before.replace("[b]$selected[/b]", "[b]Suite[/b]"), host.body.text.toString())
        }
    }

    @Test
    fun `ED90 ED93 Tab exits toward the bar and Shift Tab toward the app bar without inserting text`() {
        compose.setContent { host.Content() }
        compose.runOnIdle { host.inputMode.requestInputMode(InputMode.Keyboard); host.focus.requestFocus() }
        val before = host.body.text.toString()
        compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithContentDescription("Mise en forme").assertIsFocused()
        compose.runOnIdle { host.focus.requestFocus() }
        compose.onNode(hasSetTextAction()).performKeyInput {
            keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft)
        }
        compose.onNodeWithContentDescription("Afficher l’aperçu").assertIsFocused()
        compose.runOnIdle { assertEquals(before, host.body.text.toString()); assertTrue(host.calls.isEmpty()) }
    }

    @Test
    fun `ED85 panel hides and blocks a mounted body then restores its node selection scroll undo and focus`() {
        compose.setContent { host.Content() }
        compose.onNode(hasSetTextAction()).performTextInput("Ajout")
        compose.onNode(hasSetTextAction()).performTextInputSelection(TextRange(host.body.text.length - 6))
        val field = compose.onNode(hasSetTextAction())
        val node = field.fetchSemanticsNode().id
        val geometry = compose.onNodeWithTag("editor-scaffold-field").getUnclippedBoundsInRoot()
        val selection = host.body.selection
        val offset = host.scroll.value
        compose.runOnIdle {
            assertTrue(offset > 0); assertTrue(host.body.undoState.canUndo); host.panel.value = true
        }
        field.assertDoesNotExist()
        compose.runOnIdle { assertFalse(host.focus.requestFocus()) }
        compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
        compose.runOnIdle { host.back.onBackPressed() }
        field.assertIsFocused()
        assertEquals(node, field.fetchSemanticsNode().id)
        assertEquals(geometry, compose.onNodeWithTag("editor-scaffold-field").getUnclippedBoundsInRoot())
        compose.runOnIdle {
            assertEquals(selection, host.body.selection); assertEquals(offset, host.scroll.value)
            assertEquals(0, host.disposals); assertTrue(host.body.undoState.canUndo); assertTrue(host.calls.isEmpty())
        }
    }

    @Test
    fun `ED85 initial panel and successive visible layers consume Back before guarded leave`() {
        host.panel.value = true
        compose.setContent { host.Content() }
        compose.runOnIdle { host.back.onBackPressed() }
        compose.runOnIdle {
            assertFalse(host.panel.value)
            host.chrome.value = EditorScaffoldChrome(bar = EditorBarState(EditorBarMode.Color))
        }
        compose.runOnIdle { host.back.onBackPressed() }
        compose.runOnIdle { assertEquals(EditorBarMode.Format, host.chrome.value.bar.mode); host.back.onBackPressed() }
        compose.runOnIdle {
            assertEquals(EditorBarMode.Normal, host.chrome.value.bar.mode)
            host.chrome.value = host.chrome.value.copy(bar = EditorBarState(arm = host.arm))
        }
        compose.runOnIdle { host.back.onBackPressed() }
        compose.runOnIdle {
            assertEquals(listOf("disarm"), host.calls)
            host.detail.value = controlEvent(EditorEventContent.Problem(EditorErrors.network))
        }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle { host.back.onBackPressed() }
        compose.runOnIdle { assertEquals(null, host.detail.value); host.back.onBackPressed() }
        compose.runOnIdle { assertEquals(listOf("disarm", "detail-close", "close"), host.calls) }
    }

    @Test
    fun `ED85 opening the header disarms before the panel can consume Back`() {
        host.chrome.value = EditorScaffoldChrome(bar = EditorBarState(EditorBarMode.Format, host.arm))
        compose.setContent { host.Content() }
        compose.onNodeWithTag(EDITOR_APP_BAR_TITLE).performClick()
        compose.runOnIdle {
            assertTrue(host.panel.value)
            assertEquals(EditorBarState(), host.chrome.value.bar)
            assertEquals(listOf("disarm"), host.calls)
            host.back.onBackPressed()
        }
        compose.runOnIdle {
            assertFalse(host.panel.value)
            assertEquals(listOf("disarm"), host.calls)
        }
    }

    @Test
    fun `ED85 simultaneously open details consume Back before the arm`() {
        val event = controlEvent(EditorEventContent.Problem(EditorErrors.network))
        host.chrome.value = EditorScaffoldChrome(bar = EditorBarState(EditorBarMode.Format, host.arm), event = event)
        host.detail.value = event
        compose.setContent { host.Content() }
        compose.runOnIdle { host.back.onBackPressed() }
        compose.runOnIdle {
            assertNull(host.detail.value)
            assertEquals(host.arm, host.chrome.value.bar.arm)
            assertEquals(listOf("detail-close"), host.calls)
            host.back.onBackPressed()
        }
        compose.runOnIdle {
            assertNull(host.chrome.value.bar.arm)
            assertEquals(EditorBarMode.Format, host.chrome.value.bar.mode)
            assertEquals(listOf("detail-close", "disarm"), host.calls)
        }
        compose.onNodeWithContentDescription("Gras").assertExists()
    }

    @Test
    fun `ED85 Back leaves a mode masked by an event intact and exits without acknowledging`() {
        assertMaskedFormatBack(panelOpen = false, armed = false)
    }

    @Test
    fun `ED85 Back closes the panel before a mode masked by an event and acknowledgement restores Format`() {
        assertMaskedFormatBack(panelOpen = true, armed = false)
    }

    @Test
    fun `ED85 Back disarms before exiting when Format is also masked by an event`() {
        assertMaskedFormatBack(panelOpen = false, armed = true)
    }

    private fun assertMaskedFormatBack(panelOpen: Boolean, armed: Boolean) {
        compose.setContent { host.Content() }
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        val event = controlEvent(EditorEventContent.Information(EditorText(R.string.editor_event_already_quoted)))
        compose.runOnIdle {
            host.chrome.value = host.chrome.value.copy(
                bar = host.chrome.value.bar.copy(arm = host.arm.takeIf { armed }), event = event)
            host.panel.value = panelOpen
        }
        // Simulate independent delivery, without an OpenSurface gesture that would close Format.
        if (armed) {
            compose.runOnIdle { host.back.onBackPressed() }
            compose.runOnIdle {
                assertNull(host.chrome.value.bar.arm)
                assertEquals(event, host.chrome.value.event)
                assertEquals(listOf("disarm"), host.calls)
            }
        }
        compose.runOnIdle { host.back.onBackPressed() }
        compose.runOnIdle {
            assertEquals(EditorBarMode.Format, host.chrome.value.bar.mode)
            assertEquals(event, host.chrome.value.event)
            assertFalse(host.panel.value)
            val expected = if (armed) listOf("disarm", "close") else if (panelOpen) emptyList() else listOf("close")
            assertEquals(expected, host.calls)
        }
        compose.onNodeWithContentDescription("Gras").assertDoesNotExist()
        compose.onNode(hasClickAction() and hasContentDescription("Déjà citée, Masquer")).performClick()
        compose.onNodeWithContentDescription("Gras").assertExists()
        compose.runOnIdle { assertEquals(EditorBarMode.Format, host.chrome.value.bar.mode) }
    }

    @Test
    fun `ED44 portrait preview statuses and header consume information before opening a surface`() {
        host.chrome.value = EditorScaffoldChrome(firstStatus = host.statuses[0], secondStatus = host.statuses[1])
        compose.setContent { host.Content() }
        listOf("Afficher l’aperçu" to "preview", "12 citations" to "quotes", "Brouillon retrouvé" to "restore",
            "header" to null).forEach { (label, call) ->
            compose.runOnIdle {
                host.calls.clear()
                host.chrome.value = host.chrome.value.copy(bar = EditorBarState(EditorBarMode.Format),
                    event = controlEvent(EditorEventContent.Information(
                        EditorText(R.string.editor_event_already_quoted))))
            }
            val control = if (label == "header") compose.onNodeWithTag(EDITOR_APP_BAR_TITLE)
                else compose.onNodeWithContentDescription(label)
            control.performClick()
            compose.runOnIdle {
                assertEquals(listOf("event:Dismiss"), host.calls)
                assertNull(host.chrome.value.event)
                assertEquals(EditorBarMode.Format, host.chrome.value.bar.mode)
                assertFalse(host.panel.value)
            }
            control.performClick()
            compose.runOnIdle {
                assertEquals(EditorBarMode.Normal, host.chrome.value.bar.mode)
                assertEquals(call == null, host.panel.value)
                assertEquals(listOfNotNull("event:Dismiss", call), host.calls)
            }
            if (call == null) compose.onNodeWithText("Retour au message").performClick()
        }
    }

    @Test
    fun `ED23 ED40 app bar header closes format and event replacement never remounts body`() {
        host.chrome.value = EditorScaffoldChrome(bar = EditorBarState(EditorBarMode.Format),
            firstStatus = host.statuses[0], secondStatus = host.statuses[1])
        compose.setContent { host.Content() }
        val node = compose.onNode(hasSetTextAction()).fetchSemanticsNode().id
        compose.onNodeWithTag(EDITOR_APP_BAR_TITLE).performClick()
        compose.runOnIdle { assertEquals(EditorBarMode.Normal, host.chrome.value.bar.mode) }
        compose.onNodeWithText("Retour au message").performClick()
        listOf(EditorEventContent.Problem(EditorErrors.missingTitle), finishedUpload(10),
            EditorEventContent.InProgress(uploadText(2))).forEach { content ->
            compose.runOnIdle { host.chrome.value = host.chrome.value.copy(event = controlEvent(content)) }
            assertEquals(node, compose.onNode(hasSetTextAction()).fetchSemanticsNode().id)
        }
        compose.runOnIdle { assertEquals(0, host.disposals); assertTrue(host.calls.isEmpty()) }
    }

    @Test
    fun `ED23 hiding error details returns to Normal after opening them from Format`() {
        assertDetailsCloseFormat(useBack = false)
    }

    @Test
    fun `ED23 system back from error details returns to Normal after opening them from Format`() {
        assertDetailsCloseFormat(useBack = true)
    }

    private fun assertDetailsCloseFormat(useBack: Boolean) {
        compose.setContent { host.Content() }
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        compose.runOnIdle {
            assertEquals(EditorBarMode.Format, host.chrome.value.bar.mode)
            host.chrome.value = host.chrome.value.copy(event = controlEvent(EditorEventContent.Problem(
                EditorErrors.network)))
        }
        val label = EditorErrors.network.summary.resolve(host.view.context) + ", Afficher les détails"
        compose.onNode(hasClickAction() and hasContentDescription(label)).performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(EditorBarMode.Normal, host.chrome.value.bar.mode) }
        if (useBack) compose.runOnIdle { host.back.onBackPressed() }
        else compose.onNodeWithText("Masquer").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertNull(host.detail.value)
            assertEquals(EditorBarMode.Normal, host.chrome.value.bar.mode)
            assertEquals(listOf("event:Details", "detail-close"), host.calls)
        }
    }

    @Test
    fun `ED85 ED53 panel returns alternate open and closed IME starting open`() {
        assertPanelKeyboardReturn(initiallyOpen = true)
    }

    @Test
    fun `ED85 ED53 panel returns alternate open and closed IME starting closed`() {
        assertPanelKeyboardReturn(initiallyOpen = false)
    }

    private fun assertPanelKeyboardReturn(initiallyOpen: Boolean) {
        compose.setContent { host.Content() }
        val field = compose.onNode(hasSetTextAction())
        val selection = TextRange(12, 8)
        // Header trigger, event Complete and event Details each capture before opening.
        val cases = listOf(false, true).flatMap { compact ->
            listOf(null, EditorErrors.missingTitle, EditorErrors.network).flatMap { error ->
                listOf(false, true).map { useBack -> Triple(compact, error, useBack) }
            }
        }
        cases.forEachIndexed { index, (compact, error, useBack) ->
            val open = if (index % 2 == 0) initiallyOpen else !initiallyOpen
            compose.runOnIdle {
                host.metrics.value = EditorWindowMetrics(if (compact) 470.dp else 740.dp, 24.dp, 30.dp)
                host.chrome.value = EditorScaffoldChrome(
                    event = error?.let { controlEvent(EditorEventContent.Problem(it)) })
            }
            // A real tap starts BTF2's session, even when automatic opening on focus is disabled.
            if (open) field.performClick()
            field.performTextInputSelection(selection)
            compose.runOnIdle {
                // Compact also covers a floating IME: visible, with no overlap/inset.
                host.insets(ime = if (open && !compact) 100 else 0, imeVisible = open)
            }
            if (error == null) {
                if (compact) compose.onNodeWithContentDescription("Titre et sous-catégorie")
                    .revealEditorControl().performClick()
                else compose.onNodeWithTag(EDITOR_APP_BAR_TITLE).performClick()
            } else {
                val label = error.summary.resolve(host.view.context) + ", " +
                    if (error == EditorErrors.missingTitle) "Compléter" else "Afficher les détails"
                compose.onNode(hasClickAction() and hasContentDescription(label)).performClick()
            }
            field.assertDoesNotExist()
            val sessionsBeforeReturn = compose.runOnIdle {
                assertNull(host.inputConnection)
                assertSelectionExtent(selection)
                // Changes while covered must not replace the snapshot taken by the opening gesture.
                host.insets(ime = if (open) 0 else 100)
                host.inputSessions
            }
            if (useBack) compose.runOnIdle { host.back.onBackPressed() }
            else compose.onNodeWithText(if (error == EditorErrors.network) "Masquer" else "Retour au message")
                .performClick()
            field.assertIsFocused()
            compose.runOnIdle {
                assertSelectionExtent(selection)
                assertEquals(0, host.disposals)
                assertFalse(host.panel.value)
                assertEquals(null, host.detail.value)
                assertInputSession(open, sessionsBeforeReturn, selection)
            }
        }
    }

    private fun assertSelectionExtent(selection: TextRange) {
        // R0a keep preserves the selected extent; the field may normalize its direction.
        assertEquals(selection.min, host.body.selection.min)
        assertEquals(selection.max, host.body.selection.max)
    }

    private fun assertInputSession(open: Boolean, sessionsBeforeReturn: Int, selection: TextRange) {
        assertEquals(open, host.inputConnection != null)
        assertEquals(sessionsBeforeReturn + if (open) 1 else 0, host.inputSessions)
        if (open) {
            val connection = requireNotNull(host.inputConnection)
            val before = host.body.text.toString()
            assertEquals(before.substring(selection.min, selection.max), connection.getSelectedText(0)?.toString())
            assertTrue(connection.commitText("IME", 1))
            assertEquals(before.replaceRange(selection.min, selection.max, "IME"), host.body.text.toString())
        }
    }
}
