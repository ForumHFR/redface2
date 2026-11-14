package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.model.editor.EditorShortcutSurface
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

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi", shadows = [NoopShadowMagnifier::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorShortcutScaffoldTest {
    @get:Rule val compose = createComposeRule()
    private val host = EditorScaffoldFixture()

    private fun shortcut(choice: EditorShortcut) {
        host.chrome.value = host.chrome.value.copy(shortcut = EditorBarShortcut(choice, EditorShortcutSurface.POST))
    }

    @Test
    fun `touch focused body and bold tap never capture a tool selection`() {
        compose.setContent { host.Content() }
        compose.runOnIdle { assertTrue(host.inputMode.requestInputMode(InputMode.Touch)) }
        compose.onNode(hasSetTextAction()).performTouchInput { click() }.assertIsFocused()
        compose.runOnIdle {
            host.body.edit { replace(0, length, "nom"); selection = TextRange(3, 0) }
            assertNull(host.surfaceFocus.toolFocus.captured)
        }
        compose.onNodeWithContentDescription("Mise en forme").performTouchInput { click() }
        compose.onNodeWithContentDescription("Gras").performTouchInput { click() }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertNull(host.surfaceFocus.toolFocus.captured)
            assertEquals("[b]nom[/b]", host.body.text.toString())
            assertEquals(TextRange(3, 6), host.body.selection)
        }
    }

    @Test
    fun `H restoration at the captured maximum invalidates the range even without body focus entry`() {
        shortcut(EditorShortcut.BOLD)
        val blocker = FocusRequester()
        val restoration = mutableStateOf<EditorBodyFocusObservation?>(null)
        compose.setContent {
            Box {
                host.Content()
                TextButton({}, Modifier.focusRequester(blocker)) { Text("Focus capturé") }
            }
            restoration.value?.let { observation ->
                RestoreRecreatedEditorBodyFocus(observation, true, host.body, host.focus,
                    clearToolSelection = host.surfaceFocus::clearToolSelection)
            }
        }
        compose.runOnIdle {
            assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard))
            host.focus.requestFocus()
            host.body.edit { replace(0, length, "nom"); selection = TextRange(3, 0) }
        }
        tabToShortcut("Gras")
        compose.runOnIdle {
            assertEquals(TextRange(3, 0), host.surfaceFocus.toolFocus.captured?.selection)
            assertEquals(TextRange(3), host.body.selection)
            // A captured destination prevents H from entering the body: onEnter cannot clear it.
            assertTrue(blocker.requestFocus())
            assertTrue(blocker.captureFocus())
            restoration.value = EditorBodyFocusObservation(EditorBodyFocusMemory().apply {
                snapshot = EditorBodyFocusSnapshot(focused = true, imeVisible = false)
            }, imeVisible = false)
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.runOnIdle {
            assertNull(host.surfaceFocus.toolFocus.captured)
            assertEquals(TextRange(3), host.body.selection)
            assertTrue(blocker.freeFocus())
        }
        compose.onNodeWithContentDescription("Gras").requestFocus().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle {
            assertEquals("nom[b][/b]", host.body.text.toString())
            assertEquals(TextRange(6), host.body.selection)
        }
    }

    @Test
    fun `keyboard shortcuts use live reversed selection and return body focus in normal mode`() {
        shortcut(EditorShortcut.BOLD)
        compose.setContent { host.Content() }
        compose.runOnIdle { assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard)) }
        listOf(
            Triple(EditorShortcut.BOLD, "Gras", "[b]nom[/b]"),
            Triple(EditorShortcut.BULLET, "Puce", "[*]nom"),
            Triple(EditorShortcut.EMAIL, "E-mail", "[email]nom[/email]"),
            Triple(EditorShortcut.RED, "Rouge", "[#FF0000]nom[/#FF0000]"),
            Triple(EditorShortcut.PERSONAL_SMILEY, "Smiley perso", "[:nom]"),
        ).forEach { (choice, label, expected) ->
            compose.runOnIdle {
                host.focus.requestFocus()
                host.body.edit { replace(0, length, "nom"); selection = TextRange(3, 0) }
                shortcut(choice)
            }
            compose.onNode(hasSetTextAction()).assertIsFocused()
            compose.onNodeWithContentDescription(label).requestFocus().performKeyInput { pressKey(Key.Enter) }
            compose.onNode(hasSetTextAction()).assertIsFocused()
            compose.runOnIdle {
                assertEquals(expected, host.body.text.toString())
                val expectedSelection = when (choice) {
                    EditorShortcut.PERSONAL_SMILEY -> TextRange(6)
                    EditorShortcut.BOLD -> TextRange(3, 6)
                    EditorShortcut.BULLET -> TextRange(3, 6)
                    EditorShortcut.EMAIL -> TextRange(7, 10)
                    else -> TextRange(9, 12)
                }
                assertEquals(expectedSelection, host.body.selection)
                assertEquals(EditorBarMode.Normal, host.chrome.value.bar.mode)
                assertEquals(emptyList<TextRange>(), host.surfaceSelections)
            }
        }
    }

    @Test
    fun `Tab from the body preserves reversed selection for shortcut and format insertions`() {
        shortcut(EditorShortcut.RED)
        compose.setContent { host.Content() }
        compose.runOnIdle {
            assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard))
            host.focus.requestFocus()
            host.body.edit { replace(0, length, "nom"); selection = TextRange(3, 0) }
        }
        tabToShortcut("Rouge")
        compose.onNodeWithContentDescription("Rouge").performKeyInput { pressKey(Key.Enter) }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals("[#FF0000]nom[/#FF0000]", host.body.text.toString())
            assertEquals(TextRange(9, 12), host.body.selection)
            assertEquals(EditorBarMode.Normal, host.chrome.value.bar.mode)
            host.body.edit { replace(0, length, "nom"); selection = TextRange(3, 0) }
        }
        compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithContentDescription("Mise en forme").assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithContentDescription("Mise en forme").assertIsFocused().performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithContentDescription("Gras").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals("[b]nom[/b]", host.body.text.toString())
            assertEquals(TextRange(3, 6), host.body.selection)
            assertEquals(EditorBarMode.Format, host.chrome.value.bar.mode)
        }
    }

    @Test
    fun `shortcut does not restore a stale selection after the unfocused body changes`() {
        shortcut(EditorShortcut.BOLD)
        compose.setContent { host.Content() }
        compose.runOnIdle {
            assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard))
            host.focus.requestFocus()
            host.body.edit { replace(0, length, "nom"); selection = TextRange(3, 0) }
        }
        tabToShortcut("Gras")
        compose.runOnIdle { host.body.edit { selection = TextRange(1) } }
        compose.onNodeWithContentDescription("Gras").performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle {
            assertEquals("n[b][/b]om", host.body.text.toString())
            assertEquals(TextRange(4), host.body.selection)
        }
    }

    @Test
    fun `keyboard surface captures selection and IME before Tab closes body input`() {
        host.emulateExternalSurface = true
        shortcut(EditorShortcut.SEARCH_SMILEYS)
        compose.setContent { host.Content() }
        compose.runOnIdle {
            assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard))
            host.focus.requestFocus()
            host.body.edit { selection = TextRange(12, 8) }
            host.insets(ime = 200)
        }
        tabToShortcut("Smileys Wiki")
        compose.runOnIdle { assertNull(host.inputConnection); host.insets(ime = 0) }
        compose.onNodeWithContentDescription("Smileys Wiki").performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(TextRange(12, 8), host.surfaceSelections.single()) }
        compose.onNodeWithText("Retour au message").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(TextRange(12, 8), host.body.selection)
            assertNotNull(host.inputConnection)
        }
    }

    private fun tabToShortcut(label: String) {
        compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithContentDescription("Mise en forme").assertIsFocused()
            .performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithContentDescription("Smileys").assertIsFocused().performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithContentDescription(label).assertIsFocused()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    @Test
    fun `surface shortcuts capture selection and return a closed IME without starting input`() = surfaceReturns(false)

    @Test
    fun `surface shortcuts capture selection and return an open IME`() = surfaceReturns(true)

    private fun surfaceReturns(imeOpen: Boolean) {
        host.emulateExternalSurface = true
        shortcut(EditorShortcut.UPLOAD_IMAGE)
        compose.setContent { host.Content() }
        listOf(EditorShortcut.UPLOAD_IMAGE to "Uploader une image", EditorShortcut.IMAGE_URL to "Insérer par URL",
            EditorShortcut.SEARCH_SMILEYS to "Smileys Wiki").forEach { (choice, label) ->
            compose.runOnIdle {
                shortcut(choice)
                host.body.edit { selection = TextRange(12, 8) }
                host.insets(ime = if (imeOpen) 200 else 0)
                if (imeOpen) host.focus.requestFocus()
            }
            compose.onNodeWithContentDescription(label).revealEditorControl().performClick()
            compose.runOnIdle {
                assertEquals(TextRange(12, 8), host.surfaceSelections.last())
                host.insets(ime = 0)
            }
            compose.onNodeWithText("Retour au message").performClick()
            compose.onNode(hasSetTextAction()).assertIsFocused()
            compose.runOnIdle {
                assertEquals(TextRange(12, 8), host.body.selection)
                if (imeOpen) assertNotNull(host.inputConnection) else assertNull(host.inputConnection)
                assertEquals(EditorBarMode.Normal, host.chrome.value.bar.mode)
            }
        }
        assertEquals(listOf("upload", "url", "search"), host.calls)
    }
}
