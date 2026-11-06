package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1509 — record-only baseline of the old post editor and new-topic form before/after the M3 pin.
 * Uses the real content and routing fixtures from [EditorScreenLayoutTest], without a ViewModel.
 * Reply represents the shared reply/edit post chrome; options, preview, uploads and smileys stay shut.
 * Each surface has eight captures: portrait/landscape × font scale 1.0/1.3 × empty/multiline body.
 * The light app theme is fixed, the IME is closed and focus is cleared before recording.
 *
 * Output: `feature/editor/build/outputs/roborazzi/<surface>_<orientation>_fs<scale>_<state>.png`.
 * This harness deliberately uses no M3 1.5 API so it can also be applied before the pin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h760dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class EditorBaselineRoborazziTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun portraitFontScale1_0() = captureMatrix(orientation = "portrait", fontScale = 1.0f)

    @Test
    fun portraitFontScale1_3() = captureMatrix(orientation = "portrait", fontScale = 1.3f)

    @Test
    @Config(qualifiers = "fr-rFR-w760dp-h360dp-land-xxhdpi")
    fun landscapeFontScale1_0() = captureMatrix(orientation = "landscape", fontScale = 1.0f)

    @Test
    @Config(qualifiers = "fr-rFR-w760dp-h360dp-land-xxhdpi")
    fun landscapeFontScale1_3() = captureMatrix(orientation = "landscape", fontScale = 1.3f)

    private fun captureMatrix(orientation: String, fontScale: Float) {
        val scenario = mutableStateOf(Scenario(Surface.Post, filled = false))
        lateinit var focusManager: FocusManager
        compose.setContent {
            focusManager = LocalFocusManager.current
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = fontScale),
            ) {
                RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                    val current = scenario.value
                    // Recreate field/scroll/picker state for every capture, never inherit a caret
                    // position or a controls offset from the preceding surface or draft.
                    key(current) {
                        EditorChrome(current)
                    }
                }
            }
        }

        Surface.entries.forEach { surface ->
            listOf(false, true).forEach { filled ->
                compose.runOnIdle { scenario.value = Scenario(surface, filled) }
                compose.waitForIdle()
                // PostEditorContent auto-focuses its field. Remove the caret/IME and let the
                // focus decoration finish before freezing the clock for a static screenshot.
                compose.runOnIdle { focusManager.clearFocus(force = true) }
                compose.mainClock.advanceTimeBy(1_000L)
                compose.waitForIdle()
                compose.mainClock.autoAdvance = false
                val state = if (filled) "multiline" else "empty"
                compose.onRoot()
                    .assertWidthIsEqualTo(if (orientation == "portrait") 360.dp else 760.dp)
                    .assertHeightIsEqualTo(if (orientation == "portrait") 760.dp else 360.dp)
                    .captureRoboImage(
                        filePath = "build/outputs/roborazzi/" +
                            "${surface.fileName}_${orientation}_fs${fontScale}_$state.png",
                    )
                compose.mainClock.autoAdvance = true
            }
        }
    }

    @Composable
    private fun EditorChrome(scenario: Scenario) {
        val draft = TextFieldValue(if (scenario.filled) MULTILINE_DRAFT else "")
        when (scenario.surface) {
            Surface.Post -> {
                val scope = rememberCoroutineScope()
                val picker = remember(scope) {
                    SmileyPickerController(scope = scope, searchWiki = { _, _ -> emptyList() })
                }
                PostEditorContent(
                    state = PostEditorState(
                        mode = PostEditorMode.Reply,
                        cat = 23,
                        topicId = 447,
                        numreponse = null,
                        page = 1,
                        subcat = 0,
                        draft = draft,
                    ),
                    onIntent = {},
                    smileyPicker = picker,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Surface.NewTopic -> TopicFormContent(
                state = TopicFormState(
                    mode = TopicFormMode.New,
                    cat = 23,
                    subcat = null,
                    topicId = null,
                    page = null,
                    numreponse = null,
                    hasSubcategorySelect = false,
                    subject = TextFieldValue("Sujet de démonstration"),
                    draft = draft,
                ),
                onIntent = {},
                onOpenSmileys = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    private data class Scenario(val surface: Surface, val filled: Boolean)

    private enum class Surface(val fileName: String) {
        Post("post_reply"),
        NewTopic("new_topic"),
    }

    private companion object {
        // Longer than the tallest draft viewport, with no dates, images or remote content.
        val MULTILINE_DRAFT = (1..24).joinToString("\n") { line ->
            "Ligne $line — Texte de démonstration pour remplir le champ et observer le retour à la ligne."
        }
    }
}
