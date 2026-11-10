package fr.forumhfr.redface2.feature.editor

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
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
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
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

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PostEditorChromeComposeTest {
    @get:Rule val compose = createComposeRule()
    private val host = PostEditorChromeFixture()
    private val focus = FocusRequester()
    private val scroll = ScrollState(0)
    private lateinit var back: OnBackPressedDispatcher
    private lateinit var inputMode: InputModeManager

    @Composable private fun PostContent() {
        back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
        inputMode = LocalInputModeManager.current
        RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
            PostEditorTestContent(host.state, host::dispatch, host.body, host.events)
        }
    }

    @Composable private fun Content() {
        val scope = rememberCoroutineScope()
        val smileys = remember { SmileyPickerController(scope, searchWiki = { _, _ -> emptyList() }) }
        RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
            EditorScaffold(host.body, EditorWindowMetrics(740.dp, 24.dp, 30.dp), "Répondre", "Sujet",
                host.adapter.chrome(), host.adapter.barActions, host.adapter.submitState, host.adapter.submitActions,
                host.adapter.imageActions, host.adapter::closeRequested, bodyFocusRequester = focus,
                bodyFocusRestoreAllowed = host.adapter.surface == null && host.adapter.detail == null &&
                    !host.state.isPreviewVisible,
                bodyScrollState = scroll, overlay = { restore, body ->
                    PostEditorChromeSurfaces(host.adapter, host.body, smileys, restore, compactHeight = false, body)
                })
        }
    }

    @Test fun `ED22 ED44 ED80 selection survives tools and typing then only the second tap sends`() {
        compose.setContent { Content() }
        compose.runOnIdle { focus.requestFocus() }
        compose.onNode(hasSetTextAction()).performTextInputSelection(TextRange(6, 1))
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        compose.onNodeWithContentDescription("Gras").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        val selection = host.body.selection
        compose.runOnIdle { assertEquals("onjou", host.body.text.substring(selection.min, selection.max)) }
        // Return to Normal, then exercise the real options sheet and its expanded 16-tone grid.
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        compose.onNodeWithContentDescription("Options").performClick()
        compose.onNodeWithTag("${MSG_ICON_OPTION_TAG_PREFIX}16").assertExists()
        compose.runOnIdle { host.adapter.closeSurface() }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle { assertEquals(selection, host.body.selection) }
        val button = compose.onNodeWithTag("editor-bar-submit").getUnclippedBoundsInRoot()
        repeat(50) { compose.onNode(hasSetTextAction()).performTextInput("a") }
        assertEquals(button, compose.onNodeWithTag("editor-bar-submit").getUnclippedBoundsInRoot())
        compose.runOnIdle {
            assertFalse(host.calls.any { it is PostEditorIntent.SubmitClicked })
            host.error(EditorErrors.noImageReceived)
        }
        compose.onNodeWithTag("editor-bar-submit").performClick()
        compose.runOnIdle { assertFalse(host.calls.any { it is PostEditorIntent.SubmitClicked }) }
        compose.onNodeWithTag("editor-bar-submit").performClick()
        compose.runOnIdle { assertEquals(1, host.calls.count { it is PostEditorIntent.SubmitClicked }) }
    }

    @Test fun `ED45 details reload and hide return focus without any submission acknowledgement`() {
        host.error(EditorErrors.formNetwork)
        compose.setContent { Content() }
        compose.runOnIdle { focus.requestFocus() }
        val selection = host.body.selection
        val node = compose.onNode(hasSetTextAction()).fetchSemanticsNode().id
        compose.onNodeWithContentDescription("Hors connexion, Afficher les détails").performClick()
        compose.onNodeWithText("Recharger").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals(node, compose.onNode(hasSetTextAction()).fetchSemanticsNode().id)
        compose.runOnIdle {
            assertEquals(selection, host.body.selection)
            assertEquals(listOf(PostEditorIntent.ReloadRequested), host.calls)
            host.state = host.state.copy(pendingQuotes = listOf(QuoteSelection(QuoteLocator(1, 101, null), "", "")),
                submission = EditorSubmissionState.Uncertain(null))
            host.error(EditorErrors.uncertain, operation = 2)
        }
        compose.onNodeWithContentDescription("Envoi incertain, Afficher les détails").performClick()
        compose.onNodeWithText("Ignorer la citation").assertExists()
        compose.onNodeWithText("Après vérification, reprendre la citation").assertExists()
        compose.onNodeWithText("Masquer").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle { assertEquals(listOf(PostEditorIntent.ReloadRequested), host.calls) }
        compose.onNodeWithContentDescription("1 citation, ouvrir le panneau citations").performClick()
        compose.onNodeWithText("Ignorer la citation").performClick()
        compose.onNodeWithContentDescription("1 citation, ouvrir le panneau citations").performClick()
        compose.onNodeWithText("Après vérification, reprendre la citation").performClick()
        compose.runOnIdle {
            assertEquals(listOf(PostEditorIntent.ReloadRequested, PostEditorIntent.PendingQuoteIgnored,
                PostEditorIntent.PendingQuoteResumed), host.calls)
        }
    }

    @Test fun `ED45 explicit Masquer in the post detail forwards acknowledgement without sending`() {
        val attempt = EditorDraftStore.AttemptToken("alice", host.session.key, "attempt", 1)
        host.transition(host.state.copy(submission = EditorSubmissionState.Uncertain(attempt),
            submitError = EditorErrors.uncertain))
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Envoi incertain, Afficher les détails").performClick()
        compose.runOnIdle { assertTrue(host.calls.isEmpty()) }
        compose.onNodeWithText("Masquer").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf(PostEditorIntent.ErrorDismissed), host.calls)
            assertNull(host.adapter.detail)
            assertNull(host.events.state.visibleEvent)
        }
    }

    @Test fun `ED13 ED14 read-only programmatic upload insertion reveals final caret without stealing selection`() {
        host.state = host.state.copy(isUploading = true)
        compose.setContent { Content() }
        compose.runOnIdle {
            host.body.edit {
                replace(0, length, (1..80).joinToString("\n") { "Image insérée $it" })
                selection = TextRange(length)
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(scroll.value > 0)
            assertEquals(host.body.text.length, host.body.selection.end)
            assertFalse(host.adapter.submitState.enabled)
            assertTrue(host.calls.isEmpty())
        }
    }

    @Test fun `ED85 post system Back disarms retained Format before closing it`() {
        compose.setContent { PostContent() }
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        compose.onNodeWithContentDescription("Gras").assertExists()
        compose.runOnIdle {
            host.state = host.state.copy(submission = EditorSubmissionState.Armed(
                ArmToken(1, host.session, 0, 4_000L)))
        }
        compose.onNodeWithContentDescription("Gras").assertDoesNotExist()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithContentDescription("Gras").assertExists()
        compose.runOnIdle {
            assertNull(host.state.armToken)
            assertEquals(listOf(PostEditorIntent.SubmitConfirmationDismissed), host.calls)
            back.onBackPressed()
        }
        compose.onNodeWithContentDescription("Gras").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(PostEditorIntent.SubmitConfirmationDismissed), host.calls)
        }
    }

    @Test fun `ED85 post system Back from a keyboard sublevel or Format restores its bar trigger`() {
        compose.setContent { PostContent() }
        // The return value reflects the view's focus request, not the mode switch: assert the mode itself.
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.runOnIdle { assertEquals(InputMode.Keyboard, inputMode.inputMode) }
        openWithKeyboard("Mise en forme")
        openWithKeyboard("Couleur")
        barControl("Retour").assertIsFocused()
        // ED-85 closes the focused control's tooltip first. The next Back is resolved and reduced by the
        // host's handler, which wins over the bar's; focus must still return to the trigger.
        backClosesFocusTooltip("Retour")
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithContentDescription("Gras").assertExists()
        barControl("Couleur").assertIsFocused()
        barControl("Mise en forme").requestFocus().assertIsFocused()
        backClosesFocusTooltip("Mise en forme")
        compose.onNodeWithContentDescription("Gras").assertExists()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithContentDescription("Gras").assertDoesNotExist()
        barControl("Mise en forme").assertIsFocused()
        openWithKeyboard("Image")
        barControl("Retour").assertIsFocused()
        backClosesFocusTooltip("Retour")
        compose.runOnIdle { back.onBackPressed() }
        barControl("Image").assertIsFocused()
        compose.runOnIdle { assertTrue(host.calls.isEmpty()) }
    }

    @Test fun `ED85 post system Back disarms before the local restore panel handler can close it`() {
        host.state = host.state.copy(restorableDraft = "Brouillon retrouvé")
        compose.setContent { PostContent() }
        compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle {
            host.state = host.state.copy(submission = EditorSubmissionState.Armed(
                ArmToken(1, host.session, 0, 4_000L)))
        }
        compose.waitForIdle()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle {
            assertNull(host.state.armToken)
            assertEquals(listOf(PostEditorIntent.SubmitConfirmationDismissed), host.calls)
            back.onBackPressed()
        }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf(PostEditorIntent.SubmitConfirmationDismissed), host.calls)
        }
    }

    /** The app bar also has a « Retour » control: scope lookups to the action bar. */
    private fun barControl(label: String) = compose.onNode(hasContentDescription(label) and
        hasAnyAncestor(hasTestTag("editor-scaffold-action-bar")))

    private fun openWithKeyboard(label: String) {
        barControl(label).requestFocus().assertIsFocused().performKeyInput { pressKey(Key.Enter) }
    }

    /** material3 shows a keyboard-focused anchor's tooltip: ED-85 closes it first, keeping level and focus. */
    private fun backClosesFocusTooltip(label: String) {
        compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText(label, useUnmergedTree = true).assertDoesNotExist()
        barControl(label).assertIsFocused()
    }
}
