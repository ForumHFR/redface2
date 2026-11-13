package fr.forumhfr.redface2.feature.messages

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
import fr.forumhfr.redface2.core.ui.editor.EditorRecipientState
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

/** Real Screens, not Content-only hosts; navigation leaves the composition alive to exclude incidental ON_STOP. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivateMessageEditorSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()

    @Test fun replySettingsTapStopsBeforeNavigatingExactlyOnce() {
        val fixture = PrivateMessageReplyChromeFixture()
        val model = mockk<PrivateMessageReplyViewModel>(relaxUnitFun = true)
        every { model.state } returns MutableStateFlow(fixture.state)
        every { model.effects } returns emptyFlow()
        every { model.quotesDelivered } returns emptyFlow()
        every { model.bodyFieldState } returns fixture.body
        every { model.bodyScrollState } returns ScrollState(0)
        every { model.bodyInputTransformation } returns InputTransformation {}
        every { model.bodyCaretRevealResetKey } returns 0
        every { model.previewLayout } returns EditorPreviewState()
        every { model.editorEvents } returns fixture.events
        every { model.chromeMemory } returns fixture.memory
        every { model.onStopped() } answers { calls += "stopped" }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val picker = remember { SmileyPickerController(scope, searchWiki = { _, _ -> emptyList() }) }
            every { model.smileyPicker } returns picker
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                PrivateMessageReplyScreen(PrivateMessageReplyRequest(threadId = 42),
                    onSubmitSucceeded = { _, _ -> }, onBack = {}, viewModel = model,
                    onOpenEditorSettings = ::navigate)
            }
        }
        tapSettingsAndCheckOrder()
    }

    @Test fun composeSettingsTapStopsBeforeNavigatingExactlyOnce() {
        val fixture = PrivateMessageComposeChromeFixture()
        fixture.state = fixture.state.copy(
            recipientState = EditorRecipientState(listOf("alice")), subject = "Sujet privé")
        val model = mockk<PrivateMessageComposeViewModel>(relaxUnitFun = true)
        every { model.state } returns MutableStateFlow(fixture.state)
        every { model.effects } returns emptyFlow()
        every { model.quotesDelivered } returns emptyFlow()
        every { model.bodyFieldState } returns fixture.body
        every { model.bodyScrollState } returns ScrollState(0)
        every { model.bodyInputTransformation } returns InputTransformation {}
        every { model.bodyCaretRevealResetKey } returns 0
        every { model.previewLayout } returns EditorPreviewState()
        every { model.editorEvents } returns fixture.events
        every { model.chromeMemory } returns fixture.memory
        every { model.recipientInput } returns fixture.recipients
        every { model.subjectInput } returns fixture.subject
        every { model.onStopped() } answers { calls += "stopped" }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val picker = remember { SmileyPickerController(scope, searchWiki = { _, _ -> emptyList() }) }
            every { model.smileyPicker } returns picker
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                PrivateMessageComposeScreen(onSubmitSucceeded = {}, onBack = {}, viewModel = model,
                    onOpenEditorSettings = ::navigate)
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
