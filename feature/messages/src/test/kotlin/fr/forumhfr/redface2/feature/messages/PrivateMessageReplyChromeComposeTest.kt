package fr.forumhfr.redface2.feature.messages

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EditorBarMode
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorScaffoldChrome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
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
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivateMessageReplyChromeComposeTest {
    @get:Rule val compose = createComposeRule()
    private val f = PrivateMessageReplyChromeFixture()
    private lateinit var back: androidx.activity.OnBackPressedDispatcher
    private lateinit var inputMode: InputModeManager
    private lateinit var chrome: EditorScaffoldChrome
    private var hides = 0
    private var shows = 0
    private lateinit var view: View
    private val inputConnections = mutableSetOf<InputConnection>()
    private val input = PlatformTextInputInterceptor { request, next ->
        val connection = request.createInputConnection(EditorInfo())
        inputConnections += connection
        try { next.startInputMethod(request) } finally {
            inputConnections -= connection
            connection.closeConnection()
        }
    }
    private val keyboard = object : SoftwareKeyboardController {
        override fun show() { shows++ }
        override fun hide() { hides++ }
    }

    @Composable private fun Content(autoOpen: Boolean = false) {
        val adapter = f.adapter
        val projected = adapter.chrome()
        SideEffect { chrome = projected }
        back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
        inputMode = LocalInputModeManager.current
        view = LocalView.current
        CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
            InterceptPlatformTextInput(input) {
                RedfaceTheme {
                    PrivateMessageReplyTestContent(f.state, f.body, f.actions, f.events, f.memory, autoOpen)
                }
            }
        }
    }
    @Test fun `ED10 ED40 form title restore and owner recipients project into distinct slots`() {
        compose.setContent { Content() }
        compose.onNodeWithText("Répondre").assertExists()
        compose.onNodeWithText("Conversation privée").assertExists()
        compose.runOnIdle {
            assertNull(chrome.firstStatus)
            assertNull(chrome.secondStatus)
            f.state = f.state.copy(restorableDraft = "Brouillon privé", canManageRecipients = true,
                recipients = listOf("alice", "Administration"))
        }
        compose.onNodeWithContentDescription("2 destinataires, gérer").assertExists()
        compose.runOnIdle {
            assertTrue(chrome.firstStatus != null)
            assertEquals(2, chrome.secondStatus?.badgeCount)
            f.state = f.state.copy(canManageRecipients = false)
        }
        compose.onNodeWithContentDescription("2 destinataires, gérer").assertDoesNotExist()
        compose.runOnIdle { assertNull(chrome.secondStatus) }
    }
    @Test fun `ED111 existing manager keeps Administration warning and disables removal of last member`() {
        f.state = f.state.copy(canManageRecipients = true, recipients = listOf("alice", "Administration"))
        compose.setContent { Content(autoOpen = true) }
        compose.onNodeWithText("Administration").performClick()
        compose.runOnIdle {
            assertEquals(listOf("remove:Administration"), f.calls)
            f.state = f.state.copy(recipients = listOf("alice"))
        }
        compose.onNodeWithText("alice").assertIsNotEnabled()
        compose.onNodeWithText("Administration", substring = true).assertExists()
        compose.runOnIdle {
            assertEquals(1, f.calls.size)
            back.onBackPressed()
        }
        compose.runOnIdle { assertNull(f.adapter.surface) }
    }
    @Test fun `ED86 closing manager restores focused body selection and closed IME without reopening`() {
        f.state = f.state.copy(canManageRecipients = true, recipients = listOf("alice", "Administration"))
        compose.setContent { Content(autoOpen = true) }
        compose.runOnIdle {
            f.adapter.focusMemory.imeBeforeSurface = false
            hides = 0
            shows = 0
            back.onBackPressed()
        }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(TextRange(6, 1), f.body.selection)
            assertTrue(hides > 0)
            assertEquals(0, shows)
            assertTrue(inputConnections.isEmpty())
            assertFalse(f.adapter.consumeReturn())
            assertTrue(f.memory.recipientEntryHandled)
        }
    }
    @Test fun `ED111 automatic manager entry waits for the form without starting body input`() {
        f.state = f.state.copy(isLoadingForm = true, formAvailable = false)
        compose.setContent { Content(autoOpen = true) }
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.runOnIdle {
            assertTrue(inputConnections.isEmpty())
            f.state = f.state.copy(isLoadingForm = false, formAvailable = true,
                canManageRecipients = true, recipients = listOf("alice"))
        }
        compose.onNodeWithText("Membres de la discussion").assertExists()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertTrue(inputConnections.isEmpty())
            assertTrue(f.memory.recipientEntryHandled)
            assertFalse(f.adapter.consumeReturn())
        }
    }
    @Test fun `ED71 options expose exactly the three MP toggles`() {
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Options").performClick()
        compose.onNodeWithText("Activer la signature").assertExists()
        compose.onAllNodes(isToggleable()).assertCountEquals(3)
        compose.runOnIdle {
            assertEquals(PrivateMessageReplyChromeSurface.Options, f.adapter.surface)
            f.adapter.signature(true)
            f.adapter.smileyDisabled(true)
            f.adapter.notification(true)
            assertEquals(listOf("signature:true", "smileyDisabled:true", "notification:true"), f.calls)
        }
    }
    @Test fun `ED85 Back closes color format preview then requests saved exit`() {
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        compose.onNodeWithContentDescription("Couleur").performScrollTo().performClick()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithContentDescription("Gras").assertExists()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithContentDescription("Options").assertExists()
        compose.onNodeWithContentDescription("Afficher l’aperçu").performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNode(hasSetTextAction()).assertExists()
        compose.runOnIdle { back.onBackPressed() }
        assertEquals(listOf("preview", "preview", "close"), f.calls)
    }

    @Test fun `ED85 Back skips the event masked mode without acknowledgement`() {
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        compose.runOnIdle { f.transition(f.state.copy(submitError = EditorErrors.antiFlood)) }
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle {
            assertEquals(listOf("close"), f.calls)
            assertEquals(EditorBarMode.Format, f.adapter.scaffoldChrome().bar.mode)
            assertTrue(f.events.visibleEvent != null)
        }
    }

    @Test fun `ED85 host Back returns keyboard focus to the parent bar control`() {
        compose.setContent { Content() }
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.runOnIdle { assertEquals(InputMode.Keyboard, inputMode.inputMode) }
        compose.onNodeWithContentDescription("Mise en forme").requestFocus().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithContentDescription("Couleur").performScrollTo().requestFocus()
            .performKeyInput { pressKey(Key.Enter) }
        compose.onNode(hasContentDescription("Retour") and hasAnyAncestor(hasTestTag("editor-scaffold-action-bar")))
            .assertIsFocused()
        compose.runOnIdle { assertEquals(EditorBarMode.Color, f.adapter.scaffoldChrome().bar.mode) }
        backClosesFocusTooltip("Retour")
        compose.runOnIdle { assertEquals(EditorBarMode.Color, f.adapter.scaffoldChrome().bar.mode) }
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle { assertEquals(EditorBarMode.Format, f.adapter.scaffoldChrome().bar.mode) }
        compose.onNodeWithContentDescription("Couleur").assertIsFocused()
        compose.onNodeWithContentDescription("Mise en forme").requestFocus().assertIsFocused()
        backClosesFocusTooltip("Mise en forme")
        compose.runOnIdle { assertEquals(EditorBarMode.Format, f.adapter.scaffoldChrome().bar.mode) }
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle { assertEquals(EditorBarMode.Normal, f.adapter.scaffoldChrome().bar.mode) }
        compose.onNodeWithContentDescription("Mise en forme").assertIsFocused()
        compose.runOnIdle { assertTrue(f.calls.isEmpty()) }
    }

    private fun backClosesFocusTooltip(label: String) {
        compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText(label, useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasContentDescription(label) and hasAnyAncestor(hasTestTag("editor-scaffold-action-bar")))
            .assertIsFocused()
    }

    @Test fun `ED85 detail precedes disarm and disarm precedes a retained masked mode`() {
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        compose.runOnIdle {
            f.state = f.state.copy(submission = EditorSubmissionState.Armed(ArmToken(1, f.session, 0, 4_000L)))
        }
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithContentDescription("Gras").assertExists()
        compose.runOnIdle {
            assertEquals(EditorBarMode.Format, f.adapter.scaffoldChrome().bar.mode)
            f.transition(f.state.copy(submitError = EditorErrors.antiFlood))
        }
        compose.onNodeWithContentDescription("Anti-flood HFR, Afficher les détails").performClick()
        compose.runOnIdle {
            // Same OpenSurface behaviour as dev: opening details resets the retained bar mode.
            assertEquals(EditorBarMode.Normal, f.adapter.scaffoldChrome().bar.mode)
            f.state = f.state.copy(submission = EditorSubmissionState.Armed(ArmToken(2, f.session, 0, 4_000L)))
            back.onBackPressed()
        }
        compose.runOnIdle {
            assertNull(f.adapter.detail)
            assertTrue(f.state.submission is EditorSubmissionState.Armed)
            assertEquals(listOf("disarm"), f.calls)
            back.onBackPressed()
        }
        compose.runOnIdle { assertEquals(listOf("disarm", "disarm"), f.calls) }
    }

    @Test fun `ED85 Back dismisses action and status tooltips before leaving`() {
        f.state = f.state.copy(restorableDraft = "Brouillon")
        compose.setContent { Content() }
        for (label in listOf("Mise en forme", "Brouillon retrouvé, restaurer ou ignorer")) {
            compose.onNodeWithContentDescription(label).performTouchInput { longClick() }
            compose.onNodeWithText(label, useUnmergedTree = true).assertExists()
            compose.runOnIdle { back.onBackPressed() }
            compose.onNodeWithText(label, useUnmergedTree = true).assertDoesNotExist()
            compose.runOnIdle { assertTrue(f.calls.isEmpty()) }
        }
    }

    @Test fun `ED86 manager returns open IME and reversed selection exactly once`() {
        f.state = f.state.copy(canManageRecipients = true, recipients = listOf("alice", "Administration"))
        compose.setContent { Content() }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertTrue("Body input must be active before opening the manager", inputConnections.isNotEmpty())
            f.body.edit { selection = TextRange(6, 1) }
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 200))
                .setVisible(WindowInsetsCompat.Type.ime(), true).build())
        }
        compose.onNodeWithContentDescription("2 destinataires, gérer").performClick()
        compose.onNodeWithText("Membres de la discussion").assertExists()
        compose.runOnIdle {
            assertTrue("Opening from the body must capture the visible IME", f.adapter.focusMemory.imeBeforeSurface)
            shows = 0
            hides = 0
            back.onBackPressed()
        }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(TextRange(6, 1), f.body.selection)
            assertTrue("Returning to the body must restart its input session", inputConnections.isNotEmpty())
            assertEquals(0, hides)
            assertFalse(f.adapter.consumeReturn())
        }
    }

    @Test fun `ED86 process recreation closes the manager without repeating automatic entry`() {
        val restoration = StateRestorationTester(compose)
        val memories = mutableListOf<PrivateMessageReplyChromeMemory>()
        restoration.setContent {
            val memory = remember { PrivateMessageReplyChromeMemory().also(memories::add) }
            RedfaceTheme {
                PrivateMessageReplyTestContent(f.state.copy(canManageRecipients = true,
                    recipients = listOf("alice", "Administration")), memory = memory, autoOpen = true)
            }
        }
        compose.onNodeWithText("Membres de la discussion").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Membres de la discussion").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertExists()
        compose.runOnIdle {
            assertEquals(2, memories.size)
            assertNotSame(memories.first(), memories.last())
            // The fresh VM latch is false: only the screen's saved one-shot flag prevents reopening.
            assertFalse(memories.last().recipientEntryHandled)
            assertNull(memories.last().host.surface)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Membres de la discussion").assertDoesNotExist()
        compose.runOnIdle { assertEquals(3, memories.size) }
    }

    @Test fun `ED130 form or inline quote failure stays full screen with the existing retry`() {
        f.state = f.state.copy(formError = true, formAvailable = false)
        compose.setContent { Content() }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Options").assertDoesNotExist()
        compose.onNodeWithText("Réessayer").performClick()
        compose.runOnIdle { assertEquals(listOf("reload"), f.calls) }
    }

}
