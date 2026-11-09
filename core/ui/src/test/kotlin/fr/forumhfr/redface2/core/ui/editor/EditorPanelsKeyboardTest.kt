package fr.forumhfr.redface2.core.ui.editor

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Controller requests and focus are observable here; real IME visibility requires a device. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
class EditorPanelsKeyboardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `ED53 both panels let the host reopen an initially open keyboard`() {
        assertRestoration(initiallyOpen = true)
    }

    @Test
    fun `ED53 both panels let the host keep an initially closed keyboard closed`() {
        assertRestoration(initiallyOpen = false)
    }

    private fun assertRestoration(initiallyOpen: Boolean) {
        val open = mutableStateOf(false)
        val details = mutableStateOf(false)
        val imeBeforePanel = mutableStateOf(false)
        val bodyState = TextFieldState("Message")
        val textBefore = bodyState.text.toString()
        val panelState = TextFieldState("Titre")
        val bodyFocus = FocusRequester()
        val panelFocus = FocusRequester()
        var returns = 0
        var expectedReturns = 0
        var keyboardVisible = false
        var shows = 0
        var hides = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() { shows++; keyboardVisible = true }
            override fun hide() { hides++; keyboardVisible = false }
        }
        val event = EditorEvent(
            EventKey(EditorSession("keyboard", "alice", "topic:23:42"),
                OperationRef.Local(EditorLocalOperation.FormLoad, 1), 1),
            EditorEventContent.Problem(EditorErrors.formNetwork), 1,
        )
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                val hostKeyboard = LocalSoftwareKeyboardController.current
                val restore: () -> Unit = {
                    returns++
                    // The callback must run after the covered subtree accepts focus again.
                    assertTrue(bodyFocus.requestFocus())
                    if (imeBeforePanel.value) hostKeyboard?.show() else hostKeyboard?.hide()
                }
                val body: @Composable () -> Unit = {
                    BasicTextField(bodyState, Modifier.focusRequester(bodyFocus).testTag("body"),
                        keyboardOptions = KeyboardOptions(showKeyboardOnFocus = imeBeforePanel.value))
                }
                RedfaceTheme {
                    if (details.value) EditorEventDetails(
                        event = event.takeIf { open.value }, bodyState = bodyState,
                        onClose = { open.value = false }, onReload = {}, onReturnToBody = restore,
                        modifier = Modifier.fillMaxSize(), body = body,
                    ) else EditorHeaderPanel(
                        isOpen = open.value, title = "En-tête", bodyState = bodyState,
                        onClose = { open.value = false }, onReturnToBody = restore,
                        modifier = Modifier.fillMaxSize(), initialFocusRequester = panelFocus, body = body,
                    ) {
                        BasicTextField(panelState, Modifier.focusRequester(panelFocus))
                    }
                }
            }
        }
        compose.runOnIdle { assertEquals(0, returns) }
        listOf(false, true).forEach { showDetails ->
            compose.runOnIdle { details.value = showDetails }
            compose.runOnIdle { assertEquals(expectedReturns, returns) }
            listOf(false, true).forEach { useBack ->
                compose.runOnIdle { bodyFocus.requestFocus() }
                val selection = TextRange(6, 1)
                compose.onNodeWithTag("body").performTextInputSelection(selection)
                compose.runOnIdle {
                    // R0a A / ED-22 keep preserves extent, not the direction normalized by the field.
                    assertEquals(selection.min, bodyState.selection.min)
                    assertEquals(selection.max, bodyState.selection.max)
                    keyboardVisible = initiallyOpen
                    imeBeforePanel.value = keyboardVisible // Host snapshot BEFORE opening.
                    open.value = true
                }
                compose.onNodeWithTag("body").assertDoesNotExist()
                compose.runOnIdle {
                    assertEquals(expectedReturns, returns)
                    assertEquals(selection.min, bodyState.selection.min)
                    assertEquals(selection.max, bodyState.selection.max)
                    assertEquals(textBefore, bodyState.text.toString())
                    // A header field or a remedy may change the IME while the panel is open.
                    keyboardVisible = !initiallyOpen
                    shows = 0
                    hides = 0
                }
                if (useBack) compose.runOnIdle { dispatcher.onBackPressed() }
                else compose.onNodeWithText(if (showDetails) "Masquer" else "Retour au message").performClick()
                compose.onNodeWithTag("body").assertIsFocused()
                compose.runOnIdle {
                    expectedReturns++
                    assertEquals(expectedReturns, returns)
                    assertEquals(selection.min, bodyState.selection.min)
                    assertEquals(selection.max, bodyState.selection.max)
                    assertEquals(textBefore, bodyState.text.toString())
                    assertEquals(initiallyOpen, keyboardVisible)
                    assertEquals(if (initiallyOpen) 1 else 0, shows)
                    assertEquals(if (initiallyOpen) 0 else 1, hides)
                }
            }
        }
    }
}
