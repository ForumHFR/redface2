package fr.forumhfr.redface2.feature.messages

import android.app.Activity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
import fr.forumhfr.redface2.core.ui.editor.EditorRecipientState
import fr.forumhfr.redface2.core.ui.editor.EditorWindowMetrics
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerOpenIntent
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import fr.forumhfr.redface2.core.ui.editor.applyBbcodeAction
import fr.forumhfr.redface2.core.ui.editor.editBbcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Both real MP Content assemblies, including their retained header, modal sheet and picker launcher. */
@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivateMessageShortcutHostTest {
    @get:Rule val compose = createComposeRule()
    private val reply = PrivateMessageReplyChromeFixture()
    private val message = PrivateMessageComposeChromeFixture()
    private var composing = false
    private val body get() = if (composing) message.body else reply.body
    private val formats = mutableListOf<BbcodeAction>()
    private lateinit var smileys: SmileyPickerController
    private lateinit var inputMode: InputModeManager
    private var searches = 0
    private var launches = 0
    private var pickerRequest = 0
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) { pickerRequest = requestCode; launches++ }
    }
    private val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
    private fun format(action: BbcodeAction) {
        formats += action
        body.editBbcode { text, start, end -> applyBbcodeAction(action, text, start, end) }
    }
    private fun pick(choice: EditorShortcut) {
        if (composing) message.state = message.state.copy(editorShortcut = choice)
        else reply.state = reply.state.copy(editorShortcut = choice)
    }
    private fun field() = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("editor-scaffold-field")))
    private fun mount(composeMessage: Boolean) {
        composing = composeMessage
        message.state = message.state.copy(recipientState = EditorRecipientState(listOf("alice")), subject = "Sujet")
        val replyActions = reply.actions.copy(host = reply.actions.host.copy(onFormat = ::format))
        val composeActions = message.actions.copy(host = message.actions.host.copy(onFormat = ::format))
        compose.setContent {
            inputMode = LocalInputModeManager.current
            val scope = rememberCoroutineScope()
            smileys = remember { SmileyPickerController(scope, searchWiki = { _, _ -> searches++; emptyList() }) }
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                    if (composing) PrivateMessageComposeContent(message.state, message.body, InputTransformation {},
                        message.recipients, message.subject, composeActions, smileys, chromeEvents = message.events,
                        chromeMemory = message.memory, windowMetrics = EditorWindowMetrics(800.dp, 0.dp, 0.dp))
                    else PrivateMessageReplyContent(reply.state, false, reply.body, InputTransformation {},
                        replyActions, smileys, chromeEvents = reply.events, chromeMemory = reply.memory,
                        windowMetrics = EditorWindowMetrics(800.dp, 0.dp, 0.dp))
                }
            }
        }
        // IconButtons do not take keyboard focus while the host is in touch mode.
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
    }
    @Test fun `reply inserts formats colors personal smileys and image selection atomically`() = insertions(false)
    @Test fun `new MP inserts formats colors personal smileys and image selection atomically`() = insertions(true)
    private fun insertions(composeMessage: Boolean) {
        mount(composeMessage)
        listOf(Triple(EditorShortcut.BOLD, "Gras", "[b]nom[/b]"),
            Triple(EditorShortcut.RED, "Rouge", "[#FF0000]nom[/#FF0000]"),
            Triple(EditorShortcut.PERSONAL_SMILEY, "Smiley perso", "[:nom]"),
            Triple(EditorShortcut.IMAGE_SELECTION, "Baliser la sélection en [img]", "[img]nom[/img]")
        ).forEach { (choice, label, expected) ->
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
                assertEquals(when (choice) {
                    EditorShortcut.BOLD -> BbcodeAction.Bold
                    EditorShortcut.RED -> BbcodeAction.Color("#FF0000")
                    EditorShortcut.IMAGE_SELECTION -> BbcodeAction.Image
                    else -> BbcodeAction.PersonalSmiley
                }, formats.last())
                body.undoState.undo()
                assertEquals("nom", body.text.toString())
                assertEquals(TextRange(3, 0), body.selection)
                assertEquals(false, body.undoState.canUndo)
            }
        }
    }
    @Test fun `reply opens search and consultation and cancels picker through the real host`() = surfaces(false)
    @Test fun `new MP opens search and consultation and cancels picker through the real host`() = surfaces(true)
    private fun surfaces(composeMessage: Boolean) {
        mount(composeMessage)
        field().requestFocus()
        compose.runOnIdle { body.edit { selection = TextRange(6, 1) }; pick(EditorShortcut.SEARCH_SMILEYS) }
        compose.onNodeWithContentDescription("Smileys Wiki").requestFocus().assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        compose.onNode(hasSetTextAction() and !hasAnyAncestor(hasTestTag("editor-scaffold-field")))
            .assertIsFocused().performTextInput("ja")
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
            assertEquals("ja", (smileys.state.value as SmileyPickerState.Open).query)
            val state = smileys.state.value as SmileyPickerState.Open
            assertEquals(SmileyPickerOpenIntent.CONSULTATION, state.openingIntent)
            assertEquals(0, searches)
        }
        compose.onNodeWithTag("smiley_picker_close").performClick()
        field().assertIsFocused()
        compose.runOnIdle { pick(EditorShortcut.UPLOAD_IMAGE) }
        compose.onNodeWithContentDescription("Uploader une image").performClick()
        compose.runOnIdle {
            assertEquals(1, launches)
            registry.dispatchResult(pickerRequest, Activity.RESULT_CANCELED, null)
        }
        field().assertIsFocused()
        compose.runOnIdle {
            assertEquals(TextRange(6, 1), body.selection)
            if (composing) message.state = message.state.copy(isUploading = true)
            else reply.state = reply.state.copy(isUploading = true)
        }
        compose.onNodeWithContentDescription("Uploader une image").assertIsNotEnabled()
    }
}
