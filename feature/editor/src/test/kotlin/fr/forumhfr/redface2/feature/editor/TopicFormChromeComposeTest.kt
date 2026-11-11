package fr.forumhfr.redface2.feature.editor

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
class TopicFormChromeComposeTest {
    @get:Rule val compose = createComposeRule()
    private var fixture = TopicFormChromeFixture()
    private lateinit var chrome: EditorScaffoldChrome
    private lateinit var back: OnBackPressedDispatcher

    @Composable
    private fun Content(compact: Boolean = false) {
        back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
        val a = fixture.adapter
        val focus = remember { FocusRequester() }
        val projected = a.chrome()
        SideEffect { chrome = projected }
        RedfaceTheme {
            EditorScaffold(fixture.body, EditorWindowMetrics(if (compact) 400.dp else 740.dp, 0.dp, 0.dp),
                a.title(), a.context(), projected, a.host.barActions, a.host.submitState, a.host.submitActions,
                a.host.imageActions, a.host::closeRequested, onOpenHeader = a::openHeader,
                headerLabel = a.headerLabel(), bodyFocusRequester = focus,
                bodyFocusRestoreAllowed = a.host.surface == null && a.host.detail == null,
                onSystemBack = { a.back() },
                overlay = { restore, body ->
                    TopicFormEventDetails(a, fixture.body, restore) {
                        TopicFormHeaderPanel(a, fixture.body, restore, body)
                    }
                    BackHandler { a.back() }
                })
        }
    }
    private fun complete(error: fr.forumhfr.redface2.core.ui.editor.EditorError) {
        fixture.error(error)
        val event = fixture.events.visibleEvent!!
        fixture.adapter.host.barActions.onEvent(event.key, EditorEventAction.Complete)
    }
    @Test fun `ED101 empty creation focuses title and completion returns focus and selection to body`() {
        fixture.state = fixture.state.copy(subject = TextFieldValue())
        compose.setContent { Content() }
        compose.onNodeWithText("Titre du sujet").assertIsFocused()
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
        compose.onNodeWithText("Ce sujet contient un sondage.", substring = true).assertExists()
        compose.runOnIdle { fixture.state = fixture.state.copy(pollEditable = true) }
        compose.onNodeWithText("Ce sujet contient un sondage.", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertTrue(chrome.firstStatus == null); assertTrue(fixture.calls.isEmpty()) }
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
