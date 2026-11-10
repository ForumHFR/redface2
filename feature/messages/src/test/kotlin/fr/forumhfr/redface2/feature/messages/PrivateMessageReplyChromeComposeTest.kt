package fr.forumhfr.redface2.feature.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EditorScaffold
import fr.forumhfr.redface2.core.ui.editor.EditorScaffoldChrome
import fr.forumhfr.redface2.core.ui.editor.EditorWindowMetrics
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

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivateMessageReplyChromeComposeTest {
    @get:Rule val compose = createComposeRule()
    private val f = PrivateMessageReplyChromeFixture()
    private val focus = FocusRequester()
    private lateinit var chrome: EditorScaffoldChrome
    private var hides = 0
    private var shows = 0
    private val keyboard = object : SoftwareKeyboardController {
        override fun show() { shows++ }
        override fun hide() { hides++ }
    }

    @Composable private fun Content(autoOpen: Boolean = false) {
        val adapter = f.adapter
        val projected = adapter.chrome()
        SideEffect { chrome = projected }
        CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
            RedfaceTheme {
                EditorScaffold(f.body, EditorWindowMetrics(740.dp, 0.dp, 0.dp), adapter.title(),
                    adapter.state.context, projected, adapter.barActions, adapter.submitState, adapter.submitActions,
                    adapter.imageActions, adapter::closeRequested, bodyFocusRequester = focus,
                    surfaceFocus = adapter.focusMemory, onSystemBack = { adapter.back() },
                    fieldArea = { body -> PrivateMessageReplyChromePreview(adapter, f.body, focus, body) },
                    overlay = { restore, body ->
                        body()
                        PrivateMessageReplyChromeSheets(adapter, autoOpen, restore)
                    })
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
            f.adapter.back()
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
            f.adapter.back()
        }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(TextRange(6, 1), f.body.selection)
            assertTrue(hides > 0)
            assertEquals(0, shows)
            assertFalse(f.adapter.consumeReturn())
            assertTrue(f.memory.recipientEntryHandled)
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
}
