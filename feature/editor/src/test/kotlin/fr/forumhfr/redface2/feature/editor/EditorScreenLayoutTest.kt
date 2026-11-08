package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EDITOR_DRAFT_MIN_HEIGHT
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
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
    fun `post editor keeps 160 dp field and both draft actions on short viewport`() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                val scope = rememberCoroutineScope()
                val picker = remember(scope) {
                    SmileyPickerController(scope = scope, searchWiki = { _, _ -> emptyList() })
                }
                PostEditorContent(
                    state = postState(restorableDraft = "Brouillon à restaurer"),
                    onIntent = {},
                    smileyPicker = picker,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        compose.onNode(hasSetTextAction()).assertHeightIsAtLeast(EDITOR_DRAFT_MIN_HEIGHT - FIELD_LABEL_HEADROOM)
        assertDraftActionsFullyVisible()
    }

    @Test
    @Config(sdk = [29], qualifiers = "fr-rFR-w360dp-h330dp-xxhdpi")
    fun `post editor leaves 12 dp between toolbar and preview without upload or quotes`() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                val scope = rememberCoroutineScope()
                val picker = remember(scope) {
                    SmileyPickerController(scope = scope, searchWiki = { _, _ -> emptyList() })
                }
                PostEditorContent(
                    state = postState(),
                    onIntent = {},
                    smileyPicker = picker,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val toolbarBottom = compose.onNodeWithTag(POST_EDITOR_TOOLBAR_TAG)
            .getUnclippedBoundsInRoot()
            .bottom
        val previewTop = compose.onNodeWithTag(POST_EDITOR_PREVIEW_TOGGLE_TAG)
            .getUnclippedBoundsInRoot()
            .top

        assertEquals(
            "Only the arranged spacing may separate the toolbar and preview toggle",
            12f,
            (previewTop - toolbarBottom).value,
            1f,
        )
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

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h260dp-xxhdpi")
    fun `post editor keeps both draft actions fully visible below the 220 dp budget threshold`() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                val scope = rememberCoroutineScope()
                val picker = remember(scope) {
                    SmileyPickerController(scope = scope, searchWiki = { _, _ -> emptyList() })
                }
                PostEditorContent(
                    state = postState(restorableDraft = "Brouillon à restaurer"),
                    onIntent = {},
                    smileyPicker = picker,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        assertDraftActionsFullyVisible()
    }

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
    fun `post editor keeps title toolbar and 160 dp field visible on regular viewport`() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                val scope = rememberCoroutineScope()
                val picker = remember(scope) {
                    SmileyPickerController(scope = scope, searchWiki = { _, _ -> emptyList() })
                }
                PostEditorContent(
                    state = postState(),
                    onIntent = {},
                    smileyPicker = picker,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        compose.onNodeWithText("Répondre au sujet").assertIsDisplayed()
        compose.onNodeWithText("Uploader").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertHeightIsAtLeast(EDITOR_DRAFT_MIN_HEIGHT - FIELD_LABEL_HEADROOM)
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

    private fun postState(restorableDraft: String? = null) = PostEditorState(
        mode = PostEditorMode.Reply,
        cat = 23,
        topicId = 447,
        numreponse = null,
        page = 1,
        subcat = 0,
        restorableDraft = restorableDraft,
    )

    private fun topicState(restorableDraft: String? = null) = TopicFormState(
        mode = TopicFormMode.New,
        cat = 23,
        subcat = null,
        topicId = null,
        page = null,
        numreponse = null,
        hasSubcategorySelect = false,
        restorableDraft = restorableDraft,
    )
}

/** The editable node sits under the 8 dp floating-label headroom reserved inside the 160 dp slot. */
private val FIELD_LABEL_HEADROOM = 8.dp
