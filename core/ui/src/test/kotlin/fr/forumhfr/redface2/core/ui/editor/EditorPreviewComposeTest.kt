package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.editor.EditorPreviewDirection.Vertical
import fr.forumhfr.redface2.core.model.editor.EditorPreviewKey
import fr.forumhfr.redface2.core.model.editor.EditorPreviewWidthClass.Compact
import fr.forumhfr.redface2.core.ui.post.PostMediaDiskCachePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class, ExperimentalLayoutApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorPreviewComposeTest {
    @get:Rule val compose = createComposeRule()
    private val host = EditorScaffoldFixture()
    private val state = EditorPreviewState()
    private val keyboard = PreviewKeyboard()
    private var imeVisible = false
    private var mounts = 0
    private var disposals = 0

    private val subject = TextFieldState("Sujet")

    private fun mount(withSubject: Boolean = false) {
        host.previewAction = {
            host.chrome.value = host.chrome.value.copy(previewVisible = !host.chrome.value.previewVisible)
        }
        host.systemBack = { host.chrome.value = host.chrome.value.copy(previewVisible = false) }
        host.fieldArea = { body ->
            EditorPreview(host.chrome.value.previewVisible, PostContent(emptyList()), host.body, host.focus,
                PostMediaDiskCachePolicy.DISABLED, "Aperçu") {
                DisposableEffect(Unit) { mounts++; onDispose { disposals++ } }
                Box {
                    body()
                    if (withSubject) BasicTextField(subject, Modifier.testTag("preview-source-subject"))
                }
            }
        }
        compose.setContent {
            imeVisible = WindowInsets.isImeVisible
            val realWindow = LocalWindowInfo.current
            val density = LocalDensity.current
            // Resize the window signal independently of the physical display and IME-sized content.
            val window = remember(realWindow, density) {
                object : WindowInfo by realWindow {
                    override val containerSize: IntSize get() = with(density) {
                        IntSize(host.width.value.roundToPx(), host.metrics.value.windowHeight.roundToPx())
                    }
                }
            }
            CompositionLocalProvider(LocalWindowInfo provides window, LocalEditorPreviewState provides state,
                LocalSoftwareKeyboardController provides keyboard) {
                host.Content()
            }
        }
    }

    @Test fun `split allocates 18 dp with a 48 dp accessible target and starts at half`() {
        mount()
        open()
        val source = bounds("editor-scaffold-field")
        val preview = bounds("editor-preview-rendered")
        assertEquals(source.height.value, preview.height.value, 0.5f)
        assertEquals(18f, bounds("editor-preview-divider").height.value, 0.5f)
        assertTrue(bounds("editor-preview-divider-target").height >= 48.dp)
        val actions = compose.onNodeWithTag("editor-preview-divider-target").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        assertEquals(listOf("Agrandir l’aperçu", "Réduire l’aperçu", "Fermer le clavier"), actions.map { it.label })
        compose.runOnIdle { actions.first().action() }
        assertEquals(source.height.value - 32f, bounds("editor-scaffold-field").height.value, 0.5f)
        assertEquals(source.height.value - 32f, state.sourceDp(EditorPreviewKey(Vertical, Compact))!!, 0.5f)
    }

    @Test fun `real field three line height including label controls IME hiding at both font scales`() {
        mount()
        listOf(1f, 1.3f).forEach { scale ->
            compose.runOnIdle {
                host.chrome.value = host.chrome.value.copy(previewVisible = false)
                host.scale.value = scale
                host.insets(ime = 180)
            }
            val available = bounds("editor-scaffold-field").height.value
            open()
            // Observe the actual hard stop through accessible resizing; the independent Text oracle
            // in BbcodeTextFieldMinimumHeightTest checks that it really contains three lines.
            repeat(20) { resizePreview(actionIndex = 0) }
            val minimumDp = bounds("editor-scaffold-field").height.value
            val ime = (180f + available - (2 * minimumDp + 18f) + 2f).toInt()
            compose.runOnIdle { host.insets(ime = ime) }
            compose.onNodeWithTag("editor-preview-fallback").assertDoesNotExist()
            compose.onNodeWithTag("editor-preview-divider-target").assertDoesNotExist()
            compose.runOnIdle { assertTrue(host.surfaceFocus.preview.hiddenByIme) }
            compose.runOnIdle { host.insets(ime = (ime - 8).coerceAtLeast(1)) }
            compose.onNodeWithTag("editor-preview-rendered").assertIsDisplayed()
            assertTrue(bounds("editor-scaffold-field").height.value >= minimumDp - 1f)
        }
    }

    @Test fun `empty and one line messages still split in a normal portrait window`() {
        mount()
        listOf("", "Bonjour", (1..40).joinToString("\n") { "Ligne $it" }).forEach { text ->
            compose.runOnIdle { host.body.edit { replace(0, length, text) } }
            open()
            compose.onNodeWithTag("editor-preview-rendered").assertIsDisplayed()
            previewToggle().performClick()
        }
    }

    @Test fun `window resize changes direction and clamps while source stays mounted`() {
        mount()
        state.rememberPosition(EditorPreviewKey(Vertical, Compact), 420f)
        open()
        val fieldId = compose.onNode(hasSetTextAction()).fetchSemanticsNode().id
        compose.runOnIdle { host.width.value = 760.dp; host.metrics.value = EditorWindowMetrics(360.dp, 24.dp, 0.dp) }
        assertTrue(bounds("editor-preview-rendered").left > bounds("editor-scaffold-field").right)
        assertEquals(18f, bounds("editor-preview-divider").width.value, 0.5f)
        assertTrue(bounds("editor-preview-divider-target").width >= 48.dp)
        compose.runOnIdle {
            host.width.value = 360.dp
            host.metrics.value = EditorWindowMetrics(600.dp, 24.dp, 0.dp)
        }
        assertTrue(bounds("editor-scaffold-field").height.value < 420f)
        assertEquals(420f, state.sourceDp(EditorPreviewKey(Vertical, Compact)))
        assertEquals(fieldId, compose.onNode(hasSetTextAction()).fetchSemanticsNode().id)
        assertEquals(1, mounts)
        assertEquals(0, disposals)
    }

    @Test fun `compact height splits horizontally even in a compact width window`() {
        host.metrics.value = EditorWindowMetrics(470.dp, 24.dp, 0.dp)
        mount(); open()
        assertTrue(bounds("editor-preview-rendered").left > bounds("editor-scaffold-field").right)
        compose.runOnIdle { host.width.value = 240.dp }
        compose.onNodeWithTag("editor-preview-fallback").assertIsDisplayed()
    }

    @Test fun `shared preview toggles preserve selection input session and keyboard commands`() {
        mount()
        compose.runOnIdle { host.focus.requestFocus() }
        val selection = TextRange(12, 4)
        compose.runOnIdle { host.body.edit { this.selection = selection } }
        listOf(0, 180).forEach { ime ->
            compose.runOnIdle { host.insets(ime = ime) }
            val sessions = host.inputSessions
            val before = keyboard.calls.toList()
            val actionBar = bounds("editor-scaffold-action-bar")
            open()
            assertEquals(actionBar, bounds("editor-scaffold-action-bar"))
            assertNotNull(host.inputConnection)
            previewToggle().performClick()
            compose.onNode(hasSetTextAction()).assertIsFocused()
            compose.runOnIdle {
                assertEquals(selection, host.body.selection)
                assertEquals(sessions, host.inputSessions)
                assertEquals(before, keyboard.calls)
            }
        }
    }

    @Test fun `scaffold surface return commands the captured keyboard while preview return stays neutral`() {
        mount()
        val field = compose.onNode(hasSetTextAction())
        val selection = TextRange(12, 4)
        listOf(0, 180).forEach { ime ->
            field.performClick()
            compose.runOnIdle {
                host.insets(ime = ime)
                host.body.edit { this.selection = selection }
            }
            open()
            compose.onNodeWithTag(EDITOR_APP_BAR_TITLE).performClick()
            val beforeSurface = keyboard.calls.toList()
            compose.onNodeWithText("Retour au message").performClick()
            field.assertIsFocused()
            compose.runOnIdle {
                assertEquals(beforeSurface + if (ime > 0) listOf("show") else listOf("hide"), keyboard.calls)
                assertEquals(selection, host.body.selection)
                assertEquals(ime > 0, host.inputConnection != null)
                host.inputMode.requestInputMode(InputMode.Keyboard)
            }
            val toggle = previewToggle()
            toggle.requestFocus().assertIsFocused()
            val (sessions, beforePreview) = compose.runOnIdle {
                assertEquals(selection, host.surfaceFocus.toolFocus.captured?.selection)
                assertEquals(ime > 0, host.surfaceFocus.toolFocus.captured?.imeVisible)
                // BTF2 may collapse a range on genuine focus loss; set the preview-return range afterwards.
                host.body.edit { this.selection = selection }
                assertNull(host.inputConnection)
                host.inputSessions to keyboard.calls.toList()
            }
            toggle.performKeyInput { pressKey(Key.Enter) }
            field.assertIsFocused()
            compose.runOnIdle {
                assertEquals(selection, host.body.selection)
                assertNull(host.inputConnection)
                assertEquals(sessions, host.inputSessions)
                assertEquals(beforePreview, keyboard.calls)
            }
        }
    }

    @Test fun `IME-only fallback keeps typing visible and restores the saved split when the keyboard closes`() {
        mount()
        compose.onNode(hasSetTextAction()).performClick()
        val fieldId = compose.onNode(hasSetTextAction()).fetchSemanticsNode().id
        val selection = TextRange(12, 4)
        val key = EditorPreviewKey(Vertical, Compact)
        compose.runOnIdle {
            host.body.edit { this.selection = selection }
            state.rememberPosition(key, 210f)
        }
        open()
        val splitHeight = bounds("editor-scaffold-field").height
        val sessions = host.inputSessions
        val connection = host.inputConnection
        val commands = keyboard.calls.toList()
        compose.runOnIdle { host.insets(ime = 386) }
        compose.onNodeWithTag("editor-preview-fallback").assertDoesNotExist()
        compose.onNodeWithTag("editor-preview-rendered").assertDoesNotExist()
        compose.onNodeWithTag("editor-preview-divider-target").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsFocused().assertIsDisplayed()
        previewToggle().assertIsOn().assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Aperçu ouvert, masqué pendant la frappe"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.runOnIdle {
            assertTrue(host.chrome.value.previewVisible)
            assertTrue(imeVisible)
            assertEquals(selection, host.body.selection)
            assertNotNull(connection)
            assertTrue(connection === host.inputConnection)
            assertEquals(sessions, host.inputSessions)
            assertEquals(commands, keyboard.calls)
            host.insets(ime = 0)
        }
        compose.onNodeWithTag("editor-preview-rendered").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals(fieldId, compose.onNode(hasSetTextAction()).fetchSemanticsNode().id)
        assertEquals(splitHeight, bounds("editor-scaffold-field").height)
        previewToggle().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        compose.runOnIdle {
            assertFalse(imeVisible)
            assertEquals(selection, host.body.selection)
            assertEquals(210f, state.sourceDp(key))
            assertTrue(connection === host.inputConnection)
            assertEquals(sessions, host.inputSessions)
            assertEquals(commands, keyboard.calls)
        }
    }

    @Test fun `IME-only fallback stays hidden when a subject rather than the body has focus`() {
        mount(withSubject = true)
        // The scaffold tag belongs to BbcodeTextField's enclosing Box, not its focusable BTF2.
        val body = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("editor-scaffold-field")),
            useUnmergedTree = true)
        compose.onNodeWithTag("preview-source-subject").performClick()
        open()
        compose.onNodeWithTag("preview-source-subject").assertIsFocused()
        body.assertIsNotFocused()
        compose.runOnIdle { host.insets(ime = 386) }
        compose.onNodeWithTag("editor-preview-fallback").assertDoesNotExist()
        compose.onNodeWithTag("editor-preview-rendered").assertDoesNotExist()
        previewToggle().assertIsOn().assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Aperçu ouvert, masqué pendant la frappe"))
        body.assertIsNotFocused()
        compose.onNodeWithTag("preview-source-subject").assertIsFocused()
        body.assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(host.surfaceFocus.preview.hiddenByIme)
            assertNotNull(host.inputConnection)
        }
    }

    @Test fun `return from the header subject restores body focus and IME while preview stays hidden`() {
        host.headerContent = { BasicTextField(subject, Modifier.testTag("preview-header-subject")) }
        mount()
        compose.onNode(hasSetTextAction()).performClick()
        val selection = TextRange(12, 4)
        compose.runOnIdle { host.body.edit { this.selection = selection } }
        open()
        compose.runOnIdle { host.insets(ime = 386) }
        compose.onNodeWithTag("editor-preview-fallback").assertDoesNotExist()
        compose.onNodeWithTag("editor-preview-rendered").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsFocused()

        compose.onNodeWithTag(EDITOR_APP_BAR_TITLE).performClick()
        compose.onNodeWithTag("preview-header-subject").performClick().assertIsFocused()
        val subjectConnection = compose.runOnIdle {
            assertTrue(host.surfaceFocus.imeBeforeSurface)
            assertTrue(imeVisible)
            assertNotNull(host.inputConnection)
            host.inputConnection
        }
        val sessions = host.inputSessions
        compose.onNodeWithText("Retour au message").performClick()

        compose.onNodeWithTag("preview-header-subject").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsFocused().assertIsDisplayed()
        compose.onNodeWithTag("editor-preview-fallback").assertDoesNotExist()
        compose.onNodeWithTag("editor-preview-rendered").assertDoesNotExist()
        previewToggle().assertIsOn()
        compose.runOnIdle {
            assertTrue(host.chrome.value.previewVisible)
            assertTrue(host.surfaceFocus.preview.hiddenByIme)
            assertTrue(imeVisible)
            assertEquals(selection, host.body.selection)
            assertEquals(sessions + 1, host.inputSessions)
            assertNotNull(host.inputConnection)
            assertTrue(subjectConnection !== host.inputConnection)
        }
    }

    @Test fun `IME-only fallback leaves an already focused preview control intact`() {
        mount()
        compose.onNode(hasSetTextAction()).performClick()
        open()
        compose.runOnIdle { host.inputMode.requestInputMode(InputMode.Keyboard) }
        val toggle = previewToggle()
        toggle.requestFocus().assertIsFocused()
        val toggleId = toggle.fetchSemanticsNode().id
        val sessions = host.inputSessions
        val commands = keyboard.calls.toList()
        val selection = TextRange(12, 4)
        compose.runOnIdle {
            host.body.edit { this.selection = selection }
            // ED-62: enter IME-only hiding without changing ED-05's chrome variant. Compact height
            // removes the app-bar toggle and creates a different control in the action bar.
            host.insets(ime = 500)
        }
        compose.onNodeWithTag("editor-preview-fallback").assertDoesNotExist()
        compose.onNodeWithTag("editor-preview-rendered").assertDoesNotExist()
        assertEquals(toggleId, toggle.fetchSemanticsNode().id)
        toggle.assertIsFocused()
        compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
        compose.runOnIdle {
            assertTrue(host.surfaceFocus.preview.hiddenByIme)
            assertEquals(selection, host.body.selection)
            assertEquals(sessions, host.inputSessions)
            assertEquals(commands, keyboard.calls)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test fun `covering fallback releases body focus and rejects keys while preserving selection and undo`() {
        mount()
        val fieldId = compose.onNode(hasSetTextAction()).fetchSemanticsNode().id
        compose.onNode(hasSetTextAction()).performClick()
        val original = host.body.text.toString()
        compose.onNode(hasSetTextAction()).performTextInput("!")
        val text = host.body.text.toString()
        val selection = TextRange(12, 4)
        compose.runOnIdle { host.body.edit { this.selection = selection } }
        open()
        compose.onNodeWithTag("editor-preview-rendered").assertIsDisplayed()
        val sessions = host.inputSessions
        val commands = keyboard.calls.toList()
        compose.runOnIdle {
            assertNotNull(host.inputConnection)
            // A resize enters the determined covering fallback with an already focused body.
            host.width.value = 200.dp
            host.metrics.value = EditorWindowMetrics(360.dp, 24.dp, 0.dp)
        }
        compose.onNodeWithTag("editor-preview-fallback").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
        assertEquals(fieldId, compose.onNode(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNode().id)
        compose.onRoot().performKeyInput { pressKey(Key.Z) }
        compose.runOnIdle {
            assertEquals(text, host.body.text.toString())
            assertEquals(selection, host.body.selection)
            assertNull(host.surfaceFocus.toolFocus.captured)
            assertNull(host.inputConnection)
            assertEquals(sessions, host.inputSessions)
            assertEquals(commands, keyboard.calls)
        }
        // ED-25: at 200 dp the compact preview control is in the scrolling action row.
        previewToggle().revealEditorControl().performClick()
        compose.onNodeWithTag("editor-preview-fallback").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals(fieldId, compose.onNode(hasSetTextAction()).fetchSemanticsNode().id)
        compose.runOnIdle {
            assertEquals(selection, host.body.selection)
            assertEquals(sessions, host.inputSessions)
            assertNull(host.inputConnection) // ED-61: return does not implicitly call IME.show().
            assertEquals(commands, keyboard.calls)
            host.body.undoState.undo()
            assertEquals(original, host.body.text.toString())
            assertFalse(host.body.undoState.canUndo)
            host.body.undoState.redo()
            assertEquals(text, host.body.text.toString())
        }
    }

    @Test fun `covering preview consumes a refused surface return without IME or resize replay`() {
        val selection = TextRange(12, 4)
        openCoveringPreviewWithIme(selection)
        val sessions = host.inputSessions
        compose.onNodeWithContentDescription("Titre et sous-catégorie").revealEditorControl().performClick()
        compose.runOnIdle { assertTrue(host.surfaceFocus.imeBeforeSurface) }
        compose.onNodeWithText("Retour au message").performClick()
        compose.onNodeWithTag("editor-preview-fallback").assertIsDisplayed()
        compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
        compose.runOnIdle {
            assertEquals(selection, host.body.selection)
            assertNull(host.inputConnection)
            assertEquals(sessions, host.inputSessions)
            assertTrue(keyboard.calls.isEmpty())
            host.width.value = 760.dp
        }
        compose.onNodeWithTag("editor-preview-rendered").assertIsDisplayed()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600L)
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.runOnIdle {
            assertNull(host.inputConnection)
            assertEquals(sessions, host.inputSessions)
            assertTrue(keyboard.calls.isEmpty())
            host.focus.requestFocus()
        }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            // Observe restoreImeOnFocus before closing the preview could suppress it again.
            assertEquals(selection, host.body.selection)
            assertNull(host.inputConnection)
            assertEquals(sessions, host.inputSessions)
            assertTrue(keyboard.calls.isEmpty())
        }
        previewToggle().revealEditorControl().performClick()
        compose.onNodeWithTag("editor-preview-rendered").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(selection, host.body.selection)
            assertNull(host.surfaceFocus.toolFocus.captured)
            host.width.value = 360.dp
            host.metrics.value = EditorWindowMetrics(740.dp, 24.dp, 30.dp)
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600L)
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(selection, host.body.selection)
            assertNull(host.surfaceFocus.toolFocus.captured)
            assertNull(host.inputConnection)
            assertEquals(sessions, host.inputSessions)
            assertTrue(keyboard.calls.isEmpty())
        }
    }

    @Test fun `surface opened after covering defocus returns after expansion without stale IME show`() {
        val selection = TextRange(12, 4)
        openCoveringPreviewWithIme(selection)
        val sessions = host.inputSessions
        // Deliver the real IME closure after the covering preview ended the body's input session.
        compose.runOnIdle { host.insets(ime = 0) }
        compose.runOnIdle { assertFalse(imeVisible) }
        compose.onNodeWithContentDescription("Titre et sous-catégorie").revealEditorControl().performClick()
        compose.runOnIdle {
            assertTrue(host.panel.value)
            host.width.value = 760.dp
        }
        compose.onNodeWithText("Retour au message").performClick()
        compose.onNodeWithTag("editor-preview-rendered").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertFalse(host.surfaceFocus.imeBeforeSurface)
            assertEquals(listOf("hide"), keyboard.calls)
            assertEquals(selection, host.body.selection)
            assertNull(host.surfaceFocus.toolFocus.captured)
            assertNull(host.inputConnection)
            assertEquals(sessions, host.inputSessions)
        }
    }

    private fun openCoveringPreviewWithIme(selection: TextRange) {
        mount()
        compose.onNode(hasSetTextAction()).performClick()
        compose.runOnIdle {
            // The setup tap's own IME request is not under test: count commands from here on.
            keyboard.calls.clear()
            host.body.edit { this.selection = selection }
            host.insets(ime = 180)
        }
        open()
        compose.onNodeWithTag("editor-preview-rendered").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        val sessions = compose.runOnIdle {
            assertNotNull(host.inputConnection)
            host.inputSessions
        }
        compose.runOnIdle {
            host.width.value = 200.dp
            host.metrics.value = EditorWindowMetrics(360.dp, 24.dp, 0.dp)
        }
        compose.onNodeWithTag("editor-preview-fallback").assertIsDisplayed()
        compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
        compose.runOnIdle {
            assertTrue(imeVisible)
            assertEquals(selection, host.body.selection)
            assertNull(host.inputConnection)
            assertEquals(sessions, host.inputSessions)
            assertTrue(keyboard.calls.isEmpty())
        }
    }

    @Test fun `focused control or divider never restarts IME on button or Back with open or closed keyboard`() {
        mount()
        listOf(0, 180).forEach { ime ->
            listOf(false, true).forEach { dividerFocused ->
                listOf(false, true).forEach { back ->
                    assertFocusedPreviewReturn(ime, dividerFocused, back)
                }
            }
        }
    }

    private fun assertFocusedPreviewReturn(ime: Int, dividerFocused: Boolean, back: Boolean) {
        compose.onNode(hasSetTextAction()).performClick()
        compose.runOnIdle {
            host.insets(ime = ime)
            host.inputMode.requestInputMode(InputMode.Keyboard)
        }
        if (!dividerFocused) previewToggle().requestFocus()
        val sessionsBeforeOpen = host.inputSessions
        val commands = keyboard.calls.toList()
        if (dividerFocused) open()
        else previewToggle().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle {
            assertEquals(ime > 0, imeVisible)
            assertEquals(sessionsBeforeOpen, host.inputSessions)
            assertEquals(commands, keyboard.calls)
        }
        if (dividerFocused) compose.onNodeWithTag("editor-preview-divider-target").requestFocus()
        val selection = TextRange(12, 4)
        compose.runOnIdle { host.body.edit { this.selection = selection } }
        val sessionsBeforeClose = host.inputSessions
        when {
            back -> compose.runOnIdle { host.back.onBackPressed() }
            dividerFocused -> previewToggle().performClick()
            else -> previewToggle().performKeyInput { pressKey(Key.Enter) }
        }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(selection, host.body.selection)
            assertEquals(ime > 0, imeVisible)
            assertNull(host.inputConnection)
            assertEquals("return must not implicitly request IME.show()", sessionsBeforeClose, host.inputSessions)
            assertEquals(commands, keyboard.calls)
        }
    }

    @Test fun `send stays enabled during split without closing preview`() {
        mount(); open()
        compose.onNodeWithText("Envoyer").performClick()
        assertTrue(host.calls.contains("send"))
        compose.onNodeWithTag("editor-preview-rendered").assertIsDisplayed()
    }

    @Test fun `closed keyboard stop is 160 dp without elasticity or hint`() {
        mount(); open()
        compose.onNodeWithTag("editor-preview-divider-target").performTouchInput {
            down(center); moveBy(Offset(0f, -500f)); up()
        }
        assertEquals(160f, bounds("editor-scaffold-field").height.value, 0.5f)
        compose.onNodeWithTag("editor-preview-release").assertDoesNotExist()
        assertTrue(keyboard.calls.isEmpty())
    }

    @Test fun `overdrag keeps both pane floors during and after release and hides keyboard once`() {
        mount()
        compose.runOnIdle { host.insets(ime = 180) }
        open()
        val divider = compose.onNodeWithTag("editor-preview-divider-target")
        // Reach each hard stop without an elastic gesture, and record the actual allocations.
        listOf(-1f, 1f).forEachIndexed { index, direction ->
            repeat(20) { resizePreview(actionIndex = index) }
            val sourceAtStop = bounds("editor-scaffold-field").height.value
            val previewAtStop = bounds("editor-preview-rendered").height.value
            divider.performTouchInput { down(center); moveBy(Offset(0f, direction * 500f)) }
            compose.onNodeWithTag("editor-preview-release").assertIsDisplayed()
            assertPaneHeights(sourceAtStop, previewAtStop)
            assertEquals(index, keyboard.calls.size)
            divider.performTouchInput { up() }
            compose.onNodeWithTag("editor-preview-release").assertDoesNotExist()
            assertPaneHeights(sourceAtStop, previewAtStop)
            assertEquals(List(index + 1) { "hide" }, keyboard.calls)
            // Also deliver the insets a real IME hide would produce after the release.
            compose.runOnIdle { host.insets(ime = 0) }
            assertTrue(bounds("editor-scaffold-field").height >= 160.dp)
            assertTrue(bounds("editor-preview-rendered").height.value >= minOf(sourceAtStop, previewAtStop) - 0.5f)
            compose.runOnIdle { host.insets(ime = 180) }
        }
    }

    @Test fun `cancelled overdrag never hides the keyboard or persists the gesture`() {
        mount()
        compose.runOnIdle { host.insets(ime = 180) }
        open()
        val source = bounds("editor-scaffold-field").height.value
        val preview = bounds("editor-preview-rendered").height.value
        val divider = compose.onNodeWithTag("editor-preview-divider-target")
        divider.performTouchInput { down(center); moveBy(Offset(0f, -500f)); cancel() }
        assertPaneHeights(source, preview)
        assertTrue(keyboard.calls.isEmpty())
        assertEquals(null, state.sourceDp(EditorPreviewKey(Vertical, Compact)))
    }

    private fun assertPaneHeights(source: Float, preview: Float) {
        assertEquals("source floor", source, bounds("editor-scaffold-field").height.value, 0.5f)
        assertEquals("preview floor", preview, bounds("editor-preview-rendered").height.value, 0.5f)
    }

    private fun resizePreview(actionIndex: Int) {
        val action = compose.onNodeWithTag("editor-preview-divider-target").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions][actionIndex]
        compose.runOnIdle { action.action() }
    }

    private fun previewToggle() = compose.onNodeWithContentDescription("Aperçu")
    private fun open() = previewToggle().performClick()
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
}

private class PreviewKeyboard : SoftwareKeyboardController {
    val calls = mutableListOf<String>()
    override fun show() { calls += "show" }
    override fun hide() { calls += "hide" }
}
