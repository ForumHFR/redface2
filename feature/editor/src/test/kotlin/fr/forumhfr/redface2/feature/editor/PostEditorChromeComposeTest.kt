package fr.forumhfr.redface2.feature.editor

import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
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
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorEventInput
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
                    PostEditorChromeSurfaces(host.adapter, host.body, smileys, { returns++; restore() }, body)
                })
        }
    }

    @Test fun `1450 pending quote announces politely in the fixed bar and its detail is the offer sheet`() {
        compose.setContent { Content() }
        val field = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        val fieldNode = compose.onNode(hasSetTextAction()).fetchSemanticsNode().id
        val submit = compose.onNodeWithTag("editor-bar-submit").getUnclippedBoundsInRoot()
        compose.runOnIdle {
            host.transition(host.state.copy(restorableDraft = "saved D", pendingQuotes = listOf(
                QuoteSelection(QuoteLocator(1, 101, null), "", ""))))
            assertNull(host.adapter.surface)
        }
        compose.onNodeWithContentDescription("Brouillon retrouvé, citation en attente, Afficher les détails",
            useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        // The outer submit tag measures geometry; only the inner M3 Button owns Disabled.
        compose.onNodeWithTag("editor-bar-submit-shape").assertIsNotEnabled()
        compose.runOnIdle {
            assertFalse(host.state.canSubmit)
            assertFalse(host.adapter.state.sendActivatable)
        }
        assertEquals(field, compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot())
        assertEquals(submit, compose.onNodeWithTag("editor-bar-submit").getUnclippedBoundsInRoot())
        compose.onNodeWithTag("editor-bar-event").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithTag("editor-draft-offer-sheet").assertIsDisplayed()
        compose.onNodeWithText("Brouillon retrouvé").assertIsDisplayed()
        compose.onNodeWithText("Citation en attente").assertIsDisplayed()
        compose.onNodeWithText("Restaurer").assertIsDisplayed()
        compose.onNodeWithText("Ignorer").assertIsDisplayed()
        compose.onNodeWithText("Recharger").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(host.events.visibleEvent?.key, host.adapter.draftOfferDetailKey)
            assertTrue(host.calls.isEmpty())
        }
        compose.onNodeWithContentDescription("Fermer l’offre de brouillon").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").assertExists()
        compose.onNodeWithTag("editor-bar-submit-shape").assertIsNotEnabled()
        assertEquals(fieldNode, compose.onNode(hasSetTextAction()).fetchSemanticsNode().id)
        compose.runOnIdle {
            assertNull(host.events.visibleEvent)
            assertNull(host.adapter.detail)
            assertEquals("saved D", host.state.restorableDraft)
            assertTrue(host.state.pendingQuotes.isNotEmpty())
            assertTrue(host.calls.isEmpty())
            assertEquals(1, returns)
        }
    }

    @Test fun `1450 first restore tap under the pending event opens the same offer sheet`() {
        pendingOffer()
        compose.setContent { Content() }
        eventControl().assertExists()
        compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithTag("editor-draft-offer-sheet").assertIsDisplayed()
        compose.onNodeWithText("Citation en attente").assertIsDisplayed()
        compose.onNodeWithText("Restaurer").assertIsDisplayed()
        compose.onNodeWithText("Ignorer").assertIsDisplayed()
        compose.onNodeWithText("Recharger").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(host.adapter.draftOfferOpen)
            assertNull(host.adapter.draftOfferDetailKey)
            assertNull(host.events.visibleEvent)
            assertTrue(host.calls.isEmpty())
        }
    }

    @Test fun `1450 pending notice times out at five seconds then restore opens the same offer`() {
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilityManager provides null) { Content() }
        }
        compose.mainClock.autoAdvance = false
        val key = compose.runOnIdle { pendingOffer(); host.events.visibleEvent!!.key }
        // Publish the snapshot and wake the recomposer before the delivery's first frame: with autoAdvance
        // off, waiting only after that frame would start the timer one frame late.
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame() // Start the newly displayed event's timeout effect.
        compose.waitForIdle()
        advance(4_999L)
        compose.runOnIdle { assertEquals(key, host.events.visibleEvent?.key) }
        advance(1L)
        compose.runOnIdle { assertNull(host.events.visibleEvent) } // Retired at five seconds exactly.
        compose.mainClock.advanceTimeByFrame() // autoAdvance is off: render the retirement.
        compose.onNode(isDialog()).assertDoesNotExist()
        eventControl().assertDoesNotExist()
        compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").assertExists()
        compose.onNodeWithTag("editor-bar-submit-shape").assertIsNotEnabled()
        compose.runOnIdle {
            host.transition(host.state.copy(bodyText = "typing"))
            assertNull(host.events.visibleEvent)
            assertTrue(host.events.isPendingQuoteEvent(key))
            assertFalse(host.adapter.draftOfferOpen)
            assertEquals("saved D", host.state.restorableDraft)
            assertTrue(host.state.pendingQuotes.isNotEmpty())
            assertTrue(host.calls.isEmpty())
            assertEquals(0, returns)
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithTag("editor-draft-offer-sheet").assertIsDisplayed()
        compose.onNodeWithText("Brouillon retrouvé").assertIsDisplayed()
        compose.onNodeWithText("Citation en attente").assertIsDisplayed()
        compose.onNodeWithText("Restaurer").assertIsDisplayed()
        compose.onNodeWithText("Ignorer").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(PostEditorChromeSurface.Restore, host.adapter.surface)
            assertNull(host.adapter.draftOfferDetailKey)
            assertTrue(host.calls.isEmpty())
        }
        compose.runOnIdle { sheetBack() }
        compose.onNode(isDialog()).assertDoesNotExist()
        eventControl().assertDoesNotExist()
        compose.onNodeWithTag("editor-bar-submit-shape").assertIsNotEnabled()
        compose.runOnIdle {
            assertNull(host.events.visibleEvent)
            assertEquals("saved D", host.state.restorableDraft)
            assertTrue(host.state.pendingQuotes.isNotEmpty())
            assertTrue(host.calls.isEmpty())
            assertEquals(1, returns)
        }
    }

    @Test fun `1450 restore acknowledges a pending notice masked by a form error without deciding`() {
        compose.setContent { Content() }
        val pending = compose.runOnIdle {
            host.transition(host.state.copy(restorableDraft = "saved D", pendingQuotes = listOf(
                QuoteSelection(QuoteLocator(1, 101, null), "", "")), isLoadingForm = true))
            host.events.visibleEvent!!.key
        }
        eventControl().assertExists()
        val error = compose.runOnIdle {
            host.events.readFailed()
            host.transition(host.state.copy(isLoadingForm = false, submitError = EditorErrors.formNetwork))
            host.events.visibleEvent!!
        }
        compose.onNodeWithContentDescription("Hors connexion, Afficher les détails").assertExists()
        compose.runOnIdle { assertTrue(host.events.state.events.any { it.key == pending }) }
        compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithTag("editor-draft-offer-sheet").assertIsDisplayed()
        compose.onNodeWithText("Citation en attente").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(PostEditorChromeSurface.Restore, host.adapter.surface)
            assertEquals(error, host.events.visibleEvent)
            assertTrue(host.events.state.events.none { it.key == pending })
            sheetBack()
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNodeWithContentDescription("Hors connexion, Afficher les détails").assertExists()
        compose.runOnIdle { host.transition(host.state.copy(isLoadingForm = true)) }
        compose.runOnIdle { host.transition(host.state.copy(isLoadingForm = false, submitError = null)) }
        eventControl().assertDoesNotExist()
        compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").assertExists()
        compose.onNodeWithTag("editor-bar-submit-shape").assertIsNotEnabled()
        compose.runOnIdle {
            assertNull(host.events.visibleEvent)
            assertTrue(host.events.state.events.isEmpty())
            assertEquals("saved D", host.state.restorableDraft)
            assertTrue(host.state.pendingQuotes.isNotEmpty())
            assertEquals("Bonjour", host.body.text.toString())
            assertTrue(host.calls.isEmpty())
            assertEquals(1, returns)
        }
    }

    @Test fun `1450 either offer entry returns focus once after swipe scrim Back Restore or Ignore`() {
        compose.setContent { Content() }
        var expectedReturns = 0
        listOf(false, true).forEach { throughEvent ->
            listOf("swipe", "outside", "back", "restore", "discard").forEach { closing ->
                compose.runOnIdle {
                    // End the previous request before delivering a fresh pending-quote event.
                    host.transition(host.state.copy(restorableDraft = null, pendingQuotes = emptyList()))
                    pendingOffer()
                    focus.requestFocus()
                    host.body.edit { selection = TextRange(6, 1) }
                }
                val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().id
                val calls = host.calls.toList()
                if (throughEvent) compose.onNodeWithTag("editor-bar-event").performClick()
                else compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
                compose.onNodeWithTag("editor-draft-offer-sheet").assertIsDisplayed()
                when (closing) {
                    "swipe" -> compose.onNodeWithTag("editor-draft-offer-sheet").performTouchInput { swipeDown() }
                    "outside" -> tapScrim()
                    "back" -> compose.runOnIdle { sheetBack() }
                    "restore" -> compose.onNodeWithText("Restaurer").performScrollTo().performClick()
                    "discard" -> compose.onNodeWithText("Ignorer").performScrollTo().performClick()
                }
                compose.onNode(isDialog()).assertDoesNotExist()
                compose.onNode(hasSetTextAction()).assertIsFocused()
                assertEquals(field, compose.onNode(hasSetTextAction()).fetchSemanticsNode().id)
                compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").assertExists()
                compose.onNodeWithTag("editor-bar-submit-shape").assertIsNotEnabled()
                expectedReturns++
                compose.runOnIdle {
                    assertEquals(expectedReturns, returns)
                    assertEquals(TextRange(6, 1), host.body.selection)
                    assertEquals("Bonjour", host.body.text.toString())
                    assertEquals("saved D", host.state.restorableDraft)
                    assertTrue(host.state.pendingQuotes.isNotEmpty())
                    assertNull(host.events.visibleEvent)
                    assertNull(host.adapter.detail)
                    val decision = when (closing) {
                        "restore" -> listOf(PostEditorIntent.DraftRestoreRequested)
                        "discard" -> listOf(PostEditorIntent.DraftDiscardRequested)
                        else -> emptyList()
                    }
                    assertEquals(calls + decision, host.calls)
                    host.adapter.closeDraftOffer() // A late dismissal cannot return focus twice.
                }
                compose.waitForIdle()
                compose.runOnIdle { assertEquals(expectedReturns, returns) }
            }
        }
    }

    @Test fun `1450 losing the offer closes either sheet entry without exposing a retired event panel`() {
        compose.setContent { Content() }
        listOf(false, true).forEachIndexed { index, throughEvent ->
            compose.runOnIdle { pendingOffer() }
            if (throughEvent) compose.onNodeWithTag("editor-bar-event").performClick()
            else compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
            compose.onNodeWithTag("editor-draft-offer-sheet").assertIsDisplayed()
            compose.runOnIdle {
                host.adapter.detail?.key?.let { host.events.dispatch(EditorEventInput.Acknowledge(it)) }
                host.transition(host.state.copy(restorableDraft = null))
            }
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.onNodeWithText("Retour au message").assertDoesNotExist()
            compose.onNode(hasSetTextAction()).assertIsFocused()
            compose.runOnIdle {
                assertFalse(host.adapter.draftOfferOpen)
                assertNull(host.adapter.detail)
                assertEquals(index + 1, returns)
                assertTrue(host.calls.isEmpty())
            }
        }
    }

    @Test fun `1450 pending event disappears without acknowledgement when its request is removed`() {
        host.transition(host.state.copy(restorableDraft = "saved D", pendingQuotes = listOf(
            QuoteSelection(QuoteLocator(1, 101, null), "", ""))))
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Brouillon retrouvé, citation en attente, Afficher les détails")
            .assertExists()
        compose.runOnIdle { host.transition(host.state.copy(pendingQuotes = emptyList())) }
        compose.onNodeWithContentDescription("Brouillon retrouvé, citation en attente, Afficher les détails")
            .assertDoesNotExist()
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

    private fun pendingOffer() {
        host.transition(host.state.copy(restorableDraft = "saved D", pendingQuotes = listOf(
            QuoteSelection(QuoteLocator(1, 101, null), "", ""))))
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

    private fun advance(millis: Long) {
        compose.mainClock.advanceTimeBy(millis, ignoreFrameDuration = true)
        compose.waitForIdle()
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
