package fr.forumhfr.redface2.feature.editor

import android.app.Activity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.EditorWindowMetrics
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerOpenIntent
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Uses both production Content assemblies, not a detached action bar. Native IME remains a device check. */
@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorShortcutHostTest {
    @get:Rule val compose = createComposeRule()
    private val post = PostEditorChromeFixture()
    private val topic = TopicFormChromeFixture()
    private var isTopic = false
    private lateinit var smileys: SmileyPickerController
    private lateinit var inputMode: InputModeManager
    private var searches = 0
    private var pickerRequest = 0
    private var launches = 0
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) { pickerRequest = requestCode; launches++ }
    }
    private val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
    private val body get() = if (isTopic) topic.body else post.body
    private fun field() = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("editor-scaffold-field")))
    private fun pick(choice: EditorShortcut) {
        if (isTopic) topic.state = topic.state.copy(editorShortcut = choice)
        else post.state = post.state.copy(editorShortcut = choice)
    }
    private fun mount(topicSurface: Boolean) {
        isTopic = topicSurface
        compose.setContent {
            inputMode = LocalInputModeManager.current
            val scope = rememberCoroutineScope()
            smileys = remember { SmileyPickerController(scope, searchWiki = { _, _ -> searches++; emptyList() }) }
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                    // Both surfaces receive explicit event deliveries, without the post helper's SideEffect.
                    if (isTopic) TopicFormContent(topic.state, topic.body, null, topic::dispatch, smileys,
                        chromeEvents = topic.events, chromeMemory = topic.memory,
                        windowMetrics = EditorWindowMetrics(800.dp, 0.dp, 0.dp))
                    else PostEditorContent(post.state, post.body, null, post::dispatch, smileys,
                        chromeEvents = post.events, chromeMemory = post.memory,
                        windowMetrics = EditorWindowMetrics(800.dp, 0.dp, 0.dp))
                }
            }
        }
        // Match the real keyboard path; in touch mode requestFocus on an IconButton is refused.
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
    }
    @Test fun `post dispatches shortcut insertions atomically without entering Format`() = insertions(false)
    @Test fun `topic dispatches shortcut insertions atomically without entering Format`() = insertions(true)
    private fun insertions(topicSurface: Boolean) {
        mount(topicSurface)
        listOf(Triple(EditorShortcut.BOLD, "Gras", "[b]nom[/b]"),
            Triple(EditorShortcut.RED, "Rouge", "[#FF0000]nom[/#FF0000]"),
            Triple(EditorShortcut.PERSONAL_SMILEY, "Smiley perso", "[:nom]")).forEach { (choice, label, expected) ->
            field().requestFocus()
            compose.runOnIdle {
                body.edit { replace(0, length, "nom"); selection = TextRange(3, 0) }
                body.undoState.clearHistory()
                pick(choice)
            }
            compose.onNodeWithContentDescription(label).requestFocus().assertIsFocused()
                .performKeyInput { pressKey(Key.Enter) }
            field().assertIsFocused()
            compose.onNodeWithContentDescription("Mise en forme").assertIsDisplayed()
            compose.onNodeWithContentDescription("Italique").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(expected, body.text.toString())
                val action = if (isTopic) (topic.calls.last() as TopicFormIntent.ToolbarActionClicked).action
                    else (post.calls.last() as PostEditorIntent.ToolbarActionClicked).action
                assertEquals(when (choice) {
                    EditorShortcut.BOLD -> BbcodeAction.Bold
                    EditorShortcut.RED -> BbcodeAction.Color("#FF0000")
                    else -> BbcodeAction.PersonalSmiley
                }, action)
                body.undoState.undo()
                assertEquals("nom", body.text.toString())
                assertEquals(TextRange(3, 0), body.selection)
                assertEquals(false, body.undoState.canUndo)
            }
        }
    }
    @Test fun `post opens search consultation URL and picker through host and restores selection`() = surfaces(false)
    @Test fun `topic opens search consultation URL and picker through host and restores selection`() = surfaces(true)
    private fun surfaces(topicSurface: Boolean) {
        mount(topicSurface)
        field().requestFocus()
        compose.runOnIdle { body.edit { selection = TextRange(6, 1) }; pick(EditorShortcut.SEARCH_SMILEYS) }
        compose.onNodeWithContentDescription("Smileys Wiki").requestFocus().assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        val search = compose.onNode(hasSetTextAction() and !hasAnyAncestor(hasTestTag("editor-scaffold-field")))
        search.assertIsFocused().performTextInput("ja")
        compose.runOnIdle {
            assertEquals(SmileyPickerOpenIntent.SEARCH, (smileys.state.value as SmileyPickerState.Open).openingIntent)
            assertEquals(0, searches)
        }
        compose.onNodeWithTag("smiley_picker_close").performClick()
        field().assertIsFocused()
        compose.runOnIdle { assertEquals(TextRange(6, 1), body.selection) }
        compose.onNodeWithContentDescription("Smileys").performClick()
        compose.onNodeWithTag("smiley_selected_tab").assertIsFocused()
        compose.runOnIdle {
            val state = smileys.state.value as SmileyPickerState.Open
            assertEquals(SmileyPickerOpenIntent.CONSULTATION, state.openingIntent)
            assertEquals("ja", state.query)
            assertEquals(0, searches)
        }
        compose.onNodeWithTag("smiley_picker_close").performClick()
        field().assertIsFocused()
        compose.runOnIdle { pick(EditorShortcut.IMAGE_URL) }
        compose.onNodeWithContentDescription("Insérer par URL").performClick()
        compose.onNodeWithText("Annuler").performClick()
        field().assertIsFocused()
        compose.runOnIdle { assertEquals(TextRange(6, 1), body.selection); pick(EditorShortcut.UPLOAD_IMAGE) }
        compose.onNodeWithContentDescription("Uploader une image").performClick()
        compose.runOnIdle {
            assertEquals(1, launches)
            registry.dispatchResult(pickerRequest, Activity.RESULT_CANCELED, null)
        }
        field().assertIsFocused()
        val postBeforeUpload = post.state
        val topicBeforeUpload = topic.state
        compose.runOnIdle {
            assertEquals(TextRange(6, 1), body.selection)
            if (isTopic) topic.state = topic.state.copy(isUploading = true)
            else post.state = post.state.copy(isUploading = true)
        }
        // ED-35: even without an event delivery, the busy projection must disable the shortcut.
        compose.onNodeWithTag("editor-bar-progress").assertExists()
        compose.onNodeWithContentDescription("Uploader une image").assertIsNotEnabled()
        compose.runOnIdle {
            if (isTopic) topic.events.observe(topicBeforeUpload, topic.state)
            else post.events.observe(postBeforeUpload, post.state)
        }
        // ED-40: the real upload delivery replaces tools with non-actionable progress.
        compose.onNodeWithContentDescription("Image 1/1…").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithContentDescription("Uploader une image").assertDoesNotExist()
    }
}
