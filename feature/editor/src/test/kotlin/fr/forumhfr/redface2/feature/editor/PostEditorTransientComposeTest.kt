package fr.forumhfr.redface2.feature.editor

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.R as UiR
import fr.forumhfr.redface2.core.ui.editor.BbcodeTextField
import fr.forumhfr.redface2.core.ui.editor.EditorChromeMetrics
import fr.forumhfr.redface2.core.ui.editor.EditorScaffold
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

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h760dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PostEditorTransientComposeTest {
    @get:Rule val compose = createComposeRule()
    private val host = transientHost()
    private val focus = FocusRequester()

    @Test fun `ED50 ED51 ED53 panel reorders removes announces and returns the same source selection`() {
        lateinit var inputMode: InputModeManager
        compose.setContent {
            RedfaceTheme {
                inputMode = LocalInputModeManager.current
                TransientPanelHost(host, focus)
            }
        }
        compose.runOnIdle { focus.requestFocus() }
        val source = compose.onNode(hasSetTextAction()).fetchSemanticsNode().id
        val selection = host.body.selection
        compose.onNodeWithContentDescription("3 citations, ouvrir le panneau citations").performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Descendre la citation de Alice").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Citation de Alice déplacée en position 2").assertExists()
        compose.runOnIdle { assertEquals(listOf("Bob", "Alice", "Chloé"), host.state.quotes.map { it.author }) }
        // Compose clickables only take focus outside touch mode (Focusability.SystemDefined), as in the
        // legacy QuoteCardsColumn: exercise ED-50 removal focus through keyboard mode and a semantic click.
        compose.onNodeWithContentDescription("Retirer la citation de Alice").performScrollTo()
        // The return value reflects the view's focus request, not the mode switch: assert the mode itself.
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.runOnIdle { assertEquals(InputMode.Keyboard, inputMode.inputMode) }
        compose.onNodeWithContentDescription("Retirer la citation de Alice")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithContentDescription("Retirer la citation de Chloé").assertIsFocused()
        compose.onNodeWithContentDescription("Citation de Alice retirée. 2 citations restantes").assertExists()
        compose.onNodeWithText("Tout vider").performClick()
        compose.onNodeWithText("Aucune citation").assertIsFocused()
        compose.onNodeWithContentDescription("Toutes les citations ont été retirées").assertExists()
        compose.onNodeWithText("Tout vider").assertDoesNotExist()
        compose.onNodeWithText("Retour au message").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals(source, compose.onNode(hasSetTextAction()).fetchSemanticsNode().id)
        compose.runOnIdle {
            assertEquals(selection, host.body.selection)
            assertEquals("Bonjour", host.body.text.toString())
            assertFalse(host.calls.any { it is PostEditorIntent.SubmitClicked })
        }
    }

    @Test fun `ED50 removing the final card retains its announcement and gives focus to the empty panel`() {
        host.state = host.state.copy(quotes = host.state.quotes.take(1))
        compose.setContent { RedfaceTheme { TransientPanelHost(host, focus) } }
        compose.onNodeWithContentDescription("1 citation, ouvrir le panneau citations").performClick()
        compose.onNodeWithText("Tout vider").assertDoesNotExist()
        compose.onNodeWithContentDescription("Retirer la citation de Alice").performScrollTo().performClick()
        compose.onNodeWithText("Aucune citation").assertIsFocused()
        compose.onNodeWithContentDescription("Citation de Alice retirée. 0 citation restante").assertExists()
    }

    @Test fun `ED140 actual card is measured before the field and clearing keeps opening geometry`() {
        var allocation: PostEditorQuoteAllocation? = null
        var cards by mutableStateOf(host.state.quotes)
        compose.setContent {
            RedfaceTheme {
                Box(Modifier.size(360.dp, 300.dp)) {
                    PostEditorQuoteLayout(cards, EditorChromeMetrics(false),
                        PostEditorFieldMetrics(24.dp, 32.dp, 3), true, {}, {}) {
                        allocation = it
                        Box(Modifier.fillMaxSize().testTag("field-slot"))
                    }
                }
            }
        }
        val field = compose.onNodeWithTag("field-slot").getUnclippedBoundsInRoot()
        compose.runOnIdle {
            assertEquals(112.dp, allocation?.zoneHeight)
            assertFalse(requireNotNull(allocation).panelOnly)
            assertTrue(requireNotNull(allocation).fieldHeight >= 160.dp)
            cards = emptyList()
        }
        assertEquals(field, compose.onNodeWithTag("field-slot").getUnclippedBoundsInRoot())
    }

    @Test fun `P22 a compact field area with less than a complete card exposes only the panel route`() {
        var allocation: PostEditorQuoteAllocation? = null
        var hasStatus = false
        compose.setContent {
            RedfaceTheme {
                Box(Modifier.size(360.dp, 100.dp)) {
                    PostEditorQuoteLayout(host.state.quotes, EditorChromeMetrics(true),
                        PostEditorFieldMetrics(24.dp, 16.dp, 3), true, {}, {}) {
                        allocation = it
                        hasStatus = host.adapter.chrome(quotePanelOnly = it.panelOnly).firstStatus != null
                        Box(Modifier.fillMaxSize().testTag("field-slot"))
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(PostEditorQuoteAllocation(0.dp, 100.dp, true), allocation)
            assertTrue(hasStatus)
        }
        val field = compose.onNodeWithTag("field-slot").getUnclippedBoundsInRoot()
        assertEquals(100.dp, field.bottom - field.top)
    }

    @Test fun `ED53 panel returns exactly once to an initially open keyboard`() = panelKeyboard(true)
    @Test fun `ED53 panel returns exactly once to an initially closed keyboard`() = panelKeyboard(false)

    private fun panelKeyboard(initiallyOpen: Boolean) {
        val input = TransientInput()
        compose.setContent {
            input.Content { RedfaceTheme { TransientPanelHost(host, focus) } }
        }
        val field = compose.onNode(hasSetTextAction())
        val node = field.fetchSemanticsNode().id
        val selection = TextRange(3, 1)
        listOf(initiallyOpen, !initiallyOpen).forEachIndexed { index, open ->
            if (open) field.performClick()
            field.performTextInputSelection(selection)
            compose.runOnIdle { input.insets(open) }
            compose.onNodeWithContentDescription("3 citations, ouvrir le panneau citations").performClick()
            field.assertDoesNotExist()
            val sessions = compose.runOnIdle {
                assertNull(input.connection)
                input.insets(!open) // The return must use the opening snapshot, not these new insets.
                input.sessions
            }
            if (index == 0) compose.onNodeWithText("Retour au message").performClick()
            else compose.runOnIdle { host.adapter.back() }
            field.assertIsFocused()
            assertEquals(node, field.fetchSemanticsNode().id)
            compose.runOnIdle {
                assertEquals(selection.min, host.body.selection.min)
                assertEquals(selection.max, host.body.selection.max)
                assertEquals(open, input.connection != null)
                assertEquals(sessions + if (open) 1 else 0, input.sessions)
                if (open) {
                    val connection = requireNotNull(input.connection)
                    val before = host.body.text.toString()
                    assertEquals(before.substring(selection.min, selection.max),
                        connection.getSelectedText(0)?.toString())
                    assertTrue(connection.commitText("IME", 1))
                    assertEquals(before.replaceRange(selection.min, selection.max, "IME"), host.body.text.toString())
                }
            }
        }
    }

    @Test fun `ED140 preview keeps the focused source instance selection and IME commands`() = preview(false)
    @Test fun `ED140 preview returns from a focused keyboard control without IME commands`() = preview(true)

    @Test fun `ED140 Tab reaches and toggles a real preview spoiler while the body stays unfocused`() {
        host.state = host.state.copy(quotes = emptyList(), preview = PostContent(listOf(
            PostBlock.Spoiler("Secret", PostContent(listOf(
                PostBlock.Paragraph(listOf(PostInline.Text("Contenu révélé"))),
            ))),
        )))
        lateinit var inputMode: InputModeManager
        lateinit var previewLabel: String
        compose.setContent {
            RedfaceTheme {
                inputMode = LocalInputModeManager.current
                // feature/editor overrides core/ui's typographic apostrophe in the merged resources.
                previewLabel = stringResource(UiR.string.editor_preview_show)
                PostEditorTestContent(host.state, host::dispatch, host.body, host.events)
            }
        }
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.runOnIdle { assertEquals(InputMode.Keyboard, inputMode.inputMode) }
        val toggle = compose.onNode(hasContentDescription(previewLabel) and
            hasAnyAncestor(hasTestTag("editor-scaffold-app-bar")))
        toggle.assertIsDisplayed()
        toggle.requestFocus().assertIsFocused().performKeyInput { pressKey(Key.Spacebar) }
        compose.runOnIdle { assertTrue(host.state.isPreviewVisible) }
        compose.onNodeWithTag("post-editor-preview").assertIsDisplayed()
        compose.onNodeWithText("Contenu révélé").assertDoesNotExist()
        // With no quote/restore statuses, this is the last app-bar focus target. Tab enters fieldArea
        // to reach the preview's only clickable: guarding the whole slot would cancel that entry.
        toggle.assertIsFocused().performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithText("Secret").assertIsFocused().performKeyInput { pressKey(Key.Spacebar) }
        compose.onNodeWithText("Contenu révélé").assertExists()
        compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
    }

    private fun preview(fromControl: Boolean) {
        var mounts = 0
        var disposals = 0
        val keyboard = TransientKeyboard()
        val input = TransientInput()
        var navigate: () -> Unit = {}
        host.body.edit { selection = TextRange(6, 1) }
        compose.setContent {
            input.Content { CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                RedfaceTheme {
                    val inputMode = LocalInputModeManager.current
                    val focusManager = LocalFocusManager.current
                    navigate = {
                        inputMode.requestInputMode(InputMode.Keyboard)
                        focusManager.moveFocus(FocusDirection.Previous)
                    }
                    Column {
                        TextButton(onClick = { host.adapter.barActions.onPreview() }) { Text("Aperçu") }
                        PostEditorPreview(host.state.isPreviewVisible, PostContent(emptyList()), host.body, focus) {
                            DisposableEffect(Unit) { mounts++; onDispose { disposals++ } }
                            BbcodeTextField(host.body, "Message", Modifier.fillMaxSize().focusRequester(focus),
                                showKeyboardOnFocus = false)
                        }
                    }
                }
            } }
        }
        compose.runOnIdle { focus.requestFocus() }
        if (!fromControl) compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextInputSelection(TextRange(6, 1))
        compose.runOnIdle { input.insets(!fromControl) }
        val keyboardBefore = keyboard.calls.toList()
        val sessions = input.sessions
        if (fromControl) {
            compose.runOnIdle { navigate() }
            compose.onNodeWithText("Aperçu").assertIsFocused().performKeyInput { pressKey(Key.Spacebar) }
        } else compose.runOnIdle { host.adapter.barActions.onPreview() }
        val selection = host.body.selection
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("La prévisualisation s'affiche ici dès que vous saisissez du texte.").assertExists()
        compose.onNodeWithTag("post-editor-preview").performTouchInput { click(center) }
        if (fromControl) compose.onNodeWithText("Aperçu").assertIsFocused()
        compose.runOnIdle { host.adapter.back() }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(selection, host.body.selection)
            assertEquals(1, mounts)
            assertEquals(0, disposals)
            assertEquals(keyboardBefore, keyboard.calls)
            assertEquals(!fromControl, input.connection != null)
            assertEquals(sessions, input.sessions)
        }
    }
}

private class TransientKeyboard : SoftwareKeyboardController {
    val calls = mutableListOf<String>()
    override fun show() { calls += "show" }
    override fun hide() { calls += "hide" }
}

/** Observe BTF2's real input session; the fixture never supplies return or keyboard callbacks. */
private class TransientInput {
    lateinit var view: View
    lateinit var density: Density
    var connection: InputConnection? = null
        private set
    var sessions = 0
        private set
    private val interceptor = PlatformTextInputInterceptor { request, next ->
        val current = request.createInputConnection(EditorInfo())
        connection = current
        sessions++
        try {
            next.startInputMethod(request)
        } finally {
            connection = null
            current.closeConnection()
        }
    }

    fun insets(visible: Boolean) {
        val bottom = with(density) { if (visible) 100.dp.roundToPx() else 0 }
        ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
            .setVisible(WindowInsetsCompat.Type.ime(), visible).build())
    }

    @Composable
    fun Content(content: @Composable () -> Unit) {
        view = LocalView.current
        density = LocalDensity.current
        InterceptPlatformTextInput(interceptor) { content() }
    }
}

internal fun transientHost() = PostEditorChromeFixture().apply {
    state = state.copy(quotes = listOf("Alice", "Bob", "Chloé").mapIndexed { index, author ->
        QuoteSelection(QuoteLocator(1, 101 + index, null), author, "Extrait du message de $author")
    })
}

@Composable
internal fun TransientPanelHost(host: PostEditorChromeFixture, focus: FocusRequester, compact: Boolean = false) {
    val scope = rememberCoroutineScope()
    val smileys = remember { SmileyPickerController(scope, searchWiki = { _, _ -> emptyList() }) }
    EditorScaffold(host.body, EditorWindowMetrics(if (compact) 360.dp else 760.dp, 24.dp, 24.dp),
        "Répondre", "Sujet de démonstration", host.adapter.chrome(), host.adapter.barActions,
        host.adapter.submitState, host.adapter.submitActions, host.adapter.imageActions,
        host.adapter::closeRequested, bodyFocusRequester = focus,
        bodyFocusRestoreAllowed = host.adapter.surface == null && host.adapter.detail == null &&
            !host.state.isPreviewVisible,
        overlay = { restore, body ->
            PostEditorChromeSurfaces(host.adapter, host.body, smileys, restore, compact, body)
        })
}
