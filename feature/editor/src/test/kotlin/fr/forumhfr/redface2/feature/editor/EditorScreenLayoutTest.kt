package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.R as UiR
import fr.forumhfr.redface2.core.ui.editor.EDITOR_DRAFT_MIN_HEIGHT
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #447 — real editor layouts keep the draft and restore actions usable on an IME viewport. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h330dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorScreenLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
    fun postEditLocksBodyAndInsertionsThroughFailureUntilHydration() {
        val state = mutableStateOf(PostEditorState(PostEditorMode.Edit, 23, 42, 101, 1, 0,
            isLoadingForm = true))
        val body = TextFieldState("seed")
        val intents = mutableListOf<PostEditorIntent>()
        lateinit var inputMode: InputModeManager
        compose.setContent {
            inputMode = LocalInputModeManager.current
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                PostEditorTestContent(state.value, { intents += it }, body)
            }
        }
        // Panel close buttons can accept focus only outside touch mode (Focusability.SystemDefined).
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.runOnIdle { assertEquals(InputMode.Keyboard, inputMode.inputMode) }
        assertPostBodyAndInsertionsLocked()
        compose.onNodeWithContentDescription("Options").assertIsEnabled()
        compose.runOnIdle {
            state.value = state.value.copy(isLoadingForm = false, submitError = EditorErrors.formNetwork)
        }
        // The event masks the tools until its detail closes, as required by the new chrome.
        assertPostBodyAndInsertionsLocked(toolsVisible = false)
        compose.onNodeWithContentDescription("Hors connexion, Afficher les détails").performClick()
        compose.onNodeWithText("Recharger").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(PostEditorIntent.ReloadRequested), intents) }
        assertPostBodyAndInsertionsLocked()
        compose.onNodeWithContentDescription("Options").assertIsEnabled()
        // A blank unresolved attempt keeps its uncertainty detail and still offers the form GET.
        compose.runOnIdle { state.value = state.value.copy(submitError = EditorErrors.uncertain) }
        assertPostBodyAndInsertionsLocked(toolsVisible = false)
        compose.onNodeWithContentDescription("Envoi incertain, Afficher les détails").performClick()
        compose.onNodeWithText("Envoi incertain").assertIsDisplayed()
        compose.onNodeWithText("Recharger").assertIsEnabled().performClick()
        assertPostBodyAndInsertionsLocked()
        compose.onNodeWithContentDescription("Options").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(List(2) { PostEditorIntent.ReloadRequested }, intents)
            assertEquals("seed", body.text.toString())
            state.value = state.value.copy(draftHydratedFromForm = true, isLoadingForm = true, submitError = null)
        }
        compose.onNode(hasSetTextAction()).performTextInput(" typed")
        assertPostInsertionsEnabled()
        compose.runOnIdle { assertEquals("seed typed", body.text.toString()) }
        // Reply is editable even with an unknown body and an initial GET still running.
        compose.runOnIdle {
            state.value = state.value.copy(mode = PostEditorMode.Reply, draftHydratedFromForm = false)
        }
        compose.onNode(hasSetTextAction()).performTextInput(" reply")
        assertPostInsertionsEnabled()
        compose.runOnIdle { assertEquals("seed typed reply", body.text.toString()) }
    }

    @Test
    fun `post ED02 ED05 ED12 ED167 field and send stay fixed after fifty nearby touch edits`() {
        val host = PostEditorChromeFixture()
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                PostEditorTestContent(host.state, host::dispatch, host.body, host.events)
            }
        }
        val field = compose.onNode(hasSetTextAction())
        val bounds = compose.onNodeWithTag("editor-scaffold-field").getUnclippedBoundsInRoot()
        val send = compose.onNodeWithTag("editor-bar-submit").getUnclippedBoundsInRoot()
        // Compact mode removes only the visual label, retaining the field's accessible name.
        compose.onNodeWithContentDescription("Message").assertExists()
        field.performTouchInput { click(Offset(width - 12f, height - 12f)) }
        repeat(50) { field.performTextInput("a") }
        assertEquals(bounds, compose.onNodeWithTag("editor-scaffold-field").getUnclippedBoundsInRoot())
        assertEquals(send, compose.onNodeWithTag("editor-bar-submit").getUnclippedBoundsInRoot())
        compose.runOnIdle { assertTrue(host.calls.isEmpty()); assertTrue(host.body.text.length >= 50) }
    }

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
    fun `post ED85 system back closes color format preview then requests saved close`() {
        val host = PostEditorChromeFixture()
        lateinit var back: androidx.activity.OnBackPressedDispatcher
        lateinit var previewLabel: String
        compose.setContent {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            previewLabel = stringResource(UiR.string.editor_preview_show)
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                PostEditorTestContent(host.state, host::dispatch, host.body, host.events)
            }
        }
        compose.onNode(hasSetTextAction()).assertHeightIsAtLeast(EDITOR_DRAFT_MIN_HEIGHT - FIELD_LABEL_HEADROOM)
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        compose.onNodeWithContentDescription("Couleur").performScrollTo().performClick()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithContentDescription("Gras").assertExists()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithContentDescription("Options").assertExists()
        compose.onNodeWithContentDescription(previewLabel).assertIsDisplayed().performClick()
        compose.onNodeWithTag("post-editor-preview").assertExists()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNode(hasSetTextAction()).assertExists()
        compose.runOnIdle { back.onBackPressed() }
        assertEquals(listOf(PostEditorIntent.TogglePreview, PostEditorIntent.TogglePreview,
            PostEditorIntent.CloseRequested), host.calls)
    }

    @Test
    @Config(sdk = [29, 34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
    fun `post ED85 back dismisses action and status tooltips without requesting close`() {
        val host = PostEditorChromeFixture()
        host.state = host.state.copy(restorableDraft = "Brouillon")
        lateinit var back: androidx.activity.OnBackPressedDispatcher
        compose.setContent {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                PostEditorTestContent(host.state, host::dispatch, host.body, host.events)
            }
        }
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().id
        for (label in listOf("Mise en forme", "Brouillon retrouvé, restaurer ou ignorer")) {
            compose.onNodeWithContentDescription(label).performTouchInput { longClick() }
            compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            compose.runOnIdle { back.onBackPressed() }
            compose.onNodeWithText(label, useUnmergedTree = true).assertDoesNotExist()
            assertEquals(field, compose.onNode(hasSetTextAction()).fetchSemanticsNode().id)
            compose.runOnIdle { assertTrue(host.calls.isEmpty()) }
        }
    }

    @Test
    fun `topic form keeps 160 dp field and both draft actions on short viewport`() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicFormContent(
                    state = topicState(restorableDraft = "Brouillon à restaurer"),
                    bodyFieldState = remember { TextFieldState() },
                    bodyInputTransformation = InputTransformation {},
                    onIntent = {},
                    onOpenSmileys = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // The subject is the first editable node; the weighted BBCode field is the second.
        compose.onAllNodes(hasSetTextAction())[1]
            .assertHeightIsAtLeast(EDITOR_DRAFT_MIN_HEIGHT - FIELD_LABEL_HEADROOM)
        assertDraftActionsFullyVisible()
    }

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
    fun topicInitialDraftReadKeepsBodyComposedAndGeometryStable() {
        val state = mutableStateOf(topicState())
        val onIntent: (TopicFormIntent) -> Unit = { intent ->
            if (intent is TopicFormIntent.SubjectChanged) {
                state.value = state.value.copy(subject = intent.value)
            }
        }
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                TopicFormContent(
                    state = state.value,
                    bodyFieldState = remember { TextFieldState() },
                    bodyInputTransformation = InputTransformation {},
                    onIntent = onIntent,
                    onOpenSmileys = {},
                )
            }
        }
        for (mode in TopicFormMode.entries) {
            compose.runOnIdle { state.value = topicState().copy(mode = mode, isInitializing = true) }
            val field = compose.onNodeWithText("Contenu BBCode").assertIsDisplayed()
            val fieldId = field.fetchSemanticsNode().id
            val bounds = field.getUnclippedBoundsInRoot()
            // Cold open (no restore): the body keeps its pre-restore lock only, so creation accepts early typing.
            val editableFields = if (mode == TopicFormMode.New) 2 else 1
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(editableFields)
            compose.onNodeWithText("Titre du sujet").assertIsEnabled().performTextInput("Titre précoce")
            compose.runOnIdle {
                assertEquals("Titre précoce", state.value.subject.text)
                state.value = state.value.copy(isInitializing = false, isLoadingForm = true)
            }
            assertEquals(fieldId, field.fetchSemanticsNode().id)
            assertEquals(bounds, field.getUnclippedBoundsInRoot())
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(editableFields)
        }
    }

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
    fun topicRecreationLocksTheTitleUntilTheDraftReadCompletes() {
        val state = mutableStateOf(topicState())
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                TopicFormContent(
                    state = state.value,
                    bodyFieldState = remember { TextFieldState() },
                    bodyInputTransformation = InputTransformation {},
                    onIntent = {},
                    onOpenSmileys = {},
                )
            }
        }
        for (mode in TopicFormMode.entries) {
            compose.runOnIdle {
                state.value = topicState().copy(mode = mode, isInitializing = true, isRestoringSession = true)
            }
            compose.onNodeWithText("Contenu BBCode").assertIsDisplayed()
            // Read-only but not greyed out: the restore wiring changes nothing visible.
            compose.onNodeWithText("Titre du sujet").assertIsEnabled().assert(isEditable(false))
            compose.runOnIdle {
                state.value = state.value.copy(
                    isInitializing = false, isRestoringSession = false, subject = TextFieldValue("Titre repris"),
                )
            }
            compose.onNodeWithText("Titre repris").assertIsEnabled().assert(isEditable(true))
        }
    }

    @Test
    fun `topic restore is disabled while the seed form loads and during an upload`() {
        val state = mutableStateOf(topicState(restorableDraft = "Brouillon à restaurer").copy(isLoadingForm = true))
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicFormContent(
                    state = state.value,
                    bodyFieldState = remember { TextFieldState() },
                    bodyInputTransformation = InputTransformation {},
                    onIntent = {},
                    onOpenSmileys = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        compose.onNodeWithText("Restaurer").assertIsNotEnabled()
        compose.onNodeWithText("Ignorer").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(isLoadingForm = false) }
        compose.onNodeWithText("Restaurer").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(isUploading = true) }
        compose.onNodeWithText("Restaurer").assertIsNotEnabled()
        compose.onNodeWithText("Ignorer").assertIsEnabled()
        compose.runOnIdle {
            state.value = state.value.copy(
                isUploading = false,
                submission = EditorSubmissionState.Uncertain(AttemptToken("tester", "key", "id", 1), accepted = true),
            )
        }
        compose.onNodeWithText("Restaurer").assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
    fun topicFormLocksBodyAndInsertionControlsWhileSeedUnknown() {
        val state = mutableStateOf(topicState().copy(mode = TopicFormMode.EditFirstPost, isLoadingForm = true))
        val body = TextFieldState("seed")
        val intents = mutableListOf<TopicFormIntent>()
        var smileyOpens = 0
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicFormContent(
                    state = state.value,
                    bodyFieldState = body,
                    bodyInputTransformation = InputTransformation {},
                    onIntent = { intents += it },
                    onOpenSmileys = { smileyOpens += 1 },
                )
            }
        }
        assertTopicBodyAndInsertionsLocked()
        compose.onNodeWithText("Options").assertIsEnabled()

        // Failure ends the spinner, but does not make the missing first post editable.
        compose.runOnIdle {
            state.value = state.value.copy(isLoadingForm = false, submitError = EditorErrors.formNetwork)
        }
        assertTopicBodyAndInsertionsLocked()
        compose.onNodeWithText("Recharger").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(TopicFormIntent.ReloadRequested), intents) }

        // Once known, the body and insertions remain available even during a later refetch.
        compose.runOnIdle {
            state.value = state.value.copy(
                draftHydratedFromServer = true, isLoadingForm = true, submitError = null,
            )
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        compose.onAllNodes(hasSetTextAction())[1].performTextInput(" typed")
        for (label in listOf("Gras", "Citation", "Image", "Uploader", "Smileys")) {
            compose.onNodeWithText(label).assertIsEnabled()
        }
        compose.onNodeWithText("Smileys").performClick()
        compose.runOnIdle {
            assertEquals("seed typed", body.text.toString())
            assertEquals(1, smileyOpens)
        }
    }

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
    fun topicFormNewKeepsBodyAndInsertionControlsWhileLoading() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicFormContent(
                    state = topicState().copy(isLoadingForm = true),
                    bodyFieldState = remember { TextFieldState() },
                    bodyInputTransformation = InputTransformation {},
                    onIntent = {},
                    onOpenSmileys = {},
                )
            }
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        for (label in listOf("Gras", "Citation", "Image", "Uploader", "Smileys")) {
            compose.onNodeWithText(label).assertIsEnabled()
        }
    }

    private fun assertPostBodyAndInsertionsLocked(toolsVisible: Boolean = true) {
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        for (label in listOf("Mise en forme", "Smileys", "Image")) {
            val control = compose.onNodeWithContentDescription(label)
            if (toolsVisible) control.assertIsNotEnabled() else control.assertDoesNotExist()
        }
        for (label in listOf("Gras", "Citation", "Insérer par URL", "Uploader une image")) {
            compose.onNodeWithContentDescription(label).assertDoesNotExist()
        }
    }

    private fun assertPostInsertionsEnabled() {
        for (label in listOf("Mise en forme", "Smileys", "Image")) {
            compose.onNodeWithContentDescription(label).assertIsEnabled()
        }
        compose.onNodeWithContentDescription("Mise en forme").performScrollTo().performClick()
        for (label in listOf("Gras", "Citation")) {
            compose.onNodeWithContentDescription(label).assertIsEnabled()
        }
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        compose.onNodeWithContentDescription("Image").performScrollTo().performClick()
        for (label in listOf("Insérer par URL", "Uploader une image")) {
            compose.onNodeWithContentDescription(label).assertIsEnabled()
        }
        // The app bar also exposes « Retour »: target the action bar's sublevel return only.
        compose.onNode(hasContentDescription("Retour") and hasAnyAncestor(hasTestTag("editor-scaffold-action-bar")))
            .performScrollTo().performClick()
    }

    private fun assertTopicBodyAndInsertionsLocked() {
        // Only the subject retains SetText; the read-only body must not expose this action.
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        for (label in listOf("Gras", "Citation", "Image", "Uploader", "Smileys")) {
            compose.onNodeWithText(label).assertDoesNotExist()
        }
    }

    private fun assertDraftActionsFullyVisible() {
        val restoreBounds = assertFullyVisibleAction("Restaurer")
        val discardBounds = assertFullyVisibleAction("Ignorer")
        assertTrue(
            "Draft actions must be separated by at least 8 dp",
            discardBounds.left - restoreBounds.right >= 8.dp,
        )
    }

    private fun assertFullyVisibleAction(label: String): DpRect {
        val action = compose.onNodeWithText(label)
            .assertHeightIsAtLeast(48.dp)
        val clippedBounds = action.getBoundsInRoot()
        val unclippedBounds = action.getUnclippedBoundsInRoot()
        assertEquals("Action $label is clipped", unclippedBounds, clippedBounds)

        val windowBounds = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue(
            "Action $label must stay entirely inside the window",
            unclippedBounds.left >= windowBounds.left &&
                unclippedBounds.top >= windowBounds.top &&
                unclippedBounds.right <= windowBounds.right &&
                unclippedBounds.bottom <= windowBounds.bottom,
        )
        return unclippedBounds
    }

    private fun isEditable(editable: Boolean) = SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, editable)

    private fun topicState(restorableDraft: String? = null) = TopicFormState(
        mode = TopicFormMode.New,
        cat = 23,
        subcat = null,
        topicId = null,
        page = null,
        numreponse = null,
        hasSubcategorySelect = false,
        hasLoadedForm = true,
        restorableDraft = restorableDraft,
    )
}

/** The editable node sits under the 8 dp floating-label headroom reserved inside the 160 dp slot. */
private val FIELD_LABEL_HEADROOM = 8.dp
