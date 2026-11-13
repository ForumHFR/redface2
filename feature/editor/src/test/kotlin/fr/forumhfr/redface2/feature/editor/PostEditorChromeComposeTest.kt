package fr.forumhfr.redface2.feature.editor

import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.SubmissionToken
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
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
import org.robolectric.shadows.ShadowDialog

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PostEditorChromeComposeTest {
    private var settingsOpens = 0
    private val openSettings: () -> Unit = {
        assertNull(host.adapter.surface)
        settingsOpens++
    }
    @get:Rule val compose = createComposeRule()
    private val host = PostEditorChromeFixture()
    private val focus = FocusRequester()
    private val scroll = ScrollState(0)
    private lateinit var back: OnBackPressedDispatcher
    private lateinit var inputMode: InputModeManager
    private var returns = 0

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
                bodyScrollState = scroll, surfaceFocus = host.adapter.focusMemory, overlay = { restore, body ->
                    PostEditorChromeSurfaces(host.adapter, host.body, smileys, { returns++; restore() }, body,
                        onOpenEditorSettings = openSettings)
                })
        }
    }

    // ED-40/41/95 v0.7.8 replaces the timed information and its second entry.
    @Test fun settingsTapClosesOptionsBeforeCallingNavigationOnce() {
        host.state = host.state.copy(editorShortcut = EditorShortcut.SEARCH_SMILEYS)
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Options").performClick()
        compose.onNodeWithText("Réglages de l’éditeur").performScrollTo()
            .assert(hasText("Raccourci : Smileys Wiki, confirmation avant envoi")).performClick()
        compose.onNodeWithTag("editor-options-sheet").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, settingsOpens)
            assertNull(host.adapter.surface)
        }
    }

    @Test fun settingsAreDisabledDuringUploadAndSubmission() {
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Options").performClick()
        compose.runOnIdle { host.state = host.state.copy(isUploading = true) }
        compose.onNodeWithText("Réglages de l’éditeur").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            host.state = host.state.copy(isUploading = false, isSubmitting = true,
                submission = EditorSubmissionState.PersistingAttempt(SubmissionToken(1, host.session)))
        }
        compose.onNodeWithText("Réglages de l’éditeur").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, settingsOpens) }
    }

    @Test fun `offered draft has a persistent accessible dot without changing field geometry`() {
        compose.setContent { Content() }
        val field = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        compose.runOnIdle { host.transition(host.state.copy(restorableDraft = "saved D", bodyText = "quote")) }
        val status = compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer")
        status.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Brouillon à traiter"))
        eventControl().assertDoesNotExist()
        assertEquals(field, compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot())
        status.performClick()
        compose.onNodeWithText("Citation en attente").assertDoesNotExist()
        compose.onNodeWithContentDescription("Fermer l’offre de brouillon").performClick()
        status.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Brouillon à traiter"))
        compose.runOnIdle {
            assertEquals("saved D", host.state.restorableDraft)
            assertTrue(host.calls.isEmpty())
            assertEquals(1, returns)
            assertTrue(host.adapter.state.sendActivatable)
        }
    }

    @Test fun `draft sheet returns focus once after swipe scrim Back Restore or Ignore`() {
        host.state = host.state.copy(restorableDraft = "saved D")
        compose.setContent { Content() }
        listOf("swipe", "scrim", "back", "restore", "discard").forEachIndexed { index, close ->
            compose.runOnIdle { focus.requestFocus() }
            compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
            when (close) {
                "swipe" -> compose.onNodeWithTag("editor-draft-offer-sheet").performTouchInput { swipeDown() }
                "scrim" -> tapScrim()
                "back" -> compose.runOnIdle { sheetBack() }
                "restore" -> compose.onNodeWithText("Restaurer").performClick()
                "discard" -> compose.onNodeWithText("Ignorer").performClick()
            }
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.onNode(hasSetTextAction()).assertIsFocused()
            compose.runOnIdle { assertEquals(index + 1, returns) }
        }
    }

    @Test fun `1450 pending quote delivery offers Reload in save failure details`() {
        host.transition(host.state.copy(pendingQuotes = listOf(
            QuoteSelection(QuoteLocator(1, 101, null), "", "")), submitError = EditorErrors.draftSaveFailed))
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Brouillon non écrit, Afficher les détails").performClick()
        compose.onNode(isDialog()).assertDoesNotExist() // Save/quote recovery remains an event panel.
        compose.onNodeWithText("Recharger").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(PostEditorIntent.ReloadRequested), host.calls)
            assertEquals("Bonjour", host.body.text.toString())
            assertTrue(host.state.pendingQuotes.isNotEmpty())
        }
    }

    @Test fun `1450 quote status keeps Reload reachable after autosave retires its save information`() {
        host.transition(host.state.copy(pendingQuotes = listOf(
            QuoteSelection(QuoteLocator(1, 101, null), "", "")), submitError = EditorErrors.draftSaveFailed))
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Brouillon non écrit, Afficher les détails").assertExists()
        compose.runOnIdle {
            host.transition(host.state.copy(submitError = null))
            host.events.durableWriteSucceeded()
        }
        compose.onNodeWithContentDescription("Brouillon non écrit, Afficher les détails").assertDoesNotExist()
        compose.onNodeWithTag("editor-bar-submit-shape").assertIsNotEnabled()
        compose.onNodeWithContentDescription("1 citation, ouvrir le panneau citations").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Retour au message").assertIsDisplayed()
        compose.onNodeWithText("Recharger").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(PostEditorIntent.ReloadRequested), host.calls)
            assertTrue(host.state.pendingQuotes.isNotEmpty())
        }
        compose.runOnIdle { host.transition(host.state.copy(pendingQuotes = emptyList())) }
        compose.onNodeWithContentDescription("1 citation, ouvrir le panneau citations").assertDoesNotExist()
    }

    @Test fun `1450 invalidated binding removes quote recovery from status and open save details`() {
        host.transition(host.state.copy(pendingQuotes = listOf(
            QuoteSelection(QuoteLocator(1, 101, null), "", "")), submitError = EditorErrors.draftSaveFailed))
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Brouillon non écrit, Afficher les détails").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Recharger").assertIsDisplayed()
        compose.runOnIdle { host.transition(host.state.copy(blocksFormReload = true)) }
        compose.onNodeWithText("Recharger").assertDoesNotExist()
        compose.onNodeWithContentDescription("1 citation, ouvrir le panneau citations").assertDoesNotExist()
        compose.runOnIdle {
            host.adapter.reload(host.adapter.detail!!.key)
            assertTrue(host.calls.isEmpty())
            assertTrue(host.state.pendingQuotes.isNotEmpty())
        }
    }

    @Test fun `1450 invalidated binding removes Reload from a retained pending quote panel`() {
        host.transition(host.state.copy(pendingQuotes = listOf(QuoteSelection(QuoteLocator(1, 101, null), "", ""))))
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("1 citation, ouvrir le panneau citations").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Retour au message").assertIsDisplayed()
        compose.onNodeWithText("Recharger").assertIsDisplayed()
        compose.runOnIdle { host.transition(host.state.copy(blocksFormReload = true)) }
        compose.onNodeWithText("Recharger").assertDoesNotExist()
        compose.onNodeWithContentDescription("1 citation, ouvrir le panneau citations").assertDoesNotExist()
        compose.runOnIdle {
            host.adapter.reloadPendingQuote()
            assertTrue(host.calls.isEmpty())
            assertTrue(host.state.pendingQuotes.isNotEmpty())
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
        // Return to Normal, then exercise the real options sheet and its named 16-tone grid.
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        compose.onNodeWithContentDescription("Options").performClick()
        compose.onNodeWithTag("${MSG_ICON_OPTION_TAG_PREFIX}16").assertExists()
        compose.onNodeWithContentDescription("Aucun").assertIsSelected()
        compose.onNodeWithContentDescription("Clin d'œil").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(host.calls.contains(PostEditorIntent.MsgIconSelected(12))) }
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

    @Test fun `ED85 post system Back closes the draft offer sheet before disarming`() {
        host.state = host.state.copy(restorableDraft = "Brouillon retrouvé")
        compose.setContent { PostContent() }
        compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithTag("editor-draft-offer-sheet").assertIsDisplayed()
        compose.runOnIdle {
            host.state = host.state.copy(submission = EditorSubmissionState.Armed(
                ArmToken(1, host.session, 0, 4_000L)))
        }
        compose.waitForIdle()
        compose.runOnIdle { sheetBack() }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(ArmToken(1, host.session, 0, 4_000L), host.state.armToken)
            assertTrue(host.calls.isEmpty())
            back.onBackPressed()
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle {
            assertNull(host.state.armToken)
            assertEquals(listOf(PostEditorIntent.SubmitConfirmationDismissed), host.calls)
        }
    }

    private fun sheetBack() {
        (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
    }

    private fun tapScrim() {
        val dialog = compose.onNode(isDialog())
        val dialogNode = dialog.fetchSemanticsNode()
        val dialogBounds = dialogNode.boundsInRoot
        // The tagged outer node is reported at y = 0, before M3's anchor offset: a settled expanded sheet is
        // bottom-anchored, so its visible top is the dialog's bottom minus its height. The title proves it.
        val sheetHeight = compose.onNodeWithTag("editor-draft-offer-sheet").fetchSemanticsNode().boundsInRoot.height
        val sheetTop = dialogBounds.bottom - sheetHeight
        val title = compose.onNodeWithText("Brouillon retrouvé").fetchSemanticsNode().boundsInRoot
        assertTrue("The offer must sit inside the visible sheet: title=$title sheetTop=$sheetTop",
            title.top >= sheetTop && title.bottom <= dialogBounds.bottom)
        val exposedHeight = sheetTop - dialogBounds.top
        assertTrue("The sheet must expose a scrim above it", exposedHeight > 0f)
        val tap = Offset(dialogBounds.center.x, dialogBounds.top + exposedHeight / 2f)
        assertTrue(tap.y < sheetTop)
        dialog.performTouchInput { click(tap - dialogNode.positionInRoot) }
    }

    /** The slot is a permanent call site (1 dp when empty): an event is shown when its control is named. */
    private fun eventControl() = compose.onNode(hasAnyAncestor(hasTestTag("editor-bar-event")) and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))

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
