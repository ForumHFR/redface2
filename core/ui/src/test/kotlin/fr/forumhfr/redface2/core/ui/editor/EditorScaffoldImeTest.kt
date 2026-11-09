package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Sessions are intercepted at startInputMethod; synthetic insets alone do not prove IME opening. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi", shadows = [NoopShadowMagnifier::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorScaffoldImeTest {
    @get:Rule val compose = createComposeRule()
    private val host = EditorScaffoldFixture()
    private fun field() = compose.onNode(hasSetTextAction())

    @Test
    fun `ED13 programmatic focus requests an IME session by default`() {
        compose.setContent { host.Content() }
        compose.runOnIdle { assertEquals(0, host.inputSessions); host.focus.requestFocus() }
        field().assertIsFocused()
        compose.runOnIdle { assertNotNull(host.inputConnection); assertEquals(1, host.inputSessions) }
    }

    @Test
    fun `ED13 accessibility click requests an IME session without a physical tap`() {
        compose.setContent { host.Content() }
        field().performSemanticsAction(SemanticsActions.OnClick) { it() }
        field().assertIsFocused()
        compose.runOnIdle { assertNotNull(host.inputConnection); assertEquals(1, host.inputSessions) }
    }

    @Test
    fun `ED13 autofocus requests focus and an IME session on entry`() {
        host.autoFocus.value = true
        compose.setContent { host.Content() }
        field().assertIsFocused()
        compose.runOnIdle { assertNotNull(host.inputConnection); assertEquals(1, host.inputSessions) }
    }

    @Test
    fun `ED53 saved state restoration resets IME suppression for an explicit focus request`() {
        host.autoFocus.value = true
        val restoration = StateRestorationTester(compose)
        restoration.setContent { host.Content() }
        compose.runOnIdle { host.insets(imeVisible = false) }
        compose.onNodeWithTag(EDITOR_APP_BAR_TITLE).performClick()
        compose.onNodeWithText("Retour au message").performClick()
        val sessions = compose.runOnIdle { assertNull(host.inputConnection); host.inputSessions }
        restoration.emulateSavedInstanceStateRestore()
        // Autofocus after recreation is deliberately unspecified: R1a-2 PR 4's
        // EditorSurfaceFocusMemory will own that policy at rebase. Only explicit focus is tested here.
        compose.runOnIdle { host.focus.requestFocus() }
        field().assertIsFocused()
        compose.runOnIdle { assertNotNull(host.inputConnection); assertTrue(host.inputSessions > sessions) }
    }

    @Test
    fun `ED53 an initially open panel returns focus without ever starting the body IME`() {
        host.panel.value = true
        val selection = host.body.selection
        compose.setContent { host.Content() }
        compose.onNodeWithText("Retour au message").performClick()
        field().assertIsFocused()
        compose.runOnIdle {
            assertEquals(selection, host.body.selection)
            assertNull(host.inputConnection)
            assertEquals(0, host.inputSessions)
        }
    }

    @Test
    fun `ED53 closed panel return suppresses automatic IME until a body tap`() {
        compose.setContent { host.Content() }
        val beforeReturn = returnFromClosedPanel()
        refocusBody()
        compose.runOnIdle { assertNull(host.inputConnection); assertEquals(beforeReturn, host.inputSessions) }
        field().performTouchInput { click() }
        compose.runOnIdle { assertNotNull(host.inputConnection); assertTrue(host.inputSessions > beforeReturn) }
        refocusBody()
        compose.runOnIdle { assertNotNull(host.inputConnection) }
    }

    @Test
    fun `ED53 a body swipe after a closed return scrolls without starting an IME session`() {
        compose.setContent { host.Content() }
        val sessions = returnFromClosedPanel()
        val scroll = compose.runOnIdle { host.scroll.value }
        field().performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertNull(host.inputConnection)
            assertEquals(sessions, host.inputSessions)
            assertTrue(host.scroll.value > scroll)
        }
        field().performTouchInput { click() }
        compose.runOnIdle { assertNotNull(host.inputConnection); assertTrue(host.inputSessions > sessions) }
    }

    @Test
    fun `ED53 a body long press after a closed return does not start an IME session`() {
        compose.setContent { host.Content() }
        val sessions = returnFromClosedPanel()
        field().performTouchInput { longClick() }
        compose.runOnIdle { assertNull(host.inputConnection); assertEquals(sessions, host.inputSessions) }
    }

    @Test
    fun `ED53 accessibility click after a closed return starts an IME session and preserves its label`() {
        val label = "Saisir le message"
        host.inputTransformation = object : InputTransformation {
            override fun TextFieldBuffer.transformInput() = Unit
            override fun SemanticsPropertyReceiver.applySemantics() {
                onClick(label = label, action = null)
            }
        }
        compose.setContent { host.Content() }
        val sessions = returnFromClosedPanel()
        assertEquals(label, field().fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        field().performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        field().assertIsFocused()
        compose.runOnIdle { assertNotNull(host.inputConnection); assertTrue(host.inputSessions > sessions) }
    }

    @Test
    fun `ED53 a new capture restores an open IME after an earlier closed return`() {
        compose.setContent { host.Content() }
        compose.runOnIdle { host.insets(imeVisible = false) }
        compose.onNodeWithTag(EDITOR_APP_BAR_TITLE).performClick()
        compose.onNodeWithText("Retour au message").performClick()
        compose.runOnIdle { assertNull(host.inputConnection); host.insets(ime = 100) }
        compose.onNodeWithTag(EDITOR_APP_BAR_TITLE).performClick()
        compose.onNodeWithText("Retour au message").performClick()
        field().assertIsFocused()
        compose.runOnIdle { assertNotNull(host.inputConnection) }
    }

    @Test
    fun `ED53 captured open IME survives saved state restoration while the panel is open`() {
        assertCapturedImeRestored(open = true)
    }

    @Test
    fun `ED53 captured closed IME survives saved state restoration while the panel is open`() {
        assertCapturedImeRestored(open = false)
    }

    private fun assertCapturedImeRestored(open: Boolean) {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { host.Content() }
        compose.runOnIdle { host.insets(ime = if (open) 100 else 0) }
        compose.onNodeWithTag(EDITOR_APP_BAR_TITLE).performClick()
        compose.runOnIdle { host.insets(ime = if (open) 0 else 100) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Retour au message").performClick()
        field().assertIsFocused()
        compose.runOnIdle { assertEquals(open, host.inputConnection != null) }
    }

    @Test
    fun `ED13 becoming editable again restarts the focused input session`() {
        compose.setContent { host.Content() }
        compose.runOnIdle { host.focus.requestFocus() }
        val input = compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and
                hasAnyAncestor(hasTestTag("editor-scaffold-field")))
        val inputId = input.fetchSemanticsNode().id
        compose.runOnIdle {
            assertNotNull(host.inputConnection)
            host.chrome.value = host.chrome.value.copy(bodyReadOnly = true)
        }
        // Read-only BTF2 retains focus and text semantics, but intentionally removes SetText.
        input.assertIsFocused().assert(!hasSetTextAction())
        assertEquals(inputId, input.fetchSemanticsNode().id)
        val sessions = compose.runOnIdle {
            assertNull(host.inputConnection)
            host.chrome.value = host.chrome.value.copy(bodyReadOnly = false)
            host.inputSessions
        }
        input.assertIsFocused().assert(hasSetTextAction())
        assertEquals(inputId, input.fetchSemanticsNode().id)
        compose.runOnIdle { assertNotNull(host.inputConnection); assertEquals(sessions + 1, host.inputSessions) }
    }

    private fun refocusBody() {
        compose.onNodeWithContentDescription("Mise en forme").requestFocus()
        compose.runOnIdle { host.focus.requestFocus() }
        field().assertIsFocused()
    }

    private fun returnFromClosedPanel(): Int {
        compose.runOnIdle { host.focus.requestFocus(); host.insets(imeVisible = false) }
        compose.onNodeWithTag(EDITOR_APP_BAR_TITLE).performClick()
        val sessions = compose.runOnIdle { assertNull(host.inputConnection); host.inputSessions }
        compose.onNodeWithText("Retour au message").performClick()
        field().assertIsFocused()
        compose.runOnIdle { assertNull(host.inputConnection); assertEquals(sessions, host.inputSessions) }
        return sessions
    }
}
