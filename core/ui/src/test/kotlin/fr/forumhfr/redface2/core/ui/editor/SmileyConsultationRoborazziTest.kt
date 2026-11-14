package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.testing.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class, ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmileyConsultationRoborazziTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `light consultation at scale 1`() = capture(false, 1f)
    @Test fun `light consultation at scale 1_3`() = capture(false, 1.3f)
    @Test fun `dark consultation at scale 1`() = capture(true, 1f)
    @Test fun `dark consultation at scale 1_3`() = capture(true, 1.3f)

    private fun capture(dark: Boolean, scale: Float) {
        val originalScale = RuntimeEnvironment.getFontScale()
        try {
            InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
            // Set the system configuration before creating the dialog: host LocalDensity stops at its window.
            RuntimeEnvironment.setFontScale(scale)
            val controller = SmileyPickerController(CoroutineScope(StandardTestDispatcher()),
                searchWiki = { _, _ -> error("A consultation must not restart the cancelled search") })
            controller.open(SmileyPickerOpenIntent.SEARCH)
            controller.onQueryChanged("jap")
            controller.dismiss() // A real interrupted search restores its query in Idle, without a refetch.
            controller.open(SmileyPickerOpenIntent.CONSULTATION)
            val state = controller.state.value as SmileyPickerState.Open
            lateinit var sheetState: SheetState
            lateinit var sheetScope: CoroutineScope
            compose.setContent {
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                sheetScope = rememberCoroutineScope()
                RedfaceTheme(darkTheme = dark, amoledTheme = false, dynamicColor = false) {
                    SmileyPickerSheet(state, {}, {}, {}, sheetState = sheetState)
                }
            }
            compose.runOnIdle { sheetScope.launch { sheetState.expand() } }
            compose.mainClock.advanceTimeUntil(timeoutMillis = 5_000L) {
                sheetState.currentValue == SheetValue.Expanded && !sheetState.isAnimationRunning
            }
            compose.waitForIdle()
            compose.onNodeWithTag("smiley_selected_tab").assertIsFocused()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Wiki", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertEquals(scale, layouts.single().layoutInput.density.fontScale, 0.001f)
            captureSheet("src/test/screenshots/smiley_consultation_${dark}_$scale.png")
        } finally {
            RuntimeEnvironment.setFontScale(originalScale)
        }
    }

    private fun captureSheet(path: String) {
        val sheet = compose.onNodeWithTag("smiley_picker_sheet").assertIsDisplayed()
        val node = sheet.fetchSemanticsNode()
        val bounds = node.boundsInWindow
        // captureToImage crops this dialog node to its window bounds. Roborazzi's node API instead
        // falls back to the full screen when it detects multiple windows, even for an inner node.
        val image = sheet.captureToImage().asAndroidBitmap()
        assertEquals("The sheet must be fully visible horizontally", node.size.width, bounds.width.roundToInt())
        assertEquals("The sheet must be fully visible vertically", node.size.height, bounds.height.roundToInt())
        assertEquals(node.size.width, image.width)
        assertEquals(node.size.height, image.height)
        assertTrue("Capture must exclude the full-screen scrim",
            image.height < RuntimeEnvironment.getApplication().resources.displayMetrics.heightPixels)
        image.captureRoboImage(path)
    }
}
