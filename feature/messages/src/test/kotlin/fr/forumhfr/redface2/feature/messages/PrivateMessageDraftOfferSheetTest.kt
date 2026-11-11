package fr.forumhfr.redface2.feature.messages

import androidx.activity.ComponentDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EditorHost
import fr.forumhfr.redface2.core.ui.editor.EditorRecipientState
import fr.forumhfr.redface2.core.ui.editor.EditorScaffold
import fr.forumhfr.redface2.core.ui.editor.EditorWindowMetrics
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
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
import org.robolectric.shadows.ShadowDialog

/** Both production MP overlays use the host's deferred return, independently of their header policies. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivateMessageDraftOfferSheetTest {
    @get:Rule val compose = createComposeRule()
    private val reply = PrivateMessageReplyChromeFixture()
    private val message = PrivateMessageComposeChromeFixture()
    private val focus = FocusRequester()
    private var returns = 0

    @Composable private fun Content(isReply: Boolean) {
        val scope = rememberCoroutineScope()
        val smileys = remember { SmileyPickerController(scope, searchWiki = { _, _ -> emptyList() }) }
        val host = host(isReply)
        val body = if (isReply) reply.body else message.body
        val chrome = if (isReply) reply.adapter.chrome() else message.adapter.chrome()
        RedfaceTheme {
            EditorScaffold(body, EditorWindowMetrics(740.dp, 0.dp, 0.dp), "MP", "Conversation", chrome,
                host.barActions, host.submitState, host.submitActions, host.imageActions, host::closeRequested,
                bodyFocusRequester = focus, surfaceFocus = host.focusMemory,
                bodyFocusRestoreAllowed = host.surface == null && host.detail == null,
                overlay = { restore, content ->
                    val returnToBody = { returns++; restore() }
                    if (isReply) PrivateMessageReplyChromeSurfaces(reply.adapter, body, smileys, false,
                        returnToBody, content)
                    else PrivateMessageComposeChromeSurfaces(message.adapter, body, message.recipients,
                        message.subject, smileys, returnToBody, content)
                })
        }
    }

    @Test fun `1450 reply offer returns focus once for every dismissal and decision`() = checkOffer(isReply = true)

    @Test fun `1450 new message offer returns focus once for every dismissal and decision`() =
        checkOffer(isReply = false)

    @Test fun `1450 losing the reply offer closes its open sheet without a decision and returns focus once`() =
        checkOfferLoss(isReply = true)

    @Test fun `1450 losing the new message offer closes its open sheet without a decision and returns focus once`() =
        checkOfferLoss(isReply = false)

    private fun checkOfferLoss(isReply: Boolean) {
        reply.state = reply.state.copy(restorableDraft = "Brouillon privé")
        message.state = message.state.copy(restorableDraft = "Brouillon privé", restorableSubject = "titre",
            restorableRecipients = "bob", recipientState = EditorRecipientState(listOf("alice")), subject = "Sujet")
        val host = host(isReply)
        val body = if (isReply) reply.body else message.body
        val calls = if (isReply) reply.calls else message.calls
        compose.setContent { Content(isReply) }
        compose.runOnIdle {
            focus.requestFocus()
            body.edit { selection = TextRange(6, 1) }
        }
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().id
        compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithTag("editor-draft-offer-sheet").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(host.draftOfferOpen)
            reply.state = reply.state.copy(restorableDraft = null)
            message.state = message.state.copy(restorableDraft = null, restorableSubject = null,
                restorableRecipients = null)
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Retour au message").assertDoesNotExist()
        compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals(field, compose.onNode(hasSetTextAction()).fetchSemanticsNode().id)
        compose.runOnIdle {
            assertEquals(1, returns)
            assertEquals(1, host.returnGeneration)
            assertFalse(host.consumeReturn())
            assertFalse(host.draftOfferOpen)
            assertNull(host.surface)
            assertNull(host.detail)
            assertEquals("Bonjour", body.text.toString())
            assertEquals(TextRange(6, 1), body.selection)
            assertTrue(calls.isEmpty())
            assertTrue(if (isReply) reply.events.state.events.isEmpty() else message.events.state.events.isEmpty())
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, returns) }
    }

    private fun host(isReply: Boolean): EditorHost<*> = if (isReply) reply.adapter else message.adapter

    private fun checkOffer(isReply: Boolean) {
        reply.state = reply.state.copy(restorableDraft = "Brouillon privé")
        message.state = message.state.copy(restorableDraft = "Brouillon privé", restorableSubject = "titre",
            restorableRecipients = "bob", recipientState = EditorRecipientState(listOf("alice")), subject = "Sujet")
        val host = host(isReply)
        val body = if (isReply) reply.body else message.body
        val calls = if (isReply) reply.calls else message.calls
        compose.setContent { Content(isReply) }
        compose.onNode(isDialog()).assertDoesNotExist()
        var expectedReturns = 0
        listOf("restore", "discard", "swipe", "back", "outside", "close").forEach { closing ->
            compose.runOnIdle {
                focus.requestFocus()
                body.edit { selection = TextRange(6, 1) }
            }
            val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().id
            val previousCalls = calls.toList()
            compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
            compose.onNode(isDialog()).assertExists()
            compose.onNodeWithTag("editor-draft-offer-sheet").assertIsDisplayed()
            compose.onNodeWithText("Citation en attente").assertDoesNotExist()
            compose.runOnIdle { assertTrue(host.draftOfferOpen) }
            when (closing) {
                "restore" -> compose.onNodeWithText("Restaurer").performScrollTo().performClick()
                "discard" -> compose.onNodeWithText("Ignorer").performScrollTo().performClick()
                "swipe" -> compose.onNodeWithTag("editor-draft-offer-sheet").performTouchInput { swipeDown() }
                "back" -> compose.runOnIdle {
                    (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
                }
                "outside" -> compose.onNode(isDialog()).performTouchInput { click(Offset(4f, 4f)) }
                "close" -> compose.onNodeWithContentDescription("Fermer l’offre de brouillon").performClick()
            }
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.onNode(hasSetTextAction()).assertIsFocused()
            assertEquals(field, compose.onNode(hasSetTextAction()).fetchSemanticsNode().id)
            compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").assertExists()
            expectedReturns++
            compose.runOnIdle {
                assertEquals(expectedReturns, returns)
                assertEquals(expectedReturns, host.returnGeneration)
                assertFalse(host.consumeReturn())
                assertFalse(host.draftOfferOpen)
                assertNull(host.surface)
                assertNull(host.detail)
                assertEquals("Bonjour", body.text.toString())
                assertEquals(TextRange(6, 1), body.selection)
                val decisions = listOf(closing).filter { it == "restore" || it == "discard" }
                assertEquals(previousCalls + decisions, calls)
                val restorable = if (isReply) reply.state.restorableDraft else message.state.restorableDraft
                assertEquals("Brouillon privé", restorable)
                assertTrue(if (isReply) reply.events.state.events.isEmpty() else message.events.state.events.isEmpty())
                host.closeDraftOffer() // A late dismissal cannot schedule a second focus return.
            }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(expectedReturns, returns) }
        }
    }
}
