package fr.forumhfr.redface2.core.ui.editor

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.editor.EditorPreviewDirection.Vertical
import fr.forumhfr.redface2.core.model.editor.EditorPreviewKey
import fr.forumhfr.redface2.core.model.editor.EditorPreviewWidthClass.Compact
import fr.forumhfr.redface2.core.ui.post.PostMediaDiskCachePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/** C8: recreate the Activity/window/composition while retaining only the host ViewModel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorPreviewActivityRecreationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before fun registerHost() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager)
            .addActivityIfNotPresent(ComponentName(context, PreviewRecreationHostActivity::class.java))
    }

    @Test fun `open preview and chosen position survive real activity recreation`() {
        ActivityScenario.launch(PreviewRecreationHostActivity::class.java).use { scenario ->
            compose.waitForIdle()
            lateinit var original: PreviewRecreationHostActivity
            scenario.onActivity { original = it }
            val model = original.model
            compose.runOnIdle { model.layout.rememberPosition(EditorPreviewKey(Vertical, Compact), 210f) }
            compose.onNodeWithContentDescription("Aperçu").performClick()
            assertOpenAt210()

            scenario.recreate()
            compose.waitForIdle()

            scenario.onActivity {
                assertNotSame("Activity and composition must actually be replaced", original, it)
                assertTrue(original.isChangingConfigurations)
                assertSame("only the ViewModel survives recreation", model, it.model)
            }
            assertTrue(model.host.chrome.value.previewVisible)
            assertOpenAt210()
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test fun `shared preview restores body when portrait measurement is published ten frames after recreation`() {
        ActivityScenario.launch(PreviewRecreationHostActivity::class.java).use { scenario ->
            compose.onNode(hasSetTextAction()).performClick().performTextInput("!")
            lateinit var model: PreviewRecreationModel
            scenario.onActivity { model = it.model }
            val selection = TextRange(12, 4)
            compose.runOnIdle {
                model.host.body.edit { this.selection = selection }
                model.host.insets(ime = 120)
            }
            compose.onNodeWithContentDescription("Aperçu").performClick()
            // Exercise both directions. Delayed publication belongs to the recreated composition,
            // not a timer that might already have expired in the old window.
            listOf(false, true).forEach { portrait ->
                scenario.onActivity {
                    model.measurementDelayFrames = if (portrait) 10 else 0
                    model.nextPortrait = portrait
                }
                compose.runOnIdle {
                    assertTrue(model.host.surfaceFocus.body.snapshot.focused)
                    assertEquals(selection, model.host.body.selection)
                }
                val sessions = compose.runOnIdle { model.host.inputSessions }
                scenario.recreate()
                compose.onNodeWithTag("editor-preview-rendered").assertIsDisplayed()
                compose.onNode(hasSetTextAction()).assertIsFocused()
                compose.runOnIdle {
                    assertEquals(selection, model.host.body.selection)
                    assertTrue(model.host.body.undoState.canUndo)
                    assertEquals(sessions + 1, model.host.inputSessions)
                    assertNotNull(model.host.inputConnection)
                    model.host.insets(ime = 120)
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test fun `recreation restores body focus and IME when only the keyboard prevents the portrait split`() {
        ActivityScenario.launch(PreviewRecreationHostActivity::class.java).use { scenario ->
            compose.onNode(hasSetTextAction()).performClick().performTextInput("!")
            lateinit var original: PreviewRecreationHostActivity
            scenario.onActivity { original = it }
            val model = original.model
            val selection = TextRange(12, 4)
            val key = EditorPreviewKey(Vertical, Compact)
            compose.runOnIdle {
                model.host.body.edit { this.selection = selection }
                model.layout.rememberPosition(key, 210f)
                model.host.width.value = 760.dp
                model.host.metrics.value = EditorWindowMetrics(360.dp, 24.dp, 0.dp)
                model.host.insets(ime = 120)
            }
            compose.onNodeWithContentDescription("Aperçu").revealEditorControl().performClick()
            compose.onNodeWithTag("editor-preview-rendered").assertIsDisplayed()
            compose.runOnIdle {
                assertTrue(model.host.surfaceFocus.body.snapshot.focused)
                assertTrue(model.host.surfaceFocus.body.snapshot.imeVisible)
                model.nextPortrait = true
                model.measurementDelayFrames = 10
            }
            val sessions = model.host.inputSessions
            compose.mainClock.autoAdvance = false
            scenario.recreate()
            compose.mainClock.advanceTimeByFrame()
            scenario.onActivity {
                assertNotSame(original, it)
                assertSame(model, it.model)
            }
            // Publish the new window's IME while H still awaits the actual three-line minimum.
            // No focus request, key or pointer is injected into the recreated editor.
            compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
            compose.runOnIdle { model.host.insets(ime = 386) }
            compose.mainClock.autoAdvance = true

            compose.onNodeWithTag("editor-preview-fallback").assertDoesNotExist()
            compose.onNodeWithTag("editor-preview-rendered").assertDoesNotExist()
            compose.onNode(hasSetTextAction()).assertIsFocused().assertIsDisplayed()
            compose.runOnIdle {
                assertTrue(model.host.chrome.value.previewVisible)
                assertTrue(model.host.surfaceFocus.preview.hiddenByIme)
                assertTrue(model.host.surfaceFocus.body.snapshot.imeVisible)
                assertEquals(selection, model.host.body.selection)
                assertTrue(model.host.body.undoState.canUndo)
                assertEquals(sessions + 1, model.host.inputSessions)
                assertNotNull(model.host.inputConnection)
                model.host.insets(ime = 0)
            }
            assertOpenAt210()
            compose.onNode(hasSetTextAction()).assertIsFocused()
        }
    }

    @Test fun `closing a covering preview after recreation returns body focus and oriented selection`() {
        ActivityScenario.launch(PreviewRecreationHostActivity::class.java).use { scenario ->
            compose.onNode(hasSetTextAction()).performClick().performTextInput("!")
            lateinit var model: PreviewRecreationModel
            scenario.onActivity { model = it.model }
            val text = model.host.body.text.toString()
            val selection = TextRange(12, 4)
            compose.runOnIdle { model.host.body.edit { this.selection = selection } }
            compose.onNodeWithContentDescription("Aperçu").performClick()
            compose.onNode(hasSetTextAction()).assertIsFocused()
            // Only the NEW Activity gets fallback constraints: the old split still owns the ticket.
            scenario.onActivity { model.nextCovering = true }
            scenario.recreate()
            compose.onNodeWithTag("editor-preview-fallback").assertIsDisplayed()
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
            val fieldId = compose.onNode(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNode().id
            val sessions = compose.runOnIdle {
                assertEquals(selection, model.host.body.selection)
                model.host.inputSessions
            }
            // ED-25: the compact control can be outside its scroll viewport at this narrow width.
            compose.onNodeWithContentDescription("Aperçu").assertIsOn().revealEditorControl().performClick()
            compose.onNodeWithTag("editor-preview-fallback").assertDoesNotExist()
            compose.onNode(hasSetTextAction()).assertIsFocused()
            // Closing through the eye cancels H even for this semantics click (no key/pointer).
            // Passing its former deadline must not replay restoration or reopen the IME.
            compose.mainClock.advanceTimeBy(600L)
            compose.onNode(hasSetTextAction()).assertIsFocused()
            assertEquals(fieldId, compose.onNode(hasSetTextAction()).fetchSemanticsNode().id)
            compose.runOnIdle {
                assertEquals(text, model.host.body.text.toString())
                assertEquals(selection, model.host.body.selection)
                assertEquals(sessions, model.host.inputSessions) // No implicit IME.show() on return.
                assertFalse(model.host.surfaceFocus.preview.wasOpen)
            }
        }
    }

    private fun assertOpenAt210() {
        compose.onNodeWithTag("editor-preview-rendered").assertIsDisplayed()
        assertEquals(210f, compose.onNodeWithTag("editor-scaffold-field")
            .getUnclippedBoundsInRoot().height.value, 0.5f)
    }
}

internal class PreviewRecreationModel : ViewModel() {
    val host = EditorScaffoldFixture()
    val layout = EditorPreviewState()
    var measurementDelayFrames = 0
    var nextPortrait: Boolean? = null
    var nextCovering = false

    init {
        host.previewAction = {
            host.chrome.value = host.chrome.value.copy(previewVisible = !host.chrome.value.previewVisible)
        }
        host.fieldArea = { field ->
            EditorPreview(host.chrome.value.previewVisible, PostContent(emptyList()), host.body, host.focus,
                PostMediaDiskCachePolicy.DISABLED, "Aperçu") {
                val publish by rememberUpdatedState(LocalEditorPreviewFieldMinimum.current)
                var measured by remember { mutableStateOf<Pair<Int, Int>?>(null) }
                LaunchedEffect(measured) {
                    measured?.let { value ->
                        repeat(measurementDelayFrames) { withFrameNanos { } }
                        publish?.invoke(value.first, value.second)
                    }
                }
                CompositionLocalProvider(LocalEditorPreviewFieldMinimum provides { minimum, height ->
                    measured = minimum to height
                }) { field() }
            }
        }
    }
}

/** Same onCreate/setContent and ViewModelProvider harness as BbcodeTextFieldDetachSelectionTest. */
internal class PreviewRecreationHostActivity : ComponentActivity() {
    lateinit var model: PreviewRecreationModel
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this, viewModelFactory { initializer { PreviewRecreationModel() } })[
            PreviewRecreationModel::class.java]
        if (savedInstanceState != null) {
            model.nextPortrait?.let { portrait ->
                model.host.width.value = if (portrait) 360.dp else 760.dp
                model.host.metrics.value = EditorWindowMetrics(if (portrait) 740.dp else 360.dp, 24.dp, 0.dp)
            }
            if (model.nextCovering) {
                model.host.width.value = 200.dp
                model.host.metrics.value = EditorWindowMetrics(360.dp, 24.dp, 0.dp)
            }
        }
        setContent { Content() }
    }

    @Composable
    private fun Content() {
        val realWindow = LocalWindowInfo.current
        val density = LocalDensity.current
        val window = remember(realWindow, density) {
            object : WindowInfo by realWindow {
                override val containerSize: IntSize get() = with(density) {
                    IntSize(model.host.width.value.roundToPx(), model.host.metrics.value.windowHeight.roundToPx())
                }
            }
        }
        CompositionLocalProvider(LocalWindowInfo provides window, LocalEditorPreviewState provides model.layout) {
            model.host.Content()
        }
    }
}
