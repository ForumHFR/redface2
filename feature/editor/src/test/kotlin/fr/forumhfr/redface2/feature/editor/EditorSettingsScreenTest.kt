package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EditorPreviewState
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Screens and option rows. Keeping them mounted rules out a lifecycle stop masking a missing explicit stop. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()

    @Test fun postSettingsTapStopsBeforeNavigatingExactlyOnce() {
        val fixture = PostEditorChromeFixture()
        val model = mockk<PostEditorViewModel>(relaxUnitFun = true)
        every { model.state } returns MutableStateFlow(fixture.state)
        every { model.effects } returns emptyFlow()
        every { model.bodyFieldState } returns fixture.body
        every { model.bodyScrollState } returns ScrollState(0)
        every { model.bodyInputTransformation } returns InputTransformation {}
        every { model.bodyCaretRevealResetKey } returns 0
        every { model.previewLayout } returns EditorPreviewState()
        every { model.chromeEvents } returns fixture.events
        every { model.chromeMemory } returns fixture.memory
        every { model.submit(PostEditorIntent.Stopped) } answers { calls += "stopped" }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val picker = remember { SmileyPickerController(scope, searchWiki = { _, _ -> emptyList() }) }
            every { model.smileyPicker } returns picker
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                PostEditorScreen(PostEditorRequest(PostEditorMode.Reply, 23, 42, null, 1, 0),
                    onSubmitSucceeded = { _, _, _, _ -> }, viewModel = model,
                    onOpenEditorSettings = ::navigate)
            }
        }
        tapSettingsAndCheckOrder()
    }

    @Test fun topicSettingsTapStopsBeforeNavigatingExactlyOnce() {
        val fixture = TopicFormChromeFixture()
        val model = mockk<TopicFormViewModel>(relaxUnitFun = true)
        every { model.state } returns MutableStateFlow(fixture.state)
        every { model.effects } returns emptyFlow()
        every { model.bodyFieldState } returns fixture.body
        every { model.bodyScrollState } returns ScrollState(0)
        every { model.bodyInputTransformation } returns InputTransformation {}
        every { model.bodyCaretRevealResetKey } returns 0
        every { model.previewLayout } returns EditorPreviewState()
        every { model.editorEvents } returns fixture.events
        every { model.chromeMemory } returns fixture.memory
        every { model.submit(TopicFormIntent.Stopped) } answers { calls += "stopped" }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val picker = remember { SmileyPickerController(scope, searchWiki = { _, _ -> emptyList() }) }
            every { model.smileyPicker } returns picker
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                TopicFormScreen(TopicFormRequest(TopicFormMode.New, 23, 1, 42, 1, 101),
                    onSubmitSucceeded = { _, _, _ -> }, onNewTopicCreated = { _, _, _, _, _ -> },
                    viewModel = model, onOpenEditorSettings = ::navigate)
            }
        }
        tapSettingsAndCheckOrder()
    }

    private fun navigate() {
        assertEquals(listOf("stopped"), calls)
        calls += "navigate"
    }

    private fun tapSettingsAndCheckOrder() {
        compose.runOnIdle { assertEquals(emptyList<String>(), calls) }
        compose.onNodeWithContentDescription("Options").performClick()
        compose.onNodeWithText("Réglages de l’éditeur").performScrollTo().performClick()
        compose.onNodeWithTag("editor-options-sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("stopped", "navigate"), calls) }
    }
}
