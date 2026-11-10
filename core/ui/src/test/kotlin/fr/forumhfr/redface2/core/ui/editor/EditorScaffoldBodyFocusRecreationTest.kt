package fr.forumhfr.redface2.core.ui.editor

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * H / ED-86: new Activity, window and composition, retained VM, explicit touch mode. No refocus or
 * field action after recreate. Observe BTF2's input sessions, since Robolectric has no real IME.
 * Explicit insets supply the visibility captured before each pause, independently of a live session.
 * The post screen also covers recreation on API 34. Device H remains required.
 */
@OptIn(ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorScaffoldBodyFocusRecreationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before
    fun registerHostAndTouchMode() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager)
            .addActivityIfNotPresent(ComponentName(context, BodyFocusHostActivity::class.java))
    }

    @Test fun openImeAndMiddleCaret() = checkFocusedBody(open = true, selection = MIDDLE_CARET)
    @Test fun closedImeAndMiddleCaret() = checkFocusedBody(open = false, selection = MIDDLE_CARET)
    @Test fun openImeAndReversedSelection() = checkFocusedBody(open = true, selection = REVERSED_SELECTION)
    @Test fun closedImeAndReversedSelection() = checkFocusedBody(open = false, selection = REVERSED_SELECTION)

    private fun checkFocusedBody(open: Boolean, selection: TextRange) {
        ActivityScenario.launch(BodyFocusHostActivity::class.java).use { scenario ->
            compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("!")
            lateinit var original: BodyFocusHostActivity
            scenario.onActivity { original = it }
            val model = original.model
            compose.runOnIdle {
                assertEquals(InputMode.Touch, original.inputMode.inputMode)
                model.body.edit { this.selection = selection }
                original.setImeVisible(open)
            }
            compose.waitForIdle()
            assertBody(model, selection)
            val sessions = compose.runOnIdle {
                assertTrue(model.sessions > 0)
                assertTrue(model.inputActive) // Hiding the keyboard does not close the input connection.
                assertEquals(EditorBodyFocusSnapshot(true, open), model.focus.body.snapshot)
                model.sessions
            }
            val gains = compose.runOnIdle { model.focusGains }

            repeat(2) { recreation ->
                scenario.recreate()
                compose.waitForIdle()
                scenario.onActivity {
                    assertNotSame(original, it)
                    assertTrue(original.isChangingConfigurations)
                    assertSame(model, it.model)
                    assertEquals(InputMode.Touch, it.inputMode.inputMode)
                    original = it
                }
                assertBody(model, selection)
                compose.runOnIdle {
                    assertEquals(gains + recreation + 1, model.focusGains)
                    assertEquals(sessions + if (open) recreation + 1 else 0, model.sessions)
                    assertEquals(open, model.inputActive)
                }
                // Supply the next pre-pause visibility only AFTER observing restored focus/session.
                scenario.onActivity { it.setImeVisible(open) }
                compose.waitForIdle()
            }
            compose.runOnIdle {
                // Exactly the initial user edit remains in undo; rotation adds none and clears none.
                model.body.undoState.undo()
                assertEquals(BODY_TEXT, model.body.text.toString())
                assertFalse(model.body.undoState.canUndo)
                model.body.undoState.redo()
                assertEquals("$BODY_TEXT!", model.body.text.toString())
            }
        }
    }

    @Test
    fun unfocusedBodyDoesNotAcquireFocusOrOpenIme() {
        ActivityScenario.launch(BodyFocusHostActivity::class.java).use { scenario ->
            compose.onNode(hasSetTextAction()).assertIsFocused()
            lateinit var activity: BodyFocusHostActivity
            scenario.onActivity { activity = it }
            compose.runOnIdle { activity.focusManager.clearFocus(force = true); activity.hideIme() }
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
            val sessions = compose.runOnIdle { activity.model.sessions }
            scenario.recreate()
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
            scenario.onActivity {
                assertEquals(sessions, it.model.sessions)
                assertFalse(it.model.inputActive)
            }
        }
    }

    @Test fun panelPreventsBodyRefocusAcrossRecreation() = checkCover(BodyFocusCover.Panel)
    @Test fun sheetPreventsBodyRefocusAcrossRecreation() = checkCover(BodyFocusCover.Sheet)
    @Test fun previewPreventsBodyRefocusAcrossRecreation() = checkCover(BodyFocusCover.Preview)

    @Test
    fun blockedRestorationIsConsumedEvenWhenTheHostLaterAllowsBodyFocus() {
        ActivityScenario.launch(BodyFocusHostActivity::class.java).use { scenario ->
            compose.onNode(hasSetTextAction()).assertIsFocused()
            scenario.onActivity { it.model.restoreAllowed = false }
            scenario.recreate()
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
            scenario.onActivity { it.model.restoreAllowed = true }
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
        }
    }

    @Test
    fun newSessionObservesTheStillMountedFieldBeforeTheNextRotation() {
        ActivityScenario.launch(BodyFocusHostActivity::class.java).use { scenario ->
            compose.onNode(hasSetTextAction()).assertIsFocused()
            scenario.onActivity { it.model.focus.reset() }
            compose.waitForIdle()
            scenario.onActivity { it.hideIme() }
            compose.waitForIdle()
            scenario.recreate()
            compose.onNode(hasSetTextAction()).assertIsFocused()
            scenario.onActivity { assertFalse(it.model.inputActive) }
        }
    }

    @Test
    fun newSessionAfterClosedImePanelReturnDoesNotReopenIme() {
        ActivityScenario.launch(BodyFocusHostActivity::class.java).use { scenario ->
            compose.onNode(hasSetTextAction()).assertIsFocused()
            scenario.onActivity { it.hideIme() }
            compose.waitForIdle()
            compose.onNodeWithTag(EDITOR_APP_BAR_TITLE).performClick()
            compose.onNodeWithText("Retour au message").performClick()
            compose.onNode(hasSetTextAction()).assertIsFocused()
            lateinit var model: BodyFocusModel
            scenario.onActivity {
                model = it.model
                assertFalse(model.inputActive)
            }
            val sessions = compose.runOnIdle { model.sessions }
            scenario.onActivity { model.focus.reset() }
            compose.waitForIdle()
            compose.onNode(hasSetTextAction()).assertIsFocused()
            compose.runOnIdle {
                assertEquals(sessions, model.sessions)
                assertFalse(model.inputActive)
                assertEquals(EditorBodyFocusSnapshot(true, false), model.focus.body.snapshot)
            }
            scenario.recreate()
            compose.onNode(hasSetTextAction()).assertIsFocused()
            compose.runOnIdle { assertEquals(sessions, model.sessions); assertFalse(model.inputActive) }
        }
    }

    private fun checkCover(cover: BodyFocusCover) {
        ActivityScenario.launch(BodyFocusHostActivity::class.java).use { scenario ->
            compose.onNode(hasSetTextAction()).assertIsFocused()
            scenario.onActivity { it.model.cover = cover }
            compose.waitForIdle()
            assertCover(cover)
            lateinit var model: BodyFocusModel
            scenario.onActivity { model = it.model }
            val sessions = compose.runOnIdle { model.sessions }
            scenario.recreate()
            compose.waitForIdle()
            compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
            assertCover(cover)
            compose.runOnIdle {
                assertEquals(cover, model.cover)
                assertEquals(sessions, model.sessions)
                assertFalse(model.inputActive)
            }
        }
    }

    private fun assertCover(cover: BodyFocusCover) {
        if (cover == BodyFocusCover.Panel) compose.onNodeWithText("Retour au message").assertIsDisplayed()
        else compose.onNodeWithTag("cover-focus").assertIsDisplayed()
    }

    @Test
    fun panelReturnAfterRecreationKeepsInitiallyClosedIme() {
        ActivityScenario.launch(BodyFocusHostActivity::class.java).use { scenario ->
            compose.onNode(hasSetTextAction()).assertIsFocused()
            scenario.onActivity { it.hideIme() }
            compose.waitForIdle()
            compose.onNodeWithTag(EDITOR_APP_BAR_TITLE).performClick()
            lateinit var model: BodyFocusModel
            scenario.onActivity { model = it.model }
            val sessions = compose.runOnIdle {
                assertFalse(model.focus.imeBeforeSurface)
                model.sessions
            }
            scenario.recreate()
            compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
            compose.onNodeWithText("Retour au message").performClick()
            compose.onNode(hasSetTextAction()).assertIsFocused()
            compose.runOnIdle { assertEquals(sessions, model.sessions); assertFalse(model.inputActive) }
        }
    }

    private fun assertBody(model: BodyFocusModel, selection: TextRange) {
        val field = compose.onNode(hasSetTextAction()).assertIsFocused()
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        compose.runOnIdle {
            assertEquals("$BODY_TEXT!", model.body.text.toString())
            assertEquals(selection, model.body.selection)
            assertTrue(model.body.undoState.canUndo)
            val caret = layouts.single().getCursorRect(selection.end)
            assertTrue(model.scroll.viewportSize > 0)
            assertTrue(caret.top >= model.scroll.value - 1f)
            assertTrue(caret.bottom <= model.scroll.value + model.scroll.viewportSize + 1f)
        }
    }
}

internal enum class BodyFocusCover { None, Panel, Sheet, Preview }

internal class BodyFocusModel : ViewModel() {
    val body = TextFieldState(BODY_TEXT, TextRange(BODY_TEXT.length))
    val focus = EditorSurfaceFocusMemory()
    val scroll = ScrollState(0)
    var cover by mutableStateOf(BodyFocusCover.None)
    var restoreAllowed by mutableStateOf(true)
    var sessions = 0
    var inputActive = false
    var focusGains = 0
}

internal class BodyFocusHostActivity : ComponentActivity() {
    lateinit var model: BodyFocusModel
    lateinit var view: View
    lateinit var focusManager: FocusManager
    lateinit var inputMode: InputModeManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val factory = viewModelFactory { initializer { BodyFocusModel() } }
        model = ViewModelProvider(this, factory)[BodyFocusModel::class.java]
        setContent {
            view = LocalView.current
            focusManager = LocalFocusManager.current
            inputMode = LocalInputModeManager.current
            val input = remember { PlatformTextInputInterceptor { request, next ->
                model.sessions++
                model.inputActive = true
                try { next.startInputMethod(request) } finally { model.inputActive = false }
            } }
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                InterceptPlatformTextInput(input) { Content() }
            }
        }
    }

    override fun onPause() {
        super.onPause() // The editor freezes BEFORE the window's teardown hide/insets.
        hideIme()
    }

    fun hideIme() { setImeVisible(false) }

    fun setImeVisible(visible: Boolean) {
        ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, if (visible) 120 else 0))
            .setVisible(WindowInsetsCompat.Type.ime(), visible).build())
    }

    @Composable
    private fun Content() {
        EditorScaffold(model.body, EditorWindowMetrics(640.dp, 0.dp, 0.dp), "Répondre", "Sujet",
            EditorScaffoldChrome(previewVisible = model.cover == BodyFocusCover.Preview),
            EditorActionBarActions({}, {}, {}, {}, {}, {}, { _, _ -> }),
            ArmedSubmitState(false, false), ArmedSubmitActions({}, {}, {}), EditorBarImageActions({}, {}), {},
            bodyFocusRestoreAllowed = model.restoreAllowed && model.cover == BodyFocusCover.None,
            autoFocus = true, surfaceFocus = model.focus, bodyScrollState = model.scroll,
            onOpenHeader = { model.cover = BodyFocusCover.Panel },
            fieldArea = { field ->
                Box(Modifier.fillMaxSize().onFocusChanged { if (it.isFocused) model.focusGains++ }) { field() }
            },
            overlay = { restore, field ->
                EditorHeaderPanel(model.cover == BodyFocusCover.Panel, "En-tête", model.body,
                    { model.cover = BodyFocusCover.None }, restore, body = {
                        Box(Modifier.fillMaxSize()) {
                            field()
                            if (model.cover == BodyFocusCover.Preview) CoverFocus()
                        }
                    }) { Text("En-tête du sujet") }
                if (model.cover == BodyFocusCover.Sheet) EditorOptionsSheet({ model.cover = BodyFocusCover.None }) {
                    CoverFocus()
                }
            })
    }

    @Composable
    private fun CoverFocus() {
        val focus = remember { FocusRequester() }
        TextButton({}, Modifier.testTag("cover-focus").focusRequester(focus)) { Text("Surface") }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

private val BODY_TEXT = (1..40).joinToString("\n") { "Ligne $it du message" }
private val MIDDLE_CARET = TextRange(BODY_TEXT.indexOf("Ligne 20") + 6)
private val REVERSED_SELECTION = TextRange(MIDDLE_CARET.end + 5, MIDDLE_CARET.end)
