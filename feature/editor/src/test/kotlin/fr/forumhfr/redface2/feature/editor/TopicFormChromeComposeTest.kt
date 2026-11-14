package fr.forumhfr.redface2.feature.editor

import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorEventAction
import fr.forumhfr.redface2.core.ui.editor.EditorScaffold
import fr.forumhfr.redface2.core.ui.editor.EditorScaffoldChrome
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
class TopicFormChromeComposeTest {
    private var settingsOpens = 0
    private val openSettings: () -> Unit = {
        assertNull(fixture.adapter.host.surface)
        settingsOpens++
    }
    @get:Rule val compose = createComposeRule()
    private var fixture = TopicFormChromeFixture()
    private lateinit var chrome: EditorScaffoldChrome
    private lateinit var back: OnBackPressedDispatcher
    private var returns = 0

    @Composable
    private fun Content(compact: Boolean = false) {
        back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
        val a = fixture.adapter
        val focus = remember { FocusRequester() }
        val scope = rememberCoroutineScope()
        val smileys = remember { SmileyPickerController(scope, searchWiki = { _, _ -> emptyList() }) }
        val projected = a.chrome()
        SideEffect { chrome = projected }
        RedfaceTheme {
            EditorScaffold(fixture.body, EditorWindowMetrics(if (compact) 400.dp else 740.dp, 0.dp, 0.dp),
                a.title(), a.context(), projected, a.host.barActions, a.host.submitState, a.host.submitActions,
                a.host.imageActions, a.host::closeRequested, onOpenHeader = a::openHeader,
                headerLabel = a.headerLabel(), bodyFocusRequester = focus, surfaceFocus = a.host.focusMemory,
                bodyFocusRestoreAllowed = a.host.surface == null && a.host.detail == null,
                onSystemBack = { a.back() },
                overlay = { restore, body ->
                    TopicFormChromeSurfaces(a, fixture.body, smileys, { returns++; restore() }, compact, body,
                        onOpenEditorSettings = openSettings)
                    BackHandler { a.back() }
                })
        }
    }
    private fun complete(error: fr.forumhfr.redface2.core.ui.editor.EditorError) {
        fixture.error(error)
        val event = fixture.events.visibleEvent!!
        fixture.adapter.host.barActions.onEvent(event.key, EditorEventAction.Complete)
    }
    @Test fun listedDecisionsFollowPendingWriteAndMergedOfferGuards() {
        val older = EditorDraftStore.DraftOffer("older", 1, EditorDraftStore.Draft("Older draft", updatedAt = 1))
        val newer = EditorDraftStore.DraftOffer("newer", 2, EditorDraftStore.Draft("Newer draft", updatedAt = 2))
        fixture.state = fixture.state.copy(restorableDraft = "Newer draft", draftOffers = listOf(newer, older),
            draftOfferDecisionPending = true)
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
        for (id in listOf("older", "newer")) for (label in listOf("Restaurer", "Ignorer")) {
            compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("editor-draft-offer-$id")))
                .assertIsNotEnabled()
        }
        compose.runOnIdle {
            fixture.adapter.restoreOffer(older.id)
            fixture.adapter.discardOffer(older.id)
            assertTrue(fixture.adapter.host.draftOfferOpen)
            assertTrue(fixture.calls.isEmpty())
            assertEquals(0, returns)
        }
        compose.runOnIdle {
            fixture.state = fixture.state.copy(draftOfferDecisionPending = false,
                draftOfferToResolveId = older.id)
            fixture.adapter.restoreOffer(newer.id)
            fixture.adapter.discardOffer(newer.id)
            assertTrue(fixture.adapter.host.draftOfferOpen)
            assertTrue(fixture.calls.isEmpty())
        }
        for (label in listOf("Restaurer", "Ignorer")) {
            compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("editor-draft-offer-newer")))
                .assertIsNotEnabled()
            compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("editor-draft-offer-older")))
                .assertIsEnabled()
        }
        compose.runOnIdle { fixture.state = fixture.state.copy(draftOfferToResolveId = null) }
        compose.onNode(hasText("Ignorer") and hasAnyAncestor(hasTestTag("editor-draft-offer-newer")))
            .assertIsEnabled().performScrollTo().performClick()
        compose.onNodeWithTag("editor-draft-offer-sheet").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, fixture.calls.size); assertEquals(0, returns) }
    }

    @Test fun settingsTapClosesOptionsBeforeCallingNavigationOnce() {
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Options").performClick()
        compose.onNodeWithText("Réglages de l’éditeur").performScrollTo().performClick()
        compose.onNodeWithTag("editor-options-sheet").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, settingsOpens)
            assertNull(fixture.adapter.host.surface)
        }
    }

    @Test fun settingsAreDisabledDuringUploadAndSubmission() {
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Options").performClick()
        compose.runOnIdle { fixture.state = fixture.state.copy(isUploading = true) }
        compose.onNodeWithText("Réglages de l’éditeur").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { fixture.state = fixture.state.copy(isUploading = false, isSubmitting = true) }
        compose.onNodeWithText("Réglages de l’éditeur").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, settingsOpens) }
    }

    @Test fun `ED101 empty creation focuses title and completion returns focus and selection to body`() {
        fixture.state = fixture.state.copy(subject = TextFieldValue())
        compose.setContent { Content() }
        compose.onNodeWithText("Titre du sujet").assertIsFocused()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Retour au message").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(TextRange(6, 1), fixture.body.selection)
            complete(EditorErrors.missingTitle)
        }
        compose.onNodeWithText("Titre du sujet").assertIsFocused()
        compose.onNodeWithText("Retour au message").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle { assertEquals(TextRange(6, 1), fixture.body.selection) }
    }
    @Test fun `ED100 first post completion targets subcategory and shows no selector when unavailable`() {
        fixture = TopicFormChromeFixture(TopicFormMode.EditFirstPost)
        compose.setContent { Content() }
        compose.runOnIdle { complete(EditorErrors.missingSubcategory) }
        compose.onNodeWithText("Divers").assertIsFocused()
        compose.runOnIdle { fixture.state = fixture.state.copy(isSubmitting = true) }
        compose.onNodeWithText("Titre du sujet").assertIsNotEnabled()
        compose.onNodeWithText("Divers").assertIsNotEnabled()
        compose.runOnIdle {
            fixture.adapter.closeHeader()
            fixture.state = fixture.state.copy(firstPostSubcategoryRefused = true, isSubmitting = false)
            fixture.adapter.openHeader()
        }
        compose.onNodeWithText("Titre du sujet").assertIsEnabled().assert(hasSetTextAction())
            .performTextInput("Modifié ")
        compose.runOnIdle { assertTrue(fixture.state.subject.text.contains("Modifié")) }
        compose.onNodeWithText("Sous-catégorie").assertDoesNotExist()
        compose.onNodeWithText("L’envoi du premier message dans une catégorie sans sous-catégorie " +
            "n’est pas disponible.").assertExists()
        compose.runOnIdle {
            assertFalse(fixture.adapter.state.postAllowed)
            assertFalse(fixture.adapter.state.sendActivatable)
        }
    }
    @Test fun `ED102 poll and restoration occupy distinct slots with accessible header summary`() {
        fixture = TopicFormChromeFixture(TopicFormMode.EditFirstPost)
        fixture.state = fixture.state.copy(pollPresent = true, restorableSubject = "Sauvegardé")
        compose.setContent { Content() }
        compose.onNodeWithText("Titre · Divers").assertExists()
        compose.onNodeWithContentDescription("Modifier le titre et la sous-catégorie : " +
            "Modifier, Titre · Divers").assertExists()
        compose.runOnIdle {
            assertTrue(chrome.firstStatus != null)
            assertTrue(chrome.secondStatus != null)
        }
        compose.onNodeWithContentDescription("Sondage présent, consulter").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Ce sujet contient un sondage.", substring = true).assertExists()
        compose.runOnIdle { fixture.state = fixture.state.copy(pollEditable = true) }
        compose.onNodeWithText("Ce sujet contient un sondage.", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertTrue(chrome.firstStatus == null); assertTrue(fixture.calls.isEmpty()) }
    }
    @Test fun listedDiscardsKeepSheetUntilConfirmedExhaustion() {
        val older = EditorDraftStore.DraftOffer("older", 1, EditorDraftStore.Draft("Older draft", updatedAt = 1))
        val newer = EditorDraftStore.DraftOffer("newer", 2, EditorDraftStore.Draft("Newer draft", updatedAt = 2))
        fixture.state = fixture.state.copy(restorableDraft = "Newer draft", draftOffers = listOf(newer, older))
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
        compose.onNode(hasText("Ignorer") and hasAnyAncestor(hasTestTag("editor-draft-offer-newer")))
            .performScrollTo().performClick()
        compose.onNodeWithTag("editor-draft-offer-sheet").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(TopicFormIntent.DraftOfferDiscarded(newer.id)), fixture.calls)
            fixture.error(EditorErrors.draftSaveFailed)
            assertEquals(0, returns)
        }
        compose.onNodeWithText("Newer draft").assertIsDisplayed()
        compose.runOnIdle {
            fixture.state = fixture.state.copy(restorableDraft = "Older draft", draftOffers = listOf(older))
        }
        compose.onNodeWithText("Newer draft").assertDoesNotExist()
        compose.onNodeWithText("Older draft").assertIsDisplayed()
        compose.onNodeWithText("Ignorer").assertIsEnabled().performScrollTo().performClick()
        compose.onNodeWithTag("editor-draft-offer-sheet").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(TopicFormIntent.DraftOfferDiscarded(newer.id),
                TopicFormIntent.DraftOfferDiscarded(older.id)), fixture.calls)
            assertEquals(0, returns)
            fixture.state = fixture.state.copy(restorableDraft = null, draftOffers = emptyList())
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle { assertEquals(1, returns) }
    }

    @Test fun `1450 topic offer sheet returns focus once for each closing path`() =
        checkDraftOffer(TopicFormMode.New)

    @Test fun `1450 first post offer sheet returns focus once for each closing path`() =
        checkDraftOffer(TopicFormMode.EditFirstPost)

    private fun checkDraftOffer(mode: TopicFormMode) {
        fixture = TopicFormChromeFixture(mode)
        // A subject-only offer must be available just like a body-only offer.
        fixture.state = fixture.state.copy(
            restorableSubject = if (mode == TopicFormMode.New) "Saved title" else null,
            restorableDraft = if (mode == TopicFormMode.EditFirstPost) "Saved body" else null)
        compose.setContent { Content() }
        listOf("restore", "discard", "swipe", "back", "outside", "close").forEachIndexed { index, closing ->
            val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().id
            val calls = fixture.calls.toList()
            compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
            compose.onNode(isDialog()).assertExists()
            compose.onNodeWithTag("editor-draft-offer-sheet").assertIsDisplayed()
            compose.onNodeWithText("Brouillon retrouvé").assertIsDisplayed()
            compose.onNodeWithText("Retour au message").assertDoesNotExist()
            compose.onNodeWithText("Citation en attente").assertDoesNotExist()
            compose.runOnIdle {
                assertTrue(fixture.adapter.host.draftOfferOpen)
                assertTrue(fixture.events.state.events.isEmpty())
                assertEquals(calls, fixture.calls)
            }
            when (closing) {
                "restore" -> compose.onNodeWithText("Restaurer").performScrollTo().performClick()
                "discard" -> compose.onNodeWithText("Ignorer").performScrollTo().performClick()
                "swipe" -> compose.onNodeWithTag("editor-draft-offer-sheet").performTouchInput { swipeDown() }
                "back" -> compose.runOnIdle {
                    (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
                }
                "outside" -> tapScrim()
                "close" -> compose.onNodeWithContentDescription("Fermer l’offre de brouillon").performClick()
            }
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.onNode(hasSetTextAction()).assertIsFocused()
            assertEquals(field, compose.onNode(hasSetTextAction()).fetchSemanticsNode().id)
            compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").assertExists()
            compose.runOnIdle {
                val decision = when (closing) {
                    "restore" -> listOf(TopicFormIntent.DraftRestoreRequested)
                    "discard" -> listOf(TopicFormIntent.DraftDiscardRequested)
                    else -> emptyList()
                }
                assertEquals(calls + decision, fixture.calls)
                assertEquals(TextRange(6, 1), fixture.body.selection)
                assertEquals("Bonjour", fixture.body.text.toString())
                assertEquals(index + 1, returns)
                assertEquals(index + 1, fixture.adapter.host.returnGeneration)
                assertFalse(fixture.adapter.host.draftOfferOpen)
                fixture.adapter.host.closeDraftOffer() // Late dismissal must not return focus again.
            }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(index + 1, returns) }
        }
    }

    @Test fun `1450 losing the topic offer closes its open sheet without an intent and returns focus once`() =
        checkDraftOfferLoss(TopicFormMode.New)

    @Test fun `1450 losing the first post offer closes its open sheet without an intent and returns focus once`() =
        checkDraftOfferLoss(TopicFormMode.EditFirstPost)

    private fun checkDraftOfferLoss(mode: TopicFormMode) {
        fixture = TopicFormChromeFixture(mode)
        fixture.state = fixture.state.copy(restorableSubject = "Saved title", restorableDraft = "Saved body")
        compose.setContent { Content() }
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().id
        compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithTag("editor-draft-offer-sheet").assertIsDisplayed()
        // Either half keeps the offer available; only losing both closes the sheet.
        compose.runOnIdle { fixture.state = fixture.state.copy(restorableDraft = null) }
        compose.onNodeWithTag("editor-draft-offer-sheet").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(fixture.adapter.host.draftOfferOpen)
            assertEquals(0, returns)
            fixture.state = fixture.state.copy(restorableSubject = null)
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Retour au message").assertDoesNotExist()
        compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals(field, compose.onNode(hasSetTextAction()).fetchSemanticsNode().id)
        compose.runOnIdle {
            assertFalse(fixture.adapter.host.draftOfferOpen)
            assertNull(fixture.adapter.host.surface)
            assertNull(fixture.adapter.host.detail)
            assertEquals(1, returns)
            assertEquals(1, fixture.adapter.host.returnGeneration)
            assertFalse(fixture.adapter.host.consumeReturn())
            assertTrue(fixture.calls.isEmpty())
            assertTrue(fixture.events.state.events.isEmpty())
            assertEquals(TextRange(6, 1), fixture.body.selection)
            assertEquals("Bonjour", fixture.body.text.toString())
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, returns) }
    }

    /** Tap the scrim the sheet leaves exposed above it, in the dialog's own coordinates. */
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

    @Test fun `missing route shows a writable header without the unsupported first post notice`() {
        fixture = TopicFormChromeFixture(TopicFormMode.EditFirstPost)
        fixture.state = fixture.state.copy(subcat = null, hasSubcategorySelect = false)
        fixture.adapter.openHeader()
        compose.setContent { Content() }
        compose.onNodeWithText("Titre du sujet").assertIsEnabled().assert(hasSetTextAction())
        compose.onNodeWithText("Sous-catégorie").assertDoesNotExist()
        compose.onNodeWithText("L’envoi du premier message", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertFalse(fixture.adapter.state.sendActivatable) }
    }
    @Test fun `restoring a topic session leaves the title enabled but read only`() {
        fixture.adapter.openHeader()
        fixture.state = fixture.state.copy(isInitializing = true, isRestoringSession = true)
        compose.setContent { Content() }
        val title = compose.onNodeWithText("Titre du sujet").assertIsEnabled()
        val setText = title.fetchSemanticsNode().config.getOrNull(SemanticsActions.SetText)?.action
        compose.runOnIdle {
            assertFalse(setText?.invoke(AnnotatedString("Refusé")) == true)
            assertEquals("Titre", fixture.state.subject.text)
        }
        compose.onNodeWithText("Divers").assertIsNotEnabled()
        compose.runOnIdle {
            fixture.state = fixture.state.copy(isInitializing = false, isRestoringSession = false)
        }
        compose.onNodeWithText("Titre du sujet").assertIsEnabled().assert(hasSetTextAction())
    }
    @Test fun `ED101 initial creation hides an unknown selector until real choices arrive`() {
        val loaded = fixture.state
        fixture.state = loaded.copy(subject = TextFieldValue(), hasLoadedForm = false,
            isLoadingForm = true, subcategoryChoices = emptyList())
        compose.setContent { Content() }
        compose.onNodeWithText("Titre du sujet").assertIsFocused()
        compose.onNodeWithText("Sous-catégorie").assertDoesNotExist()
        compose.runOnIdle { fixture.state = fixture.state.copy(isLoadingForm = false, hasLoadedForm = true,
            hasSubcategorySelect = false) }
        compose.onNodeWithText("Sous-catégorie").assertDoesNotExist()
        compose.runOnIdle { fixture.state = loaded }
        compose.onNodeWithText("Divers").assertExists()
    }
    @Test fun `ED101 compact header entry works without a subcategory in creation`() {
        fixture.state = fixture.state.copy(hasSubcategorySelect = false)
        compose.setContent { Content(compact = true) }
        compose.onNodeWithContentDescription("Modifier le titre").performClick()
        compose.onNodeWithText("Titre du sujet").assertIsFocused()
        compose.onNodeWithText("Sous-catégorie").assertDoesNotExist()
        compose.onNodeWithText("Retour au message").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNodeWithTag("editor-bar-submit").assertExists()
    }
    @Test fun `ED100 failed form projection retains the real Reload remedy`() {
        fixture.error(EditorErrors.formNetwork)
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Hors connexion, Afficher les détails").performClick()
        compose.onNodeWithText("Recharger").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle { assertEquals(listOf(TopicFormIntent.ReloadRequested), fixture.calls) }
    }
    @Test fun `ED45 explicit Masquer in the topic detail forwards acknowledgement without sending`() {
        val attempt = EditorDraftStore.AttemptToken("alice", fixture.session.key, "attempt", 1)
        fixture.transition(fixture.state.copy(submission = EditorSubmissionState.Uncertain(attempt),
            submitError = EditorErrors.uncertain))
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Envoi incertain, Afficher les détails").performClick()
        compose.runOnIdle { assertTrue(fixture.calls.isEmpty()) }
        compose.onNodeWithText("Masquer").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf(TopicFormIntent.ErrorDismissed), fixture.calls)
            assertNull(fixture.adapter.host.detail)
            assertNull(fixture.events.visibleEvent)
        }
    }
    @Test fun `ED45 ED85 system Back closes the topic uncertainty detail without acknowledging it`() {
        fixture = TopicFormChromeFixture(TopicFormMode.EditFirstPost)
        val attempt = EditorDraftStore.AttemptToken("alice", fixture.session.key, "attempt", 1)
        val uncertain = EditorSubmissionState.Uncertain(attempt)
        fixture.transition(fixture.state.copy(submission = uncertain, submitError = EditorErrors.uncertain))
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Envoi incertain, Afficher les détails").performClick()
        compose.onNodeWithText("Masquer").assertExists()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText("Masquer").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertTrue(fixture.calls.isEmpty())
            assertNull(fixture.adapter.host.detail)
            assertEquals(uncertain, fixture.state.submission)
        }
    }
}
