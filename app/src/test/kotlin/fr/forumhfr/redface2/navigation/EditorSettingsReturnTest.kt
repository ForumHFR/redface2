package fr.forumhfr.redface2.navigation

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.feature.editor.PostEditorIntent
import fr.forumhfr.redface2.feature.editor.PostEditorMode
import fr.forumhfr.redface2.feature.editor.PostEditorRequest
import fr.forumhfr.redface2.feature.editor.PostEditorScreen
import fr.forumhfr.redface2.feature.editor.PostEditorViewModel
import fr.forumhfr.redface2.feature.settings.SettingsCategoryDetailScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Nav3 decorators, editor Screen/ViewModel and settings page; no Hilt or network in this host. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi")
class EditorSettingsReturnTest {
    // Semantics and navigation only: keep the same default graphics mode as the other app tests.
    @get:Rule val compose = createComposeRule()
    private val fixture = EditorSettingsReturnFixture()
    private val route = PostEditorRoute(PostEditorMode.Reply, 23, 42, page = 1, subcat = 0)
    private val stack = mutableStateListOf<NavKey>(ForumRoute, route)
    private lateinit var editor: PostEditorViewModel
    private lateinit var back: OnBackPressedDispatcher
    private var settingsOpens = 0

    @Composable private fun Content() {
        back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
        RedfaceTheme(darkTheme = false, dynamicColor = false) {
            NavDisplay(backStack = stack, onBack = { stack.removeAt(stack.lastIndex) },
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator()), entryProvider = entryProvider {
                    entry<ForumRoute> { }
                    entry<PostEditorRoute> {
                        val factory = remember { viewModelFactory { initializer { fixture.editor() } } }
                        editor = viewModel(factory = factory)
                        PostEditorScreen(PostEditorRequest(PostEditorMode.Reply, 23, 42, null, 1, 0),
                            onSubmitSucceeded = { _, _, _, _ -> }, viewModel = editor,
                            onOpenEditorSettings = {
                                // The live edit must already have crossed the stop/save boundary.
                                assertEquals(editor.bodyFieldState.text.toString(), fixture.savedBody())
                                assertNull(editor.state.value.armToken)
                                settingsOpens++
                                openEditorSettings(stack)
                            })
                    }
                    entry<SettingsCategoryRoute> { key ->
                        SettingsCategoryDetailScreen(key.categoryId, { stack.removeAt(stack.lastIndex) },
                            {}, {}, {}, {}, {}, {}, viewModel = fixture.settings,
                            startScreenViewModel = fixture.startScreen)
                    }
                })
        }
    }

    @Test fun settingsArrowReturnsToRetainedTextSelectionAndNewShortcut() {
        compose.setContent { Content() }
        compose.onNode(hasSetTextAction()).performTextInput("Bonjour")
        compose.onNode(hasSetTextAction()).performTextInputSelection(TextRange(6, 1))
        // SetSelection normalizes the bounds; restore the reversed range as in the editor restoration tests.
        compose.runOnIdle { editor.bodyFieldState.edit { selection = TextRange(6, 1) } }
        val original = editor
        compose.onNodeWithContentDescription("Options").performClick()
        compose.onNodeWithText("Réglages de l’éditeur").performScrollTo().performClick()
        compose.onNodeWithText("Bleu").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertSame(original, editor)
            assertEquals("Bonjour", editor.bodyFieldState.text.toString())
            assertEquals(TextRange(6, 1), editor.bodyFieldState.selection)
            assertEquals(EditorShortcut.BLUE, editor.state.value.editorShortcut)
            assertFalse(editor.state.value.isPreviewVisible)
            assertEquals(1, settingsOpens)
        }
        compose.onNodeWithTag("editor-options-sheet").assertDoesNotExist()
        compose.onNodeWithContentDescription("Mise en forme").assertIsNotSelected()
        compose.onNodeWithContentDescription("Bleu").assertExists()
        fixture.noPost()
    }

    @Test fun systemBackKeepsPreviewAndOptionsRequireDisarmedSubmission() {
        compose.setContent { Content() }
        compose.onNode(hasSetTextAction()).performTextInput("Bonjour")
        compose.runOnIdle { editor.submit(PostEditorIntent.TogglePreview) }
        compose.runOnIdle {
            editor.submit(PostEditorIntent.SubmitClicked(timeoutMillis = 60_000L))
            assertNotNull(editor.state.value.armToken)
        }
        // ED-34 masks secondary tools while armed; ED-85 Back disarms before Options becomes reachable.
        compose.onNodeWithContentDescription("Options").assertDoesNotExist()
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle {
            assertNull(editor.state.value.armToken)
            assertTrue(editor.state.value.isPreviewVisible)
        }
        compose.onNodeWithContentDescription("Options").performClick()
        // Check before settings navigation: stopping the editor must not be what disarms it.
        compose.runOnIdle { assertNull(editor.state.value.armToken) }
        compose.onNodeWithText("Réglages de l’éditeur").performScrollTo().performClick()
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle {
            assertEquals("Bonjour", editor.bodyFieldState.text.toString())
            assertTrue(editor.state.value.isPreviewVisible)
            assertNull(editor.state.value.armToken)
            assertEquals(1, settingsOpens)
        }
        compose.onNodeWithTag("editor-options-sheet").assertDoesNotExist()
        compose.onNodeWithTag("post-editor-preview").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mise en forme").assertIsNotSelected()
        fixture.noPost()
    }
}
