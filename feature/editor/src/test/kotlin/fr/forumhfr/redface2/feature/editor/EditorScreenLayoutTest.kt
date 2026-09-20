package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithText
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EDITOR_DRAFT_MIN_HEIGHT
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
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

        compose.onNode(hasSetTextAction()).assertHeightIsAtLeast(EDITOR_DRAFT_MIN_HEIGHT)
        assertDraftActionsDisplayed()
    }

    @Test
    fun `topic form keeps 160 dp field and both draft actions on short viewport`() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicFormContent(
                    state = topicState(restorableDraft = "Brouillon à restaurer"),
                    onIntent = {},
                    onOpenSmileys = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // The subject is the first editable node; the weighted BBCode field is the second.
        compose.onAllNodes(hasSetTextAction())[1]
            .assertHeightIsAtLeast(EDITOR_DRAFT_MIN_HEIGHT)
        assertDraftActionsDisplayed()
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
        compose.onNode(hasSetTextAction()).assertHeightIsAtLeast(EDITOR_DRAFT_MIN_HEIGHT)
    }

    private fun assertDraftActionsDisplayed() {
        compose.onNodeWithText("Restaurer").assertIsDisplayed()
        compose.onNodeWithText("Ignorer").assertIsDisplayed()
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
